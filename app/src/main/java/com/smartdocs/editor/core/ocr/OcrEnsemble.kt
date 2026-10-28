package com.smartdocs.editor.core.ocr

/**
 * Word-level combination of the two offline engines (PRO_OCR_r5): Tesseract (tessdata_best script/Arabic) and the
 * PP-OCRv5 Arabic recogniser. Overlapping words of both engines form a cluster (union-find on box overlap on the same
 * row); the cluster takes the reading with the higher score = mean engine confidence (0..1) + [LEX_WEIGHT] × the share
 * of its Arabic words that are known words ([OcrLexicon]). PP-OCR words that overlap no Tesseract word are added when
 * PP-OCR is very sure ([ADD_CONF]): Tesseract missed them. Measured on 10 ground-truth items: bag char accuracy
 * 0.818 (Tesseract) / 0.755 (PP-OCR) -> 0.85, word accuracy 0.73 / 0.66 -> 0.80.
 */
object OcrEnsemble {
    const val LEX_WEIGHT = 0.6f
    const val ADD_CONF = 90f

    /** Persian / Urdu letter forms the script model returns for Arabic text, and Persian digits. */
    fun fold(s: String): String = OcrRefine.foldScript(s)

    private fun overlaps(a: OcrRefine.Tok, b: OcrRefine.Tok): Boolean {
        val vy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0.5f * minOf(a.bottom - a.top, b.bottom - b.top)
        val hx = minOf(a.right, b.right) - maxOf(a.left, b.left) > 0.3f * minOf(a.right - a.left, b.right - b.left)
        return vy && hx
    }

    /** Share (0..1) of the Arabic words of [words] that [lex] knows; 1 when there is no Arabic word (numbers, formulas). */
    fun knownShare(words: List<String>, lex: OcrLexicon): Float {
        val ar = words.flatMap { OcrLexicon.arabicWords(it) }
        if (ar.isEmpty()) return 1f
        return ar.count { lex.known(it) }.toFloat() / ar.size
    }

    /** [tess] and [pp] (both in reading order, boxes in the same image pixels) combined; Tesseract's line ids are kept. */
    fun combine(tess: List<OcrRefine.Tok>, pp: List<OcrRefine.Tok>, lex: OcrLexicon): List<OcrRefine.Tok> {
        if (pp.isEmpty()) return tess
        if (tess.isEmpty()) return pp.filter { it.conf >= ADD_CONF }
        val n = tess.size
        val parent = IntArray(n + pp.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in tess.indices) for (j in pp.indices) if (overlaps(tess[i], pp[j])) parent[find(i)] = find(n + j)
        val clusters = LinkedHashMap<Int, Pair<MutableList<Int>, MutableList<Int>>>()
        for (i in tess.indices) clusters.getOrPut(find(i)) { ArrayList<Int>() to ArrayList() }.first += i
        for (j in pp.indices) clusters.getOrPut(find(n + j)) { ArrayList<Int>() to ArrayList() }.second += j
        val replace = HashMap<Int, List<OcrRefine.Tok>>()     // first Tesseract word of a cluster -> PP words; others dropped
        val used = BooleanArray(pp.size)
        for ((ti, pj) in clusters.values) {
            if (ti.isEmpty()) continue
            pj.forEach { used[it] = true }
            if (pj.isEmpty()) continue
            val tw = ti.map { fold(tess[it].text) }
            val pw = pj.sortedByDescending { pp[it].right }.map { pp[it] }
            val st = ti.map { tess[it].conf }.average().toFloat() / 100f + LEX_WEIGHT * knownShare(tw, lex)
            val sp = pj.map { pp[it].conf }.average().toFloat() / 100f + LEX_WEIGHT * knownShare(pw.map { fold(it.text) }, lex)
            if (sp > st) {
                val line = tess[ti.first()].line
                replace[ti.first()] = pw.map { it.copy(text = fold(it.text), line = line) }
                ti.drop(1).forEach { replace[it] = emptyList() }
            }
        }
        val out = ArrayList<OcrRefine.Tok>()
        tess.forEachIndexed { i, t -> out += replace[i] ?: listOf(t.copy(text = fold(t.text))) }
        val maxLine = (tess.maxOfOrNull { it.line } ?: -1) + 1
        pp.forEachIndexed { j, p ->
            if (used[j] || p.conf < ADD_CONF) return@forEachIndexed
            val w = p.copy(text = fold(p.text))
            // into the Tesseract line on the same row, at its reading position (right to left in Arabic lines)
            val row = out.indices.filter { k -> val o = out[k]; minOf(o.bottom, w.bottom) - maxOf(o.top, w.top) > 0.5f * minOf(o.bottom - o.top, w.bottom - w.top) }
            if (row.isEmpty()) { out += w.copy(line = maxLine + p.line); return@forEachIndexed }
            val line = out[row.first()].line
            val inLine = row.filter { out[it].line == line }
            val rtl = inLine.any { k -> out[k].text.any { OcrRefine.isArabicLetter(it) } }
            val before = inLine.lastOrNull { k -> if (rtl) out[k].right >= w.right else out[k].left <= w.left }
            out.add(if (before == null) inLine.first() else before + 1, w.copy(line = line))
        }
        return out
    }
}
