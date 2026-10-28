package com.smartdocs.editor.core.openall

import org.w3c.dom.Element
import org.w3c.dom.Node

/** 2-D affine matrix (a, b, c, d, e, f) as XPS writes it: x' = a·x + c·y + e, y' = b·x + d·y + f. */
class XMatrix(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    fun then(o: XMatrix) = XMatrix(   // this, then o
        a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d, e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f)
    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f
    val isIdentity get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f
    companion object { val ID = XMatrix() }
}

/** One path command in absolute coordinates: M/L (x,y), C (x1,y1,x2,y2,x,y), Q (x1,y1,x,y), Z. */
class XCmd(val op: Char, val p: FloatArray)
class XGeom(val cmds: List<XCmd>, val evenOdd: Boolean)

sealed class XBrush {
    class Solid(val argb: Long) : XBrush()
    /** [viewbox] in image units (x, y, w, h) drawn into [viewport] (page units). */
    class Image(val src: String, val viewbox: FloatArray, val viewport: FloatArray, val transform: XMatrix?) : XBrush()
    /**
     * fix/files-index: linear ([p] = x1, y1, x2, y2) or radial ([p] = centre x, y, origin x, y, radius x, y) gradient in
     * absolute page units; [stops] (offset 0..1, ARGB) sorted; [spread] 0 pad, 1 reflect, 2 repeat.
     */
    class Gradient(val radial: Boolean, val p: FloatArray, val stops: List<Pair<Float, Long>>, val spread: Int, val transform: XMatrix?, val opacity: Float) : XBrush()
    /** fix/files-index: a visual (canvas / path / glyphs) drawn from [viewbox] into [viewport], tiled unless [tile] is "None". */
    class Visual(val visual: XEl, val viewbox: FloatArray, val viewport: FloatArray, val tile: String, val transform: XMatrix?, val opacity: Float) : XBrush()
}

sealed class XEl(val transform: XMatrix?, val opacity: Float, val clip: XGeom?)
class XCanvas(val children: List<XEl>, transform: XMatrix?, opacity: Float, clip: XGeom?) : XEl(transform, opacity, clip)
class XPathEl(val geom: XGeom, val fill: XBrush?, val stroke: XBrush?, val strokeWidth: Float, transform: XMatrix?, opacity: Float, clip: XGeom?) : XEl(transform, opacity, clip)
class XGlyphs(
    val x: Float, val y: Float, val size: Float, val text: String, val font: String?, val fill: XBrush?,
    val rtl: Boolean, val advances: FloatArray?, val bold: Boolean, val italic: Boolean, transform: XMatrix?, opacity: Float, clip: XGeom?,
) : XEl(transform, opacity, clip)

class XPage(val width: Float, val height: Float, val root: XCanvas)

/**
 * XPS / OpenXPS reader (open-all): document structure (FixedDocumentSequence → FixedDocument → FixedPage) and the
 * FixedPage markup (Canvas, Path, Glyphs, solid and image brushes, render transforms, abbreviated geometry and
 * PathGeometry figures, page resources). Hardened DOM ([SafeDom]). Pure; drawing is done by the Android viewer.
 */
object XpsReader {

    /** Page part paths in reading order. [entry] returns a ZIP entry, [names] lists all entries. */
    fun pages(entry: (String) -> ByteArray?, names: Collection<String>): List<String> {
        fun norm(base: String, target: String): String = EpubParser.resolve(base, target.trim()).first
        fun lookup(p: String): ByteArray? = entry(p) ?: names.firstOrNull { it.equals(p, true) }?.let(entry)
        val rels = lookup("_rels/.rels")?.let { String(it, Charsets.UTF_8) }
        var seq = rels?.let { r ->
            Regex("""(?is)<Relationship\b[^>]*>""").findAll(r).map { it.value }
                .firstOrNull { it.contains("fixedrepresentation", true) }
                ?.let { Regex("""Target\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }?.let { norm("", it) }
        } ?: names.firstOrNull { it.endsWith(".fdseq", true) }
        val out = ArrayList<String>()
        val seqXml = seq?.let { lookup(it) }?.let { String(it, Charsets.UTF_8) }
        val docs = if (seqXml != null) Regex("""(?is)<DocumentReference\b[^>]*Source\s*=\s*"([^"]+)"""").findAll(seqXml).map { norm(seq!!, it.groupValues[1]) }.toList()
            else names.filter { it.endsWith(".fdoc", true) }.sortedWith(NaturalOrder)
        for (d in docs) {
            val x = lookup(d)?.let { String(it, Charsets.UTF_8) } ?: continue
            Regex("""(?is)<PageContent\b[^>]*Source\s*=\s*"([^"]+)"""").findAll(x).forEach { out += norm(d, it.groupValues[1]) }
        }
        if (out.isEmpty()) out += names.filter { it.endsWith(".fpage", true) }.sortedWith(NaturalOrder)
        return out
    }

    // ---- values

    fun color(s0: String?): Long? {
        val s = s0?.trim() ?: return null
        if (s.startsWith("sc#", true)) {
            val v = s.substring(3).split(',').mapNotNull { it.trim().toFloatOrNull() }
            val (a, r, g, b) = when (v.size) { 3 -> listOf(1f, v[0], v[1], v[2]); 4 -> v; else -> return null }
            fun c(x: Float) = (Math.pow(x.coerceIn(0f, 1f).toDouble(), 1 / 2.2) * 255).toLong()
            return ((a.coerceIn(0f, 1f) * 255).toLong() shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        if (!s.startsWith("#")) return null
        val h = s.substring(1)
        return when (h.length) {
            6 -> h.toLongOrNull(16)?.let { 0xFF000000L or it }
            8 -> h.toLongOrNull(16)
            else -> null
        }
    }

    fun matrix(s: String?): XMatrix? {
        val v = s?.split(',', ' ')?.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toFloatOrNull() } ?: return null
        return if (v.size == 6) XMatrix(v[0], v[1], v[2], v[3], v[4], v[5]) else null
    }

    private fun nums(s: String): FloatArray = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(s).map { it.value.toFloat() }.toList().toFloatArray()

    /** XPS abbreviated geometry syntax ("F1 M 0,0 L 10,0 10,10 Z"). */
    fun geometry(src: String): XGeom {
        val cmds = ArrayList<XCmd>()
        var evenOdd = true
        val tokens = Regex("""[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(src).map { it.value }.toList()
        var i = 0
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var lastCtrlX = 0f; var lastCtrlY = 0f; var lastWasCubic = false
        var cmd = 'M'
        fun num(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
        fun hasNum() = i < tokens.size && tokens[i][0].let { !it.isLetter() }
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) {
                if (t == "F" || t == "f") { i++; evenOdd = num() == 0f; continue }
                cmd = t[0]; i++
                if (cmd == 'Z' || cmd == 'z') { cmds += XCmd('Z', FloatArray(0)); cx = sx; cy = sy; lastWasCubic = false; continue }
            } else if (!hasNum()) { i++; continue }
            val rel = cmd.isLowerCase()
            fun ax() = num() + if (rel) cx else 0f
            fun ay() = num() + if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { val x = ax(); val y = ay(); cmds += XCmd('M', floatArrayOf(x, y)); cx = x; cy = y; sx = x; sy = y; cmd = if (rel) 'l' else 'L'; lastWasCubic = false }
                'L' -> { val x = ax(); val y = ay(); cmds += XCmd('L', floatArrayOf(x, y)); cx = x; cy = y; lastWasCubic = false }
                'H' -> { val x = num() + if (rel) cx else 0f; cmds += XCmd('L', floatArrayOf(x, cy)); cx = x; lastWasCubic = false }
                'V' -> { val y = num() + if (rel) cy else 0f; cmds += XCmd('L', floatArrayOf(cx, y)); cy = y; lastWasCubic = false }
                'C' -> { val x1 = ax(); val y1 = ay(); val x2 = ax(); val y2 = ay(); val x = ax(); val y = ay()
                    cmds += XCmd('C', floatArrayOf(x1, y1, x2, y2, x, y)); lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y; lastWasCubic = true }
                'S' -> { val x1 = if (lastWasCubic) 2 * cx - lastCtrlX else cx; val y1 = if (lastWasCubic) 2 * cy - lastCtrlY else cy
                    val x2 = ax(); val y2 = ay(); val x = ax(); val y = ay()
                    cmds += XCmd('C', floatArrayOf(x1, y1, x2, y2, x, y)); lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y; lastWasCubic = true }
                'Q' -> { val x1 = ax(); val y1 = ay(); val x = ax(); val y = ay(); cmds += XCmd('Q', floatArrayOf(x1, y1, x, y)); cx = x; cy = y; lastWasCubic = false }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f; val x = ax(); val y = ay()
                    arc(cx, cy, rx, ry, rot, large, sweep, x, y).forEach { cmds += XCmd('C', it) }   // fix/files-index: real arcs
                    cx = x; cy = y; lastWasCubic = false
                }
                else -> i++
            }
        }
        return XGeom(cmds, evenOdd)
    }

    /**
     * fix/files-index: elliptical arc from (x0, y0) to (x, y) as cubic Béziers (SVG endpoint parameterisation, F.6.5).
     * [sweep] true = clockwise on the y-down page (XPS SweepDirection="Clockwise"). A zero radius gives a line.
     */
    fun arc(x0: Float, y0: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float): List<FloatArray> {
        if (x0 == x && y0 == y) return emptyList()
        var rx = kotlin.math.abs(rx0.toDouble()); var ry = kotlin.math.abs(ry0.toDouble())
        if (rx < 1e-9 || ry < 1e-9) return listOf(floatArrayOf(x0, y0, x, y, x, y))
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = kotlin.math.cos(phi); val sp = kotlin.math.sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = kotlin.math.sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else kotlin.math.sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x0 + x) / 2.0; val cy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * Math.PI else if (sweep && dt < 0) dt += 2 * Math.PI
        val n = kotlin.math.ceil(kotlin.math.abs(dt) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / n
        val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
        val out = ArrayList<FloatArray>(n)
        fun px(t: Double) = cx + rx * kotlin.math.cos(t) * cp - ry * kotlin.math.sin(t) * sp
        fun py(t: Double) = cy + rx * kotlin.math.cos(t) * sp + ry * kotlin.math.sin(t) * cp
        fun dxdt(t: Double) = -rx * kotlin.math.sin(t) * cp - ry * kotlin.math.cos(t) * sp
        fun dydt(t: Double) = -rx * kotlin.math.sin(t) * sp + ry * kotlin.math.cos(t) * cp
        var t = t1
        for (i in 0 until n) {
            val t2 = t + step
            val ex = if (i == n - 1) x.toDouble() else px(t2); val ey = if (i == n - 1) y.toDouble() else py(t2)
            out += floatArrayOf((px(t) + k * dxdt(t)).toFloat(), (py(t) + k * dydt(t)).toFloat(), (px(t2) - k * dxdt(t2)).toFloat(), (py(t2) - k * dydt(t2)).toFloat(), ex.toFloat(), ey.toFloat())
            t = t2
        }
        return out
    }

    // ---- page markup

    private fun kids(e: Node): List<Element> {
        val out = ArrayList<Element>(); var n = e.firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }
    private val Element.local: String get() = localName ?: tagName.substringAfter(':')
    private fun Element.a(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    private class Ctx(val pagePath: String, val res: MutableMap<String, Element>) { var visualDepth = 0 }

    private fun resourceRef(v: String?): String? = v?.trim()?.let { Regex("""^\{StaticResource\s+([^}]+)}$""").find(it)?.groupValues?.get(1)?.trim() }

    private fun collectResources(e: Element, c: Ctx) {
        for (k in kids(e)) if (k.local.endsWith(".Resources")) {
            for (rd in kids(k)) {
                val holders = if (rd.local == "ResourceDictionary") kids(rd) else listOf(rd)
                for (r in holders) (r.getAttribute("x:Key").ifEmpty { r.getAttributeNS("http://schemas.microsoft.com/xps/2005/06/resourcedictionary-key", "Key") }.ifEmpty { r.getAttribute("Key") })
                    .takeIf { it.isNotEmpty() }?.let { c.res[it] = r }
            }
        }
    }

    private fun propElement(e: Element, prop: String): Element? = kids(e).firstOrNull { it.local == "${e.local}.$prop" }?.let { kids(it).firstOrNull() }

    private fun brushOf(e: Element?, c: Ctx): XBrush? {
        e ?: return null
        return when (e.local) {
            "SolidColorBrush" -> color(e.a("Color"))?.let { col ->
                val op = e.a("Opacity")?.toFloatOrNull() ?: 1f
                XBrush.Solid(if (op >= 1f) col else ((((col ushr 24) and 0xFF) * op).toLong() shl 24) or (col and 0xFFFFFF))
            }
            "ImageBrush" -> {
                val src = e.a("ImageSource") ?: return null
                val vb = nums(e.a("Viewbox") ?: "0,0,0,0"); val vp = nums(e.a("Viewport") ?: "0,0,0,0")
                if (vb.size < 4 || vp.size < 4) return null
                XBrush.Image(EpubParser.resolve(c.pagePath, src).first, vb, vp, matrix(e.a("Transform")) ?: propElement(e, "Transform")?.let { matrix(it.a("Matrix")) })
            }
            "LinearGradientBrush", "RadialGradientBrush" -> {
                // fix/files-index: real gradients (all stops, spread method, brush transform)
                val holder = kids(e).firstOrNull { it.local == "${e.local}.GradientStops" }
                val stops = (holder?.let { kids(it) }.orEmpty()).filter { it.local == "GradientStop" }
                    .mapNotNull { s -> color(s.a("Color"))?.let { (s.a("Offset")?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) to it } }.sortedBy { it.first }
                if (stops.isEmpty()) return null
                val op = e.a("Opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                if (stops.size == 1) return XBrush.Solid(stops[0].second).takeIf { op >= 1f } ?: XBrush.Gradient(false, floatArrayOf(0f, 0f, 1f, 0f), stops + (1f to stops[0].second), 0, null, op)
                val spread = when (e.a("SpreadMethod")) { "Reflect" -> 1; "Repeat" -> 2; else -> 0 }
                val t = matrix(e.a("Transform")) ?: propElement(e, "Transform")?.let { matrix(it.a("Matrix")) }
                if (e.local == "LinearGradientBrush") {
                    val a = nums(e.a("StartPoint") ?: "0,0"); val b = nums(e.a("EndPoint") ?: "0,0")
                    XBrush.Gradient(false, floatArrayOf(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f }, b.getOrElse(0) { 0f }, b.getOrElse(1) { 0f }), stops, spread, t, op)
                } else {
                    val c0 = nums(e.a("Center") ?: "0,0"); val o = nums(e.a("GradientOrigin") ?: (e.a("Center") ?: "0,0"))
                    XBrush.Gradient(true, floatArrayOf(c0.getOrElse(0) { 0f }, c0.getOrElse(1) { 0f }, o.getOrElse(0) { 0f }, o.getOrElse(1) { 0f },
                        e.a("RadiusX")?.toFloatOrNull() ?: 0f, e.a("RadiusY")?.toFloatOrNull() ?: 0f), stops, spread, t, op)
                }
            }
            "VisualBrush" -> {
                // fix/files-index: the visual is a Canvas / Path / Glyphs (inline or a resource)
                if (c.visualDepth > 4) return null
                val vb = nums(e.a("Viewbox") ?: "0,0,0,0"); val vp = nums(e.a("Viewport") ?: "0,0,0,0")
                if (vb.size < 4 || vp.size < 4 || vb[2] <= 0f || vb[3] <= 0f) return null
                val src = e.a("Visual")?.let { resourceRef(it) }?.let { c.res[it] } ?: propElement(e, "Visual") ?: return null
                c.visualDepth++
                val v = try { element(src, c, 0) } finally { c.visualDepth-- }
                v ?: return null
                XBrush.Visual(v, vb, vp, e.a("TileMode") ?: "None", matrix(e.a("Transform")) ?: propElement(e, "Transform")?.let { matrix(it.a("Matrix")) },
                    e.a("Opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)
            }
            else -> null
        }
    }

    private fun brushAttr(e: Element, name: String, c: Ctx): XBrush? {
        val v = e.a(name)
        if (v != null) {
            resourceRef(v)?.let { return brushOf(c.res[it], c) }
            return color(v)?.let { XBrush.Solid(it) }
        }
        return brushOf(propElement(e, name), c)
    }

    private fun geomOf(e: Element?, c: Ctx): XGeom? {
        e ?: return null
        return when (e.local) {
            "PathGeometry" -> {
                val evenOdd = e.a("FillRule") != "NonZero"
                val cmds = ArrayList<XCmd>()
                e.a("Figures")?.let { cmds += geometry(it).cmds }
                for (fig in kids(e).filter { it.local == "PathFigure" }) {
                    val start = nums(fig.a("StartPoint") ?: "0,0")
                    cmds += XCmd('M', floatArrayOf(start.getOrElse(0) { 0f }, start.getOrElse(1) { 0f }))
                    for (seg in kids(fig)) {
                        val last = cmds.last().p.let { p -> if (p.size >= 2) p[p.size - 2] to p[p.size - 1] else start.getOrElse(0) { 0f } to start.getOrElse(1) { 0f } }
                        when (seg.local) {
                            "PolyLineSegment" -> nums(seg.a("Points") ?: "").let { p -> var k = 0; while (k + 1 < p.size) { cmds += XCmd('L', floatArrayOf(p[k], p[k + 1])); k += 2 } }
                            "LineSegment" -> nums(seg.a("Point") ?: "").takeIf { it.size >= 2 }?.let { cmds += XCmd('L', floatArrayOf(it[0], it[1])) }
                            "PolyBezierSegment" -> nums(seg.a("Points") ?: "").let { p -> var k = 0; while (k + 5 < p.size) { cmds += XCmd('C', p.copyOfRange(k, k + 6)); k += 6 } }
                            "BezierSegment" -> { val p = nums((seg.a("Point1") ?: "") + " " + (seg.a("Point2") ?: "") + " " + (seg.a("Point3") ?: "")); if (p.size >= 6) cmds += XCmd('C', p.copyOf(6)) }
                            "PolyQuadraticBezierSegment" -> nums(seg.a("Points") ?: "").let { p -> var k = 0; while (k + 3 < p.size) { cmds += XCmd('Q', p.copyOfRange(k, k + 4)); k += 4 } }
                            "QuadraticBezierSegment" -> { val p = nums((seg.a("Point1") ?: "") + " " + (seg.a("Point2") ?: "")); if (p.size >= 4) cmds += XCmd('Q', p.copyOf(4)) }
                            "ArcSegment" -> nums(seg.a("Point") ?: "").takeIf { it.size >= 2 }?.let { pt ->
                                // fix/files-index: the real elliptical arc instead of a straight line
                                val size = nums(seg.a("Size") ?: "0,0")
                                arc(last.first, last.second, size.getOrElse(0) { 0f }, size.getOrElse(1) { 0f }, seg.a("RotationAngle")?.toFloatOrNull() ?: 0f,
                                    seg.a("IsLargeArc") == "true", seg.a("SweepDirection") == "Clockwise", pt[0], pt[1]).forEach { cmds += XCmd('C', it) }
                            }
                        }
                    }
                    if (fig.a("IsClosed") == "true") cmds += XCmd('Z', FloatArray(0))
                }
                val t = matrix(e.a("Transform"))
                XGeom(if (t == null || t.isIdentity) cmds else cmds.map { cmd -> XCmd(cmd.op, FloatArray(cmd.p.size) { k -> if (k % 2 == 0) t.mapX(cmd.p[k], cmd.p[k + 1]) else t.mapY(cmd.p[k - 1], cmd.p[k]) }) }, evenOdd)
            }
            else -> null
        }
    }

    private fun geomAttr(e: Element, name: String, c: Ctx): XGeom? {
        val v = e.a(name)
        if (v != null) {
            resourceRef(v)?.let { return geomOf(c.res[it], c) }
            return geometry(v)
        }
        return geomOf(propElement(e, name), c)
    }

    private fun transformOf(e: Element): XMatrix? = matrix(e.a("RenderTransform")) ?: propElement(e, "RenderTransform")?.let { matrix(it.a("Matrix")) }

    /** Glyph advances from Indices ("gi,adv;…", advance in 1/100 em). */
    fun advances(indices: String?, size: Float): FloatArray? {
        indices ?: return null
        val items = indices.split(';')
        if (items.none { it.contains(',') }) return null
        return FloatArray(items.size) { k ->
            val item = items[k].substringAfter(')')
            item.split(',').getOrNull(1)?.trim()?.toFloatOrNull()?.let { it * size / 100f } ?: Float.NaN
        }
    }

    private fun element(e: Element, c: Ctx, depth: Int): XEl? {
        if (depth > 64) return null
        val op = e.a("Opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        return when (e.local) {
            "Canvas" -> {
                collectResources(e, c)
                XCanvas(kids(e).mapNotNull { element(it, c, depth + 1) }, transformOf(e), op, geomAttr(e, "Clip", c))
            }
            "Path" -> {
                val g = geomAttr(e, "Data", c) ?: return null
                XPathEl(g, brushAttr(e, "Fill", c), brushAttr(e, "Stroke", c), e.a("StrokeThickness")?.toFloatOrNull() ?: 1f, transformOf(e), op, geomAttr(e, "Clip", c))
            }
            "Glyphs" -> {
                val text = e.a("UnicodeString")?.let { if (it.startsWith("{}")) it.substring(2) else it }.orEmpty()
                if (text.isEmpty()) return null
                val size = e.a("FontRenderingEmSize")?.toFloatOrNull() ?: 12f
                val sim = e.a("StyleSimulations").orEmpty()
                XGlyphs(
                    e.a("OriginX")?.toFloatOrNull() ?: 0f, e.a("OriginY")?.toFloatOrNull() ?: 0f, size, text,
                    e.a("FontUri")?.let { EpubParser.resolve(c.pagePath, it).first }, brushAttr(e, "Fill", c),
                    (e.a("BidiLevel")?.toIntOrNull() ?: 0) % 2 == 1, advances(e.a("Indices"), size),
                    sim.contains("Bold"), sim.contains("Italic"), transformOf(e), op, geomAttr(e, "Clip", c),
                )
            }
            else -> null
        }
    }

    fun page(xml: ByteArray, pagePath: String): XPage {
        val doc = SafeDom.parse(xml)
        val root = doc.documentElement
        val c = Ctx(pagePath, HashMap())
        collectResources(root, c)
        val w = root.a("Width")?.toFloatOrNull() ?: 816f
        val h = root.a("Height")?.toFloatOrNull() ?: 1056f
        return XPage(w, h, XCanvas(kids(root).mapNotNull { element(it, c, 0) }, null, 1f, null))
    }

    /** Obfuscated fonts (.odttf): the first 32 bytes are XORed with the GUID in the file name (ECMA-388 §9.1.7.3). */
    fun deobfuscate(font: ByteArray, partName: String): ByteArray {
        val hex = partName.substringAfterLast('/').substringBeforeLast('.').filter { it.isLetterOrDigit() }
        if (hex.length != 32 || font.size < 32) return font
        val key = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val out = font.copyOf()
        for (i in 0 until 16) {
            out[i] = (out[i].toInt() xor key[15 - i].toInt()).toByte()
            out[i + 16] = (out[i + 16].toInt() xor key[15 - i].toInt()).toByte()
        }
        return out
    }

    /** Plain text of a page (search / Convert fallbacks). */
    fun text(p: XPage): String = buildString {
        fun walk(e: XEl) { when (e) { is XCanvas -> e.children.forEach(::walk); is XGlyphs -> append(e.text).append('\n'); else -> {} } }
        walk(p.root)
    }
}
