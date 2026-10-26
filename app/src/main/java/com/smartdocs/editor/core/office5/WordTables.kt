package com.smartdocs.editor.core.office5

/** Table editing on `w:tbl` elements of a [WordDoc]. Rows/columns are addressed by `w:tr` / `w:tc` index. */
object WordTables {

    fun rows(t: XElem): List<XElem> = t.children("tr")
    fun cells(tr: XElem): List<XElem> = tr.children("tc")
    fun cellParagraphs(tc: XElem): List<XElem> = tc.children("p")

    fun span(tc: XElem): Int = tc.child("tcPr")?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1
    /** "restart", "continue" or null. */
    fun vMerge(tc: XElem): String? = tc.child("tcPr")?.child("vMerge")?.let { it.attr("val") ?: "continue" }

    fun cellText(tc: XElem): String = cellParagraphs(tc).joinToString("\n") { WordRuns.text(it) }

    fun isRtl(t: XElem): Boolean = t.child("tblPr")?.child("bidiVisual")?.let { it.attr("val").let { v -> v == null || v == "1" || v == "true" } } == true

    /** Sets a cell's text; each line is one paragraph, existing paragraphs keep their formatting. */
    fun setCellText(tc: XElem, text: String) {
        val lines = text.split('\n')
        val paras = cellParagraphs(tc).toMutableList()
        val w = tc.prefix.let { if (it.isEmpty()) "" else "$it:" }
        if (paras.isEmpty()) { val p = XElem("${w}p"); tc.add(p); paras.add(p) }
        for ((i, line) in lines.withIndex()) {
            val p = paras.getOrNull(i) ?: run {
                val last = paras.last()
                val np = XElem(last.name); last.child("pPr")?.copy()?.let { np.add(it) }
                tc.add(tc.indexOf(last) + 1, np); paras.add(np); np
            }
            WordRuns.setText(p, line)
        }
        // a cell must keep at least one paragraph
        for (i in paras.size - 1 downTo lines.size) if (i > 0) tc.remove(paras[i])
    }

    /** An empty copy of [tc] (properties and the first paragraph's formatting kept, vertical merge dropped). */
    private fun blankCell(tc: XElem): XElem {
        val c = XElem(tc.name)
        tc.attrs.forEach { c.attrs.add(XAttr(it.name, it.raw, it.quote)) }
        tc.child("tcPr")?.copy()?.let { pr -> pr.removeChildren("vMerge"); c.add(pr) }
        val first = cellParagraphs(tc).firstOrNull()
        val p = XElem(first?.name ?: (tc.prefix.let { if (it.isEmpty()) "p" else "$it:p" }))
        first?.child("pPr")?.copy()?.let { p.add(it) }
        first?.let { WordRuns.runs(it).firstOrNull()?.child("rPr")?.copy() }?.let { rp -> p.child("pPr")?.let { pp -> pp.removeChildren("rPr"); pp.setOrdered(rp, WordRuns.PPR_ORDER) } }
        c.add(p)
        return c
    }

    fun insertRow(t: XElem, rowIndex: Int, below: Boolean) {
        val rs = rows(t); if (rs.isEmpty()) return
        val src = rs[rowIndex.coerceIn(0, rs.size - 1)]
        val nr = XElem(src.name)
        src.attrs.forEach { if (!it.name.endsWith("paraId") && !it.name.endsWith("textId")) nr.attrs.add(XAttr(it.name, it.raw, it.quote)) }
        src.child("trPr")?.copy()?.let { pr -> pr.removeChildren("tblHeader"); nr.add(pr) }
        src.child("tblPrEx")?.copy()?.let { nr.add(0, it) }
        cells(src).forEach { nr.add(blankCell(it)) }
        t.add(t.indexOf(src) + if (below) 1 else 0, nr)
    }

    fun deleteRow(t: XElem, rowIndex: Int): Boolean {
        val rs = rows(t); if (rs.size <= 1 || rowIndex !in rs.indices) return false
        t.remove(rs[rowIndex]); return true
    }

    /** Grid column where cell [ci] of [tr] starts (gridSpan aware). */
    fun gridColOf(tr: XElem, ci: Int): Int = cells(tr).take(ci).sumOf { span(it) } + (tr.child("trPr")?.child("gridBefore")?.attr("val")?.toIntOrNull() ?: 0)

    /** Inserts a column next to grid column [gridCol] in every row. */
    fun insertColumn(t: XElem, gridCol: Int, after: Boolean) {
        val grid = t.child("tblGrid")
        val cols = grid?.children("gridCol") ?: emptyList()
        val target = (if (after) gridCol + 1 else gridCol).coerceIn(0, cols.size)
        val width = cols.getOrNull(gridCol)?.attr("w")?.toIntOrNull() ?: 1500
        if (grid != null) {
            val g = XElem(cols.firstOrNull()?.name ?: (grid.prefix.let { if (it.isEmpty()) "gridCol" else "$it:gridCol" })).set("${grid.prefix.let { if (it.isEmpty()) "" else "$it:" }}w", width.toString())
            if (target >= cols.size) { val last = cols.lastOrNull(); if (last != null) grid.add(grid.indexOf(last) + 1, g) else grid.add(g) } else grid.add(grid.indexOf(cols[target]), g)
        }
        for (tr in rows(t)) {
            val cs = cells(tr); if (cs.isEmpty()) continue
            var g = tr.child("trPr")?.child("gridBefore")?.attr("val")?.toIntOrNull() ?: 0
            var placed = false
            for (c in cs) {
                val sp = span(c)
                if (!after && g == target || after && g + sp - 1 == gridCol) {
                    val nc = blankCell(c); nc.child("tcPr")?.removeChildren("gridSpan")
                    tr.add(tr.indexOf(c) + if (after) 1 else 0, nc); placed = true; break
                }
                if (g + sp > target && g < target) { // inside a spanning cell: widen it
                    val pr = c.child("tcPr") ?: XElem(c.prefix.let { if (it.isEmpty()) "tcPr" else "$it:tcPr" }).also { c.add(0, it) }
                    val pf = pr.prefix.let { if (it.isEmpty()) "" else "$it:" }
                    pr.setOrdered(XElem("${pf}gridSpan").set("${pf}val", (sp + 1).toString()), TCPR_ORDER); placed = true; break
                }
                g += sp
            }
            if (!placed) tr.add(tr.indexOf(cs.last()) + 1, blankCell(cs.last()).also { it.child("tcPr")?.removeChildren("gridSpan") })
        }
    }

    /** Deletes grid column [gridCol] from every row (spanning cells shrink). */
    fun deleteColumn(t: XElem, gridCol: Int): Boolean {
        val grid = t.child("tblGrid")
        val cols = grid?.children("gridCol") ?: emptyList()
        if (cols.size <= 1 && rows(t).all { cells(it).size <= 1 }) return false
        if (grid != null && gridCol in cols.indices) grid.remove(cols[gridCol])
        for (tr in rows(t)) {
            var g = tr.child("trPr")?.child("gridBefore")?.attr("val")?.toIntOrNull() ?: 0
            for (c in cells(tr)) {
                val sp = span(c)
                if (gridCol >= g && gridCol < g + sp) {
                    if (sp > 1) {
                        val pr = c.child("tcPr")!!; val pf = pr.prefix.let { if (it.isEmpty()) "" else "$it:" }
                        if (sp - 1 == 1) pr.removeChildren("gridSpan") else pr.setOrdered(XElem("${pf}gridSpan").set("${pf}val", (sp - 1).toString()), TCPR_ORDER)
                    } else if (cells(tr).size > 1) tr.remove(c)
                    break
                }
                g += sp
            }
        }
        return true
    }

    val TCPR_ORDER = listOf("cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar", "textDirection", "tcFitText", "vAlign", "hideMark")

    /** Cell shading fill (hex) or null to remove. */
    fun setShading(tc: XElem, hex: String?) {
        val pr = tc.child("tcPr") ?: XElem(tc.prefix.let { if (it.isEmpty()) "tcPr" else "$it:tcPr" }).also { tc.add(0, it) }
        val pf = pr.prefix.let { if (it.isEmpty()) "" else "$it:" }
        if (hex == null) pr.removeChildren("shd") else pr.setOrdered(XElem("${pf}shd").set("${pf}val", "clear").set("${pf}color", "auto").set("${pf}fill", hex), TCPR_ORDER)
    }

    fun shading(tc: XElem): String? = tc.child("tcPr")?.child("shd")?.attr("fill")?.let { WordFormat.color(it) }
}
