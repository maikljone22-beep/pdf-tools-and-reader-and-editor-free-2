package com.smartdocs.editor.core.office5

import java.io.File

/**
 * An editable .pptx: slide order lives in presentation.xml `p:sldIdLst`; each slide is a DOM edited in place
 * (shape trees keep every unknown element). Saving rewrites only edited parts; removed slides take their
 * relationships, content-type overrides and notes with them.
 */
class PptDoc private constructor(val pkg: OPackage) {
    val presPath: String = pkg.mainPart("ppt/presentation.xml")
    private val undoStack = SnapshotUndo(pkg)
    var version = 0; private set
    var modified = false
    val canUndo get() = undoStack.canUndo
    val canRedo get() = undoStack.canRedo

    val pres: XDoc get() = pkg.dom(presPath) ?: throw XmlParseException("presentation.xml missing")

    fun <T> change(block: () -> T): T { undoStack.checkpoint(); val r = block(); modified = true; version++; return r }

    /** Continues the previous undo step (live dragging). */
    fun <T> continueSlide(i: Int, block: () -> T): T { editSlide(i); val r = block(); modified = true; version++; return r }

    /** Text run property children in CT_TextCharacterProperties order. */
    val RPR_ORDER = listOf("ln", "noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill", "effectLst", "effectDag", "highlight",
        "uLnTx", "uLn", "uFillTx", "uFill", "latin", "ea", "cs", "sym", "hlinkClick", "hlinkMouseOver", "rtl", "extLst")

    /** Sets a solid text colour on a run property element (null = remove the explicit colour). */
    fun setRunColor(rPr: XElem, hex: String?) {
        rPr.kids.removeAll { it is XElem && it.local in setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill") }
        if (hex != null) rPr.setOrdered(XElem("a:solidFill").add(XElem("a:srgbClr").set("val", hex)), RPR_ORDER)
    }

    /** Sets the background colour of slide [i]. */
    fun setBackground(i: Int, hex: String) {
        val d = editSlide(i) ?: return
        val p = d.prefixOf(P_NS) ?: "p"; val a = d.ensureNs("a", A_NS)
        val cSld = d.root.child("cSld") ?: return
        cSld.removeChildren("bg")
        cSld.add(0, XDom.elem("<$p:bg><$p:bgPr><$a:solidFill><$a:srgbClr val=\"$hex\"/></$a:solidFill><$a:effectLst/></$p:bgPr></$p:bg>"))
    }

    /** A change to elements of slide [i] (marks its part as edited so the change is saved). */
    fun <T> changeSlide(i: Int, block: () -> T): T = change { editSlide(i); block() }
    fun undo(): Boolean = undoStack.undo().also { if (it) { modified = true; version++ } }
    fun redo(): Boolean = undoStack.redo().also { if (it) { modified = true; version++ } }
    fun save(target: File) { pkg.saveTo(target); modified = false }

    /** Slide size in EMU. */
    val slideSize: Pair<Long, Long> get() {
        val s = pres.root.child("sldSz")
        return (s?.attr("cx")?.toLongOrNull() ?: 12192000L) to (s?.attr("cy")?.toLongOrNull() ?: 6858000L)
    }

    private fun sldIdLst(edit: Boolean): XElem {
        val d = if (edit) pkg.edit(presPath)!! else pres
        return d.root.child("sldIdLst") ?: XElem(pfx(d) + "sldIdLst").also { d.root.setOrdered(it, PRES_ORDER) }
    }

    private fun pfx(d: XDoc) = d.root.prefix.let { if (it.isEmpty()) "" else "$it:" }

    fun slideIds(): List<XElem> = pres.root.child("sldIdLst")?.children("sldId") ?: emptyList()

    fun slidePaths(): List<String> {
        val rels = pkg.rels(presPath)
        return slideIds().mapNotNull { id -> (id.attr("r:id") ?: id.attr("id"))?.let { rid -> rels.firstOrNull { it.id == rid }?.target } }
    }

    val slideCount: Int get() = slidePaths().size

    fun slide(i: Int): XDoc? = slidePaths().getOrNull(i)?.let { pkg.dom(it) }
    fun editSlide(i: Int): XDoc? = slidePaths().getOrNull(i)?.let { pkg.edit(it) }

    fun layoutOf(slidePath: String): String? = pkg.rels(slidePath).firstOrNull { it.type.endsWith("/slideLayout") }?.target

    // ---------------- slide list ----------------

    /** Moves slide [from] to position [to]. */
    fun moveSlide(from: Int, to: Int) {
        val lst = sldIdLst(true)
        val ids = lst.children("sldId")
        if (from !in ids.indices || to !in ids.indices || from == to) return
        val e = ids[from]
        lst.remove(e)
        val rest = lst.children("sldId")
        if (to >= rest.size) { val last = rest.last(); lst.add(lst.indexOf(last) + 1, e) } else lst.add(lst.indexOf(rest[to]), e)
    }

    fun deleteSlide(i: Int): Boolean {
        val ids = slideIds(); if (ids.size <= 1 || i !in ids.indices) return false
        val path = slidePaths()[i]
        val rid = ids[i].attr("r:id") ?: ids[i].attr("id")!!
        sldIdLst(true).let { lst -> lst.children("sldId").getOrNull(i)?.let { lst.remove(it) } }
        pkg.removeRel(presPath, rid)
        // notes of the slide go too
        pkg.rels(path).filter { it.type.endsWith("/notesSlide") && !it.external }.forEach { n ->
            pkg.remove(n.target); pkg.remove(pkg.relsPath(n.target)); pkg.removeOverride(n.target)
        }
        pkg.remove(path); pkg.remove(pkg.relsPath(path)); pkg.removeOverride(path)
        return true
    }

    private fun newSlideId(): Int = (slideIds().mapNotNull { it.attr("id")?.toIntOrNull() }.maxOrNull() ?: 255) + 1

    /** Registers [path] (already stored) as a slide at position [at]. */
    private fun register(path: String, at: Int) {
        pkg.addOverride(path, CT_SLIDE)
        val rid = pkg.addRel(presPath, REL_SLIDE, path)
        val d = pkg.edit(presPath)!!
        val rp = d.ensureNs("r", OPackage.R_NS)
        val e = XElem(pfx(d) + "sldId").set("id", newSlideId().toString()).set("$rp:id", rid)
        val lst = sldIdLst(true)
        val ids = lst.children("sldId")
        if (at >= ids.size) lst.add(e) else lst.add(lst.indexOf(ids[at]), e)
    }

    /** Copies slide [i] (shapes, pictures, layout link; not its notes) to position i + 1. */
    fun duplicateSlide(i: Int): Int {
        val src = slidePaths().getOrNull(i) ?: return -1
        val path = pkg.freeName("ppt/slides", "slide", "xml")
        pkg.put(path, pkg.bytes(src)!!)
        pkg.dom(pkg.relsPath(src))?.let { rels ->
            val copy = rels.copy()
            copy.root.children("Relationship").filter { it.attr("Type")?.endsWith("/notesSlide") == true }.forEach { copy.root.remove(it) }
            pkg.put(pkg.relsPath(path), copy)
        }
        register(path, i + 1)
        return i + 1
    }

    /** A new slide after [after] using the layout of slide [after] (title + body placeholders when the layout has them). */
    fun newSlide(after: Int): Int {
        val base = slidePaths().getOrNull(after.coerceAtLeast(0))
        val layout = base?.let { layoutOf(it) } ?: pkg.names.firstOrNull { it.startsWith("ppt/slideLayouts/") && it.endsWith(".xml") }
        val phs = layout?.let { pkg.dom(it) }?.root?.findAll("ph") ?: emptyList()
        val hasTitle = phs.any { it.attr("type") == "title" || it.attr("type") == "ctrTitle" }
        val bodyIdx = phs.firstOrNull { it.attr("type") == null || it.attr("type") == "body" || it.attr("type") == "obj" }?.attr("idx")
        val sb = StringBuilder()
        var id = 2
        if (hasTitle) sb.append(phShape(id++, "Title", "type=\"title\""))
        if (bodyIdx != null) sb.append(phShape(id, "Content", "idx=\"$bodyIdx\""))
        val xml = OPackage.DECL + "<p:sld xmlns:a=\"$A_NS\" xmlns:r=\"${OPackage.R_NS}\" xmlns:p=\"$P_NS\"><p:cSld><p:spTree>" +
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$sb</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
        val path = pkg.freeName("ppt/slides", "slide", "xml")
        pkg.put(path, XDom.parse(xml))
        if (layout != null) pkg.addRel(path, REL_LAYOUT, layout)
        val at = (after + 1).coerceIn(0, slideCount)
        register(path, at)
        return at
    }

    private fun phShape(id: Int, name: String, ph: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name $id\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>" +
            "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"ar-SA\"/></a:p></p:txBody></p:sp>"

    // ---------------- shapes ----------------

    fun spTree(d: XDoc): XElem? = d.root.child("cSld")?.child("spTree")

    private fun nextShapeId(d: XDoc): Int = (d.root.findAll("cNvPr").mapNotNull { it.attr("id")?.toIntOrNull() }.maxOrNull() ?: 1) + 1

    /** Adds a text box; returns its element. */
    fun addTextBox(slide: Int, x: Long, y: Long, w: Long, h: Long, text: String, rtl: Boolean): XElem? {
        val d = editSlide(slide) ?: return null
        val id = nextShapeId(d)
        val p = d.prefixOf(P_NS) ?: "p"; val a = d.ensureNs("a", A_NS)
        val paras = text.split('\n').joinToString("") { line ->
            "<$a:p><$a:pPr" + (if (rtl) " algn=\"r\" rtl=\"1\"" else "") + "/>" + (if (line.isEmpty()) "" else "<$a:r><$a:rPr lang=\"${if (rtl) "ar-SA" else "en-US"}\" sz=\"2400\" dirty=\"0\"/><$a:t>${XDom.escapeText(line)}</$a:t></$a:r>") + "</$a:p>"
        }
        val e = XDom.elem("<$p:sp><$p:nvSpPr><$p:cNvPr id=\"$id\" name=\"TextBox $id\"/><$p:cNvSpPr txBox=\"1\"/><$p:nvPr/></$p:nvSpPr>" +
            "<$p:spPr><$a:xfrm><$a:off x=\"$x\" y=\"$y\"/><$a:ext cx=\"$w\" cy=\"$h\"/></$a:xfrm><$a:prstGeom prst=\"rect\"><$a:avLst/></$a:prstGeom><$a:noFill/></$p:spPr>" +
            "<$p:txBody><$a:bodyPr wrap=\"square\" rtlCol=\"${if (rtl) 1 else 0}\"><$a:spAutoFit/></$a:bodyPr><$a:lstStyle/>$paras</$p:txBody></$p:sp>")
        spTree(d)?.add(e)
        return e
    }

    /** Adds a preset shape (rect, roundRect, ellipse, triangle, rightArrow, line…). */
    fun addShape(slide: Int, prst: String, x: Long, y: Long, w: Long, h: Long, fill: String?, line: String?): XElem? {
        val d = editSlide(slide) ?: return null
        val id = nextShapeId(d)
        val p = d.prefixOf(P_NS) ?: "p"; val a = d.ensureNs("a", A_NS)
        val isLine = prst == "line"
        val fillXml = if (isLine || fill == null) "<$a:noFill/>" else "<$a:solidFill><$a:srgbClr val=\"$fill\"/></$a:solidFill>"
        val lnXml = "<$a:ln w=\"${if (isLine) 28575 else 12700}\">" + (line?.let { "<$a:solidFill><$a:srgbClr val=\"$it\"/></$a:solidFill>" } ?: "<$a:noFill/>") + "</$a:ln>"
        val tag = if (isLine) "cxnSp" else "sp"
        val nv = if (isLine) "<$p:nvCxnSpPr><$p:cNvPr id=\"$id\" name=\"Line $id\"/><$p:cNvCxnSpPr/><$p:nvPr/></$p:nvCxnSpPr>"
            else "<$p:nvSpPr><$p:cNvPr id=\"$id\" name=\"Shape $id\"/><$p:cNvSpPr/><$p:nvPr/></$p:nvSpPr>"
        val body = if (isLine) "" else "<$p:txBody><$a:bodyPr rtlCol=\"0\" anchor=\"ctr\"/><$a:lstStyle/><$a:p><$a:pPr algn=\"ctr\"/><$a:endParaRPr lang=\"en-US\"/></$a:p></$p:txBody>"
        val e = XDom.elem("<$p:$tag>$nv<$p:spPr><$a:xfrm><$a:off x=\"$x\" y=\"$y\"/><$a:ext cx=\"$w\" cy=\"${if (isLine) 0 else h}\"/></$a:xfrm>" +
            "<$a:prstGeom prst=\"$prst\"><$a:avLst/></$a:prstGeom>$fillXml$lnXml</$p:spPr>$body</$p:$tag>")
        spTree(d)?.add(e)
        return e
    }

    /** Adds a picture centred at (x, y) scaled to fit [maxW] x [maxH]. */
    fun addImage(slide: Int, bytes: ByteArray, maxW: Long, maxH: Long): XElem? {
        val path = slidePaths().getOrNull(slide) ?: return null
        val d = editSlide(slide) ?: return null
        val ext = OPackage.imageExt(bytes)
        val media = pkg.freeName("ppt/media", "image", ext)
        pkg.put(media, bytes); pkg.ensureDefault(ext, OPackage.imageMime(ext))
        val rid = pkg.addRel(path, OPackage.REL_IMAGE, media)
        val (pw, ph) = OPackage.imageSize(bytes) ?: (400 to 300)
        var w = pw.toLong() * 9525; var h = ph.toLong() * 9525
        if (w > maxW) { h = h * maxW / w; w = maxW }
        if (h > maxH) { w = w * maxH / h; h = maxH }
        val (sw, sh) = slideSize
        val x = (sw - w) / 2; val y = (sh - h) / 2
        val id = nextShapeId(d)
        val p = d.prefixOf(P_NS) ?: "p"; val a = d.ensureNs("a", A_NS); val r = d.ensureNs("r", OPackage.R_NS)
        val e = XDom.elem("<$p:pic><$p:nvPicPr><$p:cNvPr id=\"$id\" name=\"Picture $id\"/><$p:cNvPicPr><$a:picLocks noChangeAspect=\"1\"/></$p:cNvPicPr><$p:nvPr/></$p:nvPicPr>" +
            "<$p:blipFill><$a:blip $r:embed=\"$rid\"/><$a:stretch><$a:fillRect/></$a:stretch></$p:blipFill>" +
            "<$p:spPr><$a:xfrm><$a:off x=\"$x\" y=\"$y\"/><$a:ext cx=\"$w\" cy=\"$h\"/></$a:xfrm><$a:prstGeom prst=\"rect\"><$a:avLst/></$a:prstGeom></$p:spPr></$p:pic>")
        spTree(d)?.add(e)
        return e
    }

    /** The `xfrm` holding position/size of a shape element (spPr/xfrm, or p:xfrm of a graphic frame); created when missing. */
    fun xfrm(shape: XElem): XElem {
        if (shape.local == "graphicFrame") return shape.child("xfrm") ?: XElem(shape.prefix.let { if (it.isEmpty()) "xfrm" else "$it:xfrm" }).also { shape.add(1, it) }
        val sp = shape.child("spPr") ?: shape.child("grpSpPr") ?: XElem(shape.prefix.let { if (it.isEmpty()) "spPr" else "$it:spPr" }).also { shape.add(1, it) }
        return sp.child("xfrm") ?: XElem("a:xfrm").also { sp.add(0, it) }
    }

    fun setBounds(shape: XElem, x: Long, y: Long, w: Long, h: Long) {
        val xf = xfrm(shape)
        val a = xf.prefix.let { if (it.isEmpty()) "" else "$it:" }.let { if (shape.local == "graphicFrame") "a:" else it }
        val off = xf.child("off") ?: XElem(a + "off").also { xf.add(0, it) }
        val ext = xf.child("ext") ?: XElem(a + "ext").also { xf.add(xf.indexOf(off) + 1, it) }
        off.set("x", x.toString()).set("y", y.toString()); ext.set("cx", w.coerceAtLeast(0).toString()).set("cy", h.coerceAtLeast(0).toString())
    }

    fun deleteShape(slide: Int, shape: XElem): Boolean {
        val d = editSlide(slide) ?: return false
        val tree = spTree(d) ?: return false
        val parent = WordRuns.parentOf(tree, shape) ?: return false
        return parent.remove(shape)
    }

    /** Moves a shape one step forward (+1) or backward (-1) in the drawing order. */
    fun reorderShape(slide: Int, shape: XElem, delta: Int) {
        val d = editSlide(slide) ?: return
        val tree = spTree(d) ?: return
        val shapes = tree.kids.withIndex().filter { (_, n) -> n is XElem && n.local in SHAPES }.map { it.index }
        val i = tree.kids.indexOf(shape); val pos = shapes.indexOf(i); val to = pos + delta
        if (pos < 0 || to !in shapes.indices) return
        tree.kids.removeAt(i); tree.kids.add(shapes[to], shape)
    }

    // ---------------- text ----------------

    fun paragraphs(shape: XElem): List<XElem> = shape.child("txBody")?.children("p") ?: emptyList()

    fun paragraphText(p: XElem): String = buildString { for (k in p.elems()) when (k.local) { "r", "fld" -> append(k.child("t")?.textContent() ?: ""); "br" -> append('\u000B') } }

    fun shapeText(shape: XElem): String = paragraphs(shape).joinToString("\n") { paragraphText(it).replace('\u000B', '\n') }

    /**
     * Replaces the text of a shape, one paragraph per line. Paragraph properties and the first run's
     * properties of each existing paragraph are kept; extra lines copy the last paragraph.
     */
    fun setShapeText(shape: XElem, text: String) {
        val body = shape.child("txBody") ?: XElem(shape.prefix.let { if (it.isEmpty()) "txBody" else "$it:txBody" }).also { tb ->
            tb.add(XElem("a:bodyPr")).add(XElem("a:lstStyle")); shape.add(tb)
        }
        val lines = text.split('\n')
        val paras = body.children("p").toMutableList()
        val a = paras.firstOrNull()?.prefix?.let { if (it.isEmpty()) "" else "$it:" } ?: "a:"
        if (paras.isEmpty()) { val p = XElem(a + "p"); body.add(p); paras.add(p) }
        for ((i, line) in lines.withIndex()) {
            val p = paras.getOrNull(i) ?: paras.last().copy().also { np -> body.add(body.indexOf(paras.last()) + 1 + (i - paras.size), np); paras.add(np) }
            val pPr = p.child("pPr")
            val firstR = p.elems().firstOrNull { it.local == "r" }?.child("rPr")?.copy()
            val end = p.child("endParaRPr")
            val rPr = firstR ?: end?.copy()?.also { it.name = a + "rPr" }
            p.kids.clear()
            pPr?.let { p.add(it) }
            if (line.isNotEmpty()) {
                val r = XElem(a + "r"); rPr?.let { r.add(it) }; r.add(XElem(a + "t").setText(line)); p.add(r)
            }
            (end ?: firstR?.copy()?.also { it.name = a + "endParaRPr" })?.let { p.add(it) }
        }
        for (i in paras.size - 1 downTo lines.size) body.remove(paras[i])
    }

    /** Applies [mutate] to every run property (and paragraph end property) of a shape. */
    fun formatShapeText(shape: XElem, mutate: (XElem) -> Unit) {
        for (p in paragraphs(shape)) {
            val a = p.prefix.let { if (it.isEmpty()) "" else "$it:" }
            for (r in p.children("r") + p.children("fld")) mutate(r.child("rPr") ?: XElem(a + "rPr").also { r.add(0, it) })
            p.child("endParaRPr")?.let(mutate)
        }
    }

    fun setParagraphAlign(shape: XElem, algn: String, rtl: Boolean?) {
        for (p in paragraphs(shape)) {
            val a = p.prefix.let { if (it.isEmpty()) "" else "$it:" }
            val pPr = p.child("pPr") ?: XElem(a + "pPr").also { p.add(0, it) }
            pPr.set("algn", algn); rtl?.let { if (it) pPr.set("rtl", "1") else pPr.removeAttr("rtl") }
        }
    }

    // ---------------- fill / line ----------------

    private val FILL_KIDS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

    fun setFill(shape: XElem, hex: String?) {
        val sp = shape.child("spPr") ?: return
        val a = "a:"
        sp.kids.removeAll { it is XElem && it.local in FILL_KIDS }
        val f = if (hex == null) XElem(a + "noFill") else XElem(a + "solidFill").add(XElem(a + "srgbClr").set("val", hex))
        // fill follows xfrm/geometry in CT_ShapeProperties
        val at = sp.kids.indexOfLast { it is XElem && (it.local == "xfrm" || it.local == "prstGeom" || it.local == "custGeom") } + 1
        sp.add(at, f)
    }

    fun setLine(shape: XElem, hex: String?, widthEmu: Long = 12700) {
        val sp = shape.child("spPr") ?: return
        sp.removeChildren("ln")
        val ln = XElem("a:ln").set("w", widthEmu.toString())
        ln.add(if (hex == null) XElem("a:noFill") else XElem("a:solidFill").add(XElem("a:srgbClr").set("val", hex)))
        val at = sp.kids.indexOfLast { it is XElem && (it.local in FILL_KIDS || it.local == "xfrm" || it.local == "prstGeom" || it.local == "custGeom") } + 1
        sp.add(at, ln)
    }

    // ---------------- transitions ----------------

    /** Sets a transition on slide [i] ("fade", "push", "wipe", null = none). */
    fun setTransition(i: Int, kind: String?) {
        val d = editSlide(i) ?: return
        val p = d.prefixOf(P_NS) ?: "p"
        d.root.removeChildren("transition")
        d.root.kids.removeAll { it is XElem && it.local == "AlternateContent" && it.find("transition") != null }
        if (kind == null) return
        val e = XElem("$p:transition").set("spd", "med").add(XElem("$p:$kind"))
        val after = d.root.kids.indexOfLast { it is XElem && (it.local == "cSld" || it.local == "clrMapOvr") }
        d.root.add(after + 1, e)
    }

    fun transition(i: Int): String? = slide(i)?.root?.let { r -> (r.child("transition") ?: r.find("transition"))?.elems()?.firstOrNull()?.local }

    companion object {
        const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
        const val REL_LAYOUT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
        const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        val SHAPES = setOf("sp", "pic", "graphicFrame", "grpSp", "cxnSp", "contentPart", "AlternateContent")
        private val PRES_ORDER = listOf("sldMasterIdLst", "notesMasterIdLst", "handoutMasterIdLst", "sldIdLst", "sldSz", "notesSz")

        fun open(file: File): PptDoc = PptDoc(OPackage.open(file))
        fun of(pkg: OPackage): PptDoc = PptDoc(pkg)
    }
}
