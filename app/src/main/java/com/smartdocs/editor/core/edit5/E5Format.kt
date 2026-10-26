package com.smartdocs.editor.core.edit5

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Digit helpers (pure). */
object Digits {
    private const val ARABIC_INDIC_ZERO = 0x0660
    private const val EXT_ARABIC_INDIC_ZERO = 0x06F0

    /** Western digits → Arabic-Indic digits. */
    fun toArabic(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') (ARABIC_INDIC_ZERO + (c - '0')).toChar() else c)
    }

    /** Arabic-Indic / extended (Persian) digits → Western digits; other characters unchanged. */
    fun toWestern(s: String): String = buildString(s.length) {
        for (c in s) {
            val code = c.code
            append(when (code) {
                in ARABIC_INDIC_ZERO..ARABIC_INDIC_ZERO + 9 -> ('0' + (code - ARABIC_INDIC_ZERO))
                in EXT_ARABIC_INDIC_ZERO..EXT_ARABIC_INDIC_ZERO + 9 -> ('0' + (code - EXT_ARABIC_INDIC_ZERO))
                else -> c
            })
        }
    }
}

/** Bates number format: [prefix] + zero-padded number ([digits]) + [suffix]. */
data class BatesSpec(val prefix: String = "", val suffix: String = "", val digits: Int = 6, val start: Long = 1)

object Bates {
    fun format(spec: BatesSpec, n: Long): String = spec.prefix + n.toString().padStart(spec.digits.coerceIn(1, 15), '0') + spec.suffix

    /** Number of the [index]-th (zero-based) stamped page. */
    fun numberAt(spec: BatesSpec, index: Int): Long = spec.start + index

    /**
     * Numbering carried across files: for each file's page count, the first and last Bates number it receives.
     * The next file continues where the previous one stopped.
     */
    fun ranges(spec: BatesSpec, pageCounts: List<Int>): List<LongRange> {
        var next = spec.start
        return pageCounts.map { c -> val r = next until next + c.coerceAtLeast(0); next += c.coerceAtLeast(0); r }
    }
}

/** PDF page label styles (ISO 32000 §12.4.2) plus [NONE] (prefix only). */
enum class LabelStyle(val pdf: String?) { DECIMAL("D"), ROMAN_UPPER("R"), ROMAN_LOWER("r"), LETTERS_UPPER("A"), LETTERS_LOWER("a"), NONE(null) }

/** A page label range starting at zero-based page [startPage]. */
data class LabelRange(val startPage: Int, val style: LabelStyle = LabelStyle.DECIMAL, val prefix: String = "", val start: Int = 1)

object PageLabelFormat {
    fun roman(n: Int, upper: Boolean = true): String {
        if (n <= 0) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return if (upper) sb.toString() else sb.toString().lowercase()
    }

    /** PDF letter numbering: A..Z, then AA..ZZ, then AAA … (the letter repeated). */
    fun letters(n: Int, upper: Boolean = true): String {
        if (n <= 0) return n.toString()
        val letter = 'A' + (n - 1) % 26
        val times = (n - 1) / 26 + 1
        val s = letter.toString().repeat(times)
        return if (upper) s else s.lowercase()
    }

    fun number(style: LabelStyle, n: Int): String = when (style) {
        LabelStyle.DECIMAL -> n.toString()
        LabelStyle.ROMAN_UPPER -> roman(n, true)
        LabelStyle.ROMAN_LOWER -> roman(n, false)
        LabelStyle.LETTERS_UPPER -> letters(n, true)
        LabelStyle.LETTERS_LOWER -> letters(n, false)
        LabelStyle.NONE -> ""
    }

    /** Label of every page. Pages before the first range get their plain number (as viewers do). */
    fun labels(ranges: List<LabelRange>, pageCount: Int): List<String> {
        val sorted = ranges.filter { it.startPage in 0 until pageCount }.sortedBy { it.startPage }.distinctBy { it.startPage }
        return (0 until pageCount).map { p ->
            val r = sorted.lastOrNull { it.startPage <= p } ?: return@map (p + 1).toString()
            r.prefix + number(r.style, r.start + (p - r.startPage))
        }
    }

    /** Resolve typed text to a zero-based page: an exact label first, then a plain physical number. */
    fun find(labels: List<String>, typed: String): Int? {
        val t = Digits.toWestern(typed.trim())
        if (t.isEmpty()) return null
        labels.indexOfFirst { it == t }.takeIf { it >= 0 }?.let { return it }
        labels.indexOfFirst { it.equals(t, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        return t.toIntOrNull()?.let { n -> (n - 1).takeIf { it in labels.indices } }
    }
}

/** Values for header/footer/watermark tokens. [page] is the displayed number (already offset by the start number). */
data class TokenContext(
    val page: Int = 1,
    val pages: Int = 1,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now(),
    val file: String = "",
    val title: String = "",
    val author: String = "",
    val user: String = "",
    val bates: String = "",
    val label: String = "",
    val arabic: Boolean = true,
    val ummAlQura: Boolean = true,
)

/**
 * Token expansion for header/footer/watermark text:
 * `{page} {pages} {date} {date:yyyy/MM/dd} {time} {time:HH:mm} {datetime} {hijri} {hijri:d MMMM yyyy} {file} {title}
 * {author} {user} {bates} {label}`. Unknown tokens stay as typed. [arabicDigits] converts every digit in the result.
 */
object Tokens {
    val ALL = listOf("{page}", "{pages}", "{date}", "{time}", "{hijri}", "{file}", "{title}", "{author}", "{user}", "{bates}", "{label}")
    private val re = Regex("""\{([a-z]+)(?::([^}]*))?\}""")

    fun expand(template: String, ctx: TokenContext, arabicDigits: Boolean = false): String {
        val locale = if (ctx.arabic) Locale.forLanguageTag("ar") else Locale.ENGLISH
        val out = re.replace(template) { m ->
            val key = m.groupValues[1]; val arg = m.groupValues[2].ifEmpty { null }
            when (key) {
                "page", "n" -> ctx.page.toString()
                "pages", "total" -> ctx.pages.toString()
                "date" -> fmt(arg ?: "yyyy/MM/dd", locale) { ctx.date.format(it) }
                "time" -> fmt(arg ?: "HH:mm", locale) { ctx.time.format(it) }
                "datetime" -> fmt(arg ?: "yyyy/MM/dd HH:mm", locale) { ctx.date.atTime(ctx.time).format(it) }
                "hijri" -> HijriDate.format(HijriDate.of(ctx.date, ctx.ummAlQura), arg ?: "d MMMM yyyy", ctx.arabic) + if (arg == null) (if (ctx.arabic) " هـ" else " AH") else ""
                "file" -> ctx.file
                "title" -> ctx.title
                "author" -> ctx.author
                "user" -> ctx.user
                "bates" -> ctx.bates
                "label" -> ctx.label.ifEmpty { ctx.page.toString() }
                else -> m.value
            }
        }
        // DateTimeFormatter always emits Western digits (DecimalStyle.STANDARD); convert here so all digits match
        return if (arabicDigits) Digits.toArabic(out) else out
    }

    private fun fmt(pattern: String, locale: Locale, f: (DateTimeFormatter) -> String): String =
        runCatching { f(DateTimeFormatter.ofPattern(pattern, locale)) }.getOrElse { pattern }

    fun usesPages(template: String) = template.contains("{pages}") || template.contains("{total}")
}

/** Page subsets applied after a range. */
enum class PageSubset { ALL, ODD, EVEN, PORTRAIT, LANDSCAPE }

object PageSet {
    /** "1-3, 5, 8-" style range (Arabic comma and digits accepted) into sorted zero-based indexes; blank = all. */
    fun parse(spec: String, count: Int): List<Int> {
        val s = Digits.toWestern(spec).replace('،', ',').trim()
        if (s.isEmpty()) return (0 until count).toList()
        val out = sortedSetOf<Int>()
        s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val m = Regex("""^(\d*)\s*[-–]\s*(\d*)$""").find(part)
            if (m != null) {
                val a = (m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, count.coerceAtLeast(1))   // clamp: huge ranges hang
                val b = (m.groupValues[2].toIntOrNull() ?: count).coerceIn(1, count.coerceAtLeast(1))
                for (i in minOf(a, b)..maxOf(a, b)) if (i in 1..count) out += i - 1
            } else part.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }
        }
        return out.toList()
    }

    fun filter(pages: List<Int>, subset: PageSubset, isLandscape: (Int) -> Boolean = { false }): List<Int> = when (subset) {
        PageSubset.ALL -> pages
        PageSubset.ODD -> pages.filter { it % 2 == 0 }     // zero-based index 0 = page 1 (odd)
        PageSubset.EVEN -> pages.filter { it % 2 == 1 }
        PageSubset.PORTRAIT -> pages.filter { !isLandscape(it) }
        PageSubset.LANDSCAPE -> pages.filter { isLandscape(it) }
    }
}
