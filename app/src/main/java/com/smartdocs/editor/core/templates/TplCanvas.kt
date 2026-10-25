package com.smartdocs.editor.core.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.NewField
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode

enum class Align { LEFT, CENTER, RIGHT }

/** A JavaScript action set for a field created by a template: calculate / format / keystroke. */
data class FieldScript(val calc: String? = null, val format: String? = null, val keystroke: String? = null)

/**
 * Small drawing helper over PdfBox for template pages: Arabic-shaped text with right/center/left alignment
 * and measuring, lines, rectangles, images, and a collector of AcroForm fields ([fields], [scripts]) that the
 * renderer adds afterwards with FormBuilder. Coordinates are PDF points, origin bottom-left.
 */
class TplCanvas(context: Context, val doc: PDDocument) : AutoCloseable {

    val font: PDFont = runCatching {
        context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { PDType0Font.load(doc, it, true) }
    }.getOrElse { PDType1Font.HELVETICA }

    val fields = ArrayList<NewField>()
    val scripts = LinkedHashMap<String, FieldScript>()
    /** Calculation order (/CO) of calculated fields. */
    val calcOrder = ArrayList<String>()

    private var cs: PDPageContentStream? = null
    var pageIndex = -1; private set
    var w = 0f; private set
    var h = 0f; private set
    private val imageCache = HashMap<Bitmap, PDImageXObject>()

    fun newPage(size: PDRectangle, landscape: Boolean): PDPage {
        cs?.close()
        val rect = if (landscape) PDRectangle(size.height, size.width) else size
        val page = PDPage(rect)
        doc.addPage(page)
        pageIndex = doc.numberOfPages - 1
        w = rect.width; h = rect.height
        cs = PDPageContentStream(doc, page)
        return page
    }

    private val c: PDPageContentStream get() = cs ?: error("no page")

    override fun close() { cs?.close(); cs = null }

    // ---------------------------------------------------------------- colours

    private fun rgb(color: Int) = floatArrayOf(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
    private fun fillColor(color: Int) { val a = rgb(color); c.setNonStrokingColor(a[0], a[1], a[2]) }
    private fun strokeColor(color: Int) { val a = rgb(color); c.setStrokingColor(a[0], a[1], a[2]) }

    /** Mix [color] with white: t = 0 → color, 1 → white. */
    fun tint(color: Int, t: Float): Int {
        val a = rgb(color)
        fun ch(v: Float) = ((v + (1f - v) * t) * 255).toInt().coerceIn(0, 255)
        return (ch(a[0]) shl 16) or (ch(a[1]) shl 8) or ch(a[2])
    }

    // ---------------------------------------------------------------- text

    fun width(text: String, size: Float): Float =
        runCatching { font.getStringWidth(ArabicShaper.shape(text)) / 1000f * size }.getOrDefault(text.length * size * 0.5f)

    /** Draw one line. [x] is the anchor: right edge for RIGHT, centre for CENTER, left edge for LEFT. */
    fun text(s: String, x: Float, y: Float, size: Float, align: Align = Align.RIGHT, color: Int = 0x212121, bold: Boolean = false) {
        if (s.isBlank()) return
        val tw = width(s, size)
        val left = when (align) { Align.RIGHT -> x - tw; Align.CENTER -> x - tw / 2f; Align.LEFT -> x }
        val cs = c
        cs.beginText()
        try {
            cs.setFont(font, size)
            fillColor(color)
            if (bold) { strokeColor(color); cs.setLineWidth(size * 0.035f); cs.setRenderingMode(RenderingMode.FILL_STROKE) }
            cs.newLineAtOffset(left, y)
            runCatching { cs.showArabic(s, ArabicShaper.shape(s)) }
            if (bold) cs.setRenderingMode(RenderingMode.FILL)
        } finally { cs.endText() }
    }

    /** Greedy word wrap (logical order) to [maxWidth]; returns the lines. */
    fun wrap(s: String, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        s.split('\n').forEach { para ->
            var line = ""
            para.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val cand = if (line.isEmpty()) word else "$line $word"
                if (width(cand, size) <= maxWidth || line.isEmpty()) line = cand else { out += line; line = word }
            }
            out += line
        }
        return out
    }

    /** Draw wrapped text; returns the y below the last line. */
    fun paragraph(s: String, x: Float, y: Float, size: Float, maxWidth: Float, align: Align = Align.RIGHT, color: Int = 0x212121, leading: Float = 1.6f): Float {
        var yy = y
        wrap(s, size, maxWidth).forEach { line -> text(line, x, yy, size, align, color); yy -= size * leading }
        return yy
    }

    // ---------------------------------------------------------------- shapes

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = 0x9E9E9E, width: Float = 0.6f, dash: FloatArray? = null) {
        strokeColor(color); c.setLineWidth(width)
        if (dash != null) c.setLineDashPattern(dash, 0f)
        c.moveTo(x1, y1); c.lineTo(x2, y2); c.stroke()
        if (dash != null) c.setLineDashPattern(floatArrayOf(), 0f)
    }

    fun rect(x: Float, y: Float, rw: Float, rh: Float, stroke: Int? = 0x9E9E9E, fill: Int? = null, lineWidth: Float = 0.8f) {
        if (fill != null) { fillColor(fill); c.addRect(x, y, rw, rh); c.fill() }
        if (stroke != null) { strokeColor(stroke); c.setLineWidth(lineWidth); c.addRect(x, y, rw, rh); c.stroke() }
    }

    /** Rounded rectangle outline/fill (radius [r]). */
    fun roundRect(x: Float, y: Float, rw: Float, rh: Float, r: Float, stroke: Int? = 0x9E9E9E, fill: Int? = null, lineWidth: Float = 0.8f) {
        val k = 0.5523f * r
        fun path() {
            c.moveTo(x + r, y); c.lineTo(x + rw - r, y); c.curveTo(x + rw - r + k, y, x + rw, y + r - k, x + rw, y + r)
            c.lineTo(x + rw, y + rh - r); c.curveTo(x + rw, y + rh - r + k, x + rw - r + k, y + rh, x + rw - r, y + rh)
            c.lineTo(x + r, y + rh); c.curveTo(x + r - k, y + rh, x, y + rh - r + k, x, y + rh - r)
            c.lineTo(x, y + r); c.curveTo(x, y + r - k, x + r - k, y, x + r, y); c.closePath()
        }
        if (fill != null) { fillColor(fill); path(); c.fill() }
        if (stroke != null) { strokeColor(stroke); c.setLineWidth(lineWidth); path(); c.stroke() }
    }

    fun circle(cx: Float, cy: Float, r: Float, stroke: Int? = 0x9E9E9E, fill: Int? = null, lineWidth: Float = 0.8f) =
        roundRect(cx - r, cy - r, 2 * r, 2 * r, r, stroke, fill, lineWidth)

    /** Draw [bmp] fitted inside the box (keeps aspect ratio, centred). */
    fun image(bmp: Bitmap, x: Float, y: Float, bw: Float, bh: Float) {
        val img = imageCache.getOrPut(bmp) { LosslessFactory.createFromImage(doc, bmp) }
        val s = minOf(bw / img.width, bh / img.height)
        val dw = img.width * s; val dh = img.height * s
        c.drawImage(img, x + (bw - dw) / 2f, y + (bh - dh) / 2f, dw, dh)
    }

    // ---------------------------------------------------------------- fields

    /** Register a field box (x, y = bottom-left). */
    fun field(type: FieldType, name: String, x: Float, y: Float, fw: Float, fh: Float, tooltip: String = "", multiline: Boolean = false, value: String = "", options: List<String> = emptyList(), script: FieldScript? = null) {
        fields += NewField(type, name, pageIndex, RectF(x, y + fh, x + fw, y), options = options, multiline = multiline, defaultValue = value, tooltip = tooltip)
        if (script != null) { scripts[name] = script; if (script.calc != null) calcOrder += name }
    }

    /** "Label: ______" row (RTL): label at the right, a text field filling the rest. Returns nothing; draws an underline. */
    fun labeledField(label: String, name: String, right: Float, left: Float, y: Float, size: Float = 11f, withField: Boolean = true, value: String = "", lineColor: Int = 0xBDBDBD) {
        text("$label:", right, y, size, Align.RIGHT, 0x424242, bold = true)
        val lw = width("$label:", size) + 6f
        val fx = left; val fRight = right - lw
        line(fx, y - 3f, fRight, y - 3f, lineColor, 0.6f)
        if (withField) field(FieldType.TEXT, name, fx, y - 4f, fRight - fx, size + 7f, tooltip = label, value = value)
        else if (value.isNotBlank()) text(value, fRight - 4f, y, size, Align.RIGHT, 0x1A237E)
    }
}
