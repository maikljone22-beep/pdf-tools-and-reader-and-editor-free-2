package com.smartdocs.editor.core.convert6

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * fix/files-index: Excel number formats applied to a cell value (old .xls view and conversion): built-in formats,
 * sections (positive; negative; zero; text), digit placeholders (0 # ?), thousands separators and scaling, percent,
 * scientific, simple fractions, literals ("text", \c, _x, *x), colours / locales in brackets, and dates / times
 * (y m d h s, AM/PM, elapsed [h] [m] [s], fractional seconds). Digits are Western, separators "," and ".". Pure.
 */
object XlsNumFmt {

    /** Built-in format ids (MS-XLS 2.5.165, en-US forms). */
    val BUILT_IN: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        5 to "\"$\"#,##0_);(\"$\"#,##0)", 6 to "\"$\"#,##0_);[Red](\"$\"#,##0)", 7 to "\"$\"#,##0.00_);(\"$\"#,##0.00)", 8 to "\"$\"#,##0.00_);[Red](\"$\"#,##0.00)",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yyyy h:mm",
        37 to "#,##0_);(#,##0)", 38 to "#,##0_);[Red](#,##0)", 39 to "#,##0.00_);(#,##0.00)", 40 to "#,##0.00_);[Red](#,##0.00)",
        41 to "_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)", 42 to "_(\"$\"* #,##0_);_(\"$\"* (#,##0);_(\"$\"* \"-\"_);_(@_)",
        43 to "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)", 44 to "_(\"$\"* #,##0.00_);_(\"$\"* (#,##0.00);_(\"$\"* \"-\"??_);_(@_)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )

    /** General: integers without decimals, others with up to 10 significant digits, plain notation for normal sizes. */
    fun general(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val a = kotlin.math.abs(d)
        if (a >= 1e11 || a < 1e-9) return String.format(java.util.Locale.US, "%.5E", d).replace(Regex("0+E"), "E").replace(".E", "E")
        return BigDecimal(d).round(java.math.MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }

    /** Splits [fmt] into sections at ';' outside quotes / escapes. */
    fun sections(fmt: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            when {
                c == '"' -> { q = !q; sb.append(c) }
                c == '\\' && !q && i + 1 < fmt.length -> { sb.append(c).append(fmt[i + 1]); i++ }
                c == ';' && !q -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** [value] in format [fmt] ([date1904]: the workbook's 1904 date system). */
    fun format(value: Double, fmt: String?, date1904: Boolean = false): String {
        if (fmt.isNullOrBlank() || fmt.equals("General", true)) return general(value)
        val secs = sections(fmt)
        var v = value
        val sec = when {
            value < 0 && secs.size >= 2 && secs[1].isNotEmpty() -> { v = -value; secs[1] }
            value == 0.0 && secs.size >= 3 -> secs[2]
            else -> secs[0]
        }
        if (sec.isEmpty()) return ""
        // brackets: colours / conditions / locales dropped (currency symbols kept), elapsed time kept
        val s = Regex("""\[([^\]]*)]""").replace(sec) { m ->
            val t = m.groupValues[1]
            when {
                t.matches(Regex("""(?i)h+|m+|s+""")) -> m.value
                t.startsWith("$") -> "\"" + t.substring(1).substringBefore('-') + "\""
                else -> ""
            }
        }
        val neg = value < 0 && v < 0      // only one section: the minus sign is ours
        if (isDate(s)) return runCatching { date(kotlin.math.abs(v), s, date1904) }.getOrElse { general(value) }
        if (s.trim().equals("General", true)) return (if (neg) "-" else "") + general(kotlin.math.abs(v))
        if (s.contains('@') && !Regex("[0#?]").containsMatchIn(literalFree(s))) return general(value)
        return number(if (neg) -v else v, s, neg)
    }

    /** [s] with quoted text and escaped characters removed (placeholder detection). */
    private fun literalFree(s: String): String = s.replace(Regex("\"[^\"]*\""), "").replace(Regex("""\\.|_.|\*."""), "")

    fun isDate(s: String): Boolean {
        val f = literalFree(s).replace(Regex("(?i)AM/PM|A/P"), "h")
        return Regex("[yYdDhHsS]|[mM]").containsMatchIn(f) && !Regex("[0#?]").containsMatchIn(f.replace(Regex("""[sS]\.0+"""), "s"))
    }

    // ---- numbers

    /** A piece of a number format: literal text, or part of the numeric pattern (0 # ? . , / E+ digits of a fraction). */
    private class Tok(val text: String, val lit: Boolean)

    private fun tokens(s: String): List<Tok> {
        val out = ArrayList<Tok>(); var i = 0
        fun lastPat() = out.lastOrNull()?.takeIf { !it.lit }?.text
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; out += Tok(s.substring(i + 1, e), true); i = e + 1 }
                c == '\\' && i + 1 < s.length -> { out += Tok(s[i + 1].toString(), true); i += 2 }
                c == '_' && i + 1 < s.length -> { out += Tok(" ", true); i += 2 }
                c == '*' && i + 1 < s.length -> i += 2
                (c == 'E' || c == 'e') && i + 1 < s.length && s[i + 1] in "+-" -> { out += Tok("E" + s[i + 1], false); i += 2 }
                c in "0#?.,/" -> { out += Tok(c.toString(), false); i++ }
                c in '1'..'9' && (lastPat() == "/" || lastPat()?.firstOrNull()?.isDigit() == true) -> { out += Tok(c.toString(), false); i++ }
                else -> { out += Tok(c.toString(), true); i++ }
            }
        }
        return out
    }

    private fun number(v0: Double, s: String, neg: Boolean): String {
        var v = kotlin.math.abs(v0)
        val toks = tokens(s)
        repeat(toks.count { it.lit && it.text == "%" }) { v *= 100 }
        val ph = toks.indices.filter { !toks[it].lit && toks[it].text in setOf("0", "#", "?") }
        if (ph.isEmpty()) return (if (neg) "-" else "") + toks.filter { it.lit }.joinToString("") { it.text }.trim()
        val first = ph.first(); val last = ph.last()
        // the region also takes a fixed fraction denominator ("# ?/4")
        var end = last
        while (end + 1 < toks.size && !toks[end + 1].lit && (toks[end + 1].text == "/" || toks[end + 1].text[0].isDigit())) end++
        // trailing commas after the last digit placeholder scale by 1000 each
        var k = end + 1
        while (k < toks.size && !toks[k].lit && toks[k].text == ",") { v /= 1000; k++ }
        val core = StringBuilder()
        for (j in first..end) core.append(if (toks[j].lit) (if (toks[j].text == " ") " " else "") else toks[j].text)
        val c = core.toString()
        val sci = Regex("E[+-]").find(c)
        val text = when {
            sci != null -> sciText(v, c.substring(0, sci.range.first), c.substring(sci.range.last + 1), c[sci.range.first + 1] == '+')
            c.contains('/') -> fraction(v, c)
            else -> plain(v, c)
        }
        val before = toks.subList(0, first).filter { it.lit }.joinToString("") { it.text }
        val after = toks.subList(minOf(k, toks.size), toks.size).filter { it.lit }.joinToString("") { it.text }
        return ((if (neg) "-" else "") + before + text + after).trim()
    }

    /** Digits for a mantissa-only pattern like "#,##0.00". */
    private fun plain(v: Double, core: String): String {
        val dot = core.indexOf('.')
        val intPat = if (dot >= 0) core.substring(0, dot) else core
        val decPat = if (dot >= 0) core.substring(dot + 1) else ""
        val decimals = decPat.count { it == '0' || it == '#' || it == '?' }
        val minDec = decPat.count { it == '0' }
        val minInt = intPat.count { it == '0' }
        val group = intPat.contains(',')
        val bd = BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP)
        var intStr = bd.toBigInteger().toString()
        if (intStr == "0" && minInt == 0) intStr = ""
        intStr = intStr.padStart(minInt, '0')
        if (group && intStr.length > 3) intStr = intStr.reversed().chunked(3).joinToString(",").reversed()
        var dec = if (decimals > 0) bd.toPlainString().substringAfter('.', "").padEnd(decimals, '0') else ""
        while (dec.length > minDec && dec.endsWith('0')) dec = dec.dropLast(1)
        return if (dot >= 0 && decimals > 0) "$intStr.$dec" else intStr
    }

    private fun sciText(v: Double, mant: String, exp: String, plus: Boolean): String {
        val intDigits = mant.substringBefore('.').count { it == '0' || it == '#' || it == '?' }.coerceAtLeast(1)
        var e = if (v == 0.0) 0 else kotlin.math.floor(kotlin.math.log10(v)).toInt()
        // engineering style ("##0.0E+0"): exponent a multiple of the integer digits
        if (intDigits > 1) e = Math.floorDiv(e, intDigits) * intDigits
        var m = v / Math.pow(10.0, e.toDouble())
        var ms = plain(m, mant)
        if (ms.substringBefore('.').length > intDigits && intDigits == 1) { e += 1; m = v / Math.pow(10.0, e.toDouble()); ms = plain(m, mant) }
        val ed = exp.count { it == '0' }.coerceAtLeast(1)
        val es = kotlin.math.abs(e).toString().padStart(ed, '0')
        return ms + "E" + (if (e < 0) "-" else if (plus) "+" else "") + es
    }

    /** "# ?/?" style: whole part and the closest fraction with as many denominator digits as placeholders. */
    private fun fraction(v: Double, core: String): String {
        val denPat = core.substringAfter('/').trim()
        val fixed = denPat.filter { it.isDigit() }.toIntOrNull()
        val maxDen = if (fixed != null && fixed > 0) fixed else Math.pow(10.0, denPat.count { it == '?' || it == '#' || it == '0' }.coerceIn(1, 4).toDouble()).toInt() - 1
        val hasWhole = core.substringBefore('/').trim().contains(' ')
        val whole = if (hasWhole) kotlin.math.floor(v).toLong() else 0L
        val f = v - whole
        var bestN = 0L; var bestD = 1L; var err = Double.MAX_VALUE
        val dens = if (fixed != null && fixed > 0) listOf(fixed.toLong()) else (1..maxDen).map { it.toLong() }
        for (d in dens) { val n = Math.round(f * d); val e = kotlin.math.abs(f - n.toDouble() / d); if (e < err - 1e-12) { err = e; bestN = n; bestD = d } }
        if (bestN == bestD && hasWhole) return (whole + 1).toString()
        if (bestN == 0L) return if (hasWhole) whole.toString() else "0"
        return (if (hasWhole && whole != 0L) "$whole " else "") + "$bestN/$bestD"
    }

    // ---- dates

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun date(serial: Double, s: String, date1904: Boolean): String {
        // tokens: the m after h (or before s) is minutes
        val fracDigits = Regex("""[sS]\.(0+)""").find(s)?.groupValues?.get(1)?.length ?: 0
        val totalSec = serial * 86400.0
        val rounded = BigDecimal(totalSec).setScale(fracDigits, RoundingMode.HALF_UP).toDouble()
        val wholeSec = kotlin.math.floor(rounded).toLong()
        val frac = rounded - wholeSec
        val days = wholeSec / 86400
        val secOfDay = wholeSec % 86400
        val base = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
        val dt = if (!date1904 && days < 60) java.time.LocalDate.of(1899, 12, 31).plusDays(days - 1) else base.plusDays(days)   // Excel's 1900 leap-year bug
        val h24 = (secOfDay / 3600).toInt(); val mi = ((secOfDay / 60) % 60).toInt(); val se = (secOfDay % 60).toInt()
        val ampm = Regex("(?i)AM/PM|A/P").containsMatchIn(s)
        data class DTok(val t: String, val lit: Boolean)
        val toks = ArrayList<DTok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; toks += DTok(s.substring(i + 1, e), true); i = e + 1 }
                c == '\\' && i + 1 < s.length -> { toks += DTok(s[i + 1].toString(), true); i += 2 }
                c == '_' && i + 1 < s.length -> { toks += DTok(" ", true); i += 2 }
                c == '*' && i + 1 < s.length -> i += 2
                s.regionMatches(i, "AM/PM", 0, 5, true) -> { toks += DTok("AM/PM", false); i += 5 }
                s.regionMatches(i, "A/P", 0, 3, true) -> { toks += DTok("A/P", false); i += 3 }
                c == '[' -> { val e = s.indexOf(']', i); if (e < 0) { i++ } else { toks += DTok(s.substring(i, e + 1).lowercase(), false); i = e + 1 } }
                c.lowercaseChar() in "ymdhs" -> { var j = i; while (j < s.length && s[j].lowercaseChar() == c.lowercaseChar()) j++; toks += DTok(s.substring(i, j).lowercase(), false); i = j }
                c == '.' && i + 1 < s.length && s[i + 1] == '0' -> { var j = i + 1; while (j < s.length && s[j] == '0') j++; toks += DTok(s.substring(i, j), false); i = j }
                else -> { toks += DTok(c.toString(), true); i++ }
            }
        }
        // minutes: an m token right after an h token or right before an s token (literals in between ignored)
        val minuteIdx = HashSet<Int>()
        toks.forEachIndexed { k, t ->
            if (t.lit || !t.t.startsWith("m") || t.t.length > 2) return@forEachIndexed
            val prev = (k - 1 downTo 0).firstOrNull { !toks[it].lit }?.let { toks[it].t }
            val next = (k + 1 until toks.size).firstOrNull { !toks[it].lit }?.let { toks[it].t }
            if (prev != null && (prev.startsWith("h") || prev.startsWith("[h"))) minuteIdx += k
            if (next != null && (next.startsWith("s") || next.startsWith("[s"))) minuteIdx += k
        }
        val out = StringBuilder()
        toks.forEachIndexed { k, t ->
            if (t.lit) { out.append(t.t); return@forEachIndexed }
            val x = t.t
            out.append(when {
                x == "[h]" || x == "[hh]" -> (wholeSec / 3600).toString().padStart(x.length - 2, '0')
                x == "[m]" || x == "[mm]" -> (wholeSec / 60).toString().padStart(x.length - 2, '0')
                x == "[s]" || x == "[ss]" -> wholeSec.toString().padStart(x.length - 2, '0')
                x.startsWith("y") -> if (x.length <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString()
                x.startsWith("m") && k in minuteIdx -> if (x.length == 1) mi.toString() else mi.toString().padStart(2, '0')
                x.startsWith("m") -> when (x.length) { 1 -> dt.monthValue.toString(); 2 -> dt.monthValue.toString().padStart(2, '0'); 3 -> MONTHS[dt.monthValue - 1].take(3); 5 -> MONTHS[dt.monthValue - 1].take(1); else -> MONTHS[dt.monthValue - 1] }
                x.startsWith("d") -> when (x.length) { 1 -> dt.dayOfMonth.toString(); 2 -> dt.dayOfMonth.toString().padStart(2, '0'); 3 -> DAYS[dt.dayOfWeek.value - 1].take(3); else -> DAYS[dt.dayOfWeek.value - 1] }
                x.startsWith("h") -> { val h = if (ampm) (h24 % 12).let { if (it == 0) 12 else it } else h24; if (x.length == 1) h.toString() else h.toString().padStart(2, '0') }
                x.startsWith("s") -> if (x.length == 1) se.toString() else se.toString().padStart(2, '0')
                x.startsWith(".") -> "." + BigDecimal(frac).setScale(x.length - 1, RoundingMode.HALF_UP).toPlainString().substringAfter('.', "").padEnd(x.length - 1, '0').take(x.length - 1)
                x == "AM/PM" -> if (h24 < 12) "AM" else "PM"
                x == "A/P" -> if (h24 < 12) "A" else "P"
                else -> ""
            })
        }
        return out.toString()
    }
}
