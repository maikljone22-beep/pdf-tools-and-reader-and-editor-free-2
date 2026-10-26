package com.smartdocs.editor.core.forms

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Parsed `/AA /F` format action of a field (Acrobat AF* helpers). */
sealed class FormatSpec {
    /** AFNumber_Format(nDec, sepStyle, negStyle, currStyle, strCurrency, bCurrencyPrepend) */
    data class Number(val decimals: Int, val sepStyle: Int, val negStyle: Int, val currency: String, val prepend: Boolean) : FormatSpec()
    /** AFPercent_Format(nDec, sepStyle[, bPercentPrepend]) */
    data class Percent(val decimals: Int, val sepStyle: Int, val prepend: Boolean = false) : FormatSpec()
    /** AFDate_FormatEx("fmt") / AFDate_Format(i) / AFTime_Format(i) / AFTime_FormatEx("fmt"): Acrobat date pattern */
    data class DateTime(val pattern: String, val timeOnly: Boolean = false) : FormatSpec()
    /** AFSpecial_Format(0..3): zip, zip+4, phone, SSN */
    data class Special(val kind: Int) : FormatSpec()
}

/** Input kind derived from `/AA /K` keystroke actions (used to choose the keyboard / picker). */
enum class KeystrokeKind { NONE, NUMBER, PERCENT, DATE, TIME, SPECIAL }

/** Text shown for a value: [red] for Acrobat negative styles 1 and 3. */
data class Display(val text: String, val red: Boolean = false)

object AfFormat {

    private val dateFormats = listOf("m/d", "m/d/yy", "mm/dd/yy", "mm/yy", "d-mmm", "d-mmm-yy", "dd-mmm-yy", "yy-mm-dd", "mmm-yy", "mmmm-yy", "mmm d, yyyy", "mmmm d, yyyy", "m/d/yy h:MM tt", "m/d/yy HH:MM")
    private val timeFormats = listOf("HH:MM", "h:MM tt", "HH:MM:ss", "h:MM:ss tt")

    /** Split a JS argument list (top level commas, strings kept). */
    internal fun args(s: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        var depth = 0; var quote: Char? = null; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> { sb.append(c); if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i++ } else if (c == quote) quote = null }
                c == '"' || c == '\'' -> { quote = c; sb.append(c) }
                c == '(' || c == '[' -> { depth++; sb.append(c) }
                c == ')' || c == ']' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }

    internal fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) {
            val sb = StringBuilder(); var i = 1
            while (i < t.length - 1) { if (t[i] == '\\' && i + 1 < t.length - 1) i++; sb.append(t[i]); i++ }
            return sb.toString()
        }
        return t
    }

    private fun callArgs(js: String, fn: String): List<String>? {
        val m = Regex("""\b$fn\s*\(""").find(js) ?: return null
        var depth = 1; var i = m.range.last + 1; val start = i; var quote: Char? = null
        while (i < js.length && depth > 0) {
            val c = js[i]
            if (quote != null) { if (c == '\\') i++ else if (c == quote) quote = null }
            else when (c) { '"', '\'' -> quote = c; '(' -> depth++; ')' -> depth-- }
            i++
        }
        return args(js.substring(start, (i - 1).coerceAtLeast(start)))
    }

    private fun int(a: List<String>, i: Int, def: Int) = a.getOrNull(i)?.trim()?.toDoubleOrNull()?.toInt() ?: def
    private fun bool(a: List<String>, i: Int, def: Boolean) = a.getOrNull(i)?.trim()?.let { it == "true" || it == "1" } ?: def

    fun parseFormat(js: String?): FormatSpec? {
        if (js.isNullOrBlank()) return null
        callArgs(js, "AFNumber_Format")?.let { a -> return FormatSpec.Number(int(a, 0, 2), int(a, 1, 0), int(a, 2, 0), unquote(a.getOrElse(4) { "\"\"" }), bool(a, 5, true)) }
        callArgs(js, "AFPercent_Format")?.let { a -> return FormatSpec.Percent(int(a, 0, 2), int(a, 1, 0), bool(a, 2, false)) }
        callArgs(js, "AFDate_FormatEx")?.let { a -> return FormatSpec.DateTime(unquote(a.getOrElse(0) { "\"mm/dd/yyyy\"" })) }
        callArgs(js, "AFDate_Format")?.let { a -> return FormatSpec.DateTime(dateFormats.getOrElse(int(a, 0, 2)) { "mm/dd/yy" }) }
        callArgs(js, "AFTime_FormatEx")?.let { a -> return FormatSpec.DateTime(unquote(a.getOrElse(0) { "\"HH:MM\"" }), timeOnly = true) }
        callArgs(js, "AFTime_Format")?.let { a -> return FormatSpec.DateTime(timeFormats.getOrElse(int(a, 0, 0)) { "HH:MM" }, timeOnly = true) }
        callArgs(js, "AFSpecial_Format")?.let { a -> return FormatSpec.Special(int(a, 0, 0)) }
        return null
    }

    fun parseKeystroke(js: String?): KeystrokeKind {
        if (js.isNullOrBlank()) return KeystrokeKind.NONE
        return when {
            js.contains("AFNumber_Keystroke") -> KeystrokeKind.NUMBER
            js.contains("AFPercent_Keystroke") -> KeystrokeKind.PERCENT
            js.contains("AFDate_Keystroke") -> KeystrokeKind.DATE
            js.contains("AFTime_Keystroke") -> KeystrokeKind.TIME
            js.contains("AFSpecial_Keystroke") -> KeystrokeKind.SPECIAL
            else -> KeystrokeKind.NONE
        }
    }

    // ---------------------------------------------------------------- numbers

    fun formatNumber(v: Double, decimals: Int, sepStyle: Int): String {
        val d = decimals.coerceIn(0, 10)
        if (v.isNaN() || v.isInfinite()) return ""
        val fixed = java.math.BigDecimal(abs(v)).setScale(d, java.math.RoundingMode.HALF_UP).toPlainString()
        val intPart = fixed.substringBefore('.')
        val frac = if (d > 0) fixed.substringAfter('.', "") else ""
        val (group, dec) = when (sepStyle) { 1 -> "" to "."; 2 -> "." to ","; 3 -> "" to ","; 4 -> "'" to "."; else -> "," to "." }
        val grouped = if (group.isEmpty()) intPart else intPart.reversed().chunked(3).joinToString(group).reversed()
        return if (frac.isEmpty()) grouped else grouped + dec + frac
    }

    fun format(spec: FormatSpec?, raw: String): Display {
        if (spec == null || raw.isBlank()) return Display(raw)
        return when (spec) {
            is FormatSpec.Number -> {
                val v = AfNumbers.parse(raw) ?: return Display(raw)
                var body = formatNumber(v, spec.decimals, spec.sepStyle)
                if (spec.currency.isNotEmpty()) body = if (spec.prepend) spec.currency + body else body + spec.currency
                val neg = v < 0 && formatNumber(v, spec.decimals, 1).any { it in '1'..'9' }
                when {
                    !neg -> Display(body)
                    spec.negStyle == 1 -> Display(body, red = true)
                    spec.negStyle == 2 -> Display("($body)")
                    spec.negStyle == 3 -> Display("($body)", red = true)
                    else -> Display("-$body")
                }
            }
            is FormatSpec.Percent -> {
                val v = AfNumbers.parse(raw) ?: return Display(raw)
                val body = formatNumber(v * 100, spec.decimals, spec.sepStyle)
                val sign = if (v < 0) "-" else ""
                Display(if (spec.prepend) "%$sign$body" else "$sign$body%")
            }
            is FormatSpec.DateTime -> {
                val date = parseDate(raw, spec.pattern) ?: return Display(raw)
                Display(runCatching { SimpleDateFormat(toJavaPattern(spec.pattern), Locale.US).format(date) }.getOrDefault(raw))
            }
            is FormatSpec.Special -> {
                val digits = AfNumbers.normalizeDigits(raw).filter { it.isDigit() }
                Display(when (spec.kind) {
                    0 -> digits.take(5)
                    1 -> if (digits.length >= 9) digits.substring(0, 5) + "-" + digits.substring(5, 9) else digits
                    2 -> if (digits.length == 10) "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}" else if (digits.length == 7) digits.substring(0, 3) + "-" + digits.substring(3) else raw
                    3 -> if (digits.length == 9) "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}" else raw
                    else -> raw
                })
            }
        }
    }

    // ---------------------------------------------------------------- dates

    /** Convert an Acrobat date pattern (m, mm, mmm, mmmm, d, dd, ddd, dddd, yy, yyyy, H, HH, h, hh, M, MM, s, ss, t, tt) to SimpleDateFormat. */
    fun toJavaPattern(acro: String): String {
        val tokens = listOf(
            "mmmm" to "MMMM", "mmm" to "MMM", "mm" to "MM", "m" to "M",
            "dddd" to "EEEE", "ddd" to "EEE", "dd" to "dd", "d" to "d",
            "yyyy" to "yyyy", "yy" to "yy",
            "HH" to "HH", "H" to "H", "hh" to "hh", "h" to "h",
            "MM" to "mm", "M" to "m", "ss" to "ss", "s" to "s", "tt" to "a", "t" to "a",
        )
        val sb = StringBuilder(); var i = 0; var inQuote = false
        fun closeQuote() { if (inQuote) { sb.append('\''); inQuote = false } }
        while (i < acro.length) {
            val match = tokens.firstOrNull { acro.startsWith(it.first, i) }
            if (match != null) { closeQuote(); sb.append(match.second); i += match.first.length; continue }
            val c = acro[i]
            if (c.isLetter()) { if (!inQuote) { sb.append('\''); inQuote = true }; sb.append(c) }
            else if (c == '\'') { closeQuote(); sb.append("''") }
            else { closeQuote(); sb.append(c) }
            i++
        }
        closeQuote()
        return sb.toString()
    }

    private val fallbackPatterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "dd.MM.yyyy", "MM/dd/yyyy", "d MMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "yyyyMMdd", "HH:mm", "h:mm a", "HH:mm:ss")

    fun parseDate(raw: String, acroPattern: String?): Date? {
        val s = AfNumbers.normalizeDigits(raw).trim()
        if (s.isEmpty()) return null
        val patterns = buildList { if (acroPattern != null) add(toJavaPattern(acroPattern)); addAll(fallbackPatterns) }
        for (p in patterns) {
            val d = runCatching { SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(s) }.getOrNull()
            if (d != null) return d
        }
        return null
    }

    /** Value to store after the user picked a date in a date picker: formatted with the field's own pattern. */
    fun formatPickedDate(millis: Long, spec: FormatSpec?): String {
        val p = (spec as? FormatSpec.DateTime)?.pattern ?: "yyyy-mm-dd"
        // the pattern comes from the PDF: an invalid one must not crash the date picker
        return runCatching { SimpleDateFormat(toJavaPattern(p), Locale.US).format(Date(millis)) }
            .getOrElse { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis)) }
    }
}
