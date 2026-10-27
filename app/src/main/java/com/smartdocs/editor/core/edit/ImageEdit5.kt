package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.hypot

/**
 * NVE-img: page-object operations on what the page's own content stream paints — images (`Do`, inline `BI`), vector
 * shapes (a path and its painting operator) and shadings (`sh`): crop / clip (rectangle or ellipse), opacity and blend
 * mode, exact re-placement, recolouring, z-order, duplicate, copy across documents, place on several pages,
 * replacing the pixels in place, and "merge layers". Pure PDFBox (no android.*), unit tested on the JVM. Operator
 * indexes are those of [ContentRewriter] / [PageScanner] (top-level operators of the page content).
 *
 * An edited object is written as a group the next edit recognises:
 * `/NveImg <<…>> BDC q <m> cm [state ops: gs, colours, line width…] [clip path W n] <body> Q EMC` — clip (fractions of
 * the object's own box), opacity and blend travel with the object when it is moved again, re-arranged or duplicated.
 */
internal object ImageEdit5 {
    const val TAG = "NveImg"
    private val K_CROP = COSName.getPDFName("NveCrop")
    private val K_ELL = COSName.getPDFName("NveEllipse")
    private val K_ALPHA = COSName.getPDFName("NveCA")
    private val K_GS = COSName.getPDFName("NveGS")
    private val K_BM = COSName.getPDFName("NveBM")
    /** Blend modes offered for an object (ExtGState /BM). */
    val BLEND_MODES = listOf("Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "ColorDodge", "ColorBurn",
        "HardLight", "SoftLight", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity")

    val PAINT = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")
    private val CONSTRUCT = setOf("m", "l", "c", "v", "y", "h", "re")
    private val FILL_COLOR = setOf("sc", "scn", "g", "rg", "k")
    private val STROKE_COLOR = setOf("SC", "SCN", "G", "RG", "K")
    private val STATE = setOf("gs", "cs", "CS", "w", "d", "J", "j", "M", "ri", "i") + FILL_COLOR + STROKE_COLOR

    enum class Kind { IMAGE, SHAPE, SHADING }

    /**
     * One top-level operator: its operator token, operands, the CTM / ExtGState names / colour and line state in
     * effect when it runs, and for painting operators the painted box in user space ([userBox]) and in the operator's
     * own coordinates ([localBox]); x0, y0, x1, y1.
     */
    class OpInfo(val op: Operator, val operands: List<COSBase>, val ctm: Matrix, val gs: List<COSName>, val style: List<List<Any>>) {
        val name: String get() = op.name
        var userBox: FloatArray? = null
        var localBox: FloatArray? = null
        fun tokens(): List<Any> = operands + op
    }

    class Scan(val ops: List<OpInfo>, val endCtm: Matrix)

    private class GState(val ctm: Matrix, val gs: List<COSName>, val style: LinkedHashMap<String, List<Any>>, val clip: FloatArray?)

    /** Walk the page content once (initial CTM = identity, as in PDFStreamEngine). */
    fun scan(page: PDPage): Scan {
        val p = PDFStreamParser(page); p.parse()
        return scanTokens(p.tokens)
    }

    private fun box(m: Matrix, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val pts = listOf(Affine.apply(m, x0, y0), Affine.apply(m, x1, y0), Affine.apply(m, x0, y1), Affine.apply(m, x1, y1))
        return floatArrayOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    private fun intersect(a: FloatArray?, b: FloatArray): FloatArray =
        if (a == null) b else floatArrayOf(maxOf(a[0], b[0]), maxOf(a[1], b[1]), minOf(a[2], b[2]), minOf(a[3], b[3]))

    fun scanTokens(tokens: List<Any>): Scan {
        val out = ArrayList<OpInfo>()
        var g = GState(Affine.identity(), emptyList(), LinkedHashMap(), null)
        val stack = ArrayDeque<GState>()
        val operands = ArrayList<COSBase>()
        // current path, in local coordinates
        var px0 = Float.MAX_VALUE; var py0 = Float.MAX_VALUE; var px1 = -Float.MAX_VALUE; var py1 = -Float.MAX_VALUE
        var clipPending = false
        fun pt(x: Float, y: Float) { px0 = minOf(px0, x); py0 = minOf(py0, y); px1 = maxOf(px1, x); py1 = maxOf(py1, y) }
        fun hasPath() = px0 <= px1 && py0 <= py1
        fun resetPath() { px0 = Float.MAX_VALUE; py0 = Float.MAX_VALUE; px1 = -Float.MAX_VALUE; py1 = -Float.MAX_VALUE; clipPending = false }
        for (t in tokens) {
            if (t !is Operator) { if (t is COSBase) operands += t; continue }
            val info = OpInfo(t, operands.toList(), g.ctm, g.gs, g.style.values.toList())
            out += info
            val v = operands.map { (it as? COSNumber)?.floatValue() ?: 0f }
            when (val n = t.name) {
                "q" -> stack.addLast(g)
                "Q" -> stack.removeLastOrNull()?.let { g = it }
                "cm" -> if (v.size >= 6) { val w = v.takeLast(6); g = GState(Affine.mul(Matrix(w[0], w[1], w[2], w[3], w[4], w[5]), g.ctm), g.gs, g.style, g.clip) }
                "gs" -> (operands.lastOrNull() as? COSName)?.let { g = GState(g.ctm, g.gs + it, g.style, g.clip) }
                "m", "l" -> if (v.size >= 2) pt(v[v.size - 2], v[v.size - 1])
                "c" -> if (v.size >= 6) { pt(v[0], v[1]); pt(v[2], v[3]); pt(v[4], v[5]) }
                "v", "y" -> if (v.size >= 4) { pt(v[0], v[1]); pt(v[2], v[3]) }
                "re" -> if (v.size >= 4) { pt(v[0], v[1]); pt(v[0] + v[2], v[1] + v[3]) }
                "W", "W*" -> clipPending = true
                "n" -> { if (clipPending && hasPath()) g = GState(g.ctm, g.gs, g.style, intersect(g.clip, box(g.ctm, px0, py0, px1, py1))); resetPath() }
                "sh" -> {
                    info.userBox = g.clip
                    info.localBox = g.clip?.let { box(Affine.inverse(g.ctm), it[0], it[1], it[2], it[3]) }
                }
                "Do", "BI" -> { info.localBox = floatArrayOf(0f, 0f, 1f, 1f); info.userBox = box(g.ctm, 0f, 0f, 1f, 1f) }
                else -> when {
                    n in PAINT -> {
                        if (hasPath()) { info.localBox = floatArrayOf(px0, py0, px1, py1); info.userBox = box(g.ctm, px0, py0, px1, py1) }
                        if (clipPending && hasPath()) g = GState(g.ctm, g.gs, g.style, intersect(g.clip, box(g.ctm, px0, py0, px1, py1)))
                        resetPath()
                    }
                    n in STATE -> {
                        val st = LinkedHashMap(g.style)
                        val key = when (n) {
                            in FILL_COLOR -> { if (n == "g" || n == "rg" || n == "k") st.remove("cs"); "fill" }
                            in STROKE_COLOR -> { if (n == "G" || n == "RG" || n == "K") st.remove("CS"); "stroke" }
                            "cs" -> { st.remove("fill"); "cs" }
                            "CS" -> { st.remove("stroke"); "CS" }
                            else -> n
                        }
                        st.remove(key); st[key] = info.tokens()
                        g = GState(g.ctm, g.gs, st, g.clip)
                    }
                }
            }
            operands.clear()
        }
        return Scan(out, g.ctm)
    }

    fun kindOf(page: PDPage, o: OpInfo): Kind? = when (o.name) {
        "BI" -> Kind.IMAGE
        "Do" -> if ((o.operands.lastOrNull() as? COSName)?.let { n -> runCatching { page.resources?.isImageXObject(n) }.getOrNull() } == true) Kind.IMAGE else null
        "sh" -> Kind.SHADING
        in PAINT -> if (o.userBox != null) Kind.SHAPE else null
        else -> null
    }

    fun isImageOp(page: PDPage, o: OpInfo): Boolean = kindOf(page, o) == Kind.IMAGE
    private fun isObjectOp(page: PDPage, o: OpInfo): Boolean = kindOf(page, o) != null

    /** First op of the object painted by op [j] (the path construction of a shape). */
    fun bodyStart(ops: List<OpInfo>, j: Int): Int {
        if (ops[j].name !in PAINT) return j
        var k = j - 1
        while (k >= 0 && (ops[k].name in CONSTRUCT || ops[k].name == "W" || ops[k].name == "W*")) k--
        return k + 1
    }

    /**
     * Ops [start]..[end] that draw the object of op [body] (our group, or just its own ops from [bodyStart]), with its
     * clip ([crop] = fractions x, y, w, h of the object's local box; [ellipse] when the clip is an ellipse), opacity,
     * blend mode and the state ops inside the group ([inner], without the group's own ExtGState [ownGs]).
     */
    class Group(
        val start: Int, val end: Int, val bodyStart: Int, val body: Int, val crop: FloatArray?, val opacity: Float?, val ownGs: COSName?,
        val inner: List<List<Any>>, val blend: String? = null, val ellipse: Boolean = false,
    )

    fun groupOf(ops: List<OpInfo>, j: Int): Group {
        val bs = bodyStart(ops, j)
        val single = Group(bs, j, bs, j, null, null, null, emptyList())
        if (ops.getOrNull(j + 1)?.name != "Q" || ops.getOrNull(j + 2)?.name != "EMC") return single
        var k = bs - 1
        if (ops.getOrNull(k)?.name == "n" && (ops.getOrNull(k - 1)?.name == "W" || ops.getOrNull(k - 1)?.name == "W*")) {
            k -= 2
            while (k >= 0 && ops[k].name in CONSTRUCT) k--
        }
        val inner = ArrayList<List<Any>>()
        while (k >= 0 && ops[k].name in STATE) { inner.add(0, ops[k].tokens()); k-- }
        if (ops.getOrNull(k)?.name != "cm" || ops.getOrNull(k - 1)?.name != "q") return single
        val bdc = ops.getOrNull(k - 2) ?: return single
        if (bdc.name != "BDC" || (bdc.operands.firstOrNull() as? COSName)?.name != TAG) return single
        val d = bdc.operands.getOrNull(1) as? COSDictionary
        val alpha = (d?.getDictionaryObject(K_ALPHA) as? COSNumber)?.floatValue()
        val own = d?.getDictionaryObject(K_GS) as? COSName
        val bm = (d?.getDictionaryObject(K_BM) as? COSName)?.name
        val crop = (d?.getDictionaryObject(K_CROP) as? COSArray)?.let { a -> (0 until a.size()).map { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f }.toFloatArray() }?.takeIf { it.size == 4 }
        val ell = (d?.getDictionaryObject(K_ELL) as? COSBoolean)?.value == true
        val innerNoOwn = inner.filter { op -> !(own != null && (op.lastOrNull() as? Operator)?.name == "gs" && op.firstOrNull() == own) }
        return Group(k - 2, j + 2, bs, j, crop, alpha, own, innerNoOwn, bm, ell)
    }

    private fun op(name: String) = Operator.getOperator(name)

    /** Clip path tokens for fractions [cr] of the local box [lb]. */
    private fun clipTokens(cr: FloatArray, lb: FloatArray, ellipse: Boolean): List<Any> {
        val x0 = lb[0] + cr[0] * (lb[2] - lb[0]); val y0 = lb[1] + cr[1] * (lb[3] - lb[1])
        val w = cr[2] * (lb[2] - lb[0]); val h = cr[3] * (lb[3] - lb[1])
        val l = ArrayList<Any>()
        if (!ellipse) { listOf(x0, y0, w, h).forEach { l += COSFloat(it) }; l += op("re") }
        else {
            val k = 0.5522848f
            val cx = x0 + w / 2f; val cy = y0 + h / 2f; val rx = w / 2f; val ry = h / 2f
            fun add(vararg f: Float, o: String) { f.forEach { l += COSFloat(it) }; l += op(o) }
            add(cx + rx, cy, o = "m")
            add(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry, o = "c")
            add(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy, o = "c")
            add(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry, o = "c")
            add(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy, o = "c")
            l += op("h")
        }
        l += op("W"); l += op("n")
        return l
    }

    /** Tokens of one group: the object painted with CTM `m × (CTM where it is inserted)`. */
    private fun groupTokens(
        m: Matrix, inner: List<List<Any>>, ownGs: COSName?, opacity: Float?, blend: String?, crop: FloatArray?, ellipse: Boolean,
        localBox: FloatArray, body: List<Any>,
    ): List<Any> {
        val props = COSDictionary()
        if (opacity != null && opacity < 0.999f) props.setItem(K_ALPHA, COSFloat(opacity))
        if (blend != null) props.setItem(K_BM, COSName.getPDFName(blend))
        if (ownGs != null) props.setItem(K_GS, ownGs)
        if (crop != null) {
            props.setItem(K_CROP, COSArray().apply { crop.forEach { add(COSFloat(it)) } })
            if (ellipse) props.setItem(K_ELL, COSBoolean.TRUE)
        }
        val l = ArrayList<Any>()
        l += COSName.getPDFName(TAG); l += props; l += op("BDC")
        l += op("q")
        Affine.values(m).forEach { l += COSFloat(it) }
        l += op("cm")
        inner.forEach { l.add(it) }
        if (ownGs != null) { l += ownGs; l += op("gs") }
        if (crop != null) l.addAll(clipTokens(crop, localBox, ellipse))
        l.addAll(body)
        l += op("Q")
        l += op("EMC")
        return l
    }

    /** User-space transform equal to the display-space affine [display] (6 values) on a page with [frame]. */
    fun userOf(frame: PageFrame, display: FloatArray): Matrix = Affine.mul(Affine.mul(frame.toDisplay, Affine.fromValues(display)), frame.toUser)

    /**
     * One change of one object: an extra display-space affine (applied after the current placement), a new clip
     * ([crop] fractions x, y, w, h of the object's own box, [ellipse] for an elliptic clip; [resetCrop] removes it), a
     * new opacity (1 = opaque), blend mode ("Normal" removes it), another XObject to paint (images), new fill / stroke
     * colour 0xRRGGBB and line width (shapes).
     */
    class Change(
        val op: Int, val display: FloatArray? = null, val crop: FloatArray? = null, val resetCrop: Boolean = false,
        val opacity: Float? = null, val newName: COSName? = null, val blend: String? = null, val ellipse: Boolean? = null,
        val fill: Int? = null, val stroke: Int? = null, val lineWidth: Float? = null,
    )

    private fun ownGs(res: PDResources, a: Float?, bm: String?): COSName {
        val g = PDExtendedGraphicsState()
        if (a != null) { g.nonStrokingAlphaConstant = a; g.strokingAlphaConstant = a }
        if (bm != null) g.cosObject.setItem(COSName.getPDFName("BM"), COSName.getPDFName(bm))
        return res.add(g)
    }

    private fun rgb(c: Int, o: String): List<Any> =
        listOf(COSFloat(((c shr 16) and 0xFF) / 255f), COSFloat(((c shr 8) and 0xFF) / 255f), COSFloat((c and 0xFF) / 255f), op(o))

    private fun opName(t: List<Any>): String = (t.lastOrNull() as? Operator)?.name ?: ""

    private fun bodyTokens(ops: List<OpInfo>, g: Group): List<Any> = (g.bodyStart..g.body).flatMap { ops[it].tokens() }

    /** Apply [changes] to page [pageIndex] in one content rewrite (one document edit). */
    fun apply(doc: PDDocument, pageIndex: Int, changes: List<Change>) {
        val page = doc.getPage(pageIndex)
        val frame = PageFrame.of(page)
        val s = scan(page)
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != s.ops.size) throw StalePageException()
        val res = page.resources ?: PDResources().also { page.resources = it }
        for (ch in changes) {
            val o = s.ops.getOrNull(ch.op) ?: throw StalePageException()
            if (!isObjectOp(page, o)) throw StalePageException()
            val g = groupOf(s.ops, ch.op)
            val f2 = ch.display?.let { Affine.mul(o.ctm, userOf(frame, it)) } ?: o.ctm
            val m = Affine.mul(f2, Affine.inverse(s.ops[g.start].ctm))
            val crop = if (ch.resetCrop) null else ch.crop ?: g.crop
            val ellipse = crop != null && (ch.ellipse ?: g.ellipse)
            val alpha = (ch.opacity ?: g.opacity)?.takeIf { it < 0.999f }?.coerceIn(0f, 1f)
            val blend = (ch.blend ?: g.blend)?.takeIf { it != "Normal" }
            val own = when {
                alpha == null && blend == null -> null
                ch.opacity == null && ch.blend == null && g.ownGs != null -> g.ownGs
                else -> ownGs(res, alpha, blend)
            }
            var inner = g.inner
            ch.fill?.let { c -> inner = inner.filter { opName(it) !in FILL_COLOR && opName(it) != "cs" } + listOf(rgb(c, "rg")) }
            ch.stroke?.let { c -> inner = inner.filter { opName(it) !in STROKE_COLOR && opName(it) != "CS" } + listOf(rgb(c, "RG")) }
            ch.lineWidth?.let { w -> inner = inner.filter { opName(it) != "w" } + listOf(listOf(COSFloat(w), op("w"))) }
            val body: List<Any> = ch.newName?.let { listOf(it, op("Do")) } ?: bodyTokens(s.ops, g)
            for (i in g.start..g.end) rw.remove(i)
            rw.replace(g.start, groupTokens(m, inner, own, alpha, blend, crop, ellipse, o.localBox ?: floatArrayOf(0f, 0f, 1f, 1f), body))
        }
        rw.commit()
    }

    /** Remove the object of op [op] (the whole group or path). */
    fun deleteObject(doc: PDDocument, pageIndex: Int, op: Int) {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != s.ops.size || s.ops.getOrNull(op)?.let { isObjectOp(page, it) } != true) throw StalePageException()
        val g = groupOf(s.ops, op)
        for (i in g.start..g.end) rw.remove(i)
        rw.commit()
    }

    /**
     * Remove a clip set by the document just for this object: a `… W n` before it in the same q level, with only state
     * operators in between, whose q level ends right after the object (so nothing else is unclipped). False when there is
     * no such clip (our own clip is removed with [Change.resetCrop]).
     */
    fun clearOuterClip(doc: PDDocument, pageIndex: Int, op: Int, dryRun: Boolean = false): Boolean {
        val page = doc.getPage(pageIndex)
        val s = scan(page); val ops = s.ops
        if (ops.getOrNull(op)?.let { isObjectOp(page, it) } != true) throw StalePageException()
        val g = groupOf(ops, op)
        var k = g.start - 1
        while (k >= 0 && (ops[k].name in STATE || ops[k].name == "cm" || ops[k].name == "q")) k--
        if (k < 1 || ops[k].name != "n" || (ops[k - 1].name != "W" && ops[k - 1].name != "W*")) return false
        val nOp = k
        k -= 2
        while (k >= 0 && ops[k].name in CONSTRUCT) k--
        val clipStart = k + 1
        if (clipStart >= nOp - 1) return false
        // the clip's q level must close right after the object: the q's opened since the clip, then one more Q
        var depth = 0
        for (i in clipStart..g.end) when (ops[i].name) { "q" -> depth++; "Q" -> { depth--; if (depth < 0) return false } }
        for (i in 1..depth + 1) if (ops.getOrNull(g.end + i)?.name != "Q") return false
        if (dryRun) return true
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != ops.size) throw StalePageException()
        for (i in clipStart..nOp) rw.remove(i)
        rw.commit()
        return true
    }

    enum class Arrange { FRONT, BACK, FORWARD, BACKWARD }

    /** Move the object of op [op] in the painting order; false when it already is there. */
    fun arrange(doc: PDDocument, pageIndex: Int, op: Int, how: Arrange, dryRun: Boolean = false): Boolean {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val ops = s.ops
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != ops.size || ops.getOrNull(op)?.let { isObjectOp(page, it) } != true) throw StalePageException()
        val g = groupOf(ops, op)
        val others = ops.indices.filter { (it < g.start || it > g.end) && isObjectOp(page, ops[it]) }.map { groupOf(ops, it) }
        // where to insert: before op [at] (with the CTM there) or after it
        val (at, before) = when (how) {
            Arrange.BACK -> if (g.start == 0) return false else 0 to true
            Arrange.FRONT -> if (g.end == ops.size - 1 || others.none { it.start > g.end }) return false else ops.size - 1 to false
            Arrange.FORWARD -> (others.firstOrNull { it.start > g.end } ?: return false).end to false
            Arrange.BACKWARD -> (others.lastOrNull { it.end < g.start } ?: return false).start to true
        }
        if (dryRun) return true
        val target = if (before) ops[at].ctm else ops.getOrNull(at + 1)?.ctm ?: s.endCtm
        val tokens = movedGroup(page, ops, g, target, null)
        for (i in g.start..g.end) rw.remove(i)
        rw.replace(at, if (before) tokens + rw.original(at) else rw.original(at) + tokens)
        rw.commit()
        return true
    }

    /** State in effect around the original (colours, line, ExtGState) followed by the group's own state ops. */
    private fun fullInner(ops: List<OpInfo>, g: Group): List<List<Any>> =
        ops[g.start].style + ops[g.start].gs.map { listOf(it, op("gs")) } + g.inner

    /** A copy of group [g] for insertion where the CTM is [target], moved by the display affine [display]. */
    private fun movedGroup(page: PDPage, ops: List<OpInfo>, g: Group, target: Matrix, display: FloatArray?): List<Any> {
        val o = ops[g.body]
        val f = display?.let { Affine.mul(o.ctm, userOf(PageFrame.of(page), it)) } ?: o.ctm
        val m = Affine.mul(f, Affine.inverse(target))
        return groupTokens(m, fullInner(ops, g), g.ownGs, g.opacity, g.blend, g.crop, g.ellipse, o.localBox ?: floatArrayOf(0f, 0f, 1f, 1f), bodyTokens(ops, g))
    }

    /** Paint the object of op [op] a second time, shifted by [dx], [dy] (display points, y up), right after itself. */
    fun duplicate(doc: PDDocument, pageIndex: Int, op: Int, dx: Float, dy: Float) {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != s.ops.size || s.ops.getOrNull(op)?.let { isObjectOp(page, it) } != true) throw StalePageException()
        val g = groupOf(s.ops, op)
        val target = s.ops.getOrNull(g.end + 1)?.ctm ?: s.endCtm
        val copy = movedGroup(page, s.ops, g, target, floatArrayOf(1f, 0f, 0f, 1f, dx, dy))
        rw.replace(g.end, rw.original(g.end) + copy)
        rw.commit()
    }

    /** Append [tokens] at the end of the page content (where the CTM is [Scan.endCtm]). */
    private fun append(doc: PDDocument, page: PDPage, tokens: List<Any>) {
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount == 0) {
            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(tokens) }
            page.setContents(stream)
            return
        }
        val last = rw.operatorCount - 1
        rw.replace(last, rw.original(last) + tokens)
        rw.commit()
    }

    /** Copy the resources [tokens] name (XObjects, ExtGStates, shadings, colour spaces) from [from] into [to], renaming. */
    private fun carryResources(tokens: List<Any>, from: PDResources?, to: PDResources): List<Any> {
        if (from == null) return tokens
        val rename = HashMap<COSName, COSName>()
        val out = ArrayList<Any>(tokens.size)
        var prev: COSName? = null
        for (t in tokens) {
            if (t is Operator && prev != null) {
                val n = prev
                val mapped = rename[n] ?: runCatching {
                    when (t.name) {
                        "Do" -> from.getXObject(n)?.let { to.add(it, "X") }
                        "gs" -> from.getExtGState(n)?.let { to.add(it) }
                        "sh" -> from.getShading(n)?.let { to.add(it) }
                        "cs", "CS" -> if (n.name.startsWith("Device") || n.name == "Pattern") null else from.getColorSpace(n)?.let { to.add(it) }
                        else -> null
                    }
                }.getOrNull()
                if (mapped != null) { rename[n] = mapped; out[out.size - 1] = mapped }
            }
            out += t
            prev = t as? COSName
        }
        // our group's marker names its own ExtGState: keep it pointing at the renamed copy
        out.filterIsInstance<COSDictionary>().forEach { d -> (d.getDictionaryObject(K_GS) as? COSName)?.let { n -> rename[n]?.let { d.setItem(K_GS, it) } } }
        return out
    }

    /**
     * Paint the object of op [op] of page [pageIndex] also on [targets] (page indexes) at the same displayed spot,
     * as page content (images share their XObject).
     */
    fun placeOnPages(doc: PDDocument, pageIndex: Int, op: Int, targets: List<Int>) {
        val src = doc.getPage(pageIndex)
        val s = scan(src)
        val o = s.ops.getOrNull(op)?.takeIf { isObjectOp(src, it) } ?: throw StalePageException()
        val g = groupOf(s.ops, op)
        val toDisplay = Affine.mul(o.ctm, PageFrame.of(src).toDisplay)
        val inner = fullInner(s.ops, g)
        val body = bodyTokens(s.ops, g)
        for (t in targets.distinct()) {
            if (t == pageIndex || t !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(t)
            val res = page.resources ?: PDResources().also { page.resources = it }
            val ts = scan(page)
            val m = Affine.mul(Affine.mul(toDisplay, PageFrame.of(page).toUser), Affine.inverse(ts.endCtm))
            // resource names (image, ExtGState incl. the group's own, shading) are copied and renamed by carryResources
            val tokens = groupTokens(m, inner, g.ownGs, g.opacity, g.blend, g.crop, g.ellipse, o.localBox ?: floatArrayOf(0f, 0f, 1f, 1f), body)
            append(doc, page, carryResources(tokens, src.resources, res))
        }
    }

    // ---------------- object list (for selection) ----------------

    /** A selectable object: painting op, kind, and its box in display space (left, bottom, right, top). */
    class Obj(val op: Int, val kind: Kind, val box: FloatArray)

    fun objects(page: PDPage): List<Obj> {
        val frame = PageFrame.of(page)
        val s = scan(page)
        val full = floatArrayOf(0f, 0f, frame.width, frame.height)
        return s.ops.indices.mapNotNull { i ->
            val o = s.ops[i]
            val k = kindOf(page, o) ?: return@mapNotNull null
            val ub = o.userBox
            val b = if (ub == null) full else box(frame.toDisplay, ub[0], ub[1], ub[2], ub[3])
            val clipped = floatArrayOf(maxOf(b[0], 0f), maxOf(b[1], 0f), minOf(b[2], frame.width), minOf(b[3], frame.height))
            if (clipped[2] <= clipped[0] || clipped[3] <= clipped[1]) null else Obj(i, k, clipped)
        }
    }

    // ---------------- vector clipboard (across documents) ----------------

    /** A copied shape: its content tokens (self-contained, no resources) and their mapping to display space. */
    class VectorClip(val bytes: ByteArray, val toDisplay: FloatArray, val box: FloatArray)

    fun copyVector(doc: PDDocument, pageIndex: Int, op: Int): VectorClip? {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val o = s.ops.getOrNull(op) ?: return null
        if (kindOf(page, o) != Kind.SHAPE) return null
        val g = groupOf(s.ops, op)
        val tokens = (fullInner(s.ops, g).filter { opName(it) != "gs" && opName(it) != "cs" && opName(it) != "CS" && opName(it) != "scn" && opName(it) != "SCN" }.flatten()) + bodyTokens(s.ops, g)
        val bo = ByteArrayOutputStream(); ContentStreamWriter(bo).writeTokens(tokens)
        val frame = PageFrame.of(page)
        val ub = o.userBox ?: return null
        return VectorClip(bo.toByteArray(), Affine.values(Affine.mul(o.ctm, frame.toDisplay)), box(frame.toDisplay, ub[0], ub[1], ub[2], ub[3]))
    }

    /** Paste a copied shape on [pageIndex], moved by [dx], [dy] display points from where it was copied. */
    fun pasteVector(doc: PDDocument, pageIndex: Int, clip: VectorClip, dx: Float, dy: Float) {
        val page = doc.getPage(pageIndex)
        val tokens = PDFStreamParser(clip.bytes).also { it.parse() }.tokens
        val s = scan(page)
        val m = Affine.mul(Affine.mul(Affine.mul(Affine.fromValues(clip.toDisplay), Affine.translate(dx, dy)), PageFrame.of(page).toUser), Affine.inverse(s.endCtm))
        append(doc, page, groupTokens(m, emptyList(), null, null, null, null, false, floatArrayOf(0f, 0f, 1f, 1f), tokens))
    }

    // ---------------- replacement images (raw, pure) ----------------

    private fun deflate(b: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        DeflaterOutputStream(bo, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(b) }
        return bo.toByteArray()
    }

    /** 8-bit DeviceGray image from [w]×[h] bytes (row major, top row first). */
    fun grayImage(doc: PDDocument, w: Int, h: Int, gray: ByteArray): PDImageXObject =
        PDImageXObject(doc, ByteArrayInputStream(deflate(gray)), COSName.FLATE_DECODE, w, h, 8, PDDeviceGray.INSTANCE)

    /** 1-bit black & white image (DeviceGray, 1 = white) from per-pixel booleans ([black] true = black). */
    fun bwImage(doc: PDDocument, w: Int, h: Int, black: BooleanArray): PDImageXObject {
        val stride = (w + 7) / 8
        val out = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!black[y * w + x]) out[y * stride + x / 8] = (out[y * stride + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return PDImageXObject(doc, ByteArrayInputStream(deflate(out)), COSName.FLATE_DECODE, w, h, 1, PDDeviceGray.INSTANCE)
    }

    /** Naive (uncalibrated, no ICC profile) DeviceCMYK image from 0xRRGGBB pixels. */
    fun cmykImage(doc: PDDocument, w: Int, h: Int, rgb: IntArray): PDImageXObject {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val r = ((rgb[i] shr 16) and 0xFF) / 255f; val g = ((rgb[i] shr 8) and 0xFF) / 255f; val b = (rgb[i] and 0xFF) / 255f
            val k = 1f - maxOf(r, g, b)
            val d = (1f - k).coerceAtLeast(1e-6f)
            out[i * 4] = (((1f - r - k) / d) * 255f).toInt().coerceIn(0, 255).toByte()
            out[i * 4 + 1] = (((1f - g - k) / d) * 255f).toInt().coerceIn(0, 255).toByte()
            out[i * 4 + 2] = (((1f - b - k) / d) * 255f).toInt().coerceIn(0, 255).toByte()
            out[i * 4 + 3] = (k * 255f).toInt().coerceIn(0, 255).toByte()
        }
        // pdfbox-android has no PDDeviceCMYK class: write as RGB, then set /ColorSpace /DeviceCMYK on the stream (4 components)
        return PDImageXObject(doc, ByteArrayInputStream(deflate(out)), COSName.FLATE_DECODE, w, h, 8, com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
            .also { it.cosObject.setItem(COSName.COLORSPACE, COSName.DEVICECMYK) }
    }

    /** Paint [make]'s image in place of the image of op [op], with exactly the same placement, clip and opacity. */
    fun replacePixels(doc: PDDocument, pageIndex: Int, op: Int, make: (PDDocument) -> PDImageXObject) {
        val page = doc.getPage(pageIndex)
        val res = page.resources ?: PDResources().also { page.resources = it }
        val name = res.add(make(doc), "Im")
        apply(doc, pageIndex, listOf(Change(op, newName = name)))
    }

    // ---------------- properties ----------------

    data class ImageInfo(
        val name: String?, val inline: Boolean, val pixelW: Int, val pixelH: Int, val placedW: Float, val placedH: Float,
        val ppiX: Float, val ppiY: Float, val colorSpace: String, val bitsPerComponent: Int, val filters: List<String>,
        val softMask: Boolean, val stencil: Boolean, val cropped: Boolean, val opacity: Float, val blend: String = "Normal",
    )

    /** Placed width / height in points of the unit square under [ctm]. */
    fun placedSize(ctm: Matrix): Pair<Float, Float> { val v = Affine.values(ctm); return hypot(v[0], v[1]) to hypot(v[2], v[3]) }

    fun describe(img: PDImage, name: String?, inline: Boolean, ctm: Matrix, group: Group?): ImageInfo {
        val (pw, ph) = placedSize(ctm)
        val cropW = group?.crop?.getOrNull(2) ?: 1f; val cropH = group?.crop?.getOrNull(3) ?: 1f
        val d = img.cosObject as? COSDictionary
        val f = d?.getDictionaryObject(COSName.FILTER, COSName.F)
        val filters = when (f) {
            is COSName -> listOf(f.name)
            is COSArray -> (0 until f.size()).mapNotNull { (f.getObject(it) as? COSName)?.name }
            else -> emptyList()
        }
        val cs = runCatching { img.colorSpace?.name }.getOrNull() ?: if (img.isStencil) "Stencil" else "?"
        return ImageInfo(
            name, inline, img.width, img.height, pw * cropW, ph * cropH,
            if (pw > 0f) img.width / (pw / 72f) else 0f, if (ph > 0f) img.height / (ph / 72f) else 0f,
            cs, img.bitsPerComponent, filters,
            d?.containsKey(COSName.SMASK) == true || d?.containsKey(COSName.MASK) == true, img.isStencil,
            group?.crop != null, group?.opacity ?: 1f, group?.blend ?: "Normal",
        )
    }

    fun info(doc: PDDocument, pageIndex: Int, op: Int): ImageInfo? {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val o = s.ops.getOrNull(op) ?: return null
        val g = groupOf(s.ops, op)
        return when (o.name) {
            "Do" -> {
                val n = o.operands.lastOrNull() as? COSName ?: return null
                val x = page.resources?.getXObject(n) as? PDImageXObject ?: return null
                describe(x, n.name, false, o.ctm, g)
            }
            "BI" -> runCatching { describe(PDInlineImage(o.op.imageParameters, o.op.imageData, page.resources), null, true, o.ctm, g) }.getOrNull()
            else -> null
        }
    }

    /** Current clip (fractions), opacity, blend mode and placement of the object of op [op]. */
    class Look(val crop: FloatArray?, val opacity: Float, val blend: String, val ellipse: Boolean = false, val outerClip: Boolean = false)

    fun look(doc: PDDocument, pageIndex: Int, op: Int): Look {
        val s = scan(doc.getPage(pageIndex))
        val g = groupOf(s.ops, op)
        val outer = runCatching { clearOuterClip(doc, pageIndex, op, dryRun = true) }.getOrDefault(false)
        return Look(g.crop, g.opacity ?: 1f, g.blend ?: "Normal", g.ellipse, outer)
    }

    // ---------------- layers ----------------

    private val OCPROPS = COSName.getPDFName("OCProperties")
    private val OC = COSName.getPDFName("OC")

    fun hasLayers(doc: PDDocument): Boolean = doc.documentCatalog.cosObject.containsKey(OCPROPS)

    /**
     * Merge layers: every optional-content mark becomes plain content (shown), /OC entries go, and the catalog's
     * /OCProperties is removed. Content in hidden layers becomes visible (the user is told before).
     */
    fun mergeLayers(doc: PDDocument) {
        doc.documentCatalog.cosObject.removeItem(OCPROPS)
        val seen = HashSet<COSBase>()
        for (page in doc.pages) {
            val tokens = PDFStreamParser(page).also { it.parse() }.tokens
            if (hasOcMarks(tokens)) {
                val stream = PDStream(doc)
                stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(stripOc(tokens)) }
                page.setContents(stream)
            }
            page.resources?.let { stripResources(it, seen) }
            runCatching { page.annotations }.getOrNull()?.forEach { it.cosObject.removeItem(OC) }
        }
    }

    private fun stripResources(res: PDResources, seen: HashSet<COSBase>) {
        for (n in res.xObjectNames) {
            val x = runCatching { res.getXObject(n) }.getOrNull() ?: continue
            if (!seen.add(x.cosObject)) continue
            x.cosObject.removeItem(OC)
            if (x is PDFormXObject) {
                val tokens = runCatching { PDFStreamParser(x).also { it.parse() }.tokens }.getOrNull()
                if (tokens != null && hasOcMarks(tokens)) writeStream(x.cosObject, stripOc(tokens))
                x.resources?.let { stripResources(it, seen) }
            }
        }
    }

    private fun writeStream(s: COSStream, tokens: List<Any>) {
        s.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(tokens) }
    }

    private fun isOcBdc(tokens: List<Any>, i: Int): Boolean =
        (tokens[i] as? Operator)?.name == "BDC" && i >= 2 && tokens[i - 2] == OC

    fun hasOcMarks(tokens: List<Any>): Boolean = tokens.indices.any { isOcBdc(tokens, it) }

    /** `/OC /name BDC` → `/NveLayer BMC` (the EMC that closes it stays balanced). */
    fun stripOc(tokens: List<Any>): List<Any> {
        val out = ArrayList<Any>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            if (i + 2 < tokens.size && tokens[i] == OC && isOcBdc(tokens, i + 2)) {
                out += COSName.getPDFName("NveLayer"); out += op("BMC"); i += 3; continue
            }
            out += tokens[i]; i++
        }
        return out
    }

    // ---------------- file-level wrappers (load, edit, save to out) ----------------

    private suspend fun <T> edit(src: File, out: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc -> val r = block(doc); EditIo.save(doc, out); r }
    }

    private suspend fun <T> read(src: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use(block)
    }

    suspend fun applyFile(src: File, out: File, password: String?, page: Int, changes: List<Change>) =
        edit(src, out, password) { apply(it, page, changes) }

    suspend fun deleteFile(src: File, out: File, password: String?, page: Int, op: Int) = edit(src, out, password) { deleteObject(it, page, op) }

    suspend fun clearOuterClipFile(src: File, out: File, password: String?, page: Int, op: Int) =
        edit(src, out, password) { if (!clearOuterClip(it, page, op)) throw NothingToDo() }

    suspend fun arrangeFile(src: File, out: File, password: String?, page: Int, op: Int, how: Arrange) =
        edit(src, out, password) { if (!arrange(it, page, op, how)) throw NothingToDo() }

    /** Whether [arrangeFile] would change anything (checked before starting an undoable edit). */
    suspend fun canArrangeFile(src: File, password: String?, page: Int, op: Int, how: Arrange): Boolean =
        runCatching { read(src, password) { arrange(it, page, op, how, dryRun = true) } }.getOrDefault(false)

    suspend fun duplicateFile(src: File, out: File, password: String?, page: Int, op: Int, dx: Float, dy: Float) =
        edit(src, out, password) { duplicate(it, page, op, dx, dy) }

    suspend fun placeOnPagesFile(src: File, out: File, password: String?, page: Int, op: Int, targets: List<Int>) =
        edit(src, out, password) { placeOnPages(it, page, op, targets) }

    suspend fun pasteVectorFile(src: File, out: File, password: String?, page: Int, clip: VectorClip, dx: Float, dy: Float) =
        edit(src, out, password) { pasteVector(it, page, clip, dx, dy) }

    suspend fun copyVectorFile(src: File, password: String?, page: Int, op: Int): VectorClip? = read(src, password) { copyVector(it, page, op) }

    suspend fun replacePixelsFile(src: File, out: File, password: String?, page: Int, op: Int, make: (PDDocument) -> PDImageXObject) =
        edit(src, out, password) { replacePixels(it, page, op, make) }

    suspend fun mergeLayersFile(src: File, out: File, password: String?) = edit(src, out, password) { mergeLayers(it) }

    suspend fun hasLayersFile(src: File, password: String?): Boolean = runCatching { read(src, password) { hasLayers(it) } }.getOrDefault(false)

    suspend fun infoFile(src: File, password: String?, page: Int, op: Int): ImageInfo? = read(src, password) { info(it, page, op) }

    suspend fun lookFile(src: File, password: String?, page: Int, op: Int): Look = read(src, password) { look(it, page, op) }

    suspend fun objectsFile(src: File, password: String?, page: Int): List<Obj> = read(src, password) { objects(it.getPage(page)) }

    /** The object already is where it was asked to go (z-order) / has nothing to remove: no edit, no undo step. */
    class NothingToDo : IllegalStateException("nothing to do")
}
