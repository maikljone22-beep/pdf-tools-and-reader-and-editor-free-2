package com.smartdocs.editor.core.nve

/** Word count of a text (Foxit / Word "Word Count"). */
data class WordStats(
    val words: Int = 0,
    val chars: Int = 0,
    val charsNoSpaces: Int = 0,
    val lines: Int = 0,
    val paragraphs: Int = 0,
    /** Arabic-script letters. */
    val arabic: Int = 0,
    /** CJK / Asian characters (each counts as a word, as Word does). */
    val asian: Int = 0,
    val pages: Int = 0,
) {
    operator fun plus(o: WordStats) = WordStats(words + o.words, chars + o.chars, charsNoSpaces + o.charsNoSpaces, lines + o.lines,
        paragraphs + o.paragraphs, arabic + o.arabic, asian + o.asian, pages + o.pages)
}

object TextStats {
    fun isAsian(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c) ?: return false
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    fun isArabic(c: Char): Boolean = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    /** Statistics of [text] counted as [pages] page(s). */
    fun of(text: String, pages: Int = 0): WordStats {
        var words = 0; var inWord = false; var asian = 0; var arabic = 0; var noSpace = 0
        for (c in text) {
            if (!c.isWhitespace()) noSpace++
            if (isArabic(c) && c.isLetter()) arabic++
            when {
                isAsian(c) -> { asian++; words++; inWord = false }
                c.isLetterOrDigit() || c == '\'' || c == '-' && inWord || Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> { if (!inWord) { words++; inWord = true } }
                else -> inWord = false
            }
        }
        val lines = text.split('\n').count { it.isNotBlank() }
        val paragraphs = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        return WordStats(words, text.length, noSpace, lines, paragraphs, arabic, asian, pages)
    }
}

/** A character of the page text with its box (PDF display space, y up: top > bottom). */
data class CharBox(val c: Char, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * "Copy as table": the characters of a selection grouped into rows (by baseline) and columns (by large horizontal
 * gaps that line up across rows). Pure geometry, no Android.
 */
object TableCopy {
    fun rows(chars: List<CharBox>): List<List<CharBox>> {
        val cs = chars.filter { !it.c.isWhitespace() && it.top > it.bottom }
        if (cs.isEmpty()) return emptyList()
        val h = cs.map { it.top - it.bottom }.sorted()[cs.size / 2].coerceAtLeast(1f)
        val sorted = cs.sortedByDescending { (it.top + it.bottom) / 2f }
        val out = ArrayList<MutableList<CharBox>>()
        var curY = Float.NaN
        for (c in sorted) {
            val y = (c.top + c.bottom) / 2f
            if (curY.isNaN() || kotlin.math.abs(y - curY) > h * 0.6f) { out.add(mutableListOf(c)); curY = y } else out.last().add(c)
        }
        return out.map { r -> r.sortedBy { it.left } }
    }

    /** Cells of each row: a gap wider than [gapFactor] × the median char height starts a new cell. */
    fun cells(chars: List<CharBox>, gapFactor: Float = 1.2f, rtl: Boolean = false): List<List<String>> {
        val rs = rows(chars)
        if (rs.isEmpty()) return emptyList()
        val all = rs.flatten()
        val h = all.map { it.top - it.bottom }.sorted()[all.size / 2].coerceAtLeast(1f)
        val space = h * 0.25f
        return rs.map { r ->
            val cells = ArrayList<StringBuilder>()
            var prev: CharBox? = null
            for (c in r) {
                val gap = if (prev == null) Float.MAX_VALUE else c.left - prev.right
                when {
                    prev == null || gap > h * gapFactor -> cells.add(StringBuilder().append(c.c))
                    gap > space -> cells.last().append(' ').append(c.c)
                    else -> cells.last().append(c.c)
                }
                prev = c
            }
            // characters of RTL scripts come out in visual order: reverse each cell and the cell order
            val texts = cells.map { sb -> if (rtl) sb.toString().reversed() else sb.toString() }
            if (rtl) texts.reversed() else texts
        }
    }

    fun toTsv(cells: List<List<String>>): String = cells.joinToString("\n") { r -> r.joinToString("\t") { it.replace('\t', ' ') } }

    fun toHtml(cells: List<List<String>>, rtl: Boolean = false): String = buildString {
        append("<table border=\"1\" style=\"border-collapse:collapse\"").append(if (rtl) " dir=\"rtl\"" else "").append(">")
        cells.forEach { r -> append("<tr>"); r.forEach { append("<td>").append(esc(it)).append("</td>") }; append("</tr>") }
        append("</table>")
    }

    fun toCsv(cells: List<List<String>>): String = cells.joinToString("\r\n") { r ->
        r.joinToString(",") { v -> if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** A run of text with its formatting, for "copy with formatting" (HTML clipboard). */
data class StyledRun(val text: String, val size: Float, val bold: Boolean, val italic: Boolean, val color: Int, val family: String, val newLine: Boolean = false)

object StyledCopy {
    fun html(runs: List<StyledRun>, rtl: Boolean): String = buildString {
        append("<div").append(if (rtl) " dir=\"rtl\"" else "").append(">")
        runs.forEach { r ->
            if (r.newLine) append("<br>")
            val css = StringBuilder()
            css.append("font-size:").append(String.format(java.util.Locale.US, "%.1f", r.size)).append("pt;")
            if (r.bold) css.append("font-weight:bold;")
            if (r.italic) css.append("font-style:italic;")
            css.append("color:#").append(String.format("%06X", r.color and 0xFFFFFF)).append(";")
            if (r.family.isNotBlank()) css.append("font-family:'").append(TableCopy.esc(r.family)).append("';")
            append("<span style=\"").append(css).append("\">").append(TableCopy.esc(r.text)).append("</span>")
        }
        append("</div>")
    }
}
