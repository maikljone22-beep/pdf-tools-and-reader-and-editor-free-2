package com.smartdocs.editor.core.redact

import android.graphics.Path
import android.graphics.PointF
import com.smartdocs.editor.core.edit.Affine
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentMembershipDictionary
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream

/**
 * One glyph drawn by a text operator. [op] is the top-level operator index (same numbering as
 * `PDFStreamParser(page)` / [com.smartdocs.editor.core.edit.ContentRewriter]); [str] the string operand
 * inside that operator (0 for Tj, n-th string of a TJ array) and [idx] the glyph inside that string.
 * [box] is in PDF user space. [adv] is the TJ number that advances exactly as far as the glyph did.
 */
internal class GlyphProbe(
    val op: Int, val str: Int, val idx: Int, val box: Box, val adv: Float, val unicode: String,
    val mode: RenderingMode, val fill: Int, val alpha: Float, val order: Int,
    val hidden: Boolean, val nested: Boolean, val vertical: Boolean, val badSize: Boolean,
)

internal enum class PaintKind { FILL, STROKE, IMAGE, INLINE_IMAGE, FORM, SHADING }

/** A painting operation: path fill/stroke, image, form XObject or shading, in drawing order. */
internal class PaintProbe(
    val op: Int, val kind: PaintKind, val box: Box, val color: Int, val opaque: Boolean, val rectOnly: Boolean,
    val order: Int, val hidden: Boolean, val nested: Boolean,
    val image: PDImageXObject? = null, val ctm: Matrix? = null, val name: COSName? = null,
)

/**
 * Walks a page's content stream and records every glyph and painting operation with its operator index,
 * box, colour, alpha, order and optional-content (layer) visibility. Used by [TrueRedactor] and [Sanitizer].
 * Marked content (BDC/BMC/EMC) is tracked here because PdfBox does not resolve named `/Properties`.
 */
internal class PageInspector(page: PDPage, private val oc: PDOptionalContentProperties?) : PDFGraphicsStreamEngine(page) {
    val glyphs = ArrayList<GlyphProbe>()
    val paints = ArrayList<PaintProbe>()
    /** (op, str) → codes of that string operand: (code, byte length). */
    val codes = HashMap<Long, List<Pair<Int, Int>>>()
    var opCount = 0; private set
    var failed = false; private set

    private var currentOp = -1
    private var depth = 0
    private var inOperator = false
    private var currentOperands: List<COSBase> = emptyList()
    private var order = 0
    private var strIndex = -1
    private var glyphInStr = 0
    private var curCodes: List<Pair<Int, Int>> = emptyList()
    private val hiddenStack = ArrayDeque<Boolean>()
    private var formHidden = 0

    // current path
    private var pl = Float.MAX_VALUE; private var pb = Float.MAX_VALUE; private var pr = -Float.MAX_VALUE; private var pt = -Float.MAX_VALUE
    private var pathRectOnly = true
    private var pathEmpty = true
    private var cur = PointF(0f, 0f)

    private val hidden get() = formHidden > 0 || hiddenStack.any { it }

    fun run(page: PDPage): PageInspector { try { processPage(page) } catch (e: Exception) { failed = true }; return this }

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        val name = operator.name
        when (name) {
            "BDC" -> hiddenStack.addLast(isHiddenOc(operands))
            "BMC" -> hiddenStack.addLast(false)
            "EMC" -> hiddenStack.removeLastOrNull()
        }
        if (depth > 0 || inOperator) { super.processOperator(operator, operands); return }
        currentOp = opCount++
        currentOperands = operands
        strIndex = -1
        inOperator = true
        try { super.processOperator(operator, operands) } finally { inOperator = false }
    }

    private fun isHiddenOc(operands: List<COSBase>): Boolean {
        if (operands.size < 2 || (operands[0] as? COSName)?.name != "OC") return false
        val props = when (val p = operands[1]) {
            is COSName -> runCatching { resources?.getProperties(p) }.getOrNull()
            is COSDictionary -> com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(p)
            else -> null
        }
        return isOff(props)
    }

    private fun isOff(props: Any?): Boolean {
        val o = oc ?: return false
        return when (props) {
            is PDOptionalContentGroup -> runCatching { !o.isGroupEnabled(props) }.getOrDefault(false)
            is PDOptionalContentMembershipDictionary -> {
                val groups = props.getOCGs().filterIsInstance<PDOptionalContentGroup>()
                if (groups.isEmpty()) false else {
                    val on = groups.map { runCatching { o.isGroupEnabled(it) }.getOrDefault(true) }
                    when (props.visibilityPolicy?.name) {
                        "AllOn" -> on.any { !it }
                        "AllOff" -> on.any { it }
                        "AnyOff" -> on.all { it }
                        else -> on.none { it }   // AnyOn (default)
                    }
                }
            }
            else -> false
        }
    }

    private fun ocOf(dict: COSDictionary?): Boolean {
        val d = dict?.getDictionaryObject(COSName.getPDFName("OC")) as? COSDictionary ?: return false
        return isOff(com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(d))
    }

    override fun showForm(form: PDFormXObject) {
        val off = ocOf(form.cosObject)
        if (depth == 0) {
            val m = Affine.mul(form.matrix, graphicsState.currentTransformationMatrix)
            val bb = form.bBox
            val box = if (bb != null) RedactMath.map(Affine.values(m), Box(bb.lowerLeftX, bb.lowerLeftY, bb.upperRightX, bb.upperRightY)) else pageBox()
            paints += PaintProbe(currentOp, PaintKind.FORM, box, 0, false, false, order++, hidden || off, false)
        }
        if (off) formHidden++
        depth++
        try { super.showForm(form) } finally { depth--; if (off) formHidden-- }
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) {
        if (depth == 0) {
            val m = Affine.mul(form.matrix, graphicsState.currentTransformationMatrix)
            val bb = form.bBox
            val box = if (bb != null) RedactMath.map(Affine.values(m), Box(bb.lowerLeftX, bb.lowerLeftY, bb.upperRightX, bb.upperRightY)) else pageBox()
            paints += PaintProbe(currentOp, PaintKind.FORM, box, 0, false, false, order++, hidden, false)
        }
        depth++
        try { super.showTransparencyGroup(form) } finally { depth-- }
    }

    /** A Multiply/Darken… paint (e.g. a highlight drawn into the content) does not hide what lies beneath. */
    private fun normalBlend(): Boolean = graphicsState.blendMode.let {
        it == com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL || it == com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode.COMPATIBLE
    }

    private fun pageBox(): Box = currentPage.mediaBox.let { Box(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) }

    override fun showText(string: ByteArray) {
        if (depth == 0 && !inNested()) {
            strIndex++
            glyphInStr = 0
            val font = graphicsState.textState.font
            curCodes = if (font == null) emptyList() else runCatching {
                val list = ArrayList<Pair<Int, Int>>()
                val inp = ByteArrayInputStream(string)
                while (inp.available() > 0) { val before = inp.available(); val c = font.readCode(inp); list += c to (before - inp.available()) }
                list
            }.getOrElse { failed = true; emptyList() }
            codes[key(currentOp, strIndex)] = curCodes
        }
        super.showText(string)
    }

    /** Type3 glyph procedures run text operators of their own; they are nested content. */
    private fun inNested() = depth > 0

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
        val ts = graphicsState.textState
        val trm = Affine.values(textRenderingMatrix)
        val w0 = displacement.x
        val vertical = runCatching { font.isVertical }.getOrDefault(false)
        val box = RedactMath.map(trm, Box(0f, -0.2f, if (w0 > 0f) w0 else 0.5f, 0.8f))
        val fs = ts.fontSize
        val nested = depth > 0
        var adv = 0f
        if (!nested) {
            val c = curCodes.getOrNull(glyphInStr)
            val ws = if (c != null && c.second == 1 && c.first == 32) ts.wordSpacing else 0f
            adv = if (fs != 0f) -(w0 * 1000f + (ts.characterSpacing + ws) * 1000f / fs) else 0f
        }
        val fill = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0) and 0xFFFFFF
        glyphs += GlyphProbe(
            currentOp, strIndex, glyphInStr, box, adv, unicode ?: "",
            ts.renderingMode, fill, graphicsState.nonStrokeAlphaConstant.toFloat(), order++,
            hidden, nested, vertical, fs == 0f || w0.isNaN(),
        )
        if (!nested) glyphInStr++
        super.showGlyph(textRenderingMatrix, font, code, unicode, displacement)
    }

    override fun showType3Glyph(textRenderingMatrix: Matrix, font: com.tom_roush.pdfbox.pdmodel.font.PDType3Font, code: Int, unicode: String?, displacement: Vector) {
        depth++
        try { super.showType3Glyph(textRenderingMatrix, font, code, unicode, displacement) } finally { depth-- }
    }

    override fun drawImage(pdImage: PDImage) {
        val ctm = graphicsState.currentTransformationMatrix.clone()
        val box = RedactMath.map(Affine.values(ctm), Box(0f, 0f, 1f, 1f))
        val xo = pdImage as? PDImageXObject
        val off = xo != null && ocOf(xo.cosObject)
        val name = if (xo != null) (currentOperands.firstOrNull() as? COSName) else null
        val opaque = graphicsState.nonStrokeAlphaConstant >= 0.99 && graphicsState.softMask == null && normalBlend() && !pdImage.isStencil &&
            (xo == null || (xo.cosObject.getDictionaryObject(COSName.SMASK) == null && xo.cosObject.getDictionaryObject(COSName.MASK) == null))
        paints += PaintProbe(currentOp, if (xo != null) PaintKind.IMAGE else PaintKind.INLINE_IMAGE, box, 0, opaque, false,
            order++, hidden || off, depth > 0, xo, ctm, name)
    }

    // ---- paths (points arrive in user space) ----
    private fun extend(x: Float, y: Float) { if (x < pl) pl = x; if (x > pr) pr = x; if (y < pb) pb = y; if (y > pt) pt = y; pathEmpty = false }
    private fun resetPath() { pl = Float.MAX_VALUE; pb = Float.MAX_VALUE; pr = -Float.MAX_VALUE; pt = -Float.MAX_VALUE; pathRectOnly = true; pathEmpty = true }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { extend(p0.x, p0.y); extend(p1.x, p1.y); extend(p2.x, p2.y); extend(p3.x, p3.y); cur = PointF(p0.x, p0.y) }
    override fun moveTo(x: Float, y: Float) { extend(x, y); cur = PointF(x, y); pathRectOnly = false }
    override fun lineTo(x: Float, y: Float) { extend(x, y); cur = PointF(x, y); pathRectOnly = false }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { extend(x1, y1); extend(x2, y2); extend(x3, y3); cur = PointF(x3, y3); pathRectOnly = false }
    override fun getCurrentPoint(): PointF = cur
    override fun closePath() {}
    override fun clip(windingRule: Path.FillType) {}
    override fun endPath() { resetPath() }

    private fun paintPath(kind: PaintKind) {
        if (!pathEmpty) {
            val lw = if (kind == PaintKind.STROKE) graphicsState.lineWidth.coerceAtLeast(0.5f) / 2f else 0f
            val color = runCatching { (if (kind == PaintKind.STROKE) graphicsState.strokingColor else graphicsState.nonStrokingColor).toRGB() }.getOrDefault(0) and 0xFFFFFF
            val alpha = if (kind == PaintKind.STROKE) graphicsState.alphaConstant else graphicsState.nonStrokeAlphaConstant
            paints += PaintProbe(currentOp, kind, Box(pl - lw, pb - lw, pr + lw, pt + lw), color,
                alpha >= 0.99 && graphicsState.softMask == null && normalBlend(), pathRectOnly, order++, hidden, depth > 0)
        }
        resetPath()
    }

    override fun strokePath() = paintPath(PaintKind.STROKE)
    override fun fillPath(windingRule: Path.FillType) = paintPath(PaintKind.FILL)
    override fun fillAndStrokePath(windingRule: Path.FillType) = paintPath(PaintKind.FILL)
    override fun shadingFill(shadingName: COSName) {
        paints += PaintProbe(currentOp, PaintKind.SHADING, pageBox(), 0, false, false, order++, hidden, depth > 0)
    }

    companion object {
        fun key(op: Int, str: Int): Long = (op.toLong() shl 32) or (str.toLong() and 0xFFFFFFFFL)
    }
}
