package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
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
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceCMYK
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
 * NVE-img: page-object operations on images painted by the page's own content stream (crop, opacity, exact
 * re-placement, z-order, duplicate, multi-object transforms, replacing the pixels in place) and "merge layers".
 * Pure PDFBox (no android.*), so it is unit tested on the JVM. Operator indexes are the same as
 * [ContentRewriter] / [PageScanner] (top-level operators of the page content).
 *
 * An edited image is written as a group the next edit recognises:
 * `/NveImg <<…>> BDC q <m> cm [/gs gs…] [x y w h re W n] <paint> Q EMC` — the clip ([x y w h] in the image's unit
 * square) and opacity travel with the image when it is moved again, re-arranged or duplicated.
 */
internal object ImageEdit5 {
    const val TAG = "NveImg"
    private val K_CROP = COSName.getPDFName("NveCrop")
    private val K_ALPHA = COSName.getPDFName("NveCA")
    private val K_GS = COSName.getPDFName("NveGS")
    private val K_BM = COSName.getPDFName("NveBM")
    /** Blend modes offered for an image (ExtGState /BM). */
    val BLEND_MODES = listOf("Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "ColorDodge", "ColorBurn",
        "HardLight", "SoftLight", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity")

    /** One top-level operator: its operator token, operands, and the CTM / ExtGState names in effect when it runs. */
    class OpInfo(val op: Operator, val operands: List<COSBase>, val ctm: Matrix, val gs: List<COSName>) {
        val name: String get() = op.name
        fun tokens(): List<Any> = operands + op
    }

    class Scan(val ops: List<OpInfo>, val endCtm: Matrix)

    /** Walk the page content once, tracking q / Q / cm / gs (initial CTM = identity, as in PDFStreamEngine). */
    fun scan(page: PDPage): Scan {
        val p = PDFStreamParser(page); p.parse()
        return scanTokens(p.tokens)
    }

    fun scanTokens(tokens: List<Any>): Scan {
        val out = ArrayList<OpInfo>()
        var ctm = Affine.identity()
        var gs = emptyList<COSName>()
        val stack = ArrayDeque<Pair<Matrix, List<COSName>>>()
        val operands = ArrayList<COSBase>()
        for (t in tokens) {
            if (t is Operator) {
                out += OpInfo(t, operands.toList(), ctm, gs)
                when (t.name) {
                    "q" -> stack.addLast(ctm to gs)
                    "Q" -> stack.removeLastOrNull()?.let { ctm = it.first; gs = it.second }
                    "cm" -> if (operands.size >= 6) {
                        val v = operands.takeLast(6).map { (it as? COSNumber)?.floatValue() ?: 0f }
                        ctm = Affine.mul(Matrix(v[0], v[1], v[2], v[3], v[4], v[5]), ctm)
                    }
                    "gs" -> (operands.lastOrNull() as? COSName)?.let { gs = gs + it }
                }
                operands.clear()
            } else if (t is COSBase) operands += t
        }
        return Scan(out, ctm)
    }

    /** Ops [start]..[end] that draw the image of op [body] (our group, or just the operator), with its crop / opacity. */
    class Group(val start: Int, val end: Int, val body: Int, val crop: FloatArray?, val opacity: Float?, val ownGs: COSName?, val innerGs: List<COSName>, val blend: String? = null)

    fun groupOf(ops: List<OpInfo>, j: Int): Group {
        val single = Group(j, j, j, null, null, null, emptyList())
        if (ops.getOrNull(j + 1)?.name != "Q" || ops.getOrNull(j + 2)?.name != "EMC") return single
        var k = j - 1
        var crop: FloatArray? = null
        if (ops.getOrNull(k)?.name == "n" && ops.getOrNull(k - 1)?.name == "W" && ops.getOrNull(k - 2)?.name == "re") {
            val v = ops[k - 2].operands.map { (it as? COSNumber)?.floatValue() ?: 0f }
            if (v.size == 4) crop = v.toFloatArray()
            k -= 3
        }
        val gsNames = ArrayList<COSName>()
        while (ops.getOrNull(k)?.name == "gs") { (ops[k].operands.lastOrNull() as? COSName)?.let { gsNames.add(0, it) }; k-- }
        if (ops.getOrNull(k)?.name != "cm" || ops.getOrNull(k - 1)?.name != "q") return single
        val bdc = ops.getOrNull(k - 2) ?: return single
        if (bdc.name != "BDC" || (bdc.operands.firstOrNull() as? COSName)?.name != TAG) return single
        val d = bdc.operands.getOrNull(1) as? COSDictionary
        val alpha = (d?.getDictionaryObject(K_ALPHA) as? COSNumber)?.floatValue()
        val own = d?.getDictionaryObject(K_GS) as? COSName
        val bm = (d?.getDictionaryObject(K_BM) as? COSName)?.name
        return Group(k - 2, j + 2, j, crop, alpha, own, gsNames.filter { it != own }, bm)
    }

    fun isImageOp(page: PDPage, o: OpInfo): Boolean = when (o.name) {
        "BI" -> true
        "Do" -> (o.operands.lastOrNull() as? COSName)?.let { n -> runCatching { page.resources?.isImageXObject(n) }.getOrNull() } == true
        else -> false
    }

    /** Tokens of one group: the image painted with CTM `m × (CTM where it is inserted)`. */
    private fun groupTokens(m: Matrix, gs: List<COSName>, ownGs: COSName?, opacity: Float?, crop: FloatArray?, body: List<Any>, blend: String? = null): List<Any> {
        val props = COSDictionary()
        if (opacity != null && opacity < 0.999f) props.setItem(K_ALPHA, COSFloat(opacity))
        if (blend != null) props.setItem(K_BM, COSName.getPDFName(blend))
        if (ownGs != null) props.setItem(K_GS, ownGs)
        if (crop != null) props.setItem(K_CROP, COSArray().apply { crop.forEach { add(COSFloat(it)) } })
        val l = ArrayList<Any>()
        l += COSName.getPDFName(TAG); l += props; l += Operator.getOperator("BDC")
        l += Operator.getOperator("q")
        Affine.values(m).forEach { l += COSFloat(it) }
        l += Operator.getOperator("cm")
        gs.forEach { l += it; l += Operator.getOperator("gs") }
        if (ownGs != null) { l += ownGs; l += Operator.getOperator("gs") }
        if (crop != null) {
            crop.forEach { l += COSFloat(it) }
            l += Operator.getOperator("re"); l += Operator.getOperator("W"); l += Operator.getOperator("n")
        }
        l += body
        l += Operator.getOperator("Q")
        l += Operator.getOperator("EMC")
        return l
    }

    /** User-space transform equal to the display-space affine [display] (6 values) on a page with [frame]. */
    fun userOf(frame: PageFrame, display: FloatArray): Matrix = Affine.mul(Affine.mul(frame.toDisplay, Affine.fromValues(display)), frame.toUser)

    /**
     * One change of one image: an extra display-space affine (applied after the current placement), a new crop (unit
     * square x, y, w, h; [resetCrop] removes it), a new opacity (1 = opaque) and/or another XObject to paint.
     */
    class Change(
        val op: Int, val display: FloatArray? = null, val crop: FloatArray? = null, val resetCrop: Boolean = false,
        val opacity: Float? = null, val newName: COSName? = null,
        /** Blend mode name (see [BLEND_MODES]); "Normal" removes it. */
        val blend: String? = null,
    )

    private fun ownGs(res: PDResources, a: Float?, bm: String?): COSName {
        val g = PDExtendedGraphicsState()
        if (a != null) { g.nonStrokingAlphaConstant = a; g.strokingAlphaConstant = a }
        if (bm != null) g.cosObject.setItem(COSName.getPDFName("BM"), COSName.getPDFName(bm))
        return res.add(g)
    }

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
            if (o.name != "Do" && o.name != "BI") throw StalePageException()
            val g = groupOf(s.ops, ch.op)
            val f = o.ctm
            val f2 = ch.display?.let { Affine.mul(f, userOf(frame, it)) } ?: f
            val m = Affine.mul(f2, Affine.inverse(s.ops[g.start].ctm))
            val crop = if (ch.resetCrop) null else ch.crop ?: g.crop
            val alpha = (ch.opacity ?: g.opacity)?.takeIf { it < 0.999f }?.coerceIn(0f, 1f)
            val blend = (ch.blend ?: g.blend)?.takeIf { it != "Normal" }
            val own = when {
                alpha == null && blend == null -> null
                ch.opacity == null && ch.blend == null && g.ownGs != null -> g.ownGs
                else -> ownGs(res, alpha, blend)
            }
            val body: List<Any> = ch.newName?.let { listOf(it, Operator.getOperator("Do")) } ?: o.tokens()
            for (i in g.start..g.end) rw.remove(i)
            rw.replace(g.body, groupTokens(m, g.innerGs, own, alpha, crop, body, blend))
        }
        rw.commit()
    }

    enum class Arrange { FRONT, BACK, FORWARD, BACKWARD }

    /** Move the image of op [op] in the painting order; false when it already is there. */
    fun arrange(doc: PDDocument, pageIndex: Int, op: Int, how: Arrange): Boolean {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val ops = s.ops
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != ops.size || ops.getOrNull(op)?.let { isImageOp(page, it) } != true) throw StalePageException()
        val g = groupOf(ops, op)
        // where to insert: before op [at] (with the CTM there) or after the last op
        val images = ops.indices.filter { it != op && isImageOp(page, ops[it]) }.map { groupOf(ops, it) }
        val (at, before) = when (how) {
            Arrange.BACK -> if (g.start == 0) return false else 0 to true
            Arrange.FRONT -> if (g.end == ops.size - 1) return false else ops.size - 1 to false
            Arrange.FORWARD -> (images.firstOrNull { it.start > g.end } ?: return false).end to false
            Arrange.BACKWARD -> (images.lastOrNull { it.end < g.start } ?: return false).start to true
        }
        val target = if (before) ops[at].ctm else ops.getOrNull(at + 1)?.ctm ?: s.endCtm
        val tokens = movedGroup(page, ops, g, target, null)
        for (i in g.start..g.end) rw.remove(i)
        rw.replace(at, if (before) tokens + rw.original(at) else rw.original(at) + tokens)
        rw.commit()
        return true
    }

    /** A copy of group [g] for insertion where the CTM is [target], moved by the display affine [display]. */
    private fun movedGroup(page: PDPage, ops: List<OpInfo>, g: Group, target: Matrix, display: FloatArray?): List<Any> {
        val o = ops[g.body]
        val f = display?.let { Affine.mul(o.ctm, userOf(PageFrame.of(page), it)) } ?: o.ctm
        val m = Affine.mul(f, Affine.inverse(target))
        // ExtGState in effect around the original (fill opacity, blend mode) plus the group's own
        val gs = ops[g.start].gs + g.innerGs
        return groupTokens(m, gs, g.ownGs, g.opacity, g.crop, o.tokens(), g.blend)
    }

    /** Paint the image of op [op] a second time, shifted by [dx], [dy] (display points, y up), right after itself. */
    fun duplicate(doc: PDDocument, pageIndex: Int, op: Int, dx: Float, dy: Float) {
        val page = doc.getPage(pageIndex)
        val s = scan(page)
        val rw = ContentRewriter(doc, page)
        if (rw.operatorCount != s.ops.size || s.ops.getOrNull(op)?.let { it.name == "Do" || it.name == "BI" } != true) throw StalePageException()
        val g = groupOf(s.ops, op)
        val target = s.ops.getOrNull(g.end + 1)?.ctm ?: s.endCtm
        val copy = movedGroup(page, s.ops, g, target, floatArrayOf(1f, 0f, 0f, 1f, dx, dy))
        rw.replace(g.end, rw.original(g.end) + copy)
        rw.commit()
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

    /** Naive (uncalibrated, no ICC) DeviceCMYK image from 0xRRGGBB pixels. */
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
        return PDImageXObject(doc, ByteArrayInputStream(deflate(out)), COSName.FLATE_DECODE, w, h, 8, PDDeviceCMYK.INSTANCE)
    }

    /** Paint [make]'s image in place of the image of op [op], with exactly the same placement, crop and opacity. */
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

    /** Current crop (unit square), opacity and blend mode of the image of op [op]. */
    class Look(val crop: FloatArray?, val opacity: Float, val blend: String)

    fun look(doc: PDDocument, pageIndex: Int, op: Int): Look {
        val s = scan(doc.getPage(pageIndex))
        val g = groupOf(s.ops, op)
        return Look(g.crop, g.opacity ?: 1f, g.blend ?: "Normal")
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
            if (hasOcMarks(PDFStreamParser(page).also { it.parse() }.tokens)) {
                val tokens = PDFStreamParser(page).also { it.parse() }.tokens
                val stream = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc)
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
                out += COSName.getPDFName("NveLayer"); out += Operator.getOperator("BMC"); i += 3; continue
            }
            out += tokens[i]; i++
        }
        return out
    }

    // ---------------- file-level wrappers (load, edit, save to out) ----------------

    private suspend fun <T> edit(src: File, out: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc -> val r = block(doc); EditIo.save(doc, out); r }
    }

    suspend fun applyFile(src: File, out: File, password: String?, page: Int, changes: List<Change>) =
        edit(src, out, password) { apply(it, page, changes) }

    suspend fun arrangeFile(src: File, out: File, password: String?, page: Int, op: Int, how: Arrange) =
        edit(src, out, password) { if (!arrange(it, page, op, how)) throw NothingToDo() }

    suspend fun duplicateFile(src: File, out: File, password: String?, page: Int, op: Int, dx: Float, dy: Float) =
        edit(src, out, password) { duplicate(it, page, op, dx, dy) }

    suspend fun replacePixelsFile(src: File, out: File, password: String?, page: Int, op: Int, make: (PDDocument) -> PDImageXObject) =
        edit(src, out, password) { replacePixels(it, page, op, make) }

    suspend fun mergeLayersFile(src: File, out: File, password: String?) = edit(src, out, password) { mergeLayers(it) }

    suspend fun hasLayersFile(src: File, password: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { EditIo.load(src, password).use { hasLayers(it) } }.getOrDefault(false)
    }

    suspend fun infoFile(src: File, password: String?, page: Int, op: Int): ImageInfo? = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { info(it, page, op) }
    }

    suspend fun lookFile(src: File, password: String?, page: Int, op: Int): Look = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { look(it, page, op) }
    }

    /** The object already is where it was asked to go (z-order): no edit, no undo step. */
    class NothingToDo : IllegalStateException("nothing to do")
}
