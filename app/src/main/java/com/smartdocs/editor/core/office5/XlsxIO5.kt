package com.smartdocs.editor.core.office5

import java.io.File

/**
 * .xlsx load/save for [Workbook5] on top of [OPackage]. Loading reads shared strings, styles, cells (with
 * shared formulas expanded and cached values kept), widths/heights, merges, frozen panes and sheet direction.
 * Saving regenerates `sheetData`/`cols`/`mergeCells`/the pane of changed sheets only (all other worksheet
 * markup is kept in place), appends new shared strings, rewrites the sheet list, drops calcChain and asks
 * Excel to recalculate on open. Untouched parts are copied byte-for-byte.
 */
object XlsxIO5 {
    const val NS = XStyles.NS
    const val REL_WS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    const val REL_SST = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings"
    const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    const val CT_WS = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    const val CT_SST = "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"
    const val CT_STYLES = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"

    /** What the saver needs to know about the source. */
    class Meta(val wbPath: String, val originalSheets: List<Pair<String, String>>, val sst: MutableList<String>, val csvBom: Boolean = false, val csvDelimiter: Char = ',')

    private val ORDER = listOf(
        "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData", "sheetCalcPr", "sheetProtection", "protectedRanges",
        "scenarios", "autoFilter", "sortState", "dataConsolidate", "customSheetViews", "mergeCells", "phoneticPr", "conditionalFormatting",
        "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup", "headerFooter", "rowBreaks", "colBreaks",
        "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing", "legacyDrawing", "legacyDrawingHF", "picture",
        "oleObjects", "controls", "webPublishItems", "tableParts", "extLst",
    )

    fun load(file: File): Workbook5 = load(OPackage.open(file))

    fun load(pkg: OPackage): Workbook5 {
        val wbPath = pkg.mainPart("xl/workbook.xml")
        val wb = pkg.dom(wbPath) ?: throw XmlParseException("workbook.xml missing")
        val rels = pkg.rels(wbPath)
        val book = SheetBook()
        book.date1904 = wb.root.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        val sst = ArrayList<String>()
        rels.firstOrNull { it.type == REL_SST }?.let { pkg.dom(it.target) }?.root?.children("si")?.forEach { sst.add(richText(it)) }
        val stylesPath = rels.firstOrNull { it.type == REL_STYLES }?.target ?: "xl/styles.xml".also { sp ->
            if (!pkg.has(sp)) pkg.put(sp, (OPackage.DECL + XStyles.EMPTY).toByteArray())
            pkg.addRel(wbPath, REL_STYLES, sp); pkg.addOverride(sp, CT_STYLES)
        }
        val styles = XStyles(pkg, stylesPath, pkg.dom(stylesPath))
        val orig = ArrayList<Pair<String, String>>()
        for (se in wb.root.child("sheets")?.children("sheet") ?: emptyList()) {
            val rid = se.attr("r:id") ?: se.attr("id") ?: continue
            val path = rels.firstOrNull { it.id == rid }?.target ?: continue
            val s = Sheet5(se.attr("name") ?: "Sheet${book.sheets.size + 1}")
            s.partPath = path; s.relId = rid
            pkg.dom(path)?.let { readSheet(it.root, s, sst) }
            book.sheets.add(s); orig.add(rid to path)
        }
        if (book.sheets.isEmpty()) book.sheets.add(Sheet5("Sheet1").also { it.dirty = true })
        val w = Workbook5(book, styles, pkg, Workbook5.Kind.XLSX)
        w.meta = Meta(wbPath, orig, sst)
        book.recalcAll()
        return w
    }

    private fun richText(si: XElem): String = buildString {
        for (k in si.elems()) when (k.local) { "t" -> append(k.textContent()); "r" -> k.children("t").forEach { append(it.textContent()) } }
    }

    private fun readSheet(root: XElem, s: Sheet5, sst: List<String>) {
        root.child("sheetViews")?.children("sheetView")?.firstOrNull()?.let { v ->
            s.rtl = v.attr("rightToLeft").let { it == "1" || it == "true" }
            v.child("pane")?.takeIf { it.attr("state") == "frozen" || it.attr("state") == "frozenSplit" }?.let { p ->
                s.freezeCols = p.attr("xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                s.freezeRows = p.attr("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
            }
        }
        root.child("sheetFormatPr")?.let { f ->
            f.attr("defaultColWidth")?.toFloatOrNull()?.let { s.defaultColWidth = it }
            f.attr("defaultRowHeight")?.toFloatOrNull()?.let { s.defaultRowHeight = it }
        }
        root.child("cols")?.children("col")?.forEach { c ->
            val min = (c.attr("min")?.toIntOrNull() ?: 1) - 1; val max = minOf((c.attr("max")?.toIntOrNull() ?: 1) - 1, MAX_COLS - 1)
            val w = c.attr("width")?.toFloatOrNull()
            for (i in min..max) {
                if (w != null && c.attr("customWidth") != "0") s.colWidths[i] = w
                c.attr("style")?.let { st -> s.colStyles[i] = st }
                if (c.attr("hidden") == "1" || c.attr("hidden") == "true") s.colHidden.add(i)
            }
        }
        val shared = HashMap<String, Triple<Int, Int, Fx?>>()
        var nextRow = 0
        for (row in root.child("sheetData")?.children("row") ?: emptyList()) {
            val ri = row.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
            nextRow = ri + 1
            row.attr("ht")?.toFloatOrNull()?.let { if (row.attr("customHeight").let { c -> c == "1" || c == "true" }) s.rowHeights[ri] = it }
            row.attrs.filter { it.name !in setOf("r", "spans", "ht", "customHeight") && !it.name.startsWith("x14ac") }.takeIf { it.isNotEmpty() }?.let { s.rowAttrs[ri] = it }
            var nextCol = 0
            for (c in row.children("c")) {
                val ref = c.attr("r")?.let { A1.parseCell(it) }
                val r = ref?.row ?: ri; val col = ref?.col ?: nextCol
                nextCol = col + 1
                s.cells[Sheet5.key(r, col)] = readCell(c, r, col, sst, shared)
            }
        }
        root.child("mergeCells")?.children("mergeCell")?.forEach { m -> m.attr("ref")?.let { A1.parseRange(it) }?.let { s.merges.add(it) } }
    }

    private fun readCell(c: XElem, r: Int, col: Int, sst: List<String>, shared: HashMap<String, Triple<Int, Int, Fx?>>): Cell5 {
        val t = c.attr("t") ?: "n"
        val style = c.attr("s")?.toIntOrNull() ?: 0
        val v = c.child("v")?.textContent()
        val fe = c.child("f")
        var formula: String? = fe?.textContent()?.takeIf { it.isNotBlank() }
        if (fe != null && fe.attr("t") == "shared") {
            val si = fe.attr("si") ?: ""
            if (formula != null) shared[si] = Triple(r, col, FxParser.tryParse(formula))
            else shared[si]?.let { (mr, mc, fx) -> formula = fx?.let { FxPrinter.print(FxPrinter.translate(it, r - mr, col - mc)) } }
        }
        var sstIdx = -1
        val value: FV = when (t) {
            "s" -> { sstIdx = v?.trim()?.toIntOrNull() ?: -1; FV.Str(sst.getOrNull(sstIdx) ?: "") }
            "inlineStr" -> FV.Str(c.child("is")?.let { richText(it) } ?: "")
            "str" -> FV.Str(v ?: "")
            "b" -> FV.Bool(v?.trim() == "1")
            "e" -> FV.Err(v ?: "#N/A")
            "d" -> v?.let { FxDates.parse(it.replace('T', ' ').substringBefore('.').removeSuffix("Z")) }?.let { FV.Num(it) } ?: FV.Str(v ?: "")
            else -> v?.trim()?.toDoubleOrNull()?.let { FV.Num(it) } ?: FV.Blank
        }
        return if (formula != null) Cell5(formula, FV.Blank, style, -1, value) else Cell5(null, value, style, sstIdx)
    }

    // ================= saving =================

    fun save(w: Workbook5, target: File) {
        if (w.kind == Workbook5.Kind.CSV) { CsvIO.save(w, target); w.modified = false; return }
        val pkg = w.pkg ?: throw IllegalStateException("no package")
        val meta = w.meta ?: throw IllegalStateException("no meta")
        val wb = pkg.edit(meta.wbPath) ?: throw XmlParseException("workbook.xml missing")
        val sstState = SstWriter(pkg, meta)
        val rP = wb.ensureNs("r", OPackage.R_NS)
        val p = wb.root.prefix.let { if (it.isEmpty()) "" else "$it:" }

        // removed sheets
        val keptRel = w.book.sheets.mapNotNull { it.relId }.toSet()
        meta.originalSheets.forEach { (rid, path) ->
            if (rid !in keptRel) {
                pkg.removeRel(meta.wbPath, rid); pkg.remove(path); pkg.remove(pkg.relsPath(path)); pkg.removeOverride(path)
            }
        }
        // new sheets get parts
        for (s in w.book.sheets) if (s.partPath == null) {
            val path = pkg.freeName("xl/worksheets", "sheet", "xml")
            pkg.put(path, XDom.parse(OPackage.DECL + "<worksheet xmlns=\"$NS\" xmlns:r=\"${OPackage.R_NS}\"><sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
                "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData/><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>"))
            s.relId = pkg.addRel(meta.wbPath, REL_WS, path); s.partPath = path; s.dirty = true
            pkg.addOverride(path, CT_WS)
        }
        // sheet list
        val sheetsEl = wb.root.child("sheets") ?: XElem(p + "sheets").also { wb.root.add(it) }
        val old = sheetsEl.children("sheet").associateBy { it.attr("r:id") ?: it.attr("id") }
        var maxId = sheetsEl.children("sheet").mapNotNull { it.attr("sheetId")?.toIntOrNull() }.maxOrNull() ?: 0
        sheetsEl.kids.clear()
        for (s in w.book.sheets) {
            val e = old[s.relId] ?: XElem(p + "sheet").set("sheetId", (++maxId).toString()).set("$rP:id", s.relId!!)
            e.set("name", s.name)
            sheetsEl.add(e)
        }
        // defined names scoped to sheets follow their sheet
        val newIndex = meta.originalSheets.mapIndexed { i, (rid, _) -> i to w.book.sheets.indexOfFirst { it.relId == rid }.takeIf { it >= 0 } }.toMap()
        wb.root.child("definedNames")?.let { dn ->
            for (n in dn.children("definedName")) {
                val li = n.attr("localSheetId")?.toIntOrNull() ?: continue
                val ni = newIndex[li]
                if (ni == null) dn.remove(n) else n.set("localSheetId", ni.toString())
            }
            if (dn.elems().isEmpty()) wb.root.remove(dn)
        }
        wb.root.child("bookViews")?.children("workbookView")?.forEach { v ->
            if ((v.attr("activeTab")?.toIntOrNull() ?: 0) >= w.book.sheets.size) v.set("activeTab", "0")
            if ((v.attr("firstSheet")?.toIntOrNull() ?: 0) >= w.book.sheets.size) v.set("firstSheet", "0")
        }
        requestRecalc(pkg, meta.wbPath, wb)
        // worksheets
        for (s in w.book.sheets) if (s.dirty) {
            val d = pkg.edit(s.partPath!!) ?: continue
            writeSheet(w, s, d.root, sstState)
            s.dirty = false
        }
        sstState.finish()
        pkg.saveTo(target)
        w.modified = false
    }

    private fun requestRecalc(pkg: OPackage, wbPath: String, wb: XDoc) {
        pkg.rels(wbPath).firstOrNull { it.type.endsWith("/calcChain") }?.let { ch ->
            pkg.remove(ch.target); pkg.removeOverride(ch.target); pkg.removeRel(wbPath, ch.id)
        }
        val p = wb.root.prefix.let { if (it.isEmpty()) "" else "$it:" }
        val calc = wb.root.child("calcPr") ?: XElem(p + "calcPr").also { e ->
            val after = wb.root.kids.indexOfLast { it is XElem && it.local in setOf("sheets", "functionGroups", "externalReferences", "definedNames") }
            wb.root.add(after + 1, e)
        }
        calc.set("fullCalcOnLoad", "1")
    }

    /** Appends strings to sharedStrings.xml only when needed. */
    private class SstWriter(val pkg: OPackage, val meta: Meta) {
        private val index = HashMap<String, Int>().also { m -> meta.sst.forEachIndexed { i, s -> m.putIfAbsent(s, i) } }
        private val added = ArrayList<String>()
        var refs = 0

        fun indexFor(cell: Cell5, text: String): Int {
            refs++
            if (cell.sst >= 0 && meta.sst.getOrNull(cell.sst) == text) return cell.sst
            index[text]?.let { return it }
            val i = meta.sst.size; meta.sst.add(text); index[text] = i; added.add(text); return i
        }

        fun finish() {
            if (added.isEmpty()) return
            val wbRels = pkg.rels(meta.wbPath)
            val path = wbRels.firstOrNull { it.type == REL_SST }?.target ?: run {
                val sp = "xl/sharedStrings.xml"
                pkg.put(sp, XDom.parse(OPackage.DECL + "<sst xmlns=\"$NS\" count=\"0\" uniqueCount=\"0\"></sst>"))
                pkg.addRel(meta.wbPath, REL_SST, sp); pkg.addOverride(sp, CT_SST); sp
            }
            val d = pkg.edit(path)!!
            val p = d.root.prefix.let { if (it.isEmpty()) "" else "$it:" }
            for (t in added) {
                val te = XElem(p + "t").setText(t)
                if (t.isNotEmpty() && (t.first().isWhitespace() || t.last().isWhitespace())) te.set("xml:space", "preserve")
                d.root.add(XElem(p + "si").add(te))
            }
            val n = d.root.children("si").size
            d.root.set("uniqueCount", n.toString())
            d.root.set("count", maxOf(n, (d.root.attr("count")?.toIntOrNull() ?: 0) + added.size).toString())
        }
    }

    private fun num(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else java.lang.Double.toString(d)

    private fun writeSheet(w: Workbook5, s: Sheet5, root: XElem, sst: SstWriter) {
        val p = root.prefix.let { if (it.isEmpty()) "" else "$it:" }
        // sheetData
        val rows = java.util.TreeMap<Int, MutableList<Pair<Int, Cell5>>>()
        for ((k, c) in s.cells) rows.getOrPut(Sheet5.row(k)) { ArrayList() }.add(Sheet5.col(k) to c)
        (s.rowHeights.keys + s.rowAttrs.keys).forEach { rows.getOrPut(it) { ArrayList() } }
        val sd = XElem(p + "sheetData")
        for ((r, cells) in rows) {
            val re = XElem(p + "row").set("r", (r + 1).toString())
            s.rowAttrs[r]?.forEach { re.attrs.add(XAttr(it.name, it.raw, it.quote)) }
            s.rowHeights[r]?.let { re.set("ht", NumFmt5.general(it.toDouble())); re.set("customHeight", "1") }
            for ((c, cell) in cells.sortedBy { it.first }) re.add(cellXml(w, s, r, c, cell, p, sst))
            sd.add(re)
        }
        root.setOrdered(sd, ORDER)
        // dimension
        val used = s.usedRange()
        root.child("dimension")?.set("ref", used?.let { CRange(0, 0, it.r2, it.c2).a1() } ?: "A1")
        // cols
        val colKeys = (s.colWidths.keys + s.colStyles.keys + s.colHidden).toSortedSet()
        if (colKeys.isEmpty()) root.removeChildren("cols") else {
            val ce = XElem(p + "cols")
            var start = -1; var prev = -2; var sig: Triple<Float?, String?, Boolean>? = null
            fun flush(end: Int) {
                if (start < 0) return
                val e = XElem(p + "col").set("min", (start + 1).toString()).set("max", (end + 1).toString())
                sig!!.first?.let { e.set("width", NumFmt5.general(it.toDouble())); e.set("customWidth", "1") } ?: e.set("width", NumFmt5.general(s.defaultColWidth.toDouble()))
                sig!!.second?.let { e.set("style", it) }
                if (sig!!.third) e.set("hidden", "1")
                ce.add(e)
            }
            for (c in colKeys) {
                val cs = Triple(s.colWidths[c], s.colStyles[c], c in s.colHidden)
                if (c != prev + 1 || cs != sig) { flush(prev); start = c; sig = cs }
                prev = c
            }
            flush(prev)
            root.setOrdered(ce, ORDER)
        }
        // merges
        if (s.merges.isEmpty()) root.removeChildren("mergeCells") else {
            val me = XElem(p + "mergeCells").set("count", s.merges.size.toString())
            s.merges.forEach { me.add(XElem(p + "mergeCell").set("ref", it.a1())) }
            root.setOrdered(me, ORDER)
        }
        // view: direction and frozen panes
        val views = root.child("sheetViews") ?: XElem(p + "sheetViews").also { root.setOrdered(it, ORDER) }
        val view = views.children("sheetView").firstOrNull() ?: XElem(p + "sheetView").set("workbookViewId", "0").also { views.add(it) }
        if (s.rtl) view.set("rightToLeft", "1") else view.removeAttr("rightToLeft")
        val hadFrozen = view.child("pane")?.attr("state")?.startsWith("frozen") == true
        if (s.freezeRows > 0 || s.freezeCols > 0) {
            view.removeChildren("pane"); view.removeChildren("selection")
            val pane = XElem(p + "pane")
            if (s.freezeCols > 0) pane.set("xSplit", s.freezeCols.toString())
            if (s.freezeRows > 0) pane.set("ySplit", s.freezeRows.toString())
            val ap = when { s.freezeRows > 0 && s.freezeCols > 0 -> "bottomRight"; s.freezeRows > 0 -> "bottomLeft"; else -> "topRight" }
            pane.set("topLeftCell", A1.name(s.freezeRows, s.freezeCols)).set("activePane", ap).set("state", "frozen")
            view.add(0, pane)
            view.add(1, XElem(p + "selection").set("pane", ap))
        } else if (hadFrozen) { view.removeChildren("pane"); view.removeChildren("selection") }
    }

    private fun cellXml(w: Workbook5, s: Sheet5, r: Int, c: Int, cell: Cell5, p: String, sst: SstWriter): XElem {
        val e = XElem(p + "c").set("r", A1.name(r, c))
        if (cell.style != 0) e.set("s", cell.style.toString())
        if (cell.formula != null) {
            e.add(XElem(p + "f").setText(cell.formula))
            when (val v = w.book.value(s, r, c)) {
                is FV.Num -> e.add(XElem(p + "v").setText(num(v.v)))
                is FV.Str -> { e.set("t", "str"); e.add(XElem(p + "v").setText(v.v)) }
                is FV.Bool -> { e.set("t", "b"); e.add(XElem(p + "v").setText(if (v.v) "1" else "0")) }
                is FV.Err -> if (v == FV.CIRC) e.add(XElem(p + "v").setText("0")) else { e.set("t", "e"); e.add(XElem(p + "v").setText(v.code)) }
                else -> {}
            }
            return e
        }
        when (val v = cell.literal) {
            is FV.Num -> e.add(XElem(p + "v").setText(num(v.v)))
            is FV.Str -> { e.set("t", "s"); e.add(XElem(p + "v").setText(sst.indexFor(cell, v.v).toString())) }
            is FV.Bool -> { e.set("t", "b"); e.add(XElem(p + "v").setText(if (v.v) "1" else "0")) }
            is FV.Err -> { e.set("t", "e"); e.add(XElem(p + "v").setText(v.code)) }
            else -> {}
        }
        return e
    }

    // ================= new workbook =================

    /** A minimal valid .xlsx package with one sheet. */
    fun blankPackage(sheetName: String = "Sheet1", rtl: Boolean = true): OPackage {
        val ct = OPackage.DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"$CT_WS\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"$CT_STYLES\"/></Types>"
        val rels = OPackage.DECL + "<Relationships xmlns=\"${OPackage.REL_NS}\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        val wb = OPackage.DECL + "<workbook xmlns=\"$NS\" xmlns:r=\"${OPackage.R_NS}\"><bookViews><workbookView/></bookViews><sheets><sheet name=\"${XDom.escapeAttr(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val wbRels = OPackage.DECL + "<Relationships xmlns=\"${OPackage.REL_NS}\"><Relationship Id=\"rId1\" Type=\"$REL_WS\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"$REL_STYLES\" Target=\"styles.xml\"/></Relationships>"
        val sheet = OPackage.DECL + "<worksheet xmlns=\"$NS\" xmlns:r=\"${OPackage.R_NS}\"><sheetViews><sheetView workbookViewId=\"0\"" + (if (rtl) " rightToLeft=\"1\"" else "") + "/></sheetViews>" +
            "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData/><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>"
        return OPackage.create(linkedMapOf(
            OPackage.CT to ct.toByteArray(), "_rels/.rels" to rels.toByteArray(), "xl/workbook.xml" to wb.toByteArray(),
            "xl/_rels/workbook.xml.rels" to wbRels.toByteArray(), "xl/worksheets/sheet1.xml" to sheet.toByteArray(),
            "xl/styles.xml" to (OPackage.DECL + XStyles.EMPTY).toByteArray(),
        ))
    }

    fun blank(sheetName: String = "Sheet1", rtl: Boolean = true): Workbook5 = load(blankPackage(sheetName, rtl))
}
