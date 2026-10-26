package com.smartdocs.editor.core.view5

import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Line dash presets of the style picker (PDF /BS /D arrays, in multiples of the line width). */
enum class DashStyle(val pattern: FloatArray?) {
    SOLID(null), DASH(floatArrayOf(4f, 3f)), DASH_LONG(floatArrayOf(8f, 4f)), DOT(floatArrayOf(1f, 2f)), DASH_DOT(floatArrayOf(6f, 3f, 1f, 3f)), DASH_SHORT(floatArrayOf(2f, 2f));

    fun scaled(w: Float): FloatArray? = pattern?.map { it * w.coerceAtLeast(0.5f) }?.toFloatArray()
}

/** PDF line-ending names (/LE) supported by the picker. */
enum class LineEnd(val pdf: String) {
    NONE("None"), OPEN_ARROW("OpenArrow"), CLOSED_ARROW("ClosedArrow"), R_OPEN_ARROW("ROpenArrow"), R_CLOSED_ARROW("RClosedArrow"),
    BUTT("Butt"), SQUARE("Square"), CIRCLE("Circle"), DIAMOND("Diamond"), SLASH("Slash");

    companion object { fun of(name: String?): LineEnd = entries.firstOrNull { it.pdf == name } ?: NONE }
}

/** Small PdfBox helpers shared by the view5 writers (appearance forms, colours, line endings, labels). */
object V5Pdf {
    val IT: COSName = COSName.getPDFName("IT")
    val MEASURE: COSName = COSName.getPDFName("Measure")
    val SUBJ: COSName = COSName.getPDFName("Subj")
    val LE: COSName = COSName.getPDFName("LE")
    val CL: COSName = COSName.getPDFName("CL")
    val RD: COSName = COSName.getPDFName("RD")
    val DA: COSName = COSName.getPDFName("DA")
    val Q: COSName = COSName.getPDFName("Q")
    val SD_TYPE: COSName = COSName.getPDFName("SDMeasureType")
    val SD_VALUE: COSName = COSName.getPDFName("SDMeasureValue")

    fun rgb(c: Int) = PDColor(floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f), PDDeviceRGB.INSTANCE)

    fun form(doc: PDDocument, rect: PDRectangle): PDAppearanceStream = PDAppearanceStream(doc).apply {
        bBox = PDRectangle(0f, 0f, rect.width, rect.height)
        resources = PDResources()
        setMatrix(com.tom_roush.harmony.awt.geom.AffineTransform.getTranslateInstance(rect.lowerLeftX.toDouble(), rect.lowerLeftY.toDouble()))
    }

    fun ap(s: PDAppearanceStream) = PDAppearanceDictionary().apply { setNormalAppearance(s) }

    fun alpha(cs: PDPageContentStream, o: Float, blend: BlendMode? = null) {
        if (o >= 0.999f && blend == null) return
        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
            strokingAlphaConstant = o; nonStrokingAlphaConstant = o
            if (blend != null) setBlendMode(blend)
        })
    }

    fun stroke(cs: PDPageContentStream, c: Int) = cs.setStrokingColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    fun fill(cs: PDPageContentStream, c: Int) = cs.setNonStrokingColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    fun common(m: PDAnnotationMarkup, color: Int, opacity: Float, author: String, contents: String, subject: String? = null) {
        val now = Calendar.getInstance()
        m.titlePopup = author; m.contents = contents
        m.setCreationDate(now); m.setModifiedDate(now)
        m.color = rgb(color); m.constantOpacity = opacity
        m.isPrinted = true
        if (subject != null) m.cosObject.setString(SUBJ, subject)
        m.annotationName = "sd5-" + System.nanoTime().toString(36)
    }

    fun border(width: Float, dash: DashStyle): PDBorderStyleDictionary = PDBorderStyleDictionary().apply {
        this.width = width
        val p = dash.pattern
        if (p != null) {
            style = PDBorderStyleDictionary.STYLE_DASHED
            setDashStyle(COSArray().apply { dash.scaled(width)!!.forEach { add(COSFloat(it)) } })
        } else style = PDBorderStyleDictionary.STYLE_SOLID
    }

    fun names(vararg n: String) = COSArray().apply { n.forEach { add(COSName.getPDFName(it)) } }
    fun floats(vararg f: Float) = COSArray().apply { f.forEach { add(COSFloat(it)) } }

    /** Bounds [minX, minY, maxX, maxY] of points with [pad]. */
    fun bounds(pts: List<P>, pad: Float): PDRectangle {
        val x0 = pts.minOf { it.x }.toFloat() - pad; val y0 = pts.minOf { it.y }.toFloat() - pad
        val x1 = pts.maxOf { it.x }.toFloat() + pad; val y1 = pts.maxOf { it.y }.toFloat() + pad
        return PDRectangle(x0, y0, (x1 - x0).coerceAtLeast(1f), (y1 - y0).coerceAtLeast(1f))
    }

    /**
     * Draws a line ending at [tip] for a line arriving from [from], in form coordinates. [w] is the line width.
     * The current stroke / fill colours are used; closed shapes are filled with the fill colour.
     */
    fun drawEnd(cs: PDPageContentStream, end: LineEnd, from: P, tip: P, w: Float) {
        if (end == LineEnd.NONE) return
        val ang = atan2(tip.y - from.y, tip.x - from.x)
        val len = (w * 4f + 5f).toDouble()
        fun pt(d: Double, a: Double) = P(tip.x + d * cos(a), tip.y + d * sin(a))
        cs.setLineDashPattern(FloatArray(0), 0f)
        when (end) {
            LineEnd.OPEN_ARROW, LineEnd.CLOSED_ARROW -> {
                val a = pt(len, ang + Math.PI - 0.45); val b = pt(len, ang + Math.PI + 0.45)
                cs.moveTo(a.x.toFloat(), a.y.toFloat()); cs.lineTo(tip.x.toFloat(), tip.y.toFloat()); cs.lineTo(b.x.toFloat(), b.y.toFloat())
                if (end == LineEnd.CLOSED_ARROW) { cs.closePath(); cs.fillAndStroke() } else cs.stroke()
            }
            LineEnd.R_OPEN_ARROW, LineEnd.R_CLOSED_ARROW -> {
                val a = pt(len, ang - 0.45); val b = pt(len, ang + 0.45)
                cs.moveTo(a.x.toFloat(), a.y.toFloat()); cs.lineTo(tip.x.toFloat(), tip.y.toFloat()); cs.lineTo(b.x.toFloat(), b.y.toFloat())
                if (end == LineEnd.R_CLOSED_ARROW) { cs.closePath(); cs.fillAndStroke() } else cs.stroke()
            }
            LineEnd.BUTT -> {
                val a = pt(len * 0.6, ang + Math.PI / 2); val b = pt(len * 0.6, ang - Math.PI / 2)
                cs.moveTo(a.x.toFloat(), a.y.toFloat()); cs.lineTo(b.x.toFloat(), b.y.toFloat()); cs.stroke()
            }
            LineEnd.SLASH -> {
                val a = pt(len * 0.6, ang + Math.PI / 3); val b = pt(len * 0.6, ang + Math.PI / 3 + Math.PI)
                cs.moveTo(a.x.toFloat(), a.y.toFloat()); cs.lineTo(b.x.toFloat(), b.y.toFloat()); cs.stroke()
            }
            LineEnd.SQUARE -> {
                val h = (len * 0.4).toFloat()
                cs.addRect(tip.x.toFloat() - h, tip.y.toFloat() - h, h * 2, h * 2); cs.fillAndStroke()
            }
            LineEnd.CIRCLE -> { circle(cs, tip.x.toFloat(), tip.y.toFloat(), (len * 0.4).toFloat()); cs.fillAndStroke() }
            LineEnd.DIAMOND -> {
                val h = len * 0.5
                val p1 = pt(h, ang); val p2 = pt(h, ang + Math.PI / 2); val p3 = pt(h, ang + Math.PI); val p4 = pt(h, ang - Math.PI / 2)
                cs.moveTo(p1.x.toFloat(), p1.y.toFloat()); cs.lineTo(p2.x.toFloat(), p2.y.toFloat()); cs.lineTo(p3.x.toFloat(), p3.y.toFloat()); cs.lineTo(p4.x.toFloat(), p4.y.toFloat())
                cs.closePath(); cs.fillAndStroke()
            }
            LineEnd.NONE -> {}
        }
    }

    /** Circle path (four Béziers). */
    fun circle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r
        cs.moveTo(cx + r, cy)
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        cs.closePath()
    }

    /** Characters Helvetica (WinAnsi) can draw; anything else needs the Arabic font. */
    fun latinOnly(s: String) = s.all { it.code in 0x20..0x7E || it.code in 0xA0..0xFF }

    fun fontFor(text: String, arabic: () -> PDFont): PDFont = if (latinOnly(text)) PDType1Font.HELVETICA else arabic()

    /** Replace characters [font] has no glyph for (e.g. superscript two in the Naskh font) so showText never throws. */
    fun sanitize(font: PDFont, text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                runCatching { font.encode(c.toString()) }.isSuccess -> append(c)
                c == '²' -> append('2')
                c == '³' -> append('3')
                c == '×' -> append('x')
                else -> append('?')
            }
        }
    }

    /** Text ready for showText with [font]: unsupported glyphs replaced, Arabic shaped for non-Type1 fonts. */
    fun shown(font: PDFont, text: String): String = if (font is PDType1Font) sanitize(font, text) else ArabicShaper.shape(sanitize(font, text))

    fun width(font: PDFont, text: String, size: Float): Float =
        runCatching { font.getStringWidth(shown(font, text)) / 1000f * size }.getOrDefault(text.length * size * 0.55f)

    /**
     * Label centred at (cx, cy), rotated by [angle] radians, on a white plate so it stays readable over drawings.
     * Coordinates are form coordinates.
     */
    fun label(cs: PDPageContentStream, text: String, cx: Float, cy: Float, angle: Double, size: Float, color: Int, font: PDFont, plate: Boolean = true) {
        if (text.isBlank()) return
        val shown = shown(font, text)
        val w = width(font, text, size)
        var a = angle
        if (a > Math.PI / 2) a -= Math.PI else if (a < -Math.PI / 2) a += Math.PI    // never upside down
        cs.saveGraphicsState()
        cs.transform(Matrix.getRotateInstance(a, cx, cy))
        cs.setLineDashPattern(FloatArray(0), 0f)
        if (plate) {
            cs.setNonStrokingColor(1f, 1f, 1f)
            cs.addRect(-w / 2 - 2f, -size * 0.35f, w + 4f, size * 1.25f); cs.fill()
        }
        fill(cs, color)
        cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(-w / 2, 0f); cs.showText(shown); cs.endText()
        cs.restoreGraphicsState()
    }

    /** Copy of a COSDictionary entry list (used to read numbers safely). */
    fun num(d: COSDictionary, key: COSName): Double? = (d.getDictionaryObject(key) as? com.tom_roush.pdfbox.cos.COSNumber)?.doubleValue()
}
