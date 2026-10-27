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
        return dropUnusableLines(text.split('\n').map { fixLine(it) }).joinToString("\n")
    }

    fun fixLine(line: String): String {
        if (line.none { isArabicChar(it) }) return line
        val reversed = isVisualOrder(line)
        val base = toBaseLetters(line)
        return allah(if (reversed) reverseVisualLine(base) else base)
    }

    /**
     * «الله» drawn as an alef glyph + the font's «ﷲ» glyph mapped to «لله»: PDFium reverses that multi-letter value in
     * right-to-left text and extracts «اهلل» («باهلل», «واهلل»). «اهلل» is not an Arabic word, so it is safe to put back.
     */
    private fun allah(s: String): String =
        if (!s.contains("اهلل")) s else ALLAH.replace(s) { m -> m.groupValues[1] + "الله" }

    private val ALLAH = Regex("""(^|[^\p{L}]|[وفبت])اهلل(?!\p{L})""")

    /**
     * Copy of a page that carries both a broken original text layer (a font with a wrong ToUnicode map) and a readable
     * OCR layer would paste every line twice, once as gibberish. When the selection holds readable Arabic, lines that are
     * [brokenLine] are dropped (their Latin words and numbers are kept); otherwise everything is kept as it is.
     */
    fun dropUnusableLines(lines: List<String>): List<String> {
        val broken = lines.map { brokenLine(it) }
        // readable = a clean line: 3 Arabic words or more and none impossible (a short broken line can look half right)
        val readable = lines.any { l -> lineWords(l).let { w -> w.size >= 3 && w.none { implausibleWord(it) } } }
        if (!readable || broken.none { it }) return lines
        return lines.indices.mapNotNull { i ->
            if (!broken[i]) lines[i]
            else lines[i].split(' ').filter { w -> w.isNotEmpty() && w.none { isArabicChar(it) } && w.any { it.isLetterOrDigit() } }
                .joinToString(" ").ifEmpty { null }
        }
    }

    /**
     * One line of Arabic that comes from a wrongly mapped font: at least a third of its Arabic words (and one) are
     * [implausibleWord] («إػذاد األعزبر : حمّذ ادلغمف … ِذسط ِبدح اٌىُُّبء»: 7 of 11).
     */
    fun brokenLine(line: String): Boolean {
        val words = lineWords(line)
        if (words.isEmpty()) return false
        val bad = words.count { implausibleWord(it) }
        return bad >= 1 && bad * 3 >= words.size
    }

    /** Arabic words of a line (at least two letters), punctuation around them trimmed. */
    private fun lineWords(line: String): List<String> =
        line.split(' ', '\t').map { w -> w.trim { !isArabicChar(it) && !it.isDigit() } }.filter { w -> w.count { isArabicLetter(it) } >= 2 }

    /**
     * PDFium returns some mixed Arabic / Latin lines in left-to-right visual order («: Capacity Heat الحرارية السعة»,
     * «Zn بعنصر تنتهي 80Hg»): it picks the line direction from its own run count. With the x position of every
     * character ([xs], centre of its box, null for generated spaces / line breaks) such a line is put back into
     * right-to-left reading order: words by decreasing x, runs of Latin / number words kept left to right.
     * Lines that are not mostly Arabic, or already read right to left, are returned unchanged. Plain Kotlin.
     */
    fun reorderMixedLine(line: String, xs: List<Float?>): String {
        if (xs.size != line.length) return line
        val ar = line.count { it.code in 0x0621..0x064A || isPresentation(it) }
        val lat = line.count { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() }
        if (ar < 4 || lat == 0 || ar <= lat) return line
        class Tok(val text: String, val x: Float, val ltr: Boolean)
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < line.length) {
            if (line[i] == ' ') { i++; continue }
            var j = i
            while (j < line.length && line[j] != ' ') j++
            val px = (i until j).mapNotNull { xs[it] }
            if (px.isEmpty()) return line
            val t = line.substring(i, j)
            val strong = t.firstOrNull { it.isLetterOrDigit() }
            toks += Tok(t, px.average().toFloat(), strong != null && !isArabicChar(strong))
            i = j
        }
        if (toks.size < 2) return line
        var inc = 0; var dec = 0
        for (k in 1 until toks.size) { val d = toks[k].x - toks[k - 1].x; if (d > 0.5f) inc++ else if (d < -0.5f) dec++ }
        if (inc <= dec) return line
        val sorted = toks.sortedByDescending { it.x }
        val out = ArrayList<String>()
        var k = 0
        while (k < sorted.size) {
            if (!sorted[k].ltr) { out += sorted[k].text; k++; continue }
            var e = k
            while (e < sorted.size && sorted[e].ltr) e++
            for (m in e - 1 downTo k) out += sorted[m].text
            k = e
        }
        return out.joinToString(" ")
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
            // teh marbuta and alef maksura only end a word: «ةرارح», «ىلإ» are reversed words
            if (w.last() == 'ة' || w.last() == 'ى') logical++
            if (w.first() == 'ة' || w.first() == 'ى') visual++
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
