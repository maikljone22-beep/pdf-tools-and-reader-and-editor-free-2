package com.smartdocs.editor.core.edit

import com.smartdocs.editor.core.pdf.ArabicShaper

/**
 * Bidirectional text for the content editor, one line at a time (no explicit embeddings).
 *
 * - [logicalToVisual] is a compact implementation of the Unicode bidi algorithm (W1–W7, N1/N2, I1/I2, L2 and
 *   mirroring) for a paragraph of base direction RTL or LTR: Arabic is reversed, Latin words, phrases
 *   ("Smart Docs") and numbers (ASCII, Arabic-Indic, Extended Arabic-Indic, "12.5", "2024/05/01", "50%") keep their
 *   left-to-right order.
 * - [visualToLogical] undoes it for text read back from a page in visual (left-to-right glyph position) order.
 *   The inverse is not unique ("Hello 123" and "123 Hello" after Arabic draw the same); Latin-first is assumed.
 *
 * [com.smartdocs.editor.core.pdf.ArabicShaper.shape] alone is NOT used for reordering here: it reverses
 * Arabic-Indic digits ("١٢" → "٢١") and Latin phrases inside Arabic. It is only used to pick the contextual
 * presentation forms of runs that contain nothing but Arabic letters and neutrals.
 */
object Bidi {
    private enum class T { L, R, EN, AN, ES, ET, CS, NSM, WS, ON }

    private val mirrored = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    fun mirror(c: Char): Char = mirrored[c] ?: c

    fun isArabicMark(c: Char): Boolean {
        val k = c.code
        return k in 0x064B..0x065F || k == 0x0670 || k in 0x06D6..0x06DC || k in 0x06DF..0x06E4 || k in 0x06E7..0x06E8 || k in 0x06EA..0x06ED
    }

    private fun type(c: Char): T {
        val k = c.code
        return when {
            k in 0x0660..0x0669 || k == 0x066B || k == 0x066C -> T.AN
            c in '0'..'9' || k in 0x06F0..0x06F9 -> T.EN
            isArabicMark(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> T.NSM
            k in 0x0590..0x08FF || k in 0xFB1D..0xFDFF || k in 0xFE70..0xFEFE -> T.R
            c == '+' || c == '-' -> T.ES
            c == '%' || c == '$' || c == '#' || c == '°' || k == 0x066A || c == '€' || c == '£' -> T.ET
            c == ',' || c == '.' || c == ':' || c == '/' || k == 0x00A0 -> T.CS
            c == ' ' || c == '\t' -> T.WS
            c.isLetter() -> T.L
            else -> T.ON
        }
    }

    private fun isRtlStrong(c: Char) = type(c) == T.R

    /** True when the first strong character of [s] is right-to-left (Arabic, Hebrew). */
    fun firstStrongRtl(s: String): Boolean? {
        for (c in s) when (type(c)) { T.R -> return true; T.L -> return false; else -> {} }
        return null
    }

    /** Embedding levels (0, 1 or 2) of the characters of one logical line. */
    fun levels(s: String, rtl: Boolean): IntArray {
        val n = s.length
        val base = if (rtl) T.R else T.L
        val t = Array(n) { type(s[it]) }
        // W1: NSM takes the type of the previous character
        for (i in 0 until n) if (t[i] == T.NSM) t[i] = if (i == 0) base else t[i - 1].let { if (it == T.WS) T.ON else it }
        // W4: a single separator between two numbers of the same kind joins them
        for (i in 1 until n - 1) {
            if (t[i] == T.ES && t[i - 1] == T.EN && t[i + 1] == T.EN) t[i] = T.EN
            else if (t[i] == T.CS && t[i - 1] == T.EN && t[i + 1] == T.EN) t[i] = T.EN
            else if (t[i] == T.CS && t[i - 1] == T.AN && t[i + 1] == T.AN) t[i] = T.AN
        }
        // W5: terminators next to European numbers
        for (i in 0 until n) if (t[i] == T.ET) {
            var j = i; while (j < n && t[j] == T.ET) j++
            val touches = (i > 0 && t[i - 1] == T.EN) || (j < n && t[j] == T.EN)
            for (k in i until j) t[k] = if (touches) T.EN else T.ON
        }
        // W6: remaining separators and terminators are neutral
        for (i in 0 until n) if (t[i] == T.ES || t[i] == T.CS || t[i] == T.ET) t[i] = T.ON
        // W7: European numbers after a Latin letter (or at the start of an LTR paragraph) are left-to-right
        var lastStrong = base
        for (i in 0 until n) { when (t[i]) { T.L, T.R -> lastStrong = t[i]; T.EN -> if (lastStrong == T.L) t[i] = T.L; else -> {} } }
        // N1/N2: neutrals take the direction of their neighbours when both agree, else the paragraph direction
        fun dir(x: T): T? = when (x) { T.L -> T.L; T.R, T.EN, T.AN -> T.R; else -> null }
        var i = 0
        while (i < n) {
            if (dir(t[i]) != null) { i++; continue }
            var j = i; while (j < n && dir(t[j]) == null) j++
            val before = if (i == 0) base else dir(t[i - 1])!!
            val after = if (j == n) base else dir(t[j])!!
            val r = if (before == after) before else base
            for (k in i until j) t[k] = r
            i = j
        }
        // I1/I2
        // (after N1/N2 every character is L, R, EN or AN)
        return IntArray(n) { k ->
            when (t[k]) {
                T.R -> 1
                T.EN, T.AN -> 2
                else -> if (rtl) 2 else 0
            }
        }
    }

    /** Characters of [s] in display (left-to-right) order; mirrored brackets inside right-to-left runs. */
    fun logicalToVisual(s: String, rtl: Boolean): String {
        if (s.isEmpty()) return s
        return reorder(s.toCharArray(), levels(s, rtl))
    }

    private fun reorder(chars: CharArray, lv: IntArray): String {
        val idx = IntArray(chars.size) { it }
        val max = lv.maxOrNull() ?: 0
        val minOdd = lv.filter { it % 2 == 1 }.minOrNull() ?: (max + 1)
        var level = max
        while (level >= minOdd && level >= 1) {
            var i = 0
            while (i < idx.size) {
                if (lv[idx[i]] < level) { i++; continue }
                var j = i; while (j < idx.size && lv[idx[j]] >= level) j++
                idx.reverse(i, j)
                i = j
            }
            level--
        }
        val sb = StringBuilder(chars.size)
        for (k in idx) sb.append(if (lv[k] % 2 == 1) mirror(chars[k]) else chars[k])
        return sb.toString()
    }

    /**
     * One line of logical text → the glyph string to draw left to right: Arabic letters shaped into presentation
     * forms ([ArabicShaper]), then reordered with [logicalToVisual]. Never reverses digits or Latin phrases.
     */
    fun shapeVisual(line: String, rtl: Boolean): String {
        if (line.isEmpty()) return line
        if (line.none { isRtlStrong(it) || type(it) == T.AN }) return line
        // shape every maximal run of Arabic letters (+ marks, + tatweel) in logical order, keeping the logical order
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (!isShapable(c)) { sb.append(c); i++; continue }
            var j = i; while (j < line.length && isShapable(line[j])) j++
            sb.append(shapeLogical(line.substring(i, j)))
            i = j
        }
        return logicalToVisual(sb.toString(), rtl)
    }

    private fun isShapable(c: Char) = c.code in 0x0621..0x064A || c.code in 0x064B..0x065F || c.code == 0x0670 || c.code == 0x0640 ||
        c.code in 0x0671..0x06D3 || c.code == 0x06D5

    /** Presentation forms of an Arabic word in logical order (the shaper's own output is visual: undo its reversal). */
    private fun shapeLogical(word: String): String {
        if (!ArabicShaper.isArabic(word)) return word
        return ArabicShaper.shapeRaw(word).reversed()
    }

    // ---------------- reading back ----------------

    private fun ltrIsland(c: Char): Boolean = when (type(c)) { T.L, T.EN, T.AN -> true; else -> false }
    private fun numberGlue(c: Char): Boolean = when (type(c)) { T.ES, T.ET, T.CS -> true; else -> false }

    /**
     * Logical order of one line of glyphs given in visual (left-to-right) order. [rtl]: the line is right-to-left
     * (Arabic); otherwise only the Arabic stretches inside it are reversed.
     */
    fun visualToLogical(s: String, rtl: Boolean = true): String {
        if (s.isEmpty()) return s
        return if (rtl) rtlLine(s) else ltrLine(s)
    }

    /** Segments of [s]: (isLtrIsland, text) in visual order. */
    private fun islands(s: String): List<Pair<Boolean, String>> {
        val n = s.length
        val kind = BooleanArray(n) { ltrIsland(s[it]) }
        // separators / terminators glued to a number or a word ("12.5", "50%", "a-b", "x/y")
        for (i in 0 until n) if (!kind[i] && numberGlue(s[i])) {
            val prev = i > 0 && kind[i - 1]
            val next = i + 1 < n && ltrIsland(s[i + 1])
            if ((prev && next) || ((s[i] == '%' || s[i].code == 0x066A || s[i] == '$') && (prev || next))) kind[i] = true
        }
        val raw = ArrayList<Pair<Boolean, StringBuilder>>()
        for (i in 0 until n) {
            if (raw.isEmpty() || raw.last().first != kind[i]) raw += kind[i] to StringBuilder()
            raw.last().second.append(s[i])
        }
        // spaces / punctuation between two left-to-right islands join them when one of them holds Latin letters
        val out = ArrayList<Pair<Boolean, String>>()
        var k = 0
        while (k < raw.size) {
            val (ltr, sb) = raw[k]
            if (!ltr && k > 0 && k + 1 < raw.size && raw[k - 1].first && raw[k + 1].first &&
                sb.all { it == ' ' || it == '-' || it == '.' || it == ',' || it == '/' || it == '&' || it == '_' || it == ':' } &&
                (raw[k - 1].second.any { type(it) == T.L } && (raw[k + 1].second.any { type(it) == T.L || type(it) == T.EN }))
            ) {
                // merge previous island + neutral + next island
                val prev = out.removeAt(out.size - 1)
                out += true to (prev.second + sb + raw[k + 1].second)
                k += 2
                continue
            }
            if (ltr && out.isNotEmpty() && out.last().first) out[out.size - 1] = true to (out.last().second + sb)
            else out += ltr to sb.toString()
            k++
        }
        return out
    }

    private fun rtlLine(s: String): String {
        val segs = islands(s)
        val sb = StringBuilder(s.length)
        for ((ltr, text) in segs.asReversed()) {
            if (ltr) sb.append(text) else for (c in text.reversed()) sb.append(mirror(c))
        }
        return sb.toString()
    }

    /** Left-to-right line: reverse each Arabic stretch (Arabic letters with the spaces / digits between them). */
    private fun ltrLine(s: String): String {
        val n = s.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            if (!isRtlStrong(s[i]) && type(s[i]) != T.NSM) { sb.append(s[i]); i++; continue }
            // extend to the last right-to-left letter reachable without crossing a Latin letter
            var j = i; var lastR = i
            while (j < n && type(s[j]) != T.L) { if (isRtlStrong(s[j]) || type(s[j]) == T.NSM) lastR = j; j++ }
            sb.append(rtlLine(s.substring(i, lastR + 1)))
            i = lastR + 1
        }
        return sb.toString()
    }
}
