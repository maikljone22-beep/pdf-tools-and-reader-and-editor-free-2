package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.search.ArabicTextFix

/**
 * Second look at doubtful words of Arabic lines (OCR_QUALITY_r5).
 *
 * The `ara` model (tessdata_best) only knows the digits ٠ and ١: «٨٩٠» cannot come out of it, so with ara+eng the English
 * model wins and Arabic-Indic numbers became Latin letters («٨٩٠» → «Ade», «٢٨٣» → «YAY», «١٤» → «VE»). Bold or coloured
 * Arabic words were also taken by the English model («علمت» → «Cale», «كتلة» → «lisa», «وعند» → «Leg»).
 *
 * Each such word of an Arabic line is read again, alone, with the Arabic *script* model (tessdata_best/script/Arabic: all
 * Arabic-Indic and Persian digits, Arabic letters and Latin). That model can also answer in Latin, so when it reads Arabic
 * letters or Arabic-Indic digits where English read Latin, the Arabic reading is taken. Real Latin (CO, CH4, KJ/mole,
 * 890) is read as Latin by it too and stays as it was.
 */
object OcrRefine {

    /** A recognised word in plain numbers (box in bitmap pixels, y down), so the rules run in JVM unit tests. */
    data class Tok(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val conf: Float, val line: Int = -1) {
        val height: Int get() = bottom - top
    }

    /** What may replace a candidate. */
    enum class Kind { NONE, LATIN, DIGITLIKE }

    /** Minimum confidence of the second reading. */
    const val MIN_CONF = 40f

    /** Words grouped into text lines: Tesseract's own line ids when present, else by vertical bands. Indexes into [toks]. */
    fun lines(toks: List<Tok>): List<List<Int>> {
        if (toks.isNotEmpty() && toks.all { it.line >= 0 }) {
            val m = LinkedHashMap<Int, MutableList<Int>>()
            toks.forEachIndexed { i, t -> m.getOrPut(t.line) { ArrayList() } += i }
            return m.values.toList()
        }
        // fallback: same band rule as the invisible layer, but keep reading order inside the line
        val out = ArrayList<MutableList<Int>>()
        var top = 0; var bottom = 0
        toks.forEachIndexed { i, t ->
            val cy = (t.top + t.bottom) / 2
            val cur = out.lastOrNull()
            if (cur != null && cy in top..bottom) { cur += i; top = minOf(top, t.top); bottom = maxOf(bottom, t.bottom) }
            else { out += mutableListOf(i); top = t.top; bottom = t.bottom }
        }
        return out
    }

    fun isArabicLetter(c: Char) = c.code in 0x0621..0x064A || c.code in 0x0671..0x06D3 || c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFC
    fun isArabicDigit(c: Char) = c.code in 0x0660..0x0669 || c.code in 0x06F0..0x06F9
    private fun arabicLetters(s: String) = s.count { isArabicLetter(it) }

    /** A line is Arabic when at least two of its words have two Arabic letters or more. */
    fun isArabicLine(words: List<Tok>): Boolean = words.count { arabicLetters(it.text) >= 2 } >= 2

    /** Single letters that are real one-letter words / labels in Arabic text. */
    private val ONE_LETTER = setOf('و', 'ب', 'ك', 'ل', 'س', 'ص', 'م', 'أ', 'ج', 'د', 'ه', 'ع', 'ف', 'ق', 'ي', 'ن', 'ا', 'ء')

    /** Which second reading a word of an Arabic line gets. */
    fun kind(t: Tok): Kind {
        val s = t.text
        val letters = arabicLetters(s)
        val alnum = s.count { it.isLetterOrDigit() }
        if (alnum == 0 || alnum > 14) return Kind.NONE
        // Latin letters and/or digits (any script) without Arabic letters: «Cale», «YAY», «Ade», «١6», «4»
        if (letters == 0) {
            // surely right as it is (saves time): a confident formula, Latin number or Latin word («CO», «890», «KJ/mole»)
            val core = s.trim { !it.isLetterOrDigit() }
            if (t.conf >= 85f && (OcrCleanup.isFormula(core) || (core.length >= 2 && core.all { it in '0'..'9' }) ||
                    s.count { it in 'a'..'z' } >= 3)) return Kind.NONE
            return Kind.LATIN
        }
        // Arabic letters mixed with digits or «؟» (٢/٣ read as a question mark): «١9», «4ق», «؟)»
        if (letters <= 2 && (s.any { it.isDigit() } || '؟' in s)) return Kind.DIGITLIKE
        // a lone letter that is no one-letter word («ر», «ز»: pieces of ٢, ٦…)
        if (letters == 1 && alnum == 1 && s.first { isArabicLetter(it) } !in ONE_LETTER) return Kind.DIGITLIKE
        return Kind.NONE
    }

    /** Indexes of the words that get a second reading: [kind] != NONE, in Arabic lines only. */
    fun candidates(toks: List<Tok>): List<Int> = lines(toks).flatMap { l ->
        if (!isArabicLine(l.map { toks[it] })) emptyList() else l.filter { kind(toks[it]) != Kind.NONE }
    }

    /** Persian / Urdu forms the script model may return, folded to the Arabic letters and Arabic-Indic digits. */
    fun foldScript(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            when {
                c.code in 0x06F0..0x06F9 -> b.append((c.code - 0x06F0 + 0x0660).toChar())
                c == 'ی' -> b.append('ي'); c == 'ک' || c == 'ڪ' -> b.append('ك')
                c == 'ۃ' -> b.append('ة'); c == 'ہ' || c == 'ھ' -> b.append('ه')
                c == '‎' || c == '‏' || c == '؜' || c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 -> {}
                else -> b.append(c)
            }
        }
        return b.toString().trim()
    }

    private fun allowed(c: Char) = c.code in 0x0621..0x064A || c.code in 0x064B..0x0652 || c.code in 0x0660..0x0669 ||
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c in " .,()×/+=:-%٫٬،"

    /**
     * The text that replaces [orig] given the script model's reading [alt] (confidence [altConf]), or null to keep it.
     * Taken only when it is Arabic content (Arabic letters or Arabic-Indic digits), uses no letter foreign to Arabic text,
     * and is no impossible Arabic word. A [Kind.DIGITLIKE] word may only become a number.
     */
    fun accept(orig: Tok, alt: String?, altConf: Float): String? {
        val k = kind(orig)
        if (k == Kind.NONE || alt == null || altConf < MIN_CONF) return null
        val f = foldScript(alt)
        if (f.isEmpty() || f == orig.text || !f.all { allowed(it) }) return null
        val letters = arabicLetters(f); val digits = f.count { isArabicDigit(it) }
        if (letters == 0 && digits == 0) return null                       // Latin again: the first reading stands
        if (k == Kind.DIGITLIKE && letters > 0) return null
        if (letters > 0 && f.split(' ').any { ArabicTextFix.implausibleWord(it) }) return null
        // mixed Latin letters or Latin digits + Arabic letters is no better than what we had («27 ي»)
        if (letters > 0 && f.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
        // a lone Arabic letter in the reading is a piece of a word, not a word («27 ي», «ي0»)
        if (f.split(' ').any { w -> w.count { isArabicLetter(it) } == 1 && w.length == 1 && w[0] != 'و' }) return null
        // the padded rectangle caught neighbouring words or the next line («Gaya» → «التغير في درجة»)
        if (f.length > 2 * orig.text.length + 2) return null
        return f
    }

    /** Padding around a word for its second reading: a quarter of its height, at least 6 px. */
    fun pad(t: Tok): Int = maxOf(6, t.height / 4)

    /**
     * [toks] with candidates replaced by their accepted second reading. [reRead] reads one rectangle
     * (left, top, right, bottom, already padded) and returns text + confidence, or null when it cannot.
     */
    fun refine(toks: List<Tok>, reRead: (Int, Int, Int, Int) -> Pair<String, Float>?): List<Tok> {
        val c = candidates(toks)
        if (c.isEmpty()) return toks
        val out = toks.toMutableList()
        for (i in c) {
            val t = toks[i]; val p = pad(t)
            val r = reRead(t.left - p, t.top - p, t.right + p, t.bottom + p) ?: continue
            val a = accept(t, r.first, r.second) ?: continue
            out[i] = t.copy(text = a, conf = r.second)
        }
        return out
    }
}
