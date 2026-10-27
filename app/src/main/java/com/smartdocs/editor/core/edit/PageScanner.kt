package com.smartdocs.editor.core.edit

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import kotlin.math.abs
import kotlin.math.hypot

/** One drawn glyph in display space. [x] = left of the glyph on the baseline [y]; [w] = advance width. */
internal class RawGlyph(
    val op: Int, val text: String, val x: Float, val y: Float, val w: Float, val size: Float,
    val font: PDFont?, val color: Int, val shear: Float,
    // NVE-text: glyph code and its text rendering matrix in display space (text to outlines)
    val code: Int = 0, val trm: FloatArray? = null,
)

/**
 * NVE-text: operators inside a first-level form XObject are identified by a negative id combining the page operator
 * that paints the form (`/Fm Do`) and the operator index inside the form's own content stream.
 */
internal object FormOps {
    private const val K = 1_000_000
    fun key(doOp: Int, formOp: Int): Int = -(doOp * K + formOp) - 2
    fun isForm(op: Int) = op <= -2
    fun doOp(op: Int): Int = (-(op + 2)) / K
    fun formOp(op: Int): Int = (-(op + 2)) % K
}

/** One image painted by the page's own content stream (not inside a form XObject). */
internal class RawImage(
    val op: Int, val name: String?, val inline: Boolean, val ctm: Matrix, val boxDisplay: android.graphics.RectF,
    val pixelW: Int, val pixelH: Int, val image: PDImage,
)

/** What a text-showing operator did to the text matrix, so it can be replaced by an invisible advance. */
internal class TextOpInfo(val name: String, val tx: Float, val ty: Float, val fontSize: Float, val hScale: Float, val vertical: Boolean, val operands: List<COSBase>)

/**
 * Walks a page's content stream and records glyphs, images and text-operator advances, each tagged with the
 * index of the top-level operator that produced it. Operators of nested streams (form XObjects, Type3 glyph
 * procedures, patterns) do not count, so the indexes match `PDFStreamParser(page)` tokens one to one.
 */
internal class PageScanner(page: PDPage, private val frame: PageFrame) : PDFGraphicsStreamEngine(page) {
    val glyphs = ArrayList<RawGlyph>()
    val images = ArrayList<RawImage>()
    val textOps = HashMap<Int, TextOpInfo>()
    /** NVE-text: glyphs of rotated text rows, by rotation angle (degrees, ccw, rounded to 0.5°), in that row's upright frame. */
    val rotated = HashMap<Float, ArrayList<RawGlyph>>()
    /** NVE-text: first-level form XObjects painted by the page, by the page operator that paints them. */
    val forms = HashMap<Int, PDFormXObject>()
    private var formDo = -1
    private var formOpCount = 0
    private var inFormOperator = false
    private var curFormOp = -1
    /** Recovers Unicode for glyphs PdfBox cannot map (fonts without /ToUnicode, private-use codes). */
    private val decoder = GlyphDecoder()
    var opCount = 0; private set
    private var currentOp = -1
    private var depth = 0
    private var currentOperands: List<COSBase> = emptyList()

    /** True while a top-level operator runs: operators it triggers itself (' → T*, " → Tw Tc ', form content) do not count. */
    private var inOperator = false

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        // NVE-text: operators of a first-level form get their own index inside that form
        if (depth == 1 && formDo >= 0 && !inFormOperator) {
            curFormOp = formOpCount++
            val name = operator.name
            val isText = name == "Tj" || name == "TJ" || name == "'" || name == "\""
            val before = if (isText) textMatrix?.clone() else null
            inFormOperator = true
            try { super.processOperator(operator, operands) } finally { inFormOperator = false }
            if (isText) recordTextOp(FormOps.key(formDo, curFormOp), name, before, operands)
            return
        }
        if (depth > 0 || inOperator) { super.processOperator(operator, operands); return }
        currentOp = opCount++
        currentOperands = operands
        val name = operator.name
        val isText = name == "Tj" || name == "TJ" || name == "'" || name == "\""
        val before = if (isText) textMatrix?.clone() else null
        inOperator = true
        try { super.processOperator(operator, operands) } finally { inOperator = false }
        if (isText) recordTextOp(currentOp, name, before, operands)
    }

    private fun recordTextOp(key: Int, name: String, before: Matrix?, operands: List<COSBase>) {
        runCatching {
            val after = textMatrix ?: return@runCatching
            val base = if (name == "'" || name == "\"") textLineMatrix else before
            if (base == null) return@runCatching
            val b = Affine.values(base); val a = Affine.values(after)
            val de = a[4] - b[4]; val df = a[5] - b[5]
            // solve [tx ty] × [[b0 b1][b2 b3]] = [de df]
            val det = b[0] * b[3] - b[1] * b[2]
            val tx = if (abs(det) > 1e-9f) (de * b[3] - df * b[2]) / det else 0f
            val ty = if (abs(det) > 1e-9f) (df * b[0] - de * b[1]) / det else 0f
            val ts = graphicsState.textState
            textOps[key] = TextOpInfo(name, tx, ty, ts.fontSize, ts.horizontalScaling / 100f, ts.font?.isVertical == true, operands.toList())
        }
    }

    override fun showForm(form: PDFormXObject) {
        val top = depth == 0
        if (top) { formDo = currentOp; formOpCount = 0; forms[currentOp] = form }
        depth++
        try { super.showForm(form) } finally { depth--; if (top) formDo = -1 }
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) {
        val top = depth == 0
        if (top) { formDo = currentOp; formOpCount = 0; forms[currentOp] = form }
        depth++
        try { super.showTransparencyGroup(form) } finally { depth--; if (top) formDo = -1 }
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
        // Type3 glyph procedures are not executed (super is not called): their glyphs are recorded like any other
        // NVE-text: glyphs of first-level forms are recorded too, under their form operator key
        val inForm = depth == 1 && formDo >= 0
        if (depth > 0 && !inForm) return
        val op = if (inForm) FormOps.key(formDo, curFormOp) else currentOp
        val mode = graphicsState.textState.renderingMode
        if (mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP) return     // invisible (OCR layer)
        val full = Affine.mul(textRenderingMatrix, frame.toDisplay)
        val trm = Affine.values(full)
        val sx = hypot(trm[0], trm[1]); val sy = hypot(trm[2], trm[3])
        if (sx <= 0.01f || sy <= 0.01f) return
        val color = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0) and 0xFFFFFF
        val w = displacement.x * sx
        val text = decoder.text(font, code, unicode)
        // upright, left-to-right glyph rows (italic shear is allowed)
        if (trm[0] > 0f && abs(trm[1]) <= 0.08f * sx && trm[3] > 0f) {
            glyphs += RawGlyph(op, text, trm[4], trm[5], if (w > 0f) w else sy * 0.5f, sy, font, color, trm[2] / sy, code, trm)
            return
        }
        // NVE-text: a rotated row, measured in its own upright frame (rotated back by its angle)
        val deg = Math.toDegrees(kotlin.math.atan2(trm[1].toDouble(), trm[0].toDouble())).toFloat()
        val angle = Math.round(deg * 2f) / 2f
        val up = Affine.values(Affine.mul(full, Affine.rotateAround(-angle, 0f, 0f)))
        if (up[0] <= 0f || abs(up[1]) > 0.08f * sx || up[3] <= 0f) return       // mirrored / skewed: not editable
        rotated.getOrPut(angle) { ArrayList() } += RawGlyph(op, text, up[4], up[5], if (w > 0f) w else sy * 0.5f, sy, font, color, up[2] / sy, code, trm)
    }

    override fun drawImage(pdImage: PDImage) {
        if (depth > 0) return
        val ctm = graphicsState.currentTransformationMatrix.clone()
        val box = Affine.unitBox(Affine.mul(ctm, frame.toDisplay))
        val name = if (pdImage is PDImageXObject) (currentOperands.firstOrNull() as? COSName)?.name else null
        images += RawImage(currentOp, name, pdImage !is PDImageXObject, ctm, box, pdImage.width, pdImage.height, pdImage)
    }

    // ---- path construction / painting is irrelevant here ----
    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
    override fun clip(windingRule: Path.FillType) {}
    override fun moveTo(x: Float, y: Float) {}
    override fun lineTo(x: Float, y: Float) {}
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    override fun getCurrentPoint(): PointF = PointF(0f, 0f)
    override fun closePath() {}
    override fun endPath() {}
    override fun strokePath() {}
    override fun fillPath(windingRule: Path.FillType) {}
    override fun fillAndStrokePath(windingRule: Path.FillType) {}
    override fun shadingFill(shadingName: COSName) {}

    companion object {
        fun number(b: COSBase?): Float = (b as? COSNumber)?.floatValue() ?: 0f
    }
}
