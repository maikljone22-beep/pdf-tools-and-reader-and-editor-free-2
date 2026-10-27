package com.smartdocs.editor.core.office5

/**
 * office6: find / replace for spreadsheets and presentations (Word has its own in [WordDoc.find]).
 * Pure model code, unit tested.
 */
data class FindOptions(val matchCase: Boolean = false, val whole: Boolean = false)

object TextMatch {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Start offsets of [query] in [text]. [FindOptions.whole] = whole word. */
    fun indices(text: String, query: String, o: FindOptions = FindOptions()): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(query, 0, !o.matchCase)
        while (i >= 0) {
            val end = i + query.length
            val ok = !o.whole || ((i == 0 || !isWordChar(text[i - 1])) && (end >= text.length || !isWordChar(text[end])))
            if (ok) out.add(i)
            i = text.indexOf(query, if (ok) end else i + 1, !o.matchCase)
        }
        return out
    }

    /** Replaces every match; returns the new text and the count. */
    fun replaceAll(text: String, query: String, with: String, o: FindOptions = FindOptions()): Pair<String, Int> {
        val idx = indices(text, query, o)
        if (idx.isEmpty()) return text to 0
        val sb = StringBuilder(text)
        for (i in idx.asReversed()) sb.replace(i, i + query.length, with)
        return sb.toString() to idx.size
    }
}

/** A cell that matches; [text] is the searched text (formula with "=" or the value as typed). */
data class SheetHit(val row: Int, val col: Int, val text: String)

object SheetFind {
    /** Text searched in a cell: the formula ("=SUM(A1:A3)") and the shown value both count. */
    fun searchText(book: SheetBook, s: Sheet5, r: Int, c: Int): List<String> {
        val cell = s.cell(r, c) ?: return emptyList()
        val shown = runCatching { book.display(s, r, c).text }.getOrDefault("")
        return if (cell.formula != null) listOf("=" + cell.formula, shown) else listOf(sourceText(cell), shown).distinct()
    }

    /** The cell content as the user would type it (without the "'" text marker). */
    fun sourceText(cell: Cell5): String = when {
        cell.formula != null -> "=" + cell.formula
        cell.literal is FV.Str -> (cell.literal as FV.Str).v
        else -> cell.editText()
    }

    private fun matches(t: String, q: String, o: FindOptions): Boolean =
        if (o.whole) t.equals(q, ignoreCase = !o.matchCase) else t.contains(q, ignoreCase = !o.matchCase)

    /** Matching cells in row-major order. [o.whole] = whole cell content. */
    fun find(book: SheetBook, s: Sheet5, query: String, o: FindOptions = FindOptions()): List<SheetHit> {
        if (query.isEmpty()) return emptyList()
        return s.cells.keys.map { Sheet5.row(it) to Sheet5.col(it) }.sortedWith(compareBy({ it.first }, { it.second }))
            .mapNotNull { (r, c) -> searchText(book, s, r, c).firstOrNull { matches(it, query, o) }?.let { SheetHit(r, c, it) } }
    }

    /** New typed content for a cell after replacing, or null when nothing changes. */
    fun replacedInput(cell: Cell5, query: String, with: String, o: FindOptions): String? {
        val src = sourceText(cell)
        if (o.whole) return if (src.equals(query, ignoreCase = !o.matchCase)) with else null
        val (t, n) = TextMatch.replaceAll(src, query, with, FindOptions(o.matchCase, false))
        if (n == 0) return null
        // keep a text cell text even when the result looks like a number / formula
        return if (cell.formula == null && cell.literal is FV.Str && (FxCoerce.parseNumber(t) != null || t.startsWith("="))) "'$t" else t
    }

    /** Replace in one cell (no undo step of its own: call inside [Workbook5.change]). */
    fun replaceCell(wb: Workbook5, s: Sheet5, r: Int, c: Int, query: String, with: String, o: FindOptions): Boolean {
        val cell = s.cell(r, c) ?: return false
        val input = replacedInput(cell, query, with, o) ?: return false
        return wb.setInput(s, r, c, input) == null
    }

    /** Replace in every cell of the sheet; one undo step. Returns the count of changed cells. */
    fun replaceAll(wb: Workbook5, active: Int, s: Sheet5, query: String, with: String, o: FindOptions): Int {
        if (query.isEmpty()) return 0
        val keys = s.cells.keys.filter { k -> s.cells[k]?.let { replacedInput(it, query, with, o) } != null }
        if (keys.isEmpty()) return 0
        var n = 0
        wb.change(active) { keys.forEach { k -> if (replaceCell(wb, s, Sheet5.row(k), Sheet5.col(k), query, with, o)) n++ } }
        return n
    }
}

/** A match inside one text run (`a:t`) of a slide: [ordinal] is the index of that run among the slide's `t` elements. */
data class SlideHit(val slide: Int, val ordinal: Int, val start: Int, val end: Int)

object SlideFind {
    fun runs(doc: PptDoc, slide: Int): List<XElem> = doc.slide(slide)?.root?.findAll("t").orEmpty()

    fun find(doc: PptDoc, query: String, o: FindOptions = FindOptions()): List<SlideHit> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<SlideHit>()
        for (i in 0 until doc.slideCount) runs(doc, i).forEachIndexed { k, t ->
            TextMatch.indices(t.textContent(), query, o).forEach { s -> out.add(SlideHit(i, k, s, s + query.length)) }
        }
        return out
    }

    private fun setRun(t: XElem, text: String) {
        t.setText(text)
        // keep leading / trailing spaces
        if (text.isNotEmpty() && (text.first() == ' ' || text.last() == ' ')) t.set("xml:space", "preserve")
    }

    /** Replace one match (one undo step). */
    fun replace(doc: PptDoc, h: SlideHit, with: String): Boolean = doc.changeSlide(h.slide) {
        val t = doc.editSlide(h.slide)?.root?.findAll("t")?.getOrNull(h.ordinal) ?: return@changeSlide false
        val cur = t.textContent()
        if (h.end > cur.length) return@changeSlide false
        setRun(t, cur.substring(0, h.start) + with + cur.substring(h.end)); true
    }

    /** Replace everywhere (one undo step); returns the count. */
    fun replaceAll(doc: PptDoc, query: String, with: String, o: FindOptions): Int {
        val hits = find(doc, query, o)
        if (hits.isEmpty()) return 0
        var n = 0
        doc.change {
            hits.map { it.slide }.distinct().forEach { i ->
                doc.editSlide(i)?.root?.findAll("t")?.forEach { t ->
                    val (nt, c) = TextMatch.replaceAll(t.textContent(), query, with, o)
                    if (c > 0) { setRun(t, nt); n += c }
                }
            }
        }
        return n
    }

    /** Readable text of a slide: every paragraph of every shape, then the speaker notes. */
    fun slideText(doc: PptDoc, i: Int, withNotes: Boolean = true): String {
        val root = doc.slide(i)?.root ?: return ""
        val paras = root.findAll("p")
            .map { p -> p.findAll("t").joinToString("") { it.textContent() } }.filter { it.isNotBlank() }
        val notes = if (withNotes) notesText(doc, i) else ""
        return (paras + listOfNotNull(notes.takeIf { it.isNotBlank() })).joinToString("\n")
    }

    fun notesText(doc: PptDoc, i: Int): String {
        val path = doc.slidePaths().getOrNull(i) ?: return ""
        val n = doc.pkg.rels(path).firstOrNull { it.type.endsWith("/notesSlide") && !it.external } ?: return ""
        val root = doc.pkg.dom(n.target)?.root ?: return ""
        // the notes body placeholder (type="body"); the slide image placeholder has no text
        return root.findAll("p").map { p -> p.findAll("t").joinToString("") { it.textContent() } }.filter { it.isNotBlank() }.joinToString("\n")
    }
}
