package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.search.ArabicTextFix

/**
 * Drops what Tesseract "reads" in decorations (OCR_QUALITY_r5): the chain border, vertical border pieces, arrows and the
 * logo produced lines like «ات ات امت ست الست», «وات وات …» ×50, «#رحتح», «OT», «EOE», «و». They went into the invisible
 * layer and from there into every copy, search and AI answer.
 *
 * Word rules: «#», three equal letters in a row, impossible Arabic ([ArabicTextFix.implausibleWord]), a box much taller
 * than the page's text (a border piece) or a long Latin run of capitals with low confidence.
 * Line rules: [isJunkTextLine] on the line's text, a line of mostly low-confidence words, a single low-confidence fragment.
 * Chemistry formulas (CO, CH4, C2H2(g)) and numbers are never fragments.
 */
object OcrCleanup {

    private fun isArabicLetter(c: Char) = OcrRefine.isArabicLetter(c)

    /** Short Arabic words that stand alone legitimately (anything else of ≤ 3 letters on its own is a fragment). */
    private val SHORT_WORDS = setOf(
        "من", "في", "فى", "أن", "إن", "ان", "عن", "على", "علي", "إلى", "الى", "هو", "هي", "ما", "لا", "لم", "لن", "قد", "ثم", "أو", "او",
        "مع", "كل", "بل", "لو", "كم", "ذا", "ذو", "أي", "اي", "إذ", "إذا", "اذا", "منه", "له", "لها", "به", "بها", "هل", "كي", "عند",
        "غاز", "مول", "جم", "جو", "كجم", "لتر", "سم", "مم", "ماء", "يا", "أم", "نعم", "تم", "لكن", "حيث", "ذلك", "تلك", "هذا", "هذه",
        "التي", "الذي", "وهو", "وهي", "س", "ص", "ع",
    )

    /** Element symbols, for [isFormula]. */
    private val ELEMENTS = ("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr " +
        "Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os " +
        "Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am").split(' ').toSet()

    private val FORMULA = Regex("""^\(?(?:[A-Z][a-z]?[0-9₀-₉]*|\((?:[A-Z][a-z]?[0-9₀-₉]*)+\)[0-9₀-₉]*)+\)?(?:\((?:g|l|s|aq)\))?$""")
    private val ELEMENT = Regex("[A-Z][a-z]?")

    /** A chemistry formula of at least two characters made only of element symbols: CO, CH4, C2H2(g), Fe2O3, (NH4)2. */
    fun isFormula(tok: String): Boolean {
        val t = tok.trim('.', ',', '،', ':', ';', '؛', '-', '+', '=')
        if (t.length < 2 || !FORMULA.matches(t)) return false
        return ELEMENT.findAll(t).all { it.value in ELEMENTS }
    }

    private fun isNumber(tok: String) = tok.any { it.isDigit() }

    /** A token that is no word on its own: a lone letter, an unknown two-letter Arabic piece, capitals that are no formula. */
    fun isFragment(tok: String): Boolean {
        val t = tok.trim { !it.isLetterOrDigit() }
        if (t.isEmpty()) return true
        if (isNumber(t) || isFormula(t)) return false
        val ar = t.count { isArabicLetter(it) }
        if (ar > 0) {
            if (ArabicTextFix.implausibleWord(t)) return true
            if (ar == 1) return true
            // two letters: only known short words stand alone (three-letter words are too common to judge)
            return ar == 2 && t !in SHORT_WORDS
        }
        if (t.length == 1) return true
        return t.all { it in 'A'..'Z' } && t.length <= 4
    }

    /** Text of a word that cannot be real: «#», three equal letters in a row, impossible Arabic. */
    fun isJunkWord(tok: String): Boolean {
        if ('#' in tok) return true
        var run = 1
        for (i in 1 until tok.length) {
            run = if (tok[i] == tok[i - 1] && tok[i].isLetter()) run + 1 else 1
            if (run >= 3) return true
        }
        val ar = tok.filter { isArabicLetter(it) || it.code in 0x064B..0x0652 }
        return ar.length >= 2 && ArabicTextFix.implausibleWord(ar)
    }

    /**
     * A recognised line (plain text) that is decoration, not text: «#» pieces, the same few short tokens repeated
     * («وات وات وات …», «حت ف حتت ف حتت»), or nothing but fragments («تح», «OT», «و», «: اا»). Numbers and formulas keep a line.
     */
    fun isJunkTextLine(line: String): Boolean {
        val toks = line.split(' ', '\t').filter { it.isNotBlank() }
        if (toks.isEmpty()) return false
        if (toks.any { '#' in it }) return true
        val sig = toks.filter { t -> t.any { it.isLetterOrDigit() } }
        if (sig.isEmpty()) return true                                               // only symbols («<-<----+», «—»)
        if (sig.size >= 4) {
            val core = sig.map { t -> t.trim { !it.isLetterOrDigit() } }
            val avg = core.sumOf { it.length }.toFloat() / core.size
            if (core.toSet().size * 2 <= core.size && avg <= 4f) return true          // repeated short pieces
            // one short piece (no number, no known word) making up a quarter of the line: «حت» ×8 in a chain border
            val top = core.filter { it.length <= 3 && !isNumber(it) && it !in SHORT_WORDS }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 4 && top * 4 >= core.size) return true
        }
        if (sig.any { isJunkWord(it) } && sig.count { isJunkWord(it) || isFragment(it) } * 2 >= sig.size) return true
        // nothing but fragments, or (4+ tokens) at least 80 % fragments: «EAN اك ل EAN ا EAN ANCA EO CN EAN اق افق»
        val frag = sig.count { isFragment(it) }
        return frag == sig.size || (sig.size >= 4 && frag * 5 >= sig.size * 4)
    }

    private fun hasTripleLetter(s: String): Boolean {
        for (i in 2 until s.length) if (s[i].isLetter() && s[i] == s[i - 1] && s[i] == s[i - 2]) return true
        return false
    }

    /** [toks] without junk words and junk lines. */
    fun clean(toks: List<OcrRefine.Tok>): List<OcrRefine.Tok> {
        if (toks.isEmpty()) return toks
        val heights = toks.filter { t -> t.text.any { it.isLetterOrDigit() } }.map { it.height }.sorted()
        val medianH = if (heights.size >= 8) heights[heights.size / 2] else 0
        val keep = BooleanArray(toks.size) { true }
        toks.forEachIndexed { i, t ->
            val s = t.text
            // «#», impossible Arabic; three equal letters only when unsure («تتتاسب» 88 is a misread «تتناسب»)
            if (isJunkWord(s) && ('#' in s || t.conf < 70f || !hasTripleLetter(s))) keep[i] = false
            // a vertical border piece: far taller than the text of the page
            else if (medianH > 0 && t.height > 3 * medianH && t.conf < 70f) keep[i] = false
            // «COCEHOCAHEOCAHEO»: a long run of capitals with low confidence
            else if (s.length >= 8 && s.all { it in 'A'..'Z' } && t.conf < 50f) keep[i] = false
            else if (t.conf < 10f && !isNumber(s) && !isFormula(s) && s.count { isArabicLetter(it) } < 4) keep[i] = false
        }
        for (line in OcrRefine.lines(toks)) {
            val ws = line.filter { keep[it] }
            if (ws.isEmpty()) continue
            val text = ws.joinToString(" ") { toks[it].text }
            // a row of tiny "words" (a chain border drawn 5 px high on a page whose text is 40 px high)
            if (medianH > 0 && ws.size >= 3 && ws.map { toks[it].height }.sorted()[ws.size / 2] * 10 < medianH * 4) {
                ws.forEach { keep[it] = false }; continue
            }
            val confs = ws.map { toks[it].conf }
            val mean = confs.average()
            val low = confs.count { it < 50f }
            // a confidently read short line («TV», «OK») is kept even when its text looks like a fragment
            // a line read with little confidence: its unsure words and fragments go, confident real words stay
            if (ws.size >= 3 && mean < 45.0 && low * 5 >= ws.size * 3 && !isJunkTextLine(text)) {
                ws.forEach { i -> if (toks[i].conf < 60f || isFragment(toks[i].text)) keep[i] = false }
                continue
            }
            val junk = (isJunkTextLine(text) && mean < 85.0) ||
                (ws.size <= 2 && mean < 50.0 && ws.all { isFragment(toks[it].text) || !toks[it].text.any { c -> c.isLetterOrDigit() } }) ||
                // a stray one- or two-word line read with little confidence («الى اله» 34, «Rees» 52); numbers and
                // formulas alone on a line (a fraction's denominator, «CO») are kept
                (ws.size <= 2 && mean < 40.0) ||
                (ws.size == 1 && mean < 60.0 && toks[ws[0]].text.let { s -> !isNumber(s) && !isFormula(s) })
            if (junk) ws.forEach { keep[it] = false }
        }
        return toks.filterIndexed { i, _ -> keep[i] }
    }
}
