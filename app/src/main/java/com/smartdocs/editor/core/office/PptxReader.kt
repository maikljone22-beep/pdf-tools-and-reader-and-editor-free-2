package com.smartdocs.editor.core.office

import org.xmlpull.v1.XmlPullParser

/**
 * PresentationML (.pptx) reader: slide order and size from presentation.xml, then for each slide
 * the shape tree (text shapes, pictures, tables, groups with child transforms). Placeholders that
 * carry no position inherit it from their slide layout / master. Layout artwork is skipped; a
 * solid slide background colour is kept.
 */
internal class PptxReader(private val pkg: OoxmlPackage) {
    private val presPath = pkg.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "ppt/presentation.xml"

    private data class Xfrm(val x: Long, val y: Long, val w: Long, val h: Long)
    private class Placeholder(val type: String?, val idx: String?, val xfrm: Xfrm?)
    private class GroupTx(val offX: Long, val offY: Long, val sx: Double, val sy: Double, val chX: Long, val chY: Long) {
        fun map(x: Xfrm) = Xfrm(offX + ((x.x - chX) * sx).toLong(), offY + ((x.y - chY) * sy).toLong(), (x.w * sx).toLong(), (x.h * sy).toLong())
    }

    private val layoutCache = HashMap<String, List<Placeholder>>()

    fun read(): PresentationModel {
        val p = pkg.parser(presPath) ?: throw OfficeFormatException("ppt/presentation.xml missing")
        val rels = pkg.rels(presPath)
        var cx = 12192000L; var cy = 6858000L
        val slidePaths = ArrayList<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "sldId" -> p.rAttr("id")?.let { rels[it]?.target }?.let { slidePaths.add(it) }
                "sldSz" -> { cx = p.attr("cx")?.toLongOrNull() ?: cx; cy = p.attr("cy")?.toLongOrNull() ?: cy }
            }
        }
        return PresentationModel(slidePaths.map { readSlide(it, cx, cy) })
    }

    private fun readSlide(path: String, cx: Long, cy: Long): SlideModel {
        val p = pkg.parser(path) ?: return SlideModel(cx, cy, emptyList())
        val layout = pkg.rels(path).values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val inherited = layout?.let { placeholders(it) } ?: emptyList()
        val shapes = ArrayList<SlideShape>()
        var bg: String? = null
        if (!p.seek("cSld")) return SlideModel(cx, cy, shapes)
        p.forEachChild { n ->
            when (n) {
                "bg" -> bg = findSolidFill(p)
                "spTree" -> readTree(p, path, inherited, null, shapes)
            }
        }
        // row 103: speaker notes (the slide's notesSlide part) and the hidden flag
        val notes = pkg.rels(path).values.firstOrNull { it.type.endsWith("/notesSlide") }?.target
            ?.let { pkg.bytes(it)?.toString(Charsets.UTF_8) }?.let { PptxNotes.text(it) }
        val hidden = pkg.bytes(path)?.let { b -> PptxNotes.hidden(String(b, 0, minOf(b.size, 2000), Charsets.UTF_8)) } ?: false
        return SlideModel(cx, cy, shapes, bg, notes, hidden)
    }

    /** Placeholder positions of a layout, falling back to its master for those without xfrm. */
    private fun placeholders(layoutPath: String): List<Placeholder> = layoutCache.getOrPut(layoutPath) {
        val own = collectPlaceholders(layoutPath)
        val master = pkg.rels(layoutPath).values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
        val masterPh = master?.let { m -> layoutCache.getOrPut(m) { collectPlaceholders(m) } } ?: emptyList()
        own.map { ph -> if (ph.xfrm != null) ph else Placeholder(ph.type, ph.idx, match(masterPh, ph.type, ph.idx)?.xfrm) } +
            masterPh.filter { m -> own.none { it.type == m.type } }
    }

    private fun collectPlaceholders(path: String): List<Placeholder> {
        val p = pkg.parser(path) ?: return emptyList()
        val out = ArrayList<Placeholder>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sp") {
                var type: String? = null; var idx: String? = null; var x: Xfrm? = null; var isPh = false
                p.forEachChild { c ->
                    when (c) {
                        "nvSpPr" -> p.forEachChild { k -> if (k == "nvPr") p.forEachChild { q -> if (q == "ph") { isPh = true; type = p.attr("type"); idx = p.attr("idx") } } }
                        "spPr" -> p.forEachChild { k -> if (k == "xfrm") x = readXfrm(p).first }
                    }
                }
                if (isPh) out.add(Placeholder(type, idx, x))
            }
        }
        return out
    }

    private fun match(list: List<Placeholder>, type: String?, idx: String?): Placeholder? {
        val t = type ?: "body"
        return list.firstOrNull { idx != null && it.idx == idx && (it.type ?: "body") == t }
            ?: list.firstOrNull { (it.type ?: "body") == t || (t == "ctrTitle" && it.type == "title") || (t == "title" && it.type == "ctrTitle") || (t == "subTitle" && it.type == "body") }
            ?: list.firstOrNull { idx != null && it.idx == idx }
    }

    private fun readTree(p: XmlPullParser, part: String, inherited: List<Placeholder>, tx: GroupTx?, out: MutableList<SlideShape>) {
        p.forEachChild { n ->
            when (n) {
                "sp" -> readShape(p, part, inherited, tx)?.let { out.add(it) }
                "pic" -> readPic(p, part, tx)?.let { out.add(it) }
                "graphicFrame" -> readFrame(p, tx)?.let { out.add(it) }
                "grpSp" -> readGroup(p, part, inherited, tx, out)
                "AlternateContent" -> p.forEachChild { c -> if (c == "Choice") readTree(p, part, inherited, tx, out) }
            }
        }
    }

    private fun readGroup(p: XmlPullParser, part: String, inherited: List<Placeholder>, tx: GroupTx?, out: MutableList<SlideShape>) {
        var g: GroupTx? = tx
        val d = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == d)) return
            if (ev != XmlPullParser.START_TAG || p.depth != d + 1) continue
            when (p.name) {
                "grpSpPr" -> p.forEachChild { c ->
                    if (c == "xfrm") {
                        val (x, ch) = readXfrm(p)
                        if (x != null) {
                            val mapped = tx?.map(x) ?: x
                            val chx = ch ?: x
                            g = GroupTx(mapped.x, mapped.y, if (chx.w > 0) mapped.w.toDouble() / chx.w else 1.0, if (chx.h > 0) mapped.h.toDouble() / chx.h else 1.0, chx.x, chx.y)
                        }
                    }
                }
                "sp" -> readShape(p, part, inherited, g)?.let { out.add(it) }
                "pic" -> readPic(p, part, g)?.let { out.add(it) }
                "graphicFrame" -> readFrame(p, g)?.let { out.add(it) }
                "grpSp" -> readGroup(p, part, inherited, g, out)
                else -> p.skipElement()
            }
        }
    }

    /** Reads `a:xfrm`/`p:xfrm`: (off+ext, chOff+chExt). */
    private fun readXfrm(p: XmlPullParser): Pair<Xfrm?, Xfrm?> {
        var x = 0L; var y = 0L; var w = 0L; var h = 0L; var cx = 0L; var cy = 0L; var cw = 0L; var ch = 0L; var hasCh = false; var has = false
        p.forEachChild { n ->
            when (n) {
                "off" -> { x = p.attr("x")?.toLongOrNull() ?: 0; y = p.attr("y")?.toLongOrNull() ?: 0; has = true }
                "ext" -> { w = p.attr("cx")?.toLongOrNull() ?: 0; h = p.attr("cy")?.toLongOrNull() ?: 0 }
                "chOff" -> { cx = p.attr("x")?.toLongOrNull() ?: 0; cy = p.attr("y")?.toLongOrNull() ?: 0; hasCh = true }
                "chExt" -> { cw = p.attr("cx")?.toLongOrNull() ?: 0; ch = p.attr("cy")?.toLongOrNull() ?: 0 }
            }
        }
        return (if (has) Xfrm(x, y, w, h) else null) to (if (hasCh) Xfrm(cx, cy, cw, ch) else null)
    }

    private fun readShape(p: XmlPullParser, part: String, inherited: List<Placeholder>, tx: GroupTx?): SlideShape? {
        var x: Xfrm? = null; var phType: String? = null; var phIdx: String? = null; var isPh = false
        var fill: String? = null; var line: String? = null; var anchor: String? = null; var rot = 0f
        var paras: List<SlideParagraph> = emptyList()
        p.forEachChild { n ->
            when (n) {
                "nvSpPr" -> p.forEachChild { k -> if (k == "nvPr") p.forEachChild { q -> if (q == "ph") { isPh = true; phType = p.attr("type"); phIdx = p.attr("idx") } } }
                "spPr" -> p.forEachChild { k ->
                    when (k) {
                        "xfrm" -> { rot = (p.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f; x = readXfrm(p).first }
                        "solidFill" -> fill = readColor(p)
                        "ln" -> p.forEachChild { q -> if (q == "solidFill") line = readColor(p) }
                    }
                }
                "txBody" -> {
                    val r = readTextBody(p); paras = r.first; anchor = r.second
                }
            }
        }
        if (x == null && isPh) x = match(inherited, phType, phIdx)?.xfrm
        val pos = x ?: return null
        val m = tx?.map(pos) ?: pos
        if (paras.all { sp -> sp.runs.none { it is Inline.Text && it.text.isNotBlank() } } && fill == null && line == null) return null
        if (phType == "ctrTitle" || phType == "subTitle") paras = paras.map { if (it.align == null) it.copy(align = Align.CENTER) else it }
        val defSize = when (phType) { "title", "ctrTitle" -> 40f; "subTitle" -> 24f; "body", null -> if (isPh) 24f else 18f; else -> 14f }
        return SlideShape.TextBox(m.x, m.y, m.w, m.h, paras, fill, line, anchor ?: if (phType == "title") "ctr" else null, defSize, rot)
    }

    private fun readTextBody(p: XmlPullParser): Pair<List<SlideParagraph>, String?> {
        val out = ArrayList<SlideParagraph>(); var anchor: String? = null
        p.forEachChild { n ->
            when (n) {
                "bodyPr" -> anchor = p.attr("anchor")
                "p" -> out.add(readParagraph(p))
            }
        }
        return out to anchor
    }

    private fun readParagraph(p: XmlPullParser): SlideParagraph {
        var align: Align? = null; var rtl = false; var lvl = 0; var bullet: String? = null
        val runs = ArrayList<Inline>()
        p.forEachChild { n ->
            when (n) {
                "pPr" -> {
                    align = when (p.attr("algn")) { "ctr" -> Align.CENTER; "r" -> Align.RIGHT; "l" -> Align.LEFT; "just", "dist" -> Align.JUSTIFY; else -> null }
                    rtl = p.attr("rtl") == "1"; lvl = p.attr("lvl")?.toIntOrNull() ?: 0
                    p.forEachChild { k -> when (k) { "buChar" -> bullet = p.attr("char")?.let { if (it.firstOrNull()?.code in 0xF000..0xF0FF) "•" else it }; "buAutoNum" -> bullet = "•" } }
                }
                "r", "fld" -> {
                    var style = RunStyle(); var text = ""
                    p.forEachChild { k -> when (k) { "rPr" -> style = readRunProps(p); "t" -> text = p.readText() } }
                    runs.add(Inline.Text(text, style))
                }
                "br" -> runs.add(Inline.LineBreak)
            }
        }
        return SlideParagraph(runs, align, rtl, lvl, bullet)
    }

    private fun readRunProps(p: XmlPullParser): RunStyle {
        var s = RunStyle(
            bold = p.attr("b") == "1", italic = p.attr("i") == "1",
            underline = p.attr("u").let { it != null && it != "none" }, strike = p.attr("strike").let { it != null && it != "noStrike" },
            sizePt = p.attr("sz")?.toFloatOrNull()?.div(100f),
            vertAlign = p.attr("baseline")?.toIntOrNull()?.let { if (it > 0) "superscript" else if (it < 0) "subscript" else null },
        )
        p.forEachChild { n ->
            when (n) {
                "solidFill" -> s = s.copy(color = readColor(p))
                "highlight" -> s = s.copy(background = readColor(p))
                "latin", "cs" -> if (s.font == null || n == "cs") p.attr("typeface")?.takeIf { !it.startsWith("+") }?.let { s = s.copy(font = it) }
            }
        }
        return s
    }

    /** Colour inside a fill element: srgbClr, sysClr lastClr or a few preset/scheme fallbacks. */
    private fun readColor(p: XmlPullParser): String? {
        var c: String? = null
        p.forEachChild { n ->
            when (n) {
                "srgbClr" -> c = parseColor(p.attr("val"))
                "sysClr" -> c = parseColor(p.attr("lastClr"))
                "prstClr" -> c = when (p.attr("val")) { "black" -> "000000"; "white" -> "FFFFFF"; "red" -> "FF0000"; "blue" -> "0000FF"; "green" -> "008000"; else -> null }
                "schemeClr" -> c = when (p.attr("val")) { "bg1", "lt1" -> "FFFFFF"; "tx1", "dk1" -> "000000"; else -> null }
            }
        }
        return c
    }

    private fun findSolidFill(p: XmlPullParser): String? {
        var c: String? = null
        val d = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == d)) return c
            if (ev == XmlPullParser.START_TAG && p.name == "solidFill" && c == null) c = readColor(p)
        }
    }

    private fun readPic(p: XmlPullParser, part: String, tx: GroupTx?): SlideShape? {
        var x: Xfrm? = null; var embed: String? = null
        p.forEachChild { n ->
            when (n) {
                "blipFill" -> p.forEachChild { k -> if (k == "blip") embed = p.rAttr("embed") }
                "spPr" -> p.forEachChild { k -> if (k == "xfrm") x = readXfrm(p).first }
            }
        }
        val pos = x ?: return null
        val m = tx?.map(pos) ?: pos
        return SlideShape.Picture(m.x, m.y, m.w, m.h, pkg.image(part, embed))
    }

    private fun readFrame(p: XmlPullParser, tx: GroupTx?): SlideShape? {
        var x: Xfrm? = null
        val rows = ArrayList<List<List<SlideParagraph>>>(); val cols = ArrayList<Long>()
        p.forEachChild { n ->
            when (n) {
                "xfrm" -> x = readXfrm(p).first
                "graphic" -> p.forEachChild { g ->
                    if (g == "graphicData") p.forEachChild { t ->
                        if (t == "tbl") p.forEachChild { k ->
                            when (k) {
                                "tblGrid" -> p.forEachChild { q -> if (q == "gridCol") cols.add(p.attr("w")?.toLongOrNull() ?: 0) }
                                "tr" -> {
                                    val cells = ArrayList<List<SlideParagraph>>()
                                    p.forEachChild { q ->
                                        if (q == "tc") {
                                            var paras: List<SlideParagraph> = emptyList()
                                            p.forEachChild { b -> if (b == "txBody") paras = readTextBody(p).first }
                                            cells.add(paras)
                                        }
                                    }
                                    rows.add(cells)
                                }
                            }
                        }
                    }
                }
            }
        }
        val pos = x ?: return null
        if (rows.isEmpty()) return null
        val m = tx?.map(pos) ?: pos
        return SlideShape.Table(m.x, m.y, m.w, m.h, rows, cols)
    }
}
