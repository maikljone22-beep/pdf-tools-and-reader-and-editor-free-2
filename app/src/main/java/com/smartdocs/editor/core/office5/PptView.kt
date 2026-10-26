package com.smartdocs.editor.core.office5

/*
 * Renderer-independent slide model built from the slide/layout/master DOMs: positions (with placeholder
 * inheritance and group transforms), preset geometry, solid fills/lines with theme colours (lumMod/lumOff,
 * tint/shade), text with run formatting, pictures, basic tables and the slide background.
 */

class PRun(val text: String, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val sizePt: Float?, val color: String?)
class PPara(val runs: List<PRun>, val align: String?, val rtl: Boolean, val level: Int, val bullet: String?) {
    val text: String get() = runs.joinToString("") { it.text }
}

class PShape(
    /** The editable element on the slide, or null for layout/master artwork. */
    val elem: XElem?,
    val kind: Kind,
    val x: Long, val y: Long, val w: Long, val h: Long,
    val rot: Float = 0f, val flipH: Boolean = false, val flipV: Boolean = false,
    val geom: String = "rect",
    val fill: String? = null, val line: String? = null, val lineW: Long = 0,
    val paragraphs: List<PPara> = emptyList(),
    val anchor: String = "t",
    val defaultSize: Float = 18f,
    val defaultColor: String? = null,
    val fontScale: Float = 1f,
    val insets: LongArray = longArrayOf(91440, 45720, 91440, 45720),
    val image: ByteArray? = null,
    val table: List<List<List<PPara>>> = emptyList(),
    val colWidths: List<Long> = emptyList(),
    val placeholder: String? = null,
) {
    enum class Kind { SHAPE, LINE, PICTURE, TABLE }
    val editableText: Boolean get() = elem != null && kind == Kind.SHAPE
}

class PSlide(val width: Long, val height: Long, val shapes: List<PShape>, val background: String?, val backgroundImage: ByteArray?, val transition: String?)

object PptView {
    private class Ph(val type: String?, val idx: String?, val x: Long?, val y: Long?, val w: Long?, val h: Long?, val size: Float?, val anchor: String?)
    private class Tx(val offX: Long, val offY: Long, val sx: Double, val sy: Double, val chX: Long, val chY: Long) {
        fun x(v: Long) = offX + ((v - chX) * sx).toLong(); fun y(v: Long) = offY + ((v - chY) * sy).toLong()
    }

    fun build(doc: PptDoc, index: Int): PSlide {
        val (cx, cy) = doc.slideSize
        val path = doc.slidePaths().getOrNull(index) ?: return PSlide(cx, cy, emptyList(), "FFFFFF", null, null)
        val pkg = doc.pkg
        val slide = pkg.dom(path)?.root ?: return PSlide(cx, cy, emptyList(), "FFFFFF", null, null)
        val layoutPath = doc.layoutOf(path)
        val layout = layoutPath?.let { pkg.dom(it)?.root }
        val masterPath = layoutPath?.let { lp -> pkg.rels(lp).firstOrNull { it.type.endsWith("/slideMaster") }?.target }
        val master = masterPath?.let { pkg.dom(it)?.root }
        val theme = Theme(masterPath?.let { mp -> pkg.rels(mp).firstOrNull { it.type.endsWith("/theme") }?.target }?.let { pkg.dom(it)?.root })
        val phs = (layout?.let { placeholders(it, theme) } ?: emptyList()) + (master?.let { placeholders(it, theme) } ?: emptyList())
        val shapes = ArrayList<PShape>()
        val showMaster = slide.attr("showMasterSp") != "0" && layout?.attr("showMasterSp") != "0"
        if (showMaster && master != null) master.child("cSld")?.child("spTree")?.let { tree(it, pkg, masterPath, theme, emptyList(), null, false, shapes) }
        if (layout != null) layout.child("cSld")?.child("spTree")?.let { tree(it, pkg, layoutPath, theme, emptyList(), null, false, shapes) }
        slide.child("cSld")?.child("spTree")?.let { tree(it, pkg, path, theme, phs, null, true, shapes) }
        var bgImg: ByteArray? = null
        val bg = sequenceOf(slide, layout, master).filterNotNull().firstNotNullOfOrNull { r ->
            r.child("cSld")?.child("bg")?.let { b ->
                val pr = b.child("bgPr")
                pr?.child("blipFill")?.let { bf -> bgImg = imageOf(pkg, if (r === slide) path else if (r === layout) layoutPath!! else masterPath!!, bf) }
                pr?.let { fillColor(it, theme) } ?: b.child("bgRef")?.let { theme.color(it) } ?: "FFFFFF"
            }
        } ?: "FFFFFF"
        return PSlide(cx, cy, shapes, bg, bgImg, doc.transition(index))
    }

    private fun placeholders(root: XElem, theme: Theme): List<Ph> {
        val out = ArrayList<Ph>()
        for (sp in root.findAll("sp")) {
            val ph = sp.child("nvSpPr")?.child("nvPr")?.child("ph") ?: continue
            val xf = sp.child("spPr")?.child("xfrm")
            val off = xf?.child("off"); val ext = xf?.child("ext")
            val sz = sp.child("txBody")?.find("defRPr")?.attr("sz")?.toFloatOrNull()?.div(100f)
            out.add(Ph(ph.attr("type"), ph.attr("idx"), off?.attr("x")?.toLongOrNull(), off?.attr("y")?.toLongOrNull(), ext?.attr("cx")?.toLongOrNull(), ext?.attr("cy")?.toLongOrNull(), sz,
                sp.child("txBody")?.child("bodyPr")?.attr("anchor")))
        }
        return out
    }

    private fun match(list: List<Ph>, type: String?, idx: String?): Ph? {
        val t = type ?: "body"
        return list.firstOrNull { idx != null && it.idx == idx && it.x != null }
            ?: list.firstOrNull { it.x != null && ((it.type ?: "body") == t || (t == "ctrTitle" && it.type == "title") || (t == "subTitle" && it.type == "body") || (t == "obj" && it.type == null)) }
    }

    private fun tree(t: XElem, pkg: OPackage, part: String, theme: Theme, phs: List<Ph>, tx: Tx?, editable: Boolean, out: MutableList<PShape>) {
        for (e in t.elems()) {
            when (e.local) {
                "sp", "cxnSp" -> shape(e, pkg, part, theme, phs, tx, editable)?.let { out.add(it) }
                "pic" -> pic(e, pkg, part, theme, tx, editable)?.let { out.add(it) }
                "graphicFrame" -> frame(e, theme, tx, editable)?.let { out.add(it) }
                "grpSp" -> group(e, pkg, part, theme, phs, tx, editable, out)
                "AlternateContent" -> (e.child("Choice") ?: e.child("Fallback"))?.let { tree(it, pkg, part, theme, phs, tx, editable, out) }
            }
        }
    }

    private fun group(g: XElem, pkg: OPackage, part: String, theme: Theme, phs: List<Ph>, tx: Tx?, editable: Boolean, out: MutableList<PShape>) {
        val xf = g.child("grpSpPr")?.child("xfrm")
        var ntx = tx
        if (xf != null) {
            val ox = xf.child("off")?.attr("x")?.toLongOrNull() ?: 0; val oy = xf.child("off")?.attr("y")?.toLongOrNull() ?: 0
            val w = xf.child("ext")?.attr("cx")?.toLongOrNull() ?: 0; val h = xf.child("ext")?.attr("cy")?.toLongOrNull() ?: 0
            val chx = xf.child("chOff")?.attr("x")?.toLongOrNull() ?: ox; val chy = xf.child("chOff")?.attr("y")?.toLongOrNull() ?: oy
            val cw = xf.child("chExt")?.attr("cx")?.toLongOrNull() ?: w; val ch = xf.child("chExt")?.attr("cy")?.toLongOrNull() ?: h
            val mx = tx?.x(ox) ?: ox; val my = tx?.y(oy) ?: oy
            val sx = (if (cw > 0) w.toDouble() / cw else 1.0) * (tx?.sx ?: 1.0); val sy = (if (ch > 0) h.toDouble() / ch else 1.0) * (tx?.sy ?: 1.0)
            ntx = Tx(mx, my, sx, sy, chx, chy)
        }
        val inner = ArrayList<PShape>()
        tree(g, pkg, part, theme, phs, ntx, false, inner)   // group members are shown, not edited one by one
        out.addAll(inner)
    }

    private fun bounds(xf: XElem?, tx: Tx?): LongArray? {
        val off = xf?.child("off") ?: return null; val ext = xf.child("ext") ?: return null
        var x = off.attr("x")?.toLongOrNull() ?: 0; var y = off.attr("y")?.toLongOrNull() ?: 0
        var w = ext.attr("cx")?.toLongOrNull() ?: 0; var h = ext.attr("cy")?.toLongOrNull() ?: 0
        if (tx != null) { val nx = tx.x(x); val ny = tx.y(y); w = (w * tx.sx).toLong(); h = (h * tx.sy).toLong(); x = nx; y = ny }
        return longArrayOf(x, y, w, h)
    }

    private fun shape(e: XElem, pkg: OPackage, part: String, theme: Theme, phs: List<Ph>, tx: Tx?, editable: Boolean): PShape? {
        val nv = e.child("nvSpPr") ?: e.child("nvCxnSpPr")
        val ph = nv?.child("nvPr")?.child("ph")
        // layout/master placeholders are prompts, not content
        if (!editable && ph != null) return null
        val spPr = e.child("spPr")
        val xf = spPr?.child("xfrm")
        val inherited = if (ph != null) match(phs, ph.attr("type"), ph.attr("idx")) else null
        val b = bounds(xf, tx) ?: inherited?.let { longArrayOf(it.x ?: 0, it.y ?: 0, it.w ?: 0, it.h ?: 0) } ?: return null
        val geom = spPr?.child("prstGeom")?.attr("prst") ?: if (spPr?.child("custGeom") != null) "rect" else "rect"
        val style = e.child("style")
        val fill = spPr?.let { fillColor(it, theme) } ?: if (spPr?.child("noFill") == null) style?.child("fillRef")?.takeIf { (it.attr("idx") ?: "0") != "0" }?.let { theme.color(it) } else null
        val ln = spPr?.child("ln")
        val line = ln?.let { if (it.child("noFill") != null) null else fillColor(it, theme) } ?: if (ln?.child("noFill") == null) style?.child("lnRef")?.takeIf { (it.attr("idx") ?: "0") != "0" }?.let { theme.color(it) } else null
        val lineW = ln?.attr("w")?.toLongOrNull() ?: if (line != null) 12700 else 0
        val body = e.child("txBody")
        val bodyPr = body?.child("bodyPr")
        val phType = ph?.attr("type")
        val defSize = inherited?.size ?: when (phType) { "title", "ctrTitle" -> 40f; "subTitle" -> 24f; null -> if (ph != null) 24f else 18f; "body", "obj" -> 24f; else -> 14f }
        val anchor = bodyPr?.attr("anchor") ?: inherited?.anchor ?: if (phType == "title" || phType == "ctrTitle") "ctr" else "t"
        val scale = bodyPr?.child("normAutofit")?.attr("fontScale")?.toFloatOrNull()?.div(100000f) ?: 1f
        val ins = longArrayOf(bodyPr?.attr("lIns")?.toLongOrNull() ?: 91440, bodyPr?.attr("tIns")?.toLongOrNull() ?: 45720, bodyPr?.attr("rIns")?.toLongOrNull() ?: 91440, bodyPr?.attr("bIns")?.toLongOrNull() ?: 45720)
        val styleFont = style?.child("fontRef")?.let { theme.color(it) }
        var paras = body?.children("p")?.map { para(it, theme) } ?: emptyList()
        if (phType == "ctrTitle" || phType == "subTitle") paras = paras.map { if (it.align == null) PPara(it.runs, "ctr", it.rtl, it.level, it.bullet) else it }
        val rot = (xf?.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
        val isLine = e.local == "cxnSp" || geom == "line" || geom.startsWith("straightConnector") || geom.startsWith("bentConnector")
        return PShape(if (editable) e else null, if (isLine) PShape.Kind.LINE else PShape.Kind.SHAPE, b[0], b[1], b[2], b[3], rot,
            xf?.attr("flipH") == "1", xf?.attr("flipV") == "1", geom, fill, line ?: if (isLine) "000000" else null, if (isLine && lineW == 0L) 12700 else lineW,
            paras, anchor, defSize, styleFont ?: theme.text, scale, ins, placeholder = phType ?: if (ph != null) "body" else null)
    }

    private fun para(p: XElem, theme: Theme): PPara {
        val pPr = p.child("pPr")
        val runs = ArrayList<PRun>()
        for (k in p.elems()) when (k.local) {
            "r", "fld" -> {
                val rp = k.child("rPr")
                runs.add(PRun(k.child("t")?.textContent() ?: "", rp?.attr("b") == "1", rp?.attr("i") == "1", rp?.attr("u").let { it != null && it != "none" },
                    rp?.attr("strike").let { it != null && it != "noStrike" }, rp?.attr("sz")?.toFloatOrNull()?.div(100f), rp?.let { fillColor(it, theme) }))
            }
            "br" -> runs.add(PRun("\n", false, false, false, false, null, null))
        }
        val bullet = pPr?.child("buChar")?.attr("char")?.let { if (it.firstOrNull()?.code in 0xF000..0xF0FF) "\u2022" else it } ?: if (pPr?.child("buAutoNum") != null) "\u2022" else null
        return PPara(runs, pPr?.attr("algn"), pPr?.attr("rtl") == "1", pPr?.attr("lvl")?.toIntOrNull() ?: 0, bullet)
    }

    private fun imageOf(pkg: OPackage, part: String, blipFill: XElem): ByteArray? {
        val blip = blipFill.child("blip") ?: return null
        val id = blip.attr("r:embed") ?: blip.attr("embed") ?: return null
        val rel = pkg.rel(part, id) ?: return null
        return if (rel.external) null else pkg.bytes(rel.target)
    }

    private fun pic(e: XElem, pkg: OPackage, part: String, theme: Theme, tx: Tx?, editable: Boolean): PShape? {
        val xf = e.child("spPr")?.child("xfrm")
        val b = bounds(xf, tx) ?: return null
        val img = e.child("blipFill")?.let { imageOf(pkg, part, it) }
        val ln = e.child("spPr")?.child("ln")
        return PShape(if (editable) e else null, PShape.Kind.PICTURE, b[0], b[1], b[2], b[3], (xf?.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f,
            image = img, line = ln?.let { fillColor(it, theme) }, lineW = ln?.attr("w")?.toLongOrNull() ?: 0)
    }

    private fun frame(e: XElem, theme: Theme, tx: Tx?, editable: Boolean): PShape? {
        val b = bounds(e.child("xfrm"), tx) ?: return null
        val tbl = e.find("tbl") ?: return null
        val cols = tbl.child("tblGrid")?.children("gridCol")?.map { it.attr("w")?.toLongOrNull() ?: 0L } ?: emptyList()
        val rows = tbl.children("tr").map { tr -> tr.children("tc").map { tc -> tc.child("txBody")?.children("p")?.map { para(it, theme) } ?: emptyList() } }
        return PShape(if (editable) e else null, PShape.Kind.TABLE, b[0], b[1], b[2], b[3], table = rows, colWidths = cols, line = "808080", lineW = 9525, defaultColor = theme.text)
    }

    /** Solid colour of a fill-bearing element (spPr, ln, rPr, bgPr): solidFill or the first gradient stop. */
    fun fillColor(e: XElem, theme: Theme): String? {
        e.child("solidFill")?.let { return theme.color(it) }
        e.child("gradFill")?.child("gsLst")?.children("gs")?.firstOrNull()?.let { return theme.color(it) }
        return null
    }

    /** Theme colour scheme with DrawingML colour transforms. */
    class Theme(root: XElem?) {
        private val scheme = HashMap<String, String>()
        init {
            root?.find("clrScheme")?.elems()?.forEach { c ->
                val v = c.child("srgbClr")?.attr("val") ?: c.child("sysClr")?.let { it.attr("lastClr") ?: if (it.attr("val") == "window") "FFFFFF" else "000000" }
                if (v != null) scheme[c.local] = v.uppercase()
            }
        }
        val text: String get() = scheme["dk1"] ?: "000000"

        /** Colour of the first colour child of [holder] (srgbClr, schemeClr, sysClr, prstClr) with lumMod/lumOff/tint/shade/alpha ignored. */
        fun color(holder: XElem): String? {
            val c = holder.elems().firstOrNull { it.local in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr") } ?: return null
            var hex = when (c.local) {
                "srgbClr" -> c.attr("val")?.uppercase()
                "sysClr" -> c.attr("lastClr")?.uppercase() ?: if (c.attr("val") == "window") "FFFFFF" else "000000"
                "schemeClr" -> when (val v = c.attr("val")) {
                    "bg1" -> scheme["lt1"] ?: "FFFFFF"; "tx1" -> scheme["dk1"] ?: "000000"; "bg2" -> scheme["lt2"] ?: "E7E6E6"; "tx2" -> scheme["dk2"] ?: "44546A"
                    "phClr" -> scheme["accent1"]
                    else -> scheme[v] ?: DEFAULTS[v]
                }
                "prstClr" -> PRESET[c.attr("val")]
                else -> null
            } ?: return null
            for (m in c.elems()) {
                val v = m.attr("val")?.toDoubleOrNull()?.div(100000.0) ?: continue
                hex = when (m.local) {
                    "lumMod" -> hsl(hex) { h, s, l -> Triple(h, s, l * v) }
                    "lumOff" -> hsl(hex) { h, s, l -> Triple(h, s, (l + v).coerceIn(0.0, 1.0)) }
                    "tint" -> mix(hex, "FFFFFF", 1 - v)
                    "shade" -> mix(hex, "000000", 1 - v)
                    else -> hex
                }
            }
            return hex
        }

        private fun rgb(h: String) = intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        private fun hex(r: Int, g: Int, b: Int) = String.format("%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        private fun mix(a: String, b: String, t: Double): String { val x = rgb(a); val y = rgb(b); return hex((x[0] + (y[0] - x[0]) * t).toInt(), (x[1] + (y[1] - x[1]) * t).toInt(), (x[2] + (y[2] - x[2]) * t).toInt()) }

        private inline fun hsl(h: String, f: (Double, Double, Double) -> Triple<Double, Double, Double>): String {
            val c = rgb(h).map { it / 255.0 }
            val mx = c.max(); val mn = c.min(); val l = (mx + mn) / 2
            val d = mx - mn
            val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
            val hue = when { d == 0.0 -> 0.0; mx == c[0] -> 60 * (((c[1] - c[2]) / d).mod(6.0)); mx == c[1] -> 60 * ((c[2] - c[0]) / d + 2); else -> 60 * ((c[0] - c[1]) / d + 4) }
            val (nh, ns, nl) = f(hue, s, l)
            val cc = (1 - kotlin.math.abs(2 * nl - 1)) * ns; val x = cc * (1 - kotlin.math.abs((nh / 60).mod(2.0) - 1)); val m = nl - cc / 2
            val (r, g, b) = when { nh < 60 -> Triple(cc, x, 0.0); nh < 120 -> Triple(x, cc, 0.0); nh < 180 -> Triple(0.0, cc, x); nh < 240 -> Triple(0.0, x, cc); nh < 300 -> Triple(x, 0.0, cc); else -> Triple(cc, 0.0, x) }
            return hex(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
        }

        companion object {
            private val DEFAULTS = mapOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6", "accent1" to "4472C4", "accent2" to "ED7D31",
                "accent3" to "A5A5A5", "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72")
            private val PRESET = mapOf("black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000", "blue" to "0000FF", "yellow" to "FFFF00", "gray" to "808080", "orange" to "FFA500")
        }
    }
}
