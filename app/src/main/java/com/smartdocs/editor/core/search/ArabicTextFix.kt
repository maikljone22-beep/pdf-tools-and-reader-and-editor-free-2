package com.smartdocs.editor.core.search

import java.text.Normalizer

/**
 * Makes Arabic text extracted from PDFs readable when it is copied, shared, translated or sent to the AI.
 * Many PDFs store Arabic as presentation-form glyphs (U+FB50..U+FEFF) and often in visual order (each line
 * reversed). Copied as-is the text pastes as broken, reversed letters. [fix] converts presentation forms to
 * ordinary letters and puts visually ordered lines back into logical order.
 * Do not use the result for character-index work (search hits, selection boxes): its length can differ.
 */
object ArabicTextFix {

    fun fix(text0: String): String {
        val text = demojibake(text0)
        if (text.none { isArabicChar(it) }) return text
        return text.split('\n').joinToString("\n") { fixLine(it) }
    }

    fun fixLine(line: String): String {
        if (line.none { isArabicChar(it) }) return line
        val reversed = isVisualOrder(line)
        val base = toBaseLetters(line)
        return if (reversed) reverseVisualLine(base) else base
    }

    /**
     * Arabic encoded as Windows-1256 but read as Windows-1252 ("ÇáÓáÇã" instead of "السلام"), a common result of
     * old Word/Office PDFs. Re-decodes character by character, so the length and character indexes stay the same.
     * Returns [text] unchanged when it does not look like that.
     */
    fun demojibake(text: String): String {
        if (!looksMojibake(text)) return text
        val cp1252 = runCatching { java.nio.charset.Charset.forName("windows-1252") }.getOrNull() ?: return text
        val cp1256 = runCatching { java.nio.charset.Charset.forName("windows-1256") }.getOrNull() ?: return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.code in 0x80..0xFF || c.code in 0x152..0x2122) {
                val b = c.toString().toByteArray(cp1252)
                val d = if (b.size == 1 && b[0] != '?'.code.toByte()) String(b, cp1256) else c.toString()
                sb.append(if (d.length == 1) d else c.toString())
            } else sb.append(c)
        }
        return sb.toString()
    }

    /** No Arabic letters, and at least 30 % of the letters are in the Latin-1 accented range typical of cp1256 mojibake. */
    fun looksMojibake(text: String): Boolean {
        if (text.any { isArabicChar(it) }) return false
        val letters = text.count { it.isLetter() }
        if (letters < 4) return false
        val high = text.count { it.code in 0xC0..0xFF }
        return high * 10 >= letters * 3
    }

    /**
     * Text extraction that is useless for search/copy: mostly private-use glyph codes or replacement characters
     * (fonts without a ToUnicode map). Such pages need OCR.
     */
    fun isGarbage(text: String): Boolean {
        val chars = text.filter { !it.isWhitespace() }
        if (chars.length < 4) return false
        val bad = chars.count { it.code in 0xE000..0xF8FF || it.code == 0xFFFD || it.code < 0x20 }
        return bad * 10 >= chars.length * 3
    }

    /**
     * Text layer that is not usable for search / copy / OCR de-duplication: [isGarbage], or Arabic produced by a
     * font with a wrong ToUnicode map (old Word fonts such as Simplified Arabic, PT Bold Heading, Yakout, Yaser:
     * «ا\u064Cذسط» for «الدرس», «خڀمىفى\u064F\u0655» for «الموجود»). The glyphs on the page are fine; only the extracted text is wrong.
     * Such a page still has real (vector) text, so it must never be rebuilt as a picture; it may only get an
     * invisible OCR layer.
     */
    fun isUnusableText(text: String): Boolean = isGarbage(text) || isBrokenArabic(text)

    /** At least 15 % of the Arabic words (and 4 words) are impossible in real Arabic, see [implausibleWord]. Needs 10 Arabic words. */
    fun isBrokenArabic(text: String): Boolean {
        val words = text.split(' ', '\n', '\r', '\t').map { w -> w.trim { !isArabicChar(it) && !it.isDigit() } }
            .filter { w -> w.count { isArabicLetter(it) } >= 2 }
        if (words.size < 10) return false
        val bad = words.count { implausibleWord(it) }
        return bad >= 4 && bad * 100 >= words.size * 15
    }

    /**
     * A word real Arabic / Persian / Urdu text cannot contain: tanween or a short vowel followed by another letter
     * (tanween only ends a word: «كتاب\u064Bا» is fine, «ا\u064Cذسط» is not), a short vowel starting the word, a letter used
     * by no language written with this script in normal text (U+063B..U+063F, U+066E/U+066F, Sindhi/Pashto letters
     * like ڀ ڄ ٴ, marks U+0656..U+065F), or an Arabic-Indic digit between two letters («١\u0658\u065Aذس\u065Aع\u0653١»).
     */
    fun implausibleWord(w: String): Boolean {
        if (w.isEmpty()) return false
        if (w[0].code in 0x064B..0x0652) return true
        for (i in w.indices) {
            val c = w[i]
            if (isRareLetter(c)) return true
            if (c.code in 0x064B..0x0652) {
                var j = i + 1
                while (j < w.length && w[j].code in 0x064B..0x0652) j++           // stacked marks (shadda + vowel)
                if (j < w.length && isArabicLetter(w[j])) {
                    val tanween = c.code in 0x064B..0x064D
                    val n = w[j]
                    // «\u064Bا» / «\u064Bى» before the final alef is the one legal tanween-then-letter sequence
                    if (tanween && !((n == 'ا' || n == 'ى') && j == w.length - 1)) return true
                }
            }
            if ((c.code in 0x0660..0x0669 || c.code in 0x06F0..0x06F9) && i > 0 && i < w.length - 1 &&
                isArabicLetter(w[i - 1]) && isArabicLetter(w[i + 1])) return true
        }
        return false
    }

    private fun isArabicLetter(c: Char) = (c.code in 0x0621..0x064A || c.code in 0x0671..0x06D3) || isPresentation(c) && c.isLetter()

    /** Letters that do not occur in normal Arabic, Persian or Urdu text (seen only through broken font encodings). */
    private fun isRareLetter(c: Char): Boolean {
        val k = c.code
        // U+0656..U+065F: subscript alef, inverted damma, noon ghunna mark, small v… — not used in normal text
        if (k in 0x063B..0x063F || k == 0x066E || k == 0x066F || k in 0x0656..0x065F) return true
        if (k !in 0x0672..0x06D3) return false
        return k !in COMMON_EXTENDED
    }

    // extended letters in normal use: ٹ پ چ ڈ ڑ ژ ڤ ک ڭ گ ں ھ ۀ ہ ۃ ۆ ی ێ ے ۓ
    private val COMMON_EXTENDED = setOf(0x0679, 0x067E, 0x0686, 0x0688, 0x0691, 0x0698, 0x06A4, 0x06A9, 0x06AD, 0x06AF,
        0x06BA, 0x06BE, 0x06C0, 0x06C1, 0x06C3, 0x06C6, 0x06CC, 0x06CE, 0x06D2, 0x06D3)

    private fun isArabicChar(c: Char) = c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F || isPresentation(c)
    private fun isPresentation(c: Char) = c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF

    /** Presentation forms → ordinary letters (ﻻ → لا, ﻣ → م); other characters are kept. */
    fun toBaseLetters(s: String): String {
        if (s.none { isPresentation(it) }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (isPresentation(c) && c.code != 0xFEFF) sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC))
            else if (c.code != 0xFEFF) sb.append(c)
        }
        return sb.toString()
    }

    private enum class Form { ISOLATED, INITIAL, MEDIAL, FINAL, NONE }

    private fun formOf(c: Char): Form {
        if (!isPresentation(c)) return Form.NONE
        val name = runCatching { Character.getName(c.code) }.getOrNull() ?: return Form.NONE
        return when {
            name.endsWith("INITIAL FORM") -> Form.INITIAL
            name.endsWith("MEDIAL FORM") -> Form.MEDIAL
            name.endsWith("FINAL FORM") -> Form.FINAL
            name.endsWith("ISOLATED FORM") -> Form.ISOLATED
            else -> Form.NONE
        }
    }

    /**
     * True when the line's Arabic words are stored right-to-left reversed (visual order).
     * Presentation forms tell it directly: a logical word starts with an initial form and ends with a final form.
     * Without presentation forms the article is used: logical words start with «ال», reversed ones end with «لا».
     */
    fun isVisualOrder(line: String): Boolean {
        var logical = 0; var visual = 0
        val words = line.split(' ', '\t').filter { w -> w.any { isArabicChar(it) } }
        for (w0 in words) {
            val w = w0.trim { !isArabicChar(it) }
            if (w.length < 2) continue
            val first = formOf(w.first()); val last = formOf(w.last())
            if (first == Form.INITIAL) logical++
            if (last == Form.FINAL) logical++
            if (first == Form.FINAL) visual++
            if (last == Form.INITIAL) visual++
        }
        if (logical + visual > 0) return visual > logical
        // ordinary letters: «ال» at the start of words vs «لا» at the end (reversed article)
        for (w0 in words) {
            val w = toBaseLetters(w0.trim { !isArabicChar(it) })
            if (w.length < 3) continue
            if (w.startsWith("ال")) logical++
            if (w.endsWith("لا")) visual++
        }
        return visual > logical + 1
    }

    /**
     * Reverses a visually ordered line into logical order while keeping runs of Latin letters and digits
     * readable left-to-right, and swapping mirrored brackets.
     */
    fun reverseVisualLine(s: String): String {
        val rev = s.reversed()
        val sb = StringBuilder(rev.length)
        var i = 0
        while (i < rev.length) {
            val c = rev[i]
            if (isLtrChar(c)) {
                var j = i
                while (j < rev.length && (isLtrChar(rev[j]) || (rev[j] in ".,:/-%" && j + 1 < rev.length && isLtrChar(rev[j + 1])))) j++
                sb.append(rev.substring(i, j).reversed())
                i = j
            } else {
                sb.append(mirror(c)); i++
            }
        }
        return sb.toString()
    }

    private fun isLtrChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || c.isDigit() && !(c.code in 0x0660..0x0669 || c.code in 0x06F0..0x06F9) ||
        (c.code in 0x00C0..0x024F)

    private fun mirror(c: Char) = when (c) {
        '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'; '<' -> '>'; '>' -> '<'
        '«' -> '»'; '»' -> '«'
        else -> c
    }
}
