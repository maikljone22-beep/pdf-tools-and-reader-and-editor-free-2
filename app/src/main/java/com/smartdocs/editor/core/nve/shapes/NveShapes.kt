package com.smartdocs.editor.core.nve.shapes

import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShading
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType2
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType3
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Kinds of vector shapes written as page content. */
enum class ShapeKind { LINE, RECT, ROUND_RECT, ELLIPSE, POLYLINE, POLYGON, BEZIER, CLOSED_BEZIER, FREE }

/** One gradient stop: [offset] 0..1, [color] 0xRRGGBB. */
data class GradientStop(val offset: Float, val color: Int)

/**
 * Gradient fill. Geometry in fractions of the shape's bounding box (0,0 = bottom-left): axial from (x0,y0) to
 * (x1,y1); radial centred on (x0,y0) with radius [r] (fraction of the larger box side).
 */
data class Gradient(
    val radial: Boolean = false,
    val stops: List<GradientStop> = listOf(GradientStop(0f, 0x2196F3), GradientStop(1f, 0xFFFFFF)),
    val x0: Float = 0f, val y0: Float = 0.5f, val x1: Float = 1f, val y1: Float = 0.5f, val r: Float = 0.5f,
)

/** Stroke / fill style. Null colour = none. cap / join use the PDF codes (0 butt/miter, 1 round, 2 square/bevel). */
data class ShapeStyle(
    val stroke: Int? = 0x000000,
    val fill: Int? = null,
    val width: Float = 2f,
    val dash: List<Float> = emptyList(),
    val cap: Int = 0,
    val join: Int = 0,
    val miter: Float = 10f,
    val opacity: Float = 1f,
    val gradient: Gradient? = null,
)

/** A shape in page display space (points, y up, see [PageFrame]); [points] meaning depends on [kind]. */
data class NveShape(
    val id: Int,
    val kind: ShapeKind,
    val points: List<Float>,      // x0, y0, x1, y1, …
    val style: ShapeStyle = ShapeStyle(),
    val radius: Float = 8f,
) {
    val xs: List<Float> get() = points.filterIndexed { i, _ -> i % 2 == 0 }
    val ys: List<Float> get() = points.filterIndexed { i, _ -> i % 2 == 1 }
    /** Bounding box [left, bottom, right, top] of the control points (plus half the stroke width). */
    fun bounds(): FloatArray {
        val h = if (style.stroke != null) style.width / 2f else 0f
        return floatArrayOf(xs.min() - h, ys.min() - h, xs.max() + h, ys.max() + h)
    }
    /** Shape moved by (dx, dy). */
    fun moved(dx: Float, dy: Float) = copy(points = points.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy })
    /** Shape scaled so its control box becomes [l, b, r, t]. */
    fun fitted(l: Float, b: Float, r: Float, t: Float): NveShape {
        val ox0 = xs.min(); val oy0 = ys.min(); val ow = (xs.max() - ox0).takeIf { it > 1e-3f } ?: 1f; val oh = (ys.max() - oy0).takeIf { it > 1e-3f } ?: 1f
        return copy(points = points.mapIndexed { i, v -> if (i % 2 == 0) l + (v - ox0) / ow * (r - l) else b + (v - oy0) / oh * (t - b) })
    }
}

/**
 * Writes / finds / rewrites vector shapes and gradient objects in a page's content stream. Each shape is one
 * marked-content span `/NVEShape <</Id n /K … >> BDC … EMC` whose property list holds everything needed to edit it
 * again; the span itself carries its own `q … Q`, so nothing leaks into the following content.
 * Pure PDFBox (no Android classes): unit-tested on the JVM.
 */
object NveShapes {
    const val TAG = "NVEShape"
    private const val K = 0.5523f

    // ------------------------------------------------------------------ path geometry

    private fun f(v: Float): String = if (abs(v - v.toInt()) < 1e-4f) v.toInt().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** Path operators (display space) for [s]; closed tells whether the path is a closed figure. */
    fun pathOps(s: NveShape): Pair<String, Boolean> {
        val p = s.points
        val sb = StringBuilder()
        fun m(x: Float, y: Float) = sb.append(f(x)).append(' ').append(f(y)).append(" m\n")
        fun l(x: Float, y: Float) = sb.append(f(x)).append(' ').append(f(y)).append(" l\n")
        fun c(a: Float, b: Float, cc: Float, d: Float, e: Float, g: Float) = sb.append("${f(a)} ${f(b)} ${f(cc)} ${f(d)} ${f(e)} ${f(g)} c\n")
        val n = p.size / 2
        when (s.kind) {
            ShapeKind.LINE -> { if (n >= 2) { m(p[0], p[1]); l(p[2], p[3]) }; return sb.toString() to false }
            ShapeKind.RECT -> {
                if (n >= 2) { val x0 = min(p[0], p[2]); val y0 = min(p[1], p[3]); sb.append("${f(x0)} ${f(y0)} ${f(abs(p[2] - p[0]))} ${f(abs(p[3] - p[1]))} re\n") }
                return sb.toString() to true
            }
            ShapeKind.ROUND_RECT -> {
                if (n >= 2) {
                    val l0 = min(p[0], p[2]); val r0 = max(p[0], p[2]); val b0 = min(p[1], p[3]); val t0 = max(p[1], p[3])
                    val rr = s.radius.coerceIn(0f, min(r0 - l0, t0 - b0) / 2f); val k = rr * K
                    m(l0 + rr, b0); l(r0 - rr, b0); c(r0 - rr + k, b0, r0, b0 + rr - k, r0, b0 + rr)
                    l(r0, t0 - rr); c(r0, t0 - rr + k, r0 - rr + k, t0, r0 - rr, t0)
                    l(l0 + rr, t0); c(l0 + rr - k, t0, l0, t0 - rr + k, l0, t0 - rr)
                    l(l0, b0 + rr); c(l0, b0 + rr - k, l0 + rr - k, b0, l0 + rr, b0)
                    sb.append("h\n")
                }
                return sb.toString() to true
            }
            ShapeKind.ELLIPSE -> {
                if (n >= 2) {
                    val cx = (p[0] + p[2]) / 2f; val cy = (p[1] + p[3]) / 2f; val rx = abs(p[2] - p[0]) / 2f; val ry = abs(p[3] - p[1]) / 2f
                    val kx = rx * K; val ky = ry * K
                    m(cx + rx, cy); c(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry); c(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
                    c(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry); c(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy); sb.append("h\n")
                }
                return sb.toString() to true
            }
            ShapeKind.POLYLINE, ShapeKind.FREE -> { for (i in 0 until n) if (i == 0) m(p[0], p[1]) else l(p[2 * i], p[2 * i + 1]); return sb.toString() to false }
            ShapeKind.POLYGON -> { for (i in 0 until n) if (i == 0) m(p[0], p[1]) else l(p[2 * i], p[2 * i + 1]); if (n > 0) sb.append("h\n"); return sb.toString() to true }
            ShapeKind.BEZIER, ShapeKind.CLOSED_BEZIER -> {
                val closed = s.kind == ShapeKind.CLOSED_BEZIER
                if (n < 2) return sb.toString() to closed
                // Catmull-Rom spline through the anchors, as cubic Bezier segments
                fun pt(i: Int): Pair<Float, Float> {
                    val j = if (closed) ((i % n) + n) % n else i.coerceIn(0, n - 1)
                    return p[2 * j] to p[2 * j + 1]
                }
                m(p[0], p[1])
                val segs = if (closed) n else n - 1
                for (i in 0 until segs) {
                    val (x0, y0) = pt(i - 1); val (x1, y1) = pt(i); val (x2, y2) = pt(i + 1); val (x3, y3) = pt(i + 2)
                    c(x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f, x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f, x2, y2)
                }
                if (closed) sb.append("h\n")
                return sb.toString() to closed
            }
        }
    }

    // ------------------------------------------------------------------ property list <-> shape

    private fun arr(vararg v: Float) = COSArray().apply { v.forEach { add(COSFloat(it)) } }
    private fun rgb(c: Int) = arr(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun colorOf(a: COSBase?): Int? {
        val x = a as? COSArray ?: return null
        if (x.size() < 3) return null
        fun v(i: Int) = (((x.getObject(i) as? COSNumber)?.floatValue() ?: 0f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (v(0) shl 16) or (v(1) shl 8) or v(2)
    }
    private fun floats(a: COSBase?): List<Float> = (a as? COSArray)?.let { x -> (0 until x.size()).mapNotNull { (x.getObject(it) as? COSNumber)?.floatValue() } } ?: emptyList()

    fun toProps(s: NveShape): COSDictionary = COSDictionary().apply {
        setInt(COSName.getPDFName("Id"), s.id)
        setName(COSName.getPDFName("K"), s.kind.name)
        setItem(COSName.getPDFName("P"), arr(*s.points.toFloatArray()))
        s.style.stroke?.let { setItem(COSName.getPDFName("SC"), rgb(it)) }
        s.style.fill?.let { setItem(COSName.getPDFName("FC"), rgb(it)) }
        setFloat(COSName.getPDFName("W"), s.style.width)
        if (s.style.dash.isNotEmpty()) setItem(COSName.getPDFName("D"), arr(*s.style.dash.toFloatArray()))
        setInt(COSName.getPDFName("Cap"), s.style.cap); setInt(COSName.getPDFName("Join"), s.style.join)
        setFloat(COSName.getPDFName("Ml"), s.style.miter); setFloat(COSName.getPDFName("Op"), s.style.opacity)
        setFloat(COSName.getPDFName("R"), s.radius)
        s.style.gradient?.let { g ->
            val gd = COSDictionary()
            gd.setBoolean(COSName.getPDFName("Rad"), g.radial)
            gd.setItem(COSName.getPDFName("S"), COSArray().apply { g.stops.forEach { st -> add(COSFloat(st.offset)); add(COSInteger.get(st.color.toLong())) } })
            gd.setItem(COSName.getPDFName("G"), arr(g.x0, g.y0, g.x1, g.y1, g.r))
            setItem(COSName.getPDFName("Gr"), gd)
        }
    }

    fun fromProps(d: COSDictionary): NveShape? {
        val kind = runCatching { ShapeKind.valueOf(d.getNameAsString(COSName.getPDFName("K")) ?: return null) }.getOrNull() ?: return null
        val gr = d.getDictionaryObject(COSName.getPDFName("Gr")) as? COSDictionary
        val grad = gr?.let { g ->
            val sa = g.getDictionaryObject(COSName.getPDFName("S")) as? COSArray
            val stops = ArrayList<GradientStop>()
            if (sa != null) for (i in 0 until sa.size() / 2) {
                val off = (sa.getObject(2 * i) as? COSNumber)?.floatValue() ?: 0f
                val col = (sa.getObject(2 * i + 1) as? COSNumber)?.intValue() ?: 0
                stops += GradientStop(off, col)
            }
            val geo = floats(g.getDictionaryObject(COSName.getPDFName("G")))
            Gradient(g.getBoolean(COSName.getPDFName("Rad"), false), stops.ifEmpty { Gradient().stops },
                geo.getOrElse(0) { 0f }, geo.getOrElse(1) { 0.5f }, geo.getOrElse(2) { 1f }, geo.getOrElse(3) { 0.5f }, geo.getOrElse(4) { 0.5f })
        }
        val style = ShapeStyle(
            stroke = colorOf(d.getDictionaryObject(COSName.getPDFName("SC"))),
            fill = colorOf(d.getDictionaryObject(COSName.getPDFName("FC"))),
            width = d.getFloat(COSName.getPDFName("W"), 1f),
            dash = floats(d.getDictionaryObject(COSName.getPDFName("D"))),
            cap = d.getInt(COSName.getPDFName("Cap"), 0), join = d.getInt(COSName.getPDFName("Join"), 0),
            miter = d.getFloat(COSName.getPDFName("Ml"), 10f), opacity = d.getFloat(COSName.getPDFName("Op"), 1f),
            gradient = grad,
        )
        return NveShape(d.getInt(COSName.getPDFName("Id"), 0), kind, floats(d.getDictionaryObject(COSName.getPDFName("P"))), style, d.getFloat(COSName.getPDFName("R"), 8f))
    }

    // ------------------------------------------------------------------ resources

    private fun resources(page: PDPage): PDResources = page.resources ?: PDResources().also { page.resources = it }

    /** Shading for [g] over the box [l, b, r, t] (display space; the span's cm maps it to user space). */
    fun shading(g: Gradient, l: Float, b: Float, r: Float, t: Float): PDShading {
        val stops = g.stops.sortedBy { it.offset }.ifEmpty { Gradient().stops }.let { if (it.size == 1) listOf(it[0], it[0].copy(offset = 1f)) else it }
        fun c(col: Int) = arr(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f)
        fun type2(a: GradientStop, z: GradientStop): COSDictionary = COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 2); setItem(COSName.DOMAIN, arr(0f, 1f))
            setItem(COSName.C0, c(a.color)); setItem(COSName.C1, c(z.color)); setInt(COSName.N, 1)
        }
        val fn: COSDictionary = if (stops.size == 2) type2(stops[0], stops[1]) else COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 3); setItem(COSName.DOMAIN, arr(0f, 1f))
            val fns = COSArray(); val bounds = COSArray(); val encode = COSArray()
            for (i in 0 until stops.size - 1) {
                fns.add(type2(stops[i], stops[i + 1]))
                if (i > 0) bounds.add(COSFloat(stops[i].offset.coerceIn(0f, 1f)))
                encode.add(COSFloat(0f)); encode.add(COSFloat(1f))
            }
            setItem(COSName.FUNCTIONS, fns); setItem(COSName.BOUNDS, bounds); setItem(COSName.ENCODE, encode)
        }
        val w = r - l; val h = t - b
        val dict = COSDictionary()
        dict.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
        dict.setItem(COSName.FUNCTION, fn)
        dict.setItem(COSName.EXTEND, COSArray().apply { add(COSBoolean.TRUE); add(COSBoolean.TRUE) })
        return if (g.radial) {
            dict.setInt(COSName.SHADING_TYPE, 3)
            val cx = l + g.x0 * w; val cy = b + g.y0 * h; val rad = g.r * max(w, h)
            dict.setItem(COSName.COORDS, arr(cx, cy, 0f, cx, cy, rad.coerceAtLeast(0.1f)))
            PDShadingType3(dict)
        } else {
            dict.setInt(COSName.SHADING_TYPE, 2)
            dict.setItem(COSName.COORDS, arr(l + g.x0 * w, b + g.y0 * h, l + g.x1 * w, b + g.y1 * h))
            PDShadingType2(dict)
        }
    }

    // ------------------------------------------------------------------ operators of one span

    /** Content-stream text (without BDC/EMC) drawing [s] on [page]; adds shading / ExtGState resources as needed. */
    fun body(page: PDPage, s: NveShape, toUser: FloatArray): String {
        val st = s.style
        val sb = StringBuilder("q\n")
        sb.append(toUser.joinToString(" ") { f(it) }).append(" cm\n")
        if (st.opacity < 0.999f) {
            val gs = PDExtendedGraphicsState(); gs.strokingAlphaConstant = st.opacity; gs.nonStrokingAlphaConstant = st.opacity
            val name = resources(page).add(gs)
            sb.append('/').append(name.name).append(" gs\n")
        }
        val (path, closed) = pathOps(s)
        val grad = st.gradient?.takeIf { closed }
        if (grad != null) {
            val bb = floatArrayOf(s.xs.min(), s.ys.min(), s.xs.max(), s.ys.max())
            val sh = resources(page).add(shading(grad, bb[0], bb[1], bb[2], bb[3]))
            sb.append("q\n").append(path).append("W n\n/").append(sh.name).append(" sh\nQ\n")
        }
        val stroke = st.stroke
        val fill = st.fill?.takeIf { closed && grad == null }
        if (stroke != null) {
            sb.append("${f(((stroke shr 16) and 0xFF) / 255f)} ${f(((stroke shr 8) and 0xFF) / 255f)} ${f((stroke and 0xFF) / 255f)} RG\n")
            sb.append("${f(st.width)} w ${st.cap} J ${st.join} j ${f(st.miter)} M\n")
            sb.append('[').append(st.dash.joinToString(" ") { f(it) }).append("] 0 d\n")
        }
        if (fill != null) sb.append("${f(((fill shr 16) and 0xFF) / 255f)} ${f(((fill shr 8) and 0xFF) / 255f)} ${f((fill and 0xFF) / 255f)} rg\n")
        val paint = when {
            fill != null && stroke != null -> "B"
            fill != null -> "f"
            stroke != null -> "S"
            else -> null
        }
        if (paint != null) sb.append(path).append(paint).append('\n')
        sb.append("Q\n")
        return sb.toString()
    }

    /** Whole span as raw content text: `/NVEShape <<…>> BDC … EMC`. */
    fun span(page: PDPage, s: NveShape, toUser: FloatArray): String {
        val bos = ByteArrayOutputStream()
        ContentStreamWriter(bos).writeTokens(listOf<Any>(COSName.getPDFName(TAG), toProps(s), Operator.getOperator("BDC")))
        return bos.toString("ISO-8859-1") + "\n" + body(page, s, toUser) + "EMC\n"
    }

    // ------------------------------------------------------------------ page level

    private fun toUser(page: PDPage): FloatArray = Affine.values(PageFrame.of(page).toUser)

    /** Next free shape id on [page]. */
    fun nextId(page: PDPage): Int = (list(page).maxOfOrNull { it.id } ?: 0) + 1

    /** Append [s] (id assigned when 0) to the page content; returns the stored shape. */
    fun add(doc: PDDocument, page: PDPage, s: NveShape): NveShape {
        val shape = if (s.id == 0) s.copy(id = nextId(page)) else s
        val tokens = tokens(page)
        val text = span(page, shape, toUser(page))
        writeAll(doc, page, tokens, text)
        return shape
    }

    /** Shapes written by [add] on [page], in painting order. */
    fun list(page: PDPage): List<NveShape> {
        val out = ArrayList<NveShape>()
        val t = tokens(page)
        for (i in t.indices) {
            val op = t[i] as? Operator ?: continue
            if (op.name != "BDC" || i < 2) continue
            if ((t[i - 2] as? COSName)?.name != TAG) continue
            (t[i - 1] as? COSDictionary)?.let { fromProps(it) }?.let { out += it }
        }
        return out
    }

    /** Replace shape [id] by [s] (null = delete). False when it was not found. */
    fun replace(doc: PDDocument, page: PDPage, id: Int, s: NveShape?): Boolean {
        val t = tokens(page)
        val range = spanRange(t, id) ?: return false
        val kept = ArrayList<Any>(t.size)
        kept.addAll(t.subList(0, range.first)); kept.addAll(t.subList(range.last + 1, t.size))
        val insert = s?.let { span(page, it.copy(id = id), toUser(page)) }
        // the edited shape keeps its place in the painting order: tokens before, new span, tokens after
        if (insert == null) writeAll(doc, page, kept, null, wrap = false)
        else writeSplice(doc, page, t.subList(0, range.first), insert, t.subList(range.last + 1, t.size))
        return true
    }

    /** Token index range (BDC operands … EMC) of shape [id]. */
    private fun spanRange(t: List<Any>, id: Int): IntRange? {
        for (i in t.indices) {
            val op = t[i] as? Operator ?: continue
            if (op.name != "BDC" || i < 2 || (t[i - 2] as? COSName)?.name != TAG) continue
            val d = t[i - 1] as? COSDictionary ?: continue
            if (d.getInt(COSName.getPDFName("Id"), -1) != id) continue
            var depth = 0
            for (j in i + 1 until t.size) {
                val o = t[j] as? Operator ?: continue
                when (o.name) {
                    "BDC", "BMC" -> depth++
                    "EMC" -> { if (depth == 0) return (i - 2)..j; depth-- }
                }
            }
            return null
        }
        return null
    }

    private fun tokens(page: PDPage): List<Any> = if (page.cosObject.getDictionaryObject(COSName.CONTENTS) == null) emptyList()
        else PDFStreamParser(page).let { it.parse(); it.tokens }

    private fun tokenBytes(t: List<Any>): ByteArray { val b = ByteArrayOutputStream(); ContentStreamWriter(b).writeTokens(t); return b.toByteArray() }

    /** Rewrite the page as one stream: (q [old tokens] Q) + [extra]. */
    private fun writeAll(doc: PDDocument, page: PDPage, old: List<Any>, extra: String?, wrap: Boolean = true) {
        val bos = ByteArrayOutputStream()
        if (old.isNotEmpty()) {
            if (wrap && !isWrapped(old)) bos.write("q\n".toByteArray())
            bos.write(tokenBytes(old)); bos.write("\n".toByteArray())
            if (wrap && !isWrapped(old)) bos.write("Q\n".toByteArray())
        }
        extra?.let { bos.write(it.toByteArray(Charsets.ISO_8859_1)) }
        setContents(doc, page, bos.toByteArray())
    }

    private fun writeSplice(doc: PDDocument, page: PDPage, before: List<Any>, middle: String, after: List<Any>) {
        val bos = ByteArrayOutputStream()
        bos.write(tokenBytes(before)); bos.write("\n".toByteArray())
        bos.write(middle.toByteArray(Charsets.ISO_8859_1))
        bos.write(tokenBytes(after)); bos.write("\n".toByteArray())
        setContents(doc, page, bos.toByteArray())
    }

    /** True when the stream is already "q … Q" balanced at the top (avoids nesting a new q/Q on every edit). */
    internal fun isWrapped(t: List<Any>): Boolean {
        // the original content (everything before our first span) must be exactly one balanced "q … Q" group
        val firstSpan = t.indices.firstOrNull { i -> (t[i] as? Operator)?.name == "BDC" && i >= 2 && (t[i - 2] as? COSName)?.name == TAG }?.let { it - 2 } ?: t.size
        val ops = t.subList(0, firstSpan).filterIsInstance<Operator>()
        if (ops.isEmpty()) return true                     // nothing but our own spans (each has its own q … Q)
        if (ops.first().name != "q" || ops.last().name != "Q") return false
        var depth = 0
        for ((k, o) in ops.withIndex()) {
            if (o.name == "q") depth++ else if (o.name == "Q") { depth--; if (depth == 0 && k != ops.size - 1) return false }
        }
        return depth == 0
    }

    private fun setContents(doc: PDDocument, page: PDPage, bytes: ByteArray) {
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(bytes) }
        page.setContents(stream)
    }

    /** Hit test in display space: the topmost shape whose box (inflated by [tol]) contains (x, y). */
    fun hit(shapes: List<NveShape>, x: Float, y: Float, tol: Float): NveShape? = shapes.lastOrNull { s ->
        val b = s.bounds(); x in (b[0] - tol)..(b[2] + tol) && y in (b[1] - tol)..(b[3] + tol)
    }
}
