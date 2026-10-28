package com.smartdocs.editor.core.forms

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * XFA picture clauses (`<format><picture>`): the display of a field value from its canonical form. Supports the
 * num{}, date{}, time{}, text{} and zero{} / null{} categories with their common symbols; a bare pattern takes its
 * category from the field type. Alternatives ("a|b") use the first one that formats. Returns null when a pattern
 * cannot format the value (the raw value is then shown, as Acrobat does for values that do not match).
 */
object XfaPicture {
    enum class Hint { TEXT, NUMBER, DATE, TIME }

    fun format(picture: String?, raw: String, hint: Hint = Hint.TEXT): String? {
        val p = picture?.trim().orEmpty()
        if (p.isEmpty()) return null
        for (alt in splitAlternatives(p)) {
            val (cat, body) = category(alt, hint)
            val r = when (cat) {
                "zero" -> if (raw.toBigDecimalOrNull()?.signum() == 0) literalOnly(body) else null
                "null" -> if (raw.isEmpty()) literalOnly(body) else null
                else -> if (raw.isEmpty()) null else when (cat) {
                    "num" -> num(body, raw)
                    "date" -> date(body, raw)
                    "time" -> time(body, raw)
                    "text" -> text(body, raw)
                    else -> null
                }
            }
            if (r != null) return r
        }
        return null
    }

    /** Key of the generated AcroForm text field that keeps the XFA display picture (our key). */
    const val KEY = "SDXfaPicture"

    private val CATEGORY_START = Regex("""^(num|date|time|text|zero|null|datetime)(\([^)]*\))?\{""")

    /** [p] with an explicit category: a bare pattern gets num{} / date{} / time{} from [hint] (text stays bare). */
    fun withCategory(p: String, hint: Hint): String {
        val t = p.trim()
        if (t.isEmpty() || CATEGORY_START.containsMatchIn(t)) return t
        return when (hint) { Hint.NUMBER -> "num{$t}"; Hint.DATE -> "date{$t}"; Hint.TIME -> "time{$t}"; Hint.TEXT -> "text{$t}" }
    }

    private fun splitAlternatives(p: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var q = false; val sb = StringBuilder()
        for (c in p) {
            when {
                c == '\'' -> { q = !q; sb.append(c) }
                !q && c == '{' -> { depth++; sb.append(c) }
                !q && c == '}' -> { depth--; sb.append(c) }
                !q && c == '|' && depth == 0 -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun category(alt: String, hint: Hint): Pair<String, String> {
        val m = Regex("""^(num|date|time|text|zero|null|datetime)(\([^)]*\))?\{(.*)\}$""", RegexOption.DOT_MATCHES_ALL).find(alt)
        if (m != null) return m.groupValues[1] to m.groupValues[3]
        return when (hint) { Hint.NUMBER -> "num"; Hint.DATE -> "date"; Hint.TIME -> "time"; Hint.TEXT -> "text" } to alt
    }

    /** Pattern tokens: quoted literals stay literal; letters repeat into runs ("MMM", "zz"). */
    private fun tokens(body: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\'') {
                val end = body.indexOf('\'', i + 1).let { if (it < 0) body.length else it }
                out += "'" + body.substring(i + 1, end); i = end + 1; continue
            }
            var j = i + 1
            if (c.isLetter()) while (j < body.length && body[j] == c) j++
            out += body.substring(i, j); i = j
        }
        return out
    }

    private fun literalOnly(body: String): String = tokens(body).joinToString("") { if (it.startsWith("'")) it.substring(1) else it }

    // ---------------------------------------------------------------- num

    private fun num(body: String, raw: String): String? {
        val v = raw.trim().replace(",", "").toBigDecimalOrNull() ?: return null
        val value = v      // '%' is printed as a literal; the value is not scaled
        // split into integer and fraction pattern at the radix (. or v / V)
        var radix = -1; var q = false
        for ((i, c) in body.withIndex()) { if (c == '\'') q = !q; if (!q && (c == '.' || c == 'v' || c == 'V')) { radix = i; break } }
        val intPat = if (radix < 0) body else body.substring(0, radix)
        val fracPat = if (radix < 0) "" else body.substring(radix + 1)
        val fracDigits = fracPat.count { it == '9' || it == 'z' || it == 'Z' || it == '8' }
        val rounded = value.abs().setScale(fracDigits, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        var intDigits = plain.substringBefore('.').trimStart('0')
        val frac = if (fracDigits > 0) plain.substringAfter('.', "").padEnd(fracDigits, '0') else ""
        val neg = value.signum() < 0 && rounded.signum() != 0
        // integer part, right to left
        val itoks = tokens(intPat)
        val digitSlots = itoks.sumOf { t -> if (t.startsWith("'")) 0 else t.count { it == '9' || it == 'z' || it == 'Z' } }
        if (intDigits.length > digitSlots) return null       // does not fit the picture
        // flat (char, literal) slots so a grouping comma can see what lies to its left
        val slots = itoks.flatMap { t -> if (t.startsWith("'")) listOf(t.substring(1) to true) else t.map { it.toString() to false } }
        val sbI = StringBuilder()
        var signDone = false
        for (k in slots.indices.reversed()) {
            val (str, lit) = slots[k]
            if (lit) { sbI.insert(0, str); continue }
            when (val c = str[0]) {
                '9' -> { sbI.insert(0, if (intDigits.isNotEmpty()) intDigits.last() else '0'); intDigits = intDigits.dropLast(1) }
                'z' -> { if (intDigits.isNotEmpty()) { sbI.insert(0, intDigits.last()); intDigits = intDigits.dropLast(1) } }
                'Z' -> { sbI.insert(0, if (intDigits.isNotEmpty()) intDigits.last() else ' '); intDigits = intDigits.dropLast(1) }
                // a grouping comma shows only between digits: more digits to place, or a forced 9 further left
                ',' -> if (intDigits.isNotEmpty() || (0 until k).any { !slots[it].second && slots[it].first == "9" }) sbI.insert(0, ',')
                's' -> { if (neg) sbI.insert(0, '-'); signDone = true }
                'S' -> { sbI.insert(0, if (neg) '-' else ' '); signDone = true }
                else -> sbI.insert(0, c)
            }
        }
        val sbF = StringBuilder(); var fi = 0
        for (t in tokens(fracPat)) {
            if (t.startsWith("'")) { sbF.append(t.substring(1)); continue }
            for (c in t) when (c) {
                '9', 'Z', '8' -> { sbF.append(frac.getOrElse(fi) { '0' }); fi++ }
                'z' -> { sbF.append(frac.getOrElse(fi) { '0' }); fi++ }
                's' -> { if (neg) sbF.append('-'); signDone = true }
                'S' -> { sbF.append(if (neg) '-' else ' '); signDone = true }
                else -> sbF.append(c)
            }
        }
        // 8 = optional trailing digits: drop trailing zeros they produced
        var fracOut = sbF.toString()
        if (fracPat.contains('8')) fracOut = fracOut.trimEnd('0')
        val sepOut = if (radix < 0 || (fracPat.contains('8') && fracOut.none { it.isDigit() })) "" else "."
        val text = sbI.toString() + sepOut + fracOut
        return if (neg && !signDone) "-$text" else text
    }

    // ---------------------------------------------------------------- date / time

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** Canonical XFA date: YYYY-MM-DD or YYYYMMDD (time part ignored). */
    fun parseDate(raw: String): Triple<Int, Int, Int>? {
        val s = raw.trim().substringBefore('T')
        val m = Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").find(s) ?: return null
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return Triple(y, mo, d)
    }

    private fun date(body: String, raw: String): String? {
        val (y, mo, d) = parseDate(raw) ?: return null
        val cal = GregorianCalendar(y, mo - 1, d)
        if (cal.get(Calendar.DAY_OF_MONTH) != d) return null
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
        return tokens(body).joinToString("") { t ->
            when {
                t.startsWith("'") -> t.substring(1)
                t == "D" -> d.toString(); t == "DD" -> "%02d".format(Locale.US, d)
                t == "J" -> cal.get(Calendar.DAY_OF_YEAR).toString(); t == "JJJ" -> "%03d".format(Locale.US, cal.get(Calendar.DAY_OF_YEAR))
                t == "M" -> mo.toString(); t == "MM" -> "%02d".format(Locale.US, mo)
                t == "MMM" -> MONTHS[mo - 1].take(3); t == "MMMM" -> MONTHS[mo - 1]
                t == "E" -> (dow + 1).toString(); t == "EEE" -> DAYS[dow].take(3); t == "EEEE" -> DAYS[dow]
                t == "YY" -> "%02d".format(Locale.US, y % 100); t == "YYYY" -> y.toString()
                else -> t
            }
        }
    }

    private fun time(body: String, raw: String): String? {
        val s = raw.trim().substringAfter('T')
        val m = Regex("""^(\d{2}):?(\d{2})(?::?(\d{2}))?""").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); val se = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (h !in 0..24 || mi !in 0..59 || se !in 0..60) return null
        val h12 = if (h % 12 == 0) 12 else h % 12
        return tokens(body).joinToString("") { t ->
            when {
                t.startsWith("'") -> t.substring(1)
                t == "h" -> h12.toString(); t == "hh" -> "%02d".format(Locale.US, h12)
                t == "H" -> h.toString(); t == "HH" -> "%02d".format(Locale.US, h)
                t == "k" -> (h % 12).toString(); t == "kk" -> "%02d".format(Locale.US, h % 12)
                t == "K" -> (if (h == 0) 24 else h).toString(); t == "KK" -> "%02d".format(Locale.US, if (h == 0) 24 else h)
                t == "M" -> mi.toString(); t == "MM" -> "%02d".format(Locale.US, mi)
                t == "S" -> se.toString(); t == "SS" -> "%02d".format(Locale.US, se)
                t == "A" -> if (h < 12) "AM" else "PM"
                else -> t
            }
        }
    }

    // ---------------------------------------------------------------- text

    private fun text(body: String, raw: String): String? {
        val sb = StringBuilder(); var i = 0
        for (t in tokens(body)) {
            if (t.startsWith("'")) { sb.append(t.substring(1)); continue }
            for (c in t) {
                val need: ((Char) -> Boolean)? = when (c) {
                    '9' -> { ch -> ch.isDigit() }
                    'A' -> { ch -> ch.isLetter() }
                    'X' -> { _ -> true }
                    'O', '0' -> { ch -> ch.isLetterOrDigit() }
                    else -> null
                }
                if (need == null) { sb.append(c); continue }
                val ch = raw.getOrNull(i) ?: return null
                if (!need(ch)) return null
                sb.append(ch); i++
            }
        }
        return if (i == raw.length) sb.toString() else null
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
}
