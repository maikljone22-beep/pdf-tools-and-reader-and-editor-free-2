package com.smartdocs.editor.core.office5

/** Resolved formatting of a cell style (what the grid draws and what the ribbon edits). */
data class CellFormat(
    val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
    val fontColor: String? = null, val fontSize: Float = 11f, val fontName: String? = null,
    val fill: String? = null,
    /** left/center/right/general... ("general" = by value type). */
    val hAlign: String? = null, val vAlign: String? = null, val wrap: Boolean = false,
    val numFmt: String = "General",
    /** Border line styles (thin, medium, thick, dashed, double…) or null. */
    val left: String? = null, val right: String? = null, val top: String? = null, val bottom: String? = null,
    val borderColor: String? = null,
)

/**
 * styles.xml as an editable DOM: reads fonts/fills/borders/numFmts/cellXfs into [CellFormat]s and creates new
 * entries for new combinations (appended, counts updated, everything else in the part is kept).
 */
class XStyles(private val pkg: OPackage?, private val path: String?, doc: XDoc?) {
    private val dom: XDoc = doc ?: XDom.parse(OPackage.DECL + EMPTY)
    private var editing = false
    private val cache = HashMap<Int, CellFormat>()
    private val byFormat = HashMap<CellFormat, Int>()

    private val root get() = dom.root
    private fun list(name: String) = root.child(name)?.elems() ?: emptyList()

    val xfCount: Int get() = list("cellXfs").size.coerceAtLeast(1)

    fun numFmtCode(id: Int): String? = list("numFmts").firstOrNull { it.attr("numFmtId")?.toIntOrNull() == id }?.attr("formatCode") ?: NumFmt5.builtin(id)

    fun numFmtOfStyle(style: Int): String? {
        val xf = list("cellXfs").getOrNull(style) ?: return null
        return numFmtCode(xf.attr("numFmtId")?.toIntOrNull() ?: 0)
    }

    fun format(style: Int): CellFormat = cache.getOrPut(style) { read(style) }

    private fun read(style: Int): CellFormat {
        val xf = list("cellXfs").getOrNull(style) ?: return CellFormat()
        val font = list("fonts").getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
        val fill = list("fills").getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0)
        val border = list("borders").getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)
        val al = xf.child("alignment")
        fun on(e: XElem?) = e != null && e.attr("val").let { it == null || it == "1" || it == "true" }
        fun side(n: String) = border?.child(n)?.attr("style")?.takeIf { it != "none" }
        return CellFormat(
            bold = on(font?.child("b")), italic = on(font?.child("i")), underline = font?.child("u")?.let { it.attr("val") != "none" } == true,
            strike = on(font?.child("strike")), fontColor = color(font?.child("color"))?.takeIf { it != "000000" }, fontSize = font?.child("sz")?.attr("val")?.toFloatOrNull() ?: 11f,
            fontName = font?.child("name")?.attr("val"),
            fill = fill?.child("patternFill")?.takeIf { it.attr("patternType") != "none" && it.attr("patternType") != null }?.let { color(it.child("fgColor")) },
            hAlign = al?.attr("horizontal"), vAlign = al?.attr("vertical"), wrap = al?.attr("wrapText").let { it == "1" || it == "true" },
            numFmt = numFmtCode(xf.attr("numFmtId")?.toIntOrNull() ?: 0) ?: "General",
            left = side("left"), right = side("right"), top = side("top"), bottom = side("bottom"),
            borderColor = border?.elems()?.firstNotNullOfOrNull { color(it.child("color")) },
        )
    }

    private fun color(e: XElem?): String? {
        e ?: return null
        e.attr("rgb")?.let { return WordFormat.color(it) }
        e.attr("theme")?.toIntOrNull()?.let { return THEME.getOrNull(it) }
        e.attr("indexed")?.toIntOrNull()?.let { return INDEXED.getOrNull(it) }
        return null
    }

    /** Style index with format [f], derived from [base] (reused when an equal one exists). */
    fun styleFor(f: CellFormat, base: Int = 0): Int {
        if (f == format(base)) return base
        byFormat[f]?.let { return it }
        for (i in 0 until list("cellXfs").size) if (format(i) == f) { byFormat[f] = i; return i }
        startEdit()
        val baseXf = list("cellXfs").getOrNull(base)
        val xf = baseXf?.copy() ?: XElem("xf").set("numFmtId", "0").set("fontId", "0").set("fillId", "0").set("borderId", "0").set("xfId", "0")
        xf.set("fontId", add("fonts", fontXml(f)).toString()).set("applyFont", "1")
        xf.set("fillId", add("fills", fillXml(f.fill)).toString()).set("applyFill", "1")
        xf.set("borderId", add("borders", borderXml(f)).toString()).set("applyBorder", "1")
        xf.set("numFmtId", numFmtId(f.numFmt).toString()).set("applyNumberFormat", "1")
        xf.removeChildren("alignment")
        if (f.hAlign != null || f.vAlign != null || f.wrap) {
            val al = XElem(pfx() + "alignment")
            f.hAlign?.let { al.set("horizontal", it) }; f.vAlign?.let { al.set("vertical", it) }; if (f.wrap) al.set("wrapText", "1")
            xf.add(0, al); xf.set("applyAlignment", "1")
        }
        val idx = add("cellXfs", xf)
        cache[idx] = f; byFormat[f] = idx
        return idx
    }

    private fun pfx() = root.prefix.let { if (it.isEmpty()) "" else "$it:" }

    private fun startEdit() { if (!editing && pkg != null && path != null) { pkg.edit(path); editing = true } }

    /** Appends [e] (or returns the index of an equal entry) in collection [name]; updates the count. */
    private fun add(name: String, e: XElem): Int {
        val coll = root.child(name) ?: XElem(pfx() + name).also { root.setOrdered(it, ORDER) }
        val key = e.toXml()
        val existing = coll.elems().indexOfFirst { it.toXml() == key }
        if (existing >= 0) return existing
        coll.add(e); coll.set("count", coll.elems().size.toString())
        return coll.elems().size - 1
    }

    private fun numFmtId(code: String): Int {
        for (id in 0..49) if (NumFmt5.builtin(id) == code) return id
        list("numFmts").firstOrNull { it.attr("formatCode") == code }?.attr("numFmtId")?.toIntOrNull()?.let { return it }
        val coll = root.child("numFmts") ?: XElem(pfx() + "numFmts").also { root.add(0, it) }
        val id = maxOf(163, coll.elems().mapNotNull { it.attr("numFmtId")?.toIntOrNull() }.maxOrNull() ?: 163) + 1
        coll.add(XElem(pfx() + "numFmt").set("numFmtId", id.toString()).set("formatCode", code))
        coll.set("count", coll.elems().size.toString())
        return id
    }

    private fun fontXml(f: CellFormat): XElem {
        val p = pfx()
        val e = XElem(p + "font")
        if (f.bold) e.add(XElem(p + "b")); if (f.italic) e.add(XElem(p + "i")); if (f.strike) e.add(XElem(p + "strike")); if (f.underline) e.add(XElem(p + "u"))
        e.add(XElem(p + "sz").set("val", NumFmt5.general(f.fontSize.toDouble())))
        e.add(XElem(p + "color").also { c -> if (f.fontColor != null) c.set("rgb", "FF" + f.fontColor) else c.set("theme", "1") })
        e.add(XElem(p + "name").set("val", f.fontName ?: defaultFontName()))
        e.add(XElem(p + "family").set("val", "2"))
        return e
    }

    private fun defaultFontName(): String = list("fonts").firstOrNull()?.child("name")?.attr("val") ?: "Calibri"

    private fun fillXml(hex: String?): XElem {
        val p = pfx()
        if (hex == null) return XElem(p + "fill").add(XElem(p + "patternFill").set("patternType", "none"))
        return XElem(p + "fill").add(XElem(p + "patternFill").set("patternType", "solid")
            .add(XElem(p + "fgColor").set("rgb", "FF$hex")).add(XElem(p + "bgColor").set("indexed", "64")))
    }

    private fun borderXml(f: CellFormat): XElem {
        val p = pfx()
        val e = XElem(p + "border")
        fun side(n: String, st: String?) = XElem(p + n).also { s -> if (st != null) { s.set("style", st); s.add(XElem(p + "color").also { c -> if (f.borderColor != null) c.set("rgb", "FF" + f.borderColor) else c.set("auto", "1") }) } }
        e.add(side("left", f.left)).add(side("right", f.right)).add(side("top", f.top)).add(side("bottom", f.bottom)).add(XElem(p + "diagonal"))
        return e
    }

    fun toBytes(): ByteArray = dom.toBytes()

    companion object {
        private val ORDER = listOf("numFmts", "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst")
        /** Office theme colours (lt1 dk1 lt2 dk2 accent1-6) in theme index order 0..9. */
        private val THEME = listOf("FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47")
        private val INDEXED = listOf("000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080")
        const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val EMPTY = "<styleSheet xmlns=\"$NS\"><fonts count=\"1\"><font><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
    }
}
