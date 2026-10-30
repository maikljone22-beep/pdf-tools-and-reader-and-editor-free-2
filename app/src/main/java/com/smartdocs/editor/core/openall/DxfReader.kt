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
    /**
     * fix/files-index: a HATCH: boundary [loops] (x, y pairs, filled even-odd); [solid] fill, else pattern lines at
     * [angles] (degrees) [spacing] drawing units apart.
     */
    class Hatch(val loops: List<DoubleArray>, val solid: Boolean, val angles: DoubleArray, val spacing: Double, color: Long) : DxfShape(color)
}

class DxfDrawing(val shapes: List<DxfShape>, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * ASCII DXF reader (open-all): LINE, CIRCLE, ARC, ELLIPSE, LWPOLYLINE, POLYLINE/VERTEX, SPLINE (control polygon),
 * POINT, TEXT, MTEXT, SOLID/3DFACE outlines and INSERT of BLOCKS (scaled / rotated, nested up to 8 levels); fix/files-index:
 * HATCH (solid or pattern lines), DIMENSION (its block, else line + measurement) and binary DXF ([binaryPairs]). Pure.
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

    fun read(text: String, maxShapes: Int = 300_000): DxfDrawing = read(asciiPairs(text), maxShapes)

    /** Group code / value pairs of an ASCII DXF. */
    fun asciiPairs(text: String): List<Pair<Int, String>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val pairs = ArrayList<Pair<Int, String>>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) { lines[i].trim().toIntOrNull()?.let { pairs += it to lines[i + 1] }; i += 2 }
        return pairs
    }

    private const val BINARY_SENTINEL = "AutoCAD Binary DXF\r\n\u001A\u0000"

    fun isBinary(b: ByteArray): Boolean = b.size >= 22 && String(b, 0, 22, Charsets.ISO_8859_1) == BINARY_SENTINEL

    /**
     * fix/files-index: group code / value pairs of a binary DXF (R12 one-byte or R13+ two-byte group codes, little
     * endian; values typed by group code range). Numbers come back as text so [read] treats both forms alike.
     */
    fun binaryPairs(b: ByteArray, maxPairs: Int = 20_000_000): List<Pair<Int, String>> {
        require(isBinary(b)) { "not a binary DXF" }
        var p = 22
        // the first group is 0 "SECTION": two zero bytes when codes are 16 bit
        val wide = b.size > 24 && b[22].toInt() == 0 && b[23].toInt() == 0
        val out = ArrayList<Pair<Int, String>>()
        fun u8(): Int { if (p >= b.size) throw IndexOutOfBoundsException(); return b[p++].toInt() and 0xFF }
        fun le(n: Int): Long { var v = 0L; for (k in 0 until n) v = v or (u8().toLong() shl (8 * k)); return v }
        fun str(): String { val s = p; while (p < b.size && b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.UTF_8); p++; return r }
        try {
            while (p < b.size && out.size < maxPairs) {
                val code = if (wide) le(2).toInt() else u8().let { if (it == 255) le(2).toInt() else it }
                val v: String = when (code) {
                    in 0..9, in 100..109, in 300..309, in 320..369, in 390..399, in 410..419, in 430..439, in 470..481, 999, in 1000..1003, in 1005..1009 -> str()
                    in 310..319, 1004 -> { val n = u8(); p += n; "" }
                    in 10..59, in 110..149, in 210..239, in 460..469, in 1010..1059 -> java.lang.Double.longBitsToDouble(le(8)).toString()
                    in 60..79, in 170..179, in 270..289, in 370..389, in 400..409, in 1060..1070 -> le(2).toShort().toString()
                    in 90..99, in 420..429, in 440..459, 1071 -> le(4).toInt().toString()
                    in 160..169 -> le(8).toString()
                    in 290..299 -> u8().toString()
                    else -> str()
                }
                out += code to v
                if (code == 0 && v == "EOF") break
            }
        } catch (_: IndexOutOfBoundsException) { }
        return out
    }

    fun read(pairs: List<Pair<Int, String>>, maxShapes: Int = 300_000): DxfDrawing {
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
                    "HATCH" -> hatch(e, ::tx, scale, rotDeg, color)?.let { out += it }   // fix/files-index
                    "DIMENSION" -> {
                        // fix/files-index: the dimension's anonymous block ("*D…") holds its lines, arrows and text
                        val b = blocks[e.s(2)?.trim()]
                        if (b != null && depth < 8) emit(b.ents, m, depth + 1)
                        else {
                            val a = tx(e.d(13), e.d(23)); val c = tx(e.d(14), e.d(24))
                            if (a[0] != c[0] || a[1] != c[1]) out += DxfShape.Line(a[0], a[1], c[0], c[1], color)
                            val t = dimText(e.s(1), e.d(42))
                            val p = tx(e.d(11), e.d(21))
                            if (t.isNotBlank()) out += DxfShape.Text(p[0], p[1], 2.5 * scale, e.d(53) + rotDeg, t, color)
                        }
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
            is DxfShape.Hatch -> s.loops.forEach { l -> var j = 0; while (j + 1 < l.size) { acc(l[j], l[j + 1]); j += 2 } }
        }
        if (minX > maxX) { minX = 0.0; minY = 0.0; maxX = 1.0; maxY = 1.0 }
        return DxfDrawing(out, minX, minY, maxX, maxY)
    }

    /** Dimension text: the override with "<>" as the measurement, else the measurement itself. */
    fun dimText(override: String?, measured: Double): String {
        val m = if (measured == Math.rint(measured)) measured.toLong().toString() else "%.2f".format(java.util.Locale.US, measured).trimEnd('0').trimEnd('.')
        val o = override?.let { mtext(it) }
        return when {
            o.isNullOrEmpty() -> if (measured != 0.0) m else ""
            o == " " -> ""                       // a single space suppresses the text
            else -> o.replace("<>", m)
        }
    }

    /**
     * Points along an arc (degrees) into [pts], counter-clockwise when [ccw]. Hatch edges store a clockwise arc with
     * mirrored angles ([mirrored]); a polyline bulge gives the real angles.
     */
    private fun arcPts(pts: MutableList<Double>, cx: Double, cy: Double, rx: Double, ry: Double, rotRad: Double, a1Deg: Double, a2Deg: Double, ccw: Boolean, mirrored: Boolean = true) {
        var a1 = a1Deg; var a2 = a2Deg
        if (!ccw && mirrored) { a1 = -a1Deg; a2 = -a2Deg }
        var sweep = if (ccw) a2 - a1 else a1 - a2
        while (sweep <= 0) sweep += 360.0
        if (sweep > 360.0) sweep = 360.0
        val n = maxOf(4, (sweep / 5).toInt())
        for (k in 0..n) {
            val t = Math.toRadians(if (ccw) a1 + sweep * k / n else a1 - sweep * k / n)
            val x = rx * cos(t); val y = ry * sin(t)
            pts += cx + x * cos(rotRad) - y * sin(rotRad); pts += cy + x * sin(rotRad) + y * cos(rotRad)
        }
    }

    /**
     * fix/files-index: HATCH boundary paths (polyline with bulges, or line / arc / ellipse / spline edges) and its
     * solid fill or pattern line angles and spacing. Codes are read in order (the same codes repeat per loop).
     */
    private fun hatch(e: Ent, tx: (Double, Double) -> DoubleArray, scale: Double, rotDeg: Double, color: Long): DxfShape.Hatch? {
        val c = e.codes
        var i = 0
        fun seek(code: Int): Boolean { while (i < c.size && c[i].first != code) i++; return i < c.size }
        fun num(code: Int): Double = if (seek(code)) (c[i++].second.trim().toDoubleOrNull() ?: 0.0) else 0.0
        fun numAt(code: Int): Double? = if (i < c.size && c[i].first == code) c[i++].second.trim().toDoubleOrNull() else null
        val solid = e.i(70) == 1
        if (!seek(91)) return null
        val nLoops = (c[i++].second.trim().toIntOrNull() ?: 0).coerceIn(0, 10_000)
        val loops = ArrayList<DoubleArray>()
        repeat(nLoops) {
            if (!seek(92)) return@repeat
            val type = c[i++].second.trim().toIntOrNull() ?: 0
            val pts = ArrayList<Double>()
            if (type and 2 != 0) {
                val n = num(93).toInt().coerceIn(0, 1_000_000)
                val vs = ArrayList<DoubleArray>(n)
                repeat(n) { val x = num(10); val y = num(20); vs += doubleArrayOf(x, y, numAt(42) ?: 0.0) }
                for (k in vs.indices) {
                    val a = vs[k]; pts += a[0]; pts += a[1]
                    val b = vs[(k + 1) % vs.size]
                    if (a[2] != 0.0 && (k + 1 < vs.size || vs.size > 2)) {
                        // bulge = tan(θ/4): arc through the chord a → b
                        val theta = 4 * kotlin.math.atan(a[2]); val chord = kotlin.math.hypot(b[0] - a[0], b[1] - a[1])
                        if (chord > 0) {
                            val r = chord / (2 * sin(theta / 2))
                            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
                            val d = r * cos(theta / 2)
                            val nx = -(b[1] - a[1]) / chord; val ny = (b[0] - a[0]) / chord
                            val cx = mx + nx * d; val cy = my + ny * d
                            val s0 = Math.toDegrees(kotlin.math.atan2(a[1] - cy, a[0] - cx)); val s1 = Math.toDegrees(kotlin.math.atan2(b[1] - cy, b[0] - cx))
                            arcPts(pts, cx, cy, kotlin.math.abs(r), kotlin.math.abs(r), 0.0, s0, s1, a[2] > 0, mirrored = false)
                        }
                    }
                }
            } else {
                val nEdges = num(93).toInt().coerceIn(0, 100_000)
                repeat(nEdges) {
                    when (num(72).toInt()) {
                        1 -> { val x1 = num(10); val y1 = num(20); val x2 = num(11); val y2 = num(21); pts += x1; pts += y1; pts += x2; pts += y2 }
                        2 -> { val cx = num(10); val cy = num(20); val r = num(40); val a1 = num(50); val a2 = num(51); val ccw = num(73) != 0.0
                            arcPts(pts, cx, cy, r, r, 0.0, a1, a2, ccw) }
                        3 -> { val cx = num(10); val cy = num(20); val mx = num(11); val my = num(21); val ratio = num(40); val a1 = num(50); val a2 = num(51); val ccw = num(73) != 0.0
                            val major = kotlin.math.hypot(mx, my)
                            arcPts(pts, cx, cy, major, major * ratio, kotlin.math.atan2(my, mx), a1, a2, ccw) }
                        4 -> {
                            num(94); num(73); num(74); val nk = num(95).toInt().coerceIn(0, 100_000); val nc = num(96).toInt().coerceIn(0, 100_000)
                            repeat(nk) { num(40) }
                            repeat(nc) { pts += num(10); pts += num(20); numAt(42) }
                        }
                    }
                }
            }
            if (pts.size >= 6) {
                val arr = DoubleArray(pts.size)
                var k = 0
                while (k + 1 < pts.size) { val p = tx(pts[k], pts[k + 1]); arr[k] = p[0]; arr[k + 1] = p[1]; k += 2 }
                loops += arr
            }
        }
        if (loops.isEmpty()) return null
        // pattern definition lines: angle (53) and offset (45, 46) of each family
        val angles = ArrayList<Double>(); var spacing = 0.0; var dx = 0.0
        while (i < c.size) {
            val v = c[i].second.trim().toDoubleOrNull() ?: 0.0
            when (c[i].first) {
                53 -> { angles += v; dx = 0.0 }
                45 -> dx = v
                46 -> if (angles.isNotEmpty()) spacing = maxOf(spacing, kotlin.math.hypot(dx, v))
            }
            i++
        }
        if (angles.isEmpty()) angles += e.d(52) + 45.0
        if (spacing <= 0) spacing = 3.0 * e.d(41, 1.0)
        return DxfShape.Hatch(loops, solid, angles.map { it + rotDeg }.toDoubleArray(), spacing * scale, color)
    }

    /** MTEXT formatting codes removed (\P = new paragraph). */
    fun mtext(s: String): String = s.replace("\\P", "\n").replace(Regex("""\\[A-Za-z][^;\\]*;"""), "").replace(Regex("""\\[LlOoKk]"""), "")
        .replace("{", "").replace("}", "").replace("\\~", " ").replace("%%c", "Ø").replace("%%d", "°").replace("%%p", "±")
}
