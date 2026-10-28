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
    /** E10: operators of deeper (nested) forms get ids from here down, registered in [PageScanner.nested]. */
    const val NESTED_BASE = -2_100_000_000
    fun key(doOp: Int, formOp: Int): Int = -(doOp * K + formOp) - 2
    fun isForm(op: Int) = op <= -2
    /** E10: an operator inside a form XObject painted by another form (see [NestedOp]). */
    fun isNested(op: Int) = op <= NESTED_BASE
    fun doOp(op: Int): Int = (-(op + 2)) / K
    fun formOp(op: Int): Int = (-(op + 2)) % K
}

/**
 * E10: an operator inside a nested form XObject. [path] = the `Do` operators from the page down: path[0] is the page
 * operator painting forms[0], path[i] the operator of forms[i-1] painting forms[i]; [formOp] is the operator index
 * inside the innermost form (forms.last()).
 */
internal class NestedOp(val path: IntArray, val forms: List<PDFormXObject>, val formOp: Int)

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
    /**
     * E10: glyphs of mirrored / skewed rows (negative scale or shear of the text matrix), grouped by their frame:
     * the 4 linear values (rounded) mapping the row's upright frame to display space, see [GlyphFrames.of].
     */
    val framed = HashMap<List<Float>, ArrayList<RawGlyph>>()
    /** NVE-text: first-level form XObjects painted by the page, by the page operator that paints them. */
    val forms = HashMap<Int, PDFormXObject>()
    /** E10: operators of nested form XObjects (keys from [FormOps.NESTED_BASE] down). */
    val nested = HashMap<Int, NestedOp>()
    private val nestedKeys = HashMap<String, Int>()
    /** One open form XObject: its paint path from the page and the operator count inside it. */
    private class Level(val form: PDFormXObject, val path: IntArray) { var count = 0; var cur = -1; var busy = false }
    private val levels = ArrayList<Level>()
    /** Recovers Unicode for glyphs PdfBox cannot map (fonts without /ToUnicode, private-use codes). */
    private val decoder = GlyphDecoder()
    var opCount = 0; private set
    private var currentOp = -1
    private var depth = 0
    private var currentOperands: List<COSBase> = emptyList()

    /** True while a top-level operator runs: operators it triggers itself (' → T*, " → Tw Tc ', form content) do not count. */
    private var inOperator = false

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        // NVE-text / E10: operators of a form get their own index inside that form (every nesting level counts its own)
        if (depth > 0) {
            val lv = levels.lastOrNull()
            if (lv == null || lv.busy) { super.processOperator(operator, operands); return }
            lv.cur = lv.count++
            val name = operator.name
            val isText = name == "Tj" || name == "TJ" || name == "'" || name == "\""
            val before = if (isText) textMatrix?.clone() else null
            lv.busy = true
            try { super.processOperator(operator, operands) } finally { lv.busy = false }
            if (isText) recordTextOp(keyOf(lv), name, before, operands)
            return
        }
        if (inOperator) { super.processOperator(operator, operands); return }
        currentOp = opCount++
        currentOperands = operands
        val name = operator.name
        val isText = name == "Tj" || name == "TJ" || name == "'" || name == "\""
        val before = if (isText) textMatrix?.clone() else null
        inOperator = true
        try { super.processOperator(operator, operands) } finally { inOperator = false }
        if (isText) recordTextOp(currentOp, name, before, operands)
    }

    /** Operator id of the current operator of form level [lv]: [FormOps.key] for first-level forms, a [NestedOp] id deeper. */
    private fun keyOf(lv: Level): Int {
        if (levels.size == 1) return FormOps.key(lv.path[0], lv.cur)
        val k = lv.path.joinToString(",") + ":" + lv.cur
        return nestedKeys.getOrPut(k) {
            val id = FormOps.NESTED_BASE - nested.size
            nested[id] = NestedOp(lv.path, levels.map { it.form }, lv.cur)
            id
        }
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

    override fun showForm(form: PDFormXObject) = enterForm(form) { super.showForm(form) }

    override fun showTransparencyGroup(form: PDTransparencyGroup) = enterForm(form) { super.showTransparencyGroup(form) }

    /**
     * E10: every form painted by a counted operator (a `Do` of the page or of an open form) opens a level whose
     * operators are counted. Anything else (patterns, soft masks, forms deeper than [MAX_DEPTH]) runs without a level,
     * so its operators and glyphs are not recorded.
     */
    private fun enterForm(form: PDFormXObject, run: () -> Unit) {
        val parent = levels.lastOrNull()
        val path = when {
            depth == 0 && inOperator -> intArrayOf(currentOp).also { forms[currentOp] = form }
            depth > 0 && parent != null && parent.busy && levels.size == depth && depth < MAX_DEPTH -> parent.path + parent.cur
            else -> null
        }
        depth++
        if (path == null) {
            val saved = ArrayList(levels); levels.clear()
            try { run() } finally { depth--; levels.addAll(saved) }
            return
        }
        levels += Level(form, path)
        try { run() } finally { depth--; levels.removeAt(levels.size - 1) }
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
        // Type3 glyph procedures are not executed (super is not called): their glyphs are recorded like any other
        // NVE-text / E10: glyphs of forms (nested ones too) are recorded under their form operator key
        val op = if (depth > 0) {
            val lv = levels.lastOrNull() ?: return
            if (levels.size != depth || lv.cur < 0) return
            keyOf(lv)
        } else currentOp
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
        if (up[0] > 0f && abs(up[1]) <= 0.08f * sx && up[3] > 0f) {
            rotated.getOrPut(angle) { ArrayList() } += RawGlyph(op, text, up[4], up[5], if (w > 0f) w else sy * 0.5f, sy, font, color, up[2] / sy, code, trm)
            return
        }
        // E10: mirrored / skewed text: measured in the frame that makes it upright (see GlyphFrames.of)
        val f = GlyphFrames.of(trm) ?: return
        val (x, y) = Affine.apply(Affine.inverse(Affine.of(f[0], f[1], f[2], f[3], 0f, 0f)), trm[4], trm[5])
        framed.getOrPut(f) { ArrayList() } += RawGlyph(op, text, x, y, if (w > 0f) w else sy * 0.5f, sy, font, color, 0f, code, trm)
    }

    /** Every recorded glyph (upright, rotated and mirrored / skewed rows). */
    fun allGlyphs(): List<RawGlyph> = glyphs + rotated.values.flatten() + framed.values.flatten()

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
        /** E10: forms nested deeper than this are not editable (their glyphs are not recorded). */
        const val MAX_DEPTH = 6

        fun number(b: COSBase?): Float = (b as? COSNumber)?.floatValue() ?: 0f
    }
}

/** E10: frames of mirrored / skewed glyph rows. */
internal object GlyphFrames {
    /**
     * E10: the frame of a glyph drawn with the display-space text rendering matrix [trm] (6 values): its linear
     * part with each row scaled to length 1 (the x row by the glyph's width scale, the y row by its height),
     * rounded to 0.02 so a whole row shares it. In that frame the glyph is upright at its own size; frame → display
     * is the returned 2×2 matrix (a, b, c, d). Null when degenerate. Pure: unit tested.
     */
    fun of(trm: FloatArray): List<Float>? {
        val sx = hypot(trm[0], trm[1]); val sy = hypot(trm[2], trm[3])
        if (sx <= 1e-4f || sy <= 1e-4f) return null
        fun r(v: Float) = Math.round(v * 50f) / 50f + 0f      // + 0f: no -0.0 keys
        val f = listOf(r(trm[0] / sx), r(trm[1] / sx), r(trm[2] / sy), r(trm[3] / sy))
        return if (abs(f[0] * f[3] - f[1] * f[2]) < 0.05f) null else f
    }
}
