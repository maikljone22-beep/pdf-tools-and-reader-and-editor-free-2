package com.smartdocs.editor.core.openall

import kotlin.math.cos
import kotlin.math.sin

/** Drawable 2-D DXF primitives in drawing units. [color] = ARGB. */
sealed class DxfShape(val color: Long) {
    class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, color: Long) : DxfShape(color)
    class Poly(val pts: DoubleArray, val closed: Boolean, color: Long) : DxfShape(color)
    class Circle(val x: Double, val y: Double, val r: Double, color: Long) : DxfShape(color)
    /** Angles in degrees, counter-clockwise from +X. */
    class Arc(val x: Double, val y: Double, val r: Double, val a1: Double, val a2: Double, color: Long) : DxfShape(color)
    class Text(val x: Double, val y: Double, val h: Double, val rot: Double, val text: String, color: Long) : DxfShape(color)
}

class DxfDrawing(val shapes: List<DxfShape>, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * ASCII DXF reader (open-all): LINE, CIRCLE, ARC, ELLIPSE, LWPOLYLINE, POLYLINE/VERTEX, SPLINE (control polygon),
 * POINT, TEXT, MTEXT, SOLID/3DFACE outlines and INSERT of BLOCKS (scaled / rotated, nested up to 8 levels). Pure.
 */
object DxfReader {

    private class Ent(val type: String, val codes: List<Pair<Int, String>>) {
        fun d(code: Int, def: Double = 0.0) = codes.firstOrNull { it.first == code }?.second?.trim()?.toDoubleOrNull() ?: def
        fun s(code: Int) = codes.firstOrNull { it.first == code }?.second
        fun i(code: Int, def: Int = 0) = codes.firstOrNull { it.first == code }?.second?.trim()?.toIntOrNull() ?: def
        fun all(code: Int) = codes.filter { it.first == code }.mapNotNull { it.second.trim().toDoubleOrNull() }
    }

    private class Block(val bx: Double, val by: Double, val ents: List<Ent>)

    /** AutoCAD colour index → ARGB for the basic colours; others grey. 7 = black on the white canvas. */
    fun aci(c: Int): Long = when (c) {
        1 -> 0xFFE53935; 2 -> 0xFFF9A825; 3 -> 0xFF43A047; 4 -> 0xFF00ACC1; 5 -> 0xFF1E88E5; 6 -> 0xFFD81B60
        8 -> 0xFF757575; 9 -> 0xFFBDBDBD; else -> 0xFF212121
    }

    fun read(text: String, maxShapes: Int = 300_000): DxfDrawing {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val pairs = ArrayList<Pair<Int, String>>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) { lines[i].trim().toIntOrNull()?.let { pairs += it to lines[i + 1] }; i += 2 }
        // split into sections → entities
        val blocks = HashMap<String, Block>()
        val entities = ArrayList<Ent>()
        var section = ""
        var k = 0
        var blockName: String? = null; var blockBase = 0.0 to 0.0; var blockEnts = ArrayList<Ent>()
        var layerColors = HashMap<String, Int>()
        while (k < pairs.size) {
            val (code, value) = pairs[k]
            if (code != 0) { k++; continue }
            val type = value.trim()
            val start = ++k
            while (k < pairs.size && pairs[k].first != 0) k++
            val e = Ent(type, pairs.subList(start, k))
            when (type) {
                "SECTION" -> section = e.s(2)?.trim().orEmpty()
                "ENDSEC" -> section = ""
                "BLOCK" -> { blockName = e.s(2)?.trim(); blockBase = e.d(10) to e.d(20); blockEnts = ArrayList() }
                "ENDBLK" -> { blockName?.let { blocks[it] = Block(blockBase.first, blockBase.second, blockEnts) }; blockName = null }
                "LAYER" -> if (section == "TABLES") e.s(2)?.trim()?.let { layerColors[it] = kotlin.math.abs(e.i(62, 7)) }
                else -> when (section) {
                    "BLOCKS" -> if (blockName != null) blockEnts += e
                    "ENTITIES" -> entities += e
                }
            }
        }
        val out = ArrayList<DxfShape>()
        // a running POLYLINE gathers its VERTEX entities
        fun emit(list: List<Ent>, m: DoubleArray, depth: Int) {
            var poly: MutableList<Double>? = null; var polyClosed = false; var polyColor = 0L
            fun tx(x: Double, y: Double) = doubleArrayOf(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
            val scale = kotlin.math.sqrt(kotlin.math.abs(m[0] * m[3] - m[1] * m[2]))
            val rotDeg = Math.toDegrees(kotlin.math.atan2(m[1], m[0]))
            for (e in list) {
                if (out.size >= maxShapes) return
                val ci = e.i(62, 256)
                val color = aci(if (ci == 256 || ci == 0) layerColors[e.s(8)?.trim()] ?: 7 else kotlin.math.abs(ci))
                when (e.type) {
                    "LINE" -> { val a = tx(e.d(10), e.d(20)); val b = tx(e.d(11), e.d(21)); out += DxfShape.Line(a[0], a[1], b[0], b[1], color) }
                    "POINT" -> { val a = tx(e.d(10), e.d(20)); out += DxfShape.Circle(a[0], a[1], 0.0, color) }
                    "CIRCLE" -> { val a = tx(e.d(10), e.d(20)); out += DxfShape.Circle(a[0], a[1], e.d(40) * scale, color) }
                    "ARC" -> { val a = tx(e.d(10), e.d(20)); out += DxfShape.Arc(a[0], a[1], e.d(40) * scale, e.d(50) + rotDeg, e.d(51) + rotDeg, color) }
                    "ELLIPSE" -> {
                        val cx = e.d(10); val cy = e.d(20); val mx = e.d(11); val my = e.d(21); val ratio = e.d(40, 1.0)
                        val p1 = e.d(41, 0.0); var p2 = e.d(42, Math.PI * 2); if (p2 <= p1) p2 += Math.PI * 2
                        val major = kotlin.math.hypot(mx, my); val ang = kotlin.math.atan2(my, mx)
                        val n = 72; val pts = DoubleArray((n + 1) * 2)
                        for (s in 0..n) {
                            val t = p1 + (p2 - p1) * s / n
                            val x = major * cos(t); val y = major * ratio * sin(t)
                            val p = tx(cx + x * cos(ang) - y * sin(ang), cy + x * sin(ang) + y * cos(ang))
                            pts[s * 2] = p[0]; pts[s * 2 + 1] = p[1]
                        }
                        out += DxfShape.Poly(pts, false, color)
                    }
                    "LWPOLYLINE" -> {
                        val xs = e.all(10); val ys = e.all(20)
                        val n = minOf(xs.size, ys.size)
                        val pts = DoubleArray(n * 2); for (j in 0 until n) { val p = tx(xs[j], ys[j]); pts[j * 2] = p[0]; pts[j * 2 + 1] = p[1] }
                        if (n >= 2) out += DxfShape.Poly(pts, e.i(70) and 1 == 1, color)
                    }
                    "SPLINE" -> {
                        val xs = e.all(10).ifEmpty { e.all(11) }; val ys = e.all(20).ifEmpty { e.all(21) }
                        val n = minOf(xs.size, ys.size)
                        val pts = DoubleArray(n * 2); for (j in 0 until n) { val p = tx(xs[j], ys[j]); pts[j * 2] = p[0]; pts[j * 2 + 1] = p[1] }
                        if (n >= 2) out += DxfShape.Poly(pts, e.i(70) and 1 == 1, color)
                    }
                    "SOLID", "3DFACE", "TRACE" -> {
                        val ps = listOf(10 to 20, 11 to 21, 13 to 23, 12 to 22).map { (a, b) -> tx(e.d(a), e.d(b)) }
                        out += DxfShape.Poly(ps.flatMap { listOf(it[0], it[1]) }.toDoubleArray(), true, color)
                    }
                    "POLYLINE" -> { poly = ArrayList(); polyClosed = e.i(70) and 1 == 1; polyColor = color }
                    "VERTEX" -> poly?.let { val p = tx(e.d(10), e.d(20)); it += p[0]; it += p[1] }
                    "SEQEND" -> { poly?.let { if (it.size >= 4) out += DxfShape.Poly(it.toDoubleArray(), polyClosed, polyColor) }; poly = null }
                    "TEXT", "MTEXT", "ATTRIB" -> {
                        var t = (e.codes.filter { it.first == 3 }.joinToString("") { it.second } + (e.s(1) ?: ""))
                        if (e.type == "MTEXT") t = mtext(t)
                        val p = tx(e.d(10), e.d(20))
                        if (t.isNotBlank()) out += DxfShape.Text(p[0], p[1], e.d(40, 2.5) * scale, e.d(50) + rotDeg, t, color)
                    }
                    "INSERT" -> if (depth < 8) {
                        val b = blocks[e.s(2)?.trim()] ?: continue
                        val sx = e.d(41, 1.0); val sy = e.d(42, 1.0); val r = Math.toRadians(e.d(50))
                        val ix = e.d(10); val iy = e.d(20)
                        // local: translate(-base) → scale → rotate → translate(insert), then the parent matrix
                        val c = cos(r); val s = sin(r)
                        val la = sx * c; val lb = sx * s; val lc = -sy * s; val ld = sy * c
                        val le = ix - (la * b.bx + lc * b.by); val lf = iy - (lb * b.bx + ld * b.by)
                        val nm = doubleArrayOf(
                            m[0] * la + m[2] * lb, m[1] * la + m[3] * lb, m[0] * lc + m[2] * ld, m[1] * lc + m[3] * ld,
                            m[0] * le + m[2] * lf + m[4], m[1] * le + m[3] * lf + m[5])
                        emit(b.ents, nm, depth + 1)
                    }
                }
            }
        }
        emit(entities, doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), 0)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        fun acc(x: Double, y: Double) { if (x.isFinite() && y.isFinite()) { minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y) } }
        for (s in out) when (s) {
            is DxfShape.Line -> { acc(s.x1, s.y1); acc(s.x2, s.y2) }
            is DxfShape.Poly -> { var j = 0; while (j + 1 < s.pts.size) { acc(s.pts[j], s.pts[j + 1]); j += 2 } }
            is DxfShape.Circle -> { acc(s.x - s.r, s.y - s.r); acc(s.x + s.r, s.y + s.r) }
            is DxfShape.Arc -> { acc(s.x - s.r, s.y - s.r); acc(s.x + s.r, s.y + s.r) }
            is DxfShape.Text -> { acc(s.x, s.y); acc(s.x + s.h * s.text.length * 0.6, s.y + s.h) }
        }
        if (minX > maxX) { minX = 0.0; minY = 0.0; maxX = 1.0; maxY = 1.0 }
        return DxfDrawing(out, minX, minY, maxX, maxY)
    }

    /** MTEXT formatting codes removed (\P = new paragraph). */
    fun mtext(s: String): String = s.replace("\\P", "\n").replace(Regex("""\\[A-Za-z][^;\\]*;"""), "").replace(Regex("""\\[LlOoKk]"""), "")
        .replace("{", "").replace("}", "").replace("\\~", " ").replace("%%c", "Ø").replace("%%d", "°").replace("%%p", "±")
}
