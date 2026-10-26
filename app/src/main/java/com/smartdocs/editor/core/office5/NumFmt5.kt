package com.smartdocs.editor.core.office5

import java.math.BigDecimal
import java.math.RoundingMode

/** A formatted cell value; [color] is a hex colour from a [Red]-style section, or null. */
data class Formatted(val text: String, val color: String? = null)

/**
 * Excel number-format engine (subset): General, up to four sections (pos;neg;zero;text) with [Red]/[Color]
 * and [>=n] conditions, literals ("text", \x, _x, *x), 0 # ? placeholders, thousands separators and
 * trailing-comma scaling, percent, scientific, @ text, currency tags ([$SAR] / [$-409]), and date/time
 * tokens (yyyy yy mmmm mmm mm m dddd ddd dd d hh h mm ss AM/PM [h] .0).
 */
object NumFmt5 {
    /** Built-in format codes by id (ECMA-376 18.8.30). */
    fun builtin(id: Int): String? = when (id) {
        0 -> "General"; 1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"
        5 -> "\"$\"#,##0_);(\"$\"#,##0)"; 6 -> "\"$\"#,##0_);[Red](\"$\"#,##0)"; 7 -> "\"$\"#,##0.00_);(\"$\"#,##0.00)"; 8 -> "\"$\"#,##0.00_);[Red](\"$\"#,##0.00)"
        9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"; 12 -> "# ?/?"; 13 -> "# ??/??"
        14 -> "yyyy-mm-dd"; 15 -> "d-mmm-yy"; 16 -> "d-mmm"; 17 -> "mmm-yy"; 18 -> "h:mm AM/PM"; 19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"; 21 -> "h:mm:ss"; 22 -> "yyyy-mm-dd h:mm"
        37 -> "#,##0_);(#,##0)"; 38 -> "#,##0_);[Red](#,##0)"; 39 -> "#,##0.00_);(#,##0.00)"; 40 -> "#,##0.00_);[Red](#,##0.00)"
        44 -> "_(\"$\"* #,##0.00_);_(\"$\"* (#,##0.00);_(\"$\"* \"-\"??_);_(@_)"
        45 -> "mm:ss"; 46 -> "[h]:mm:ss"; 47 -> "mm:ss.0"; 48 -> "##0.0E+0"; 49 -> "@"
        else -> null
    }

    private val COLORS = mapOf("red" to "FF0000", "blue" to "0000FF", "green" to "00A000", "yellow" to "C8B400", "magenta" to "FF00FF", "cyan" to "00A0A0", "white" to "FFFFFF", "black" to "000000")

    fun format(v: FV, code: String?, date1904: Boolean = false): Formatted = when (v) {
        is FV.Num -> format(v.v, code, date1904)
        is FV.Str -> textSection(code)?.let { Formatted(applyText(it, v.v)) } ?: Formatted(v.v)
        is FV.Bool -> Formatted(if (v.v) "TRUE" else "FALSE")
        is FV.Err -> Formatted(v.code)
        is FV.Blank -> Formatted("")
        is FV.Arr -> format(v.first(), code, date1904)
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { q = !q; sb.append(c) }
                c == '\\' && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i++ }
                c == ';' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun textSection(code: String?): String? {
        if (code == null) return null
        val s = splitSections(code)
        return when { s.size >= 4 -> s[3]; s.size == 1 && s[0].contains('@') -> s[0]; else -> null }
    }

    private fun applyText(section: String, text: String): String {
        val (body, _) = stripBrackets(section)
        val sb = StringBuilder()
        walkLiterals(body) { lit, tok -> if (lit != null) sb.append(lit) else if (tok == '@') sb.append(text) }
        return sb.toString()
    }

    /** Removes [Red], [>=100], [$-409] tags; returns (body, color). Currency tags keep their symbol as a literal. */
    private fun stripBrackets(sec: String): Pair<String, String?> {
        var color: String? = null
        val sb = StringBuilder(); var i = 0
        while (i < sec.length) {
            val c = sec[i]
            if (c == '"') { val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length - 1 else it }; sb.append(sec, i, e + 1); i = e + 1; continue }
            if (c == '\\' && i + 1 < sec.length) { sb.append(sec, i, i + 2); i += 2; continue }
            if (c == '[') {
                val e = sec.indexOf(']', i); if (e < 0) { sb.append(c); i++; continue }
                val tag = sec.substring(i + 1, e)
                when {
                    tag.startsWith("$") -> { val sym = tag.substring(1).substringBefore('-'); if (sym.isNotEmpty()) sb.append('"').append(sym).append('"') }
                    COLORS.containsKey(tag.lowercase()) -> color = COLORS[tag.lowercase()]
                    tag.matches(Regex("(?i)h+|m+|s+")) -> sb.append('[').append(tag).append(']')
                    else -> {}
                }
                i = e + 1; continue
            }
            sb.append(c); i++
        }
        return sb.toString() to color
    }

    private val condRx = Regex("""\[(<=|>=|<>|<|>|=)(-?[\d.]+)]""")

    fun format(d: Double, code: String?, date1904: Boolean = false): Formatted {
        if (code == null || code.isBlank() || code.equals("General", true)) return Formatted(general(d))
        val secs = splitSections(code)
        var sec: String; var value = d
        val conds = secs.map { condRx.find(it) }
        if (conds.any { it != null }) {
            val idx = secs.indices.firstOrNull { i -> conds[i]?.let { m -> test(d, m.groupValues[1], m.groupValues[2].toDouble()) } ?: false }
                ?: secs.indices.firstOrNull { conds[it] == null && it < 3 } ?: 0
            sec = secs[idx]; if (idx > 0 && d < 0) value = -d
        } else {
            sec = when {
                d > 0 || secs.size == 1 -> secs[0]
                d < 0 -> if (secs.size >= 2) { value = -d; secs[1] } else secs[0]
                else -> if (secs.size >= 3) secs[2] else secs[0]
            }
        }
        val (body, color) = stripBrackets(sec)
        if (body.equals("General", true)) return Formatted(general(value), color)
        return Formatted(if (isDate(body)) date(value, body, date1904) else number(value, body), color)
    }

    private fun test(d: Double, op: String, v: Double) = when (op) { "<" -> d < v; ">" -> d > v; "<=" -> d <= v; ">=" -> d >= v; "<>" -> d != v; else -> d == v }

    fun general(d: Double): String {
        if (d == 0.0) return "0"
        val a = kotlin.math.abs(d)
        if (a >= 1e11 || a < 1e-9) {
            val s = String.format(java.util.Locale.US, "%.5E", d)
            val mant = s.substringBefore('E').trimEnd('0').trimEnd('.')
            val exp = s.substringAfter('E').toInt()
            return mant + "E" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
        }
        // about 11 significant characters like Excel's General column display
        val intDigits = if (a < 1) 1 else Math.floor(Math.log10(a)).toInt() + 1
        val dec = (10 - intDigits).coerceIn(0, 10)
        return BigDecimal(java.lang.Double.toString(d)).setScale(dec, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** Walks a section calling [f] with literal text, or with a placeholder/token char. */
    private inline fun walkLiterals(s: String, f: (String?, Char) -> Unit) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; f(s.substring(i + 1, e), c); i = e + 1; continue }
                c == '\\' && i + 1 < s.length -> { f(s[i + 1].toString(), c); i += 2; continue }
                c == '_' && i + 1 < s.length -> { f(" ", c); i += 2; continue }
                c == '*' && i + 1 < s.length -> { i += 2; continue }
                c in "$-+/():!^&'~{}<>= " || c.code > 0x7F -> f(c.toString(), c)
                else -> f(null, c)
            }
            i++
        }
    }

    private fun isDate(body: String): Boolean {
        val stripped = body.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")
        return stripped.any { it in "yYdDhHsS" } || (stripped.contains(Regex("[mM]")) && !stripped.contains(Regex("[0#?]")))
    }

    private fun number(v: Double, body: String): String {
        var pct = 0; var q = false
        for (c in body) { if (c == '"') q = !q else if (!q && c == '%') pct++ }
        var x = v * Math.pow(100.0, pct.toDouble())
        // numeric region: first placeholder .. last placeholder (with separators and an exponent inside)
        var first = -1; var last = -1; var sci = false; var i = 0; q = false
        while (i < body.length) {
            val c = body[i]
            if (c == '"') { q = !q; i++; continue }
            if (q) { i++; continue }
            if (c == '\\' || c == '_' || c == '*') { i += 2; continue }
            if (c in "0#?" || (first >= 0 && (c == '.' || c == ','))) { if (first < 0) first = i; last = i }
            if (first >= 0 && (c == 'E' || c == 'e') && (body.getOrNull(i + 1) == '+' || body.getOrNull(i + 1) == '-')) {
                sci = true; last = i + 1; i += 2
                while (i < body.length && body[i] in "0#") { last = i; i++ }
                continue
            }
            i++
        }
        val out = StringBuilder()
        if (first < 0) { walkLiterals(body) { lit, tok -> if (lit != null) out.append(lit) else if (tok == '@') out.append(FxCoerce.general(v)) }; return out.toString() }
        val pre = body.substring(0, first); var pat = body.substring(first, last + 1); val post = body.substring(last + 1)
        walkLiterals(pre) { lit, _ -> if (lit != null) out.append(lit) }
        // trailing commas scale by 1000
        while (pat.endsWith(",")) { x /= 1000.0; pat = pat.dropLast(1) }
        out.append(if (sci) scientific(x, pat) else fixed(x, pat))
        walkLiterals(post) { lit, tok -> if (lit != null) out.append(lit) else if (tok == '%') out.append('%') }
        return out.toString()
    }

    private fun fixed(x: Double, p: String): String {
        val intPat = p.substringBefore('.'); val decPat = if (p.contains('.')) p.substringAfter('.').filter { it in "0#?" } else ""
        val group = intPat.contains(',')
        val minInt = intPat.count { it == '0' }
        val minDec = decPat.count { it == '0' }
        val maxDec = decPat.length
        val bd = BigDecimal(java.lang.Double.toString(kotlin.math.abs(x))).setScale(maxDec, RoundingMode.HALF_UP)
        var digits = bd.toPlainString()
        var ip = digits.substringBefore('.'); var dp = if (digits.contains('.')) digits.substringAfter('.') else ""
        while (dp.length > minDec && dp.endsWith("0")) dp = dp.dropLast(1)
        if (ip == "0" && minInt == 0) ip = ""
        while (ip.length < minInt) ip = "0$ip"
        if (group && ip.length > 3) ip = ip.reversed().chunked(3).joinToString(",").reversed()
        val neg = x < 0 && (ip.any { it in "123456789" } || dp.any { it in "123456789" })
        digits = ip + if (dp.isNotEmpty() || (maxDec > 0 && minDec > 0)) ".$dp" else if (p.contains('.') && maxDec == 0) "." else ""
        return (if (neg) "-" else "") + digits
    }

    private fun scientific(x: Double, p: String): String {
        val mant = p.substringBefore('E').substringBefore('e')
        val expPat = p.substring(mant.length + 2)
        val decs = if (mant.contains('.')) mant.substringAfter('.').length else 0
        if (x == 0.0) return fixed(0.0, mant) + "E+" + "0".repeat(expPat.count { it == '0' }.coerceAtLeast(1))
        var e = Math.floor(Math.log10(kotlin.math.abs(x))).toInt()
        var m = x / Math.pow(10.0, e.toDouble())
        if (FxCoerce.round(kotlin.math.abs(m), decs) >= 10.0) { m /= 10; e++ }
        val es = kotlin.math.abs(e).toString().padStart(expPat.count { it == '0' }.coerceAtLeast(1), '0')
        return fixed(m, mant) + "E" + (if (e < 0) "-" else "+") + es
    }

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun date(v: Double, body: String, date1904: Boolean): String {
        if (v < 0) return "#".repeat(8)
        val dt = FxDates.date(v, date1904)
        val ampm = body.contains("AM/PM", true) || body.contains("A/P", true)
        val sb = StringBuilder()
        var i = 0
        val s = body
        // "m" after h or before s means minutes
        var lastWasHour = false
        fun ahead(from: Int): Boolean { var j = from; while (j < s.length && !s[j].isLetter()) j++; return j < s.length && (s[j] == 's' || s[j] == 'S') }
        while (i < s.length) {
            val c = s[i]
            val lc = c.lowercaseChar()
            var n = 1; while (i + n < s.length && s[i + n].lowercaseChar() == lc && lc in "ymdhs") n++
            when {
                c == '"' -> { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; sb.append(s, i + 1, e); i = e + 1; continue }
                c == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i += 2; continue }
                c == '[' -> {
                    val e = s.indexOf(']', i); val tag = s.substring(i + 1, e).lowercase()
                    val total = v * 24
                    sb.append(when (tag.first()) { 'h' -> Math.floor(total).toLong().toString(); 'm' -> Math.floor(total * 60).toLong().toString(); else -> Math.floor(total * 3600).toLong().toString() })
                    i = e + 1; lastWasHour = tag.first() == 'h'; continue
                }
                s.regionMatches(i, "AM/PM", 0, 5, true) -> { sb.append(if (dt.hour < 12) "AM" else "PM"); i += 5; continue }
                s.regionMatches(i, "A/P", 0, 3, true) -> { sb.append(if (dt.hour < 12) "A" else "P"); i += 3; continue }
                lc == 'y' -> sb.append(if (n <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString())
                lc == 'd' -> sb.append(when (n) { 1 -> dt.dayOfMonth.toString(); 2 -> dt.dayOfMonth.toString().padStart(2, '0'); 3 -> DAYS[dt.dayOfWeek.value - 1].take(3); else -> DAYS[dt.dayOfWeek.value - 1] })
                lc == 'h' -> { val h = if (ampm) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour; sb.append(if (n >= 2) h.toString().padStart(2, '0') else h.toString()); lastWasHour = true; i += n; continue }
                lc == 'm' -> {
                    if ((lastWasHour || ahead(i + n)) && n <= 2) sb.append(if (n == 2) dt.minute.toString().padStart(2, '0') else dt.minute.toString())
                    else sb.append(when (n) { 1 -> dt.monthValue.toString(); 2 -> dt.monthValue.toString().padStart(2, '0'); 3 -> MONTHS[dt.monthValue - 1].take(3); 5 -> MONTHS[dt.monthValue - 1].take(1); else -> MONTHS[dt.monthValue - 1] })
                }
                lc == 's' -> {
                    sb.append(if (n >= 2) dt.second.toString().padStart(2, '0') else dt.second.toString())
                    if (i + n < s.length && s[i + n] == '.') { var z = 0; while (i + n + 1 + z < s.length && s[i + n + 1 + z] == '0') z++; val frac = ((v * 86400) % 1).let { f -> Math.round(f * Math.pow(10.0, z.toDouble())) }; sb.append('.').append(frac.toString().padStart(z, '0')); i += n + 1 + z; continue }
                }
                else -> sb.append(c)
            }
            if (lc != 'h' && lc != ' ' && lc != ':') lastWasHour = false
            i += n
        }
        return sb.toString()
    }

    /** True when [code] displays dates/times (for input parsing and the format menu). */
    fun isDateCode(code: String?): Boolean = code != null && !code.equals("General", true) && isDate(stripBrackets(splitSections(code)[0]).first)
}
