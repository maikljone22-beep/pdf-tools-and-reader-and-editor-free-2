package com.smartdocs.editor.core.forms

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * XFA picture clauses: the display (`<format><picture>`), edit (`<ui><picture>`) and validation
 * (`<validate><picture>`) patterns of a field. [format] turns the canonical value into its pattern form; [parse]
 * reads text typed in a pattern form back to the canonical value (num: plain decimal, date: YYYY-MM-DD, time:
 * HH:MM:SS, text: the placeholder characters). Supports the num{}, date{}, time{}, text{} and zero{} / null{}
 * categories with their common symbols; a bare pattern takes its category from the field type. Alternatives
 * ("a|b") use the first one that works. A locale in the category ("date(ar_SA){…}") or the field's locale gives
 * Arabic month and day names. In num patterns '%' shows the value as a percentage (0.25 → 25%).
 * Null results mean the value does not match (the raw value is then shown, as Acrobat does).
 */
object XfaPicture {
    enum class Hint { TEXT, NUMBER, DATE, TIME }

    /** Key of the generated AcroForm text field that keeps the XFA display picture (our key). */
    const val KEY = "SDXfaPicture"
    /** Keys of the edit and validation pictures (our keys). */
    const val KEY_EDIT = "SDXfaEditPicture"
    const val KEY_VALIDATE = "SDXfaValidatePicture"

    private val CATEGORY_START = Regex("""^(num|date|time|text|zero|null|datetime)(\([^)]*\))?\{""")
    private val CATEGORY = Regex("""^(num|date|time|text|zero|null|datetime)(?:\(([^)]*)\))?\{(.*)\}$""", RegexOption.DOT_MATCHES_ALL)

    fun format(picture: String?, raw: String, hint: Hint = Hint.TEXT): String? {
        val p = picture?.trim().orEmpty()
        if (p.isEmpty()) return null
        for (alt in splitAlternatives(p)) {
            val c = category(alt, hint)
            val r = when (c.cat) {
                "zero" -> if (raw.toBigDecimalOrNull()?.signum() == 0) literalOnly(c.body) else null
                "null" -> if (raw.isEmpty()) literalOnly(c.body) else null
                else -> if (raw.isEmpty()) null else when (c.cat) {
                    "num" -> num(c.body, raw)
                    "date" -> date(c.body, raw, c.arabic)
                    "time" -> time(c.body, raw, c.arabic)
                    "text" -> text(c.body, raw)
                    else -> null
                }
            }
            if (r != null) return r
        }
        return null
    }

    /** Canonical value of [input] typed in the form of [picture]; null when it does not match. */
    fun parse(picture: String?, input: String, hint: Hint = Hint.TEXT): String? {
        val p = picture?.trim().orEmpty()
        val s = input.trim()
        if (p.isEmpty() || s.isEmpty()) return null
        for (alt in splitAlternatives(p)) {
            val c = category(alt, hint)
            val r = when (c.cat) {
                "num" -> parseNum(c.body, s)
                "date" -> parseDateText(c.body, s)
                "time" -> parseTime(c.body, s)
                "text" -> parseText(c.body, s)
                else -> null
            }
            if (r != null) return r
        }
        return null
    }

    /** Whether [value] (canonical or typed) conforms to the validation [picture] (empty values always do). */
    fun conforms(picture: String?, value: String, hint: Hint = Hint.TEXT): Boolean =
        picture.isNullOrBlank() || value.isEmpty() || format(picture, value, hint) != null || parse(picture, value, hint) != null

    /** [p] with an explicit category: a bare pattern gets num{} / date{} / time{} from [hint]; [locale] is kept with it. */
    fun withCategory(p: String, hint: Hint, locale: String? = null): String {
        val t = p.trim()
        if (t.isEmpty()) return t
        val loc = locale?.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
        return splitAlternatives(t).joinToString("|") { alt ->
            val m = CATEGORY.find(alt)
            when {
                m == null -> when (hint) { Hint.NUMBER -> "num$loc{$alt}"; Hint.DATE -> "date$loc{$alt}"; Hint.TIME -> "time$loc{$alt}"; Hint.TEXT -> "text$loc{$alt}" }
                m.groupValues[2].isEmpty() && loc.isNotEmpty() -> m.groupValues[1] + loc + "{" + m.groupValues[3] + "}"
                else -> alt
            }
        }
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

    private class Cat(val cat: String, val arabic: Boolean, val body: String)

    private fun category(alt: String, hint: Hint): Cat {
        val m = CATEGORY.find(alt)
        if (m != null) return Cat(m.groupValues[1], m.groupValues[2].lowercase().startsWith("ar"), m.groupValues[3])
        return Cat(when (hint) { Hint.NUMBER -> "num"; Hint.DATE -> "date"; Hint.TIME -> "time"; Hint.TEXT -> "text" }, false, alt)
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

    private fun radixOf(body: String): Int {
        var q = false
        for ((i, c) in body.withIndex()) { if (c == '\'') q = !q; if (!q && (c == '.' || c == 'v' || c == 'V')) return i }
        return -1
    }

    private fun hasPercent(body: String): Boolean = tokens(body).any { !it.startsWith("'") && it.contains('%') }

    private fun num(body: String, raw: String): String? {
        val v = raw.trim().replace(",", "").toBigDecimalOrNull() ?: return null
        val value = if (hasPercent(body)) v.movePointRight(2) else v
        val radix = radixOf(body)
        val intPat = if (radix < 0) body else body.substring(0, radix)
        val fracPat = if (radix < 0) "" else body.substring(radix + 1)
        val fracDigits = tokens(fracPat).filter { !it.startsWith("'") }.sumOf { t -> t.count { it == '9' || it == 'z' || it == 'Z' || it == '8' } }
        val rounded = value.abs().setScale(fracDigits, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        var intDigits = plain.substringBefore('.').trimStart('0')
        val frac = if (fracDigits > 0) plain.substringAfter('.', "").padEnd(fracDigits, '0') else ""
        val neg = value.signum() < 0 && rounded.signum() != 0
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
                '9', 'Z', '8', 'z' -> { sbF.append(frac.getOrElse(fi) { '0' }); fi++ }
                's' -> { if (neg) sbF.append('-'); signDone = true }
                'S' -> { sbF.append(if (neg) '-' else ' '); signDone = true }
                else -> sbF.append(c)
            }
        }
        // 8 = optional trailing digits: drop the trailing zeros they produced (only digits right before literals / the end)
        var fracOut = sbF.toString()
        if (fracPat.contains('8')) fracOut = Regex("0+(?=\\D*$)").replace(fracOut, "")
        val sepOut = if (radix < 0 || (fracPat.contains('8') && fracOut.none { it.isDigit() })) "" else "."
        val text = sbI.toString() + sepOut + fracOut
        return if (neg && !signDone) "-$text" else text
    }

    private fun parseNum(body: String, s: String): String? {
        // keep digits, the radix and a sign; parentheses or a trailing minus also mean negative
        val neg = s.contains('-') || (s.contains('(') && s.contains(')'))
        val digits = StringBuilder(); var seenRadix = false
        for (c in s) when {
            c.isDigit() -> digits.append(Character.getNumericValue(c))      // Arabic-Indic digits too
            c == '.' || c == '٫' -> if (!seenRadix) { digits.append('.'); seenRadix = true } else return null
            c == ',' || c == '٬' || c == ' ' || c == '-' || c == '+' || c == '(' || c == ')' || c == '$' || c == '%' -> {}
            c.isLetter() -> {}                                                  // currency words / units of the pattern
            else -> if (!literalOnly(body).contains(c)) return null
        }
        if (digits.isEmpty() || digits.toString() == ".") return null
        var v = digits.toString().toBigDecimalOrNull() ?: return null
        if (hasPercent(body)) v = v.movePointLeft(2)
        if (neg) v = v.negate()
        return v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    }

    // ---------------------------------------------------------------- date / time

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS_AR = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private val DAYS_AR = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

    /** Canonical XFA date: YYYY-MM-DD or YYYYMMDD (time part ignored). */
    fun parseDate(raw: String): Triple<Int, Int, Int>? {
        val s = raw.trim().substringBefore('T')
        val m = Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").find(s) ?: return null
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return Triple(y, mo, d)
    }

    private fun month(mo: Int, full: Boolean, ar: Boolean) = if (ar) MONTHS_AR[mo - 1] else MONTHS[mo - 1].let { if (full) it else it.take(3) }
    private fun day(dow: Int, full: Boolean, ar: Boolean) = if (ar) DAYS_AR[dow] else DAYS[dow].let { if (full) it else it.take(3) }

    private fun date(body: String, raw: String, ar: Boolean): String? {
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
                t == "MMM" -> month(mo, false, ar); t == "MMMM" -> month(mo, true, ar)
                t == "E" -> (dow + 1).toString(); t == "EEE" -> day(dow, false, ar); t == "EEEE" -> day(dow, true, ar)
                t == "YY" -> "%02d".format(Locale.US, y % 100); t == "YYYY" -> y.toString()
                else -> t
            }
        }
    }

    private fun parseDateText(body: String, s: String): String? {
        val names = (MONTHS.map { it.lowercase() } + MONTHS.map { it.take(3).lowercase() } + MONTHS_AR)
        val rx = StringBuilder("^")
        val roles = ArrayList<String>()
        for (t in tokens(body)) {
            when {
                t.startsWith("'") -> rx.append(Regex.escape(t.substring(1)))
                t == "D" || t == "M" -> { rx.append("(\\d{1,2})"); roles += t }
                t == "DD" || t == "MM" || t == "YY" -> { rx.append("(\\d{2})"); roles += t }
                t == "YYYY" -> { rx.append("(\\d{4})"); roles += t }
                t == "MMM" || t == "MMMM" -> { rx.append("(\\p{L}+)"); roles += "MN" }
                t == "EEE" || t == "EEEE" -> { rx.append("\\p{L}+") }
                t == "E" -> rx.append("\\d")
                t == "J" || t == "JJJ" -> { rx.append("(\\d{1,3})"); roles += "J" }
                t.isBlank() -> rx.append("\\s*")
                else -> rx.append(Regex.escape(t))
            }
        }
        rx.append("$")
        val m = Regex(rx.toString(), RegexOption.IGNORE_CASE).find(toLatinDigits(s)) ?: return null
        var y = -1; var mo = -1; var d = -1
        roles.forEachIndexed { i, r ->
            val g = m.groupValues[i + 1]
            when (r) {
                "D", "DD" -> d = g.toInt()
                "M", "MM" -> mo = g.toInt()
                "YY" -> y = g.toInt().let { if (it < 30) 2000 + it else 1900 + it }    // XFA's default century split (30)
                "YYYY" -> y = g.toInt()
                "MN" -> mo = names.indexOf(g.lowercase()).takeIf { it >= 0 }?.let { it % 12 + 1 } ?: return null
            }
        }
        if (y < 0 || mo !in 1..12 || d !in 1..31) return null
        val cal = GregorianCalendar(y, mo - 1, d)
        if (cal.get(Calendar.DAY_OF_MONTH) != d) return null
        return "%04d-%02d-%02d".format(Locale.US, y, mo, d)
    }

    private fun time(body: String, raw: String, ar: Boolean): String? {
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
                t == "A" -> if (ar) (if (h < 12) "ص" else "م") else if (h < 12) "AM" else "PM"
                else -> t
            }
        }
    }

    private fun parseTime(body: String, s: String): String? {
        val rx = StringBuilder("^"); val roles = ArrayList<String>()
        for (t in tokens(body)) when {
            t.startsWith("'") -> rx.append(Regex.escape(t.substring(1)))
            t in setOf("h", "H", "k", "K", "M", "S") -> { rx.append("(\\d{1,2})"); roles += t }
            t in setOf("hh", "HH", "kk", "KK", "MM", "SS") -> { rx.append("(\\d{2})"); roles += t.take(1) }
            t == "A" -> { rx.append("(AM|PM|am|pm|ص|م)"); roles += "A" }
            t.isBlank() -> rx.append("\\s*")
            else -> rx.append(Regex.escape(t))
        }
        rx.append("$")
        val m = Regex(rx.toString()).find(toLatinDigits(s)) ?: return null
        var h = 0; var mi = 0; var se = 0; var pm: Boolean? = null; var twelve = false
        roles.forEachIndexed { i, r ->
            val g = m.groupValues[i + 1]
            when (r) {
                "h" -> { h = g.toInt() % 12; twelve = true }
                "H" -> h = g.toInt()
                "k" -> { h = g.toInt(); twelve = true }
                "K" -> h = g.toInt() % 24
                "M" -> mi = g.toInt(); "S" -> se = g.toInt()
                "A" -> pm = g.equals("PM", true) || g == "م"
            }
        }
        if (twelve && pm == true) h += 12
        if (h !in 0..23 || mi !in 0..59 || se !in 0..59) return null
        return "%02d:%02d:%02d".format(Locale.US, h, mi, se)
    }

    private fun toLatinDigits(s: String): String = buildString { s.forEach { c -> append(if (c.isDigit()) ('0' + Character.getNumericValue(c)) else c) } }

    // ---------------------------------------------------------------- text

    private fun text(body: String, raw: String): String? {
        val sb = StringBuilder(); var i = 0
        for (t in tokens(body)) {
            if (t.startsWith("'")) { sb.append(t.substring(1)); continue }
            for (c in t) {
                val need = placeholder(c)
                if (need == null) { sb.append(c); continue }
                val ch = raw.getOrNull(i) ?: return null
                if (!need(ch)) return null
                sb.append(ch); i++
            }
        }
        return if (i == raw.length) sb.toString() else null
    }

    private fun placeholder(c: Char): ((Char) -> Boolean)? = when (c) {
        '9' -> { ch -> ch.isDigit() }
        'A' -> { ch -> ch.isLetter() }
        'X' -> { _ -> true }
        'O', '0' -> { ch -> ch.isLetterOrDigit() }
        else -> null
    }

    /** Typed text in the form of a text picture → its placeholder characters (literals dropped). */
    private fun parseText(body: String, s: String): String? {
        val out = StringBuilder(); var i = 0
        for (t in tokens(body)) {
            val lit = if (t.startsWith("'")) t.substring(1) else null
            if (lit != null) { if (!s.startsWith(lit, i)) return null; i += lit.length; continue }
            for (c in t) {
                val need = placeholder(c)
                if (need == null) { if (s.getOrNull(i) != c) return null; i++; continue }
                val ch = s.getOrNull(i) ?: return null
                if (!need(ch)) return null
                out.append(ch); i++
            }
        }
        return if (i == s.length) out.toString() else null
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
}
