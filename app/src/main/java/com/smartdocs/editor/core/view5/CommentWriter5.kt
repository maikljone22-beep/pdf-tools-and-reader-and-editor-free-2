package com.smartdocs.editor.core.view5

import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import java.util.Locale
import kotlin.math.abs

/** Axis-aligned box in PDF space (x0 < x1, y0 < y1). */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    fun pd() = PDRectangle(x0, y0, w, h)
    companion object { fun of(a: P, b: P) = Box(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat(), maxOf(a.x, b.x).toFloat(), maxOf(a.y, b.y).toFloat()) }
}

/** A text box, typewriter text or callout. [tip] and [knee] make it a callout (FreeTextCallout). */
data class TextBoxSpec(
    val page: Int,
    val box: Box,
    val text: String,
    val fontSize: Float = 12f,
    val textColor: Int = 0x212121,
    val borderColor: Int? = 0xE53935,
    val borderWidth: Float = 1f,
    val fill: Int? = 0xFFFFFF,
    val dash: DashStyle = DashStyle.SOLID,
    val rtl: Boolean = false,
    val align: Int = 0,                    // 0 start, 1 centre, 2 end (start = right for RTL)
    val tip: P? = null,
    val knee: P? = null,
    val lineEnd: LineEnd = LineEnd.OPEN_ARROW,
    val typewriter: Boolean = false,
    val author: String = "",
    val opacity: Float = 1f,
)

enum class MarkKind { TICK, CROSS, DOT, QUESTION }

/**
 * Writers for the comment tools of view5: callout with arrow, typewriter / text box, tick and cross marks,
 * area highlight, lines and arrows with a dash style and line endings, plus restyling stored lines.
 */
object CommentWriter5 {

    private fun fmt(v: Float) = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** Greedy word wrap measured on shaped text so Arabic widths are right. */
    fun wrap(text: String, font: PDFont, size: Float, maxW: Float): List<String> {
        val out = ArrayList<String>()
        text.replace("\r", "").split('\n').forEach { para ->
            if (para.isBlank()) { out += ""; return@forEach }
            var cur = ""
            para.split(' ').forEach { w ->
                val cand = if (cur.isEmpty()) w else "$cur $w"
                if (cur.isNotEmpty() && V5Pdf.width(font, cand, size) > maxW) { out += cur; cur = w } else cur = cand
            }
            out += cur
        }
        return out
    }

    private fun boxEdgePoint(b: Box, toward: P): P {
        val cx = (b.x0 + b.x1) / 2.0; val cy = (b.y0 + b.y1) / 2.0
        val dx = toward.x - cx; val dy = toward.y - cy
        return if (abs(dx) * b.h > abs(dy) * b.w) P(if (dx > 0) b.x1.toDouble() else b.x0.toDouble(), cy)
        else P(cx, if (dy > 0) b.y1.toDouble() else b.y0.toDouble())
    }

    fun writeTextBox(doc: PDDocument, page: PDPage, s: TextBoxSpec, arabicFont: () -> PDFont) {
        val callout = s.tip != null
        val knee = s.knee ?: s.tip?.let { t -> P((t.x + (s.box.x0 + s.box.x1) / 2) / 2, (t.y + (s.box.y0 + s.box.y1) / 2) / 2) }
        val edge = if (callout) boxEdgePoint(s.box, knee!!) else null
        val pts = mutableListOf(P(s.box.x0.toDouble(), s.box.y0.toDouble()), P(s.box.x1.toDouble(), s.box.y1.toDouble()))
        if (callout) { pts += s.tip!!; pts += knee!! }
        val pad = if (callout) s.borderWidth * 4 + 8f else s.borderWidth + 1f
        val rect = V5Pdf.bounds(pts, pad)
        val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "FreeText") }
        V5Pdf.common(an, s.borderColor ?: s.textColor, s.opacity, s.author, s.text, if (callout) "Callout" else if (s.typewriter) "Typewriter" else "Text Box")
        an.rectangle = rect
        val cos = an.cosObject
        cos.setName(V5Pdf.IT, when { callout -> "FreeTextCallout"; s.typewriter -> "FreeTextTypeWriter"; else -> "FreeText" })
        val r = (s.textColor shr 16 and 0xFF) / 255f; val g = (s.textColor shr 8 and 0xFF) / 255f; val b = (s.textColor and 0xFF) / 255f
        cos.setString(V5Pdf.DA, "/Helv ${fmt(s.fontSize)} Tf ${fmt(r)} ${fmt(g)} ${fmt(b)} rg")
        cos.setString(COSName.getPDFName("DS"), "font: Helvetica ${fmt(s.fontSize)}pt; text-align:${if (s.align == 1) "center" else if ((s.align == 2) != s.rtl) "right" else "left"}; color:#${String.format(Locale.US, "%06X", s.textColor and 0xFFFFFF)}")
        cos.setInt(V5Pdf.Q, when (s.align) { 1 -> 1; 2 -> if (s.rtl) 0 else 2; else -> if (s.rtl) 2 else 0 })
        cos.setItem(V5Pdf.RD, V5Pdf.floats(s.box.x0 - rect.lowerLeftX, s.box.y0 - rect.lowerLeftY, rect.upperRightX - s.box.x1, rect.upperRightY - s.box.y1))
        if (s.borderColor != null && !s.typewriter) an.borderStyle = V5Pdf.border(s.borderWidth, s.dash)
        else an.borderStyle = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary().apply { width = 0f }
        s.fill?.takeIf { !s.typewriter }?.let { an.interiorColor = V5Pdf.rgb(it) }
        if (callout) {
            cos.setItem(V5Pdf.CL, V5Pdf.floats(s.tip!!.x.toFloat(), s.tip.y.toFloat(), knee!!.x.toFloat(), knee.y.toFloat(), edge!!.x.toFloat(), edge.y.toFloat()))
            cos.setName(V5Pdf.LE, s.lineEnd.pdf)
        }
        val f = V5Pdf.form(doc, rect)
        val ox = rect.lowerLeftX; val oy = rect.lowerLeftY
        val bx0 = s.box.x0 - ox; val by0 = s.box.y0 - oy
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.alpha(cs, s.opacity)
            if (s.fill != null && !s.typewriter) { V5Pdf.fill(cs, s.fill); cs.addRect(bx0, by0, s.box.w, s.box.h); cs.fill() }
            if (s.borderColor != null && !s.typewriter && s.borderWidth > 0f) {
                V5Pdf.stroke(cs, s.borderColor); cs.setLineWidth(s.borderWidth)
                s.dash.scaled(s.borderWidth)?.let { cs.setLineDashPattern(it, 0f) }
                cs.addRect(bx0, by0, s.box.w, s.box.h); cs.stroke()
                cs.setLineDashPattern(FloatArray(0), 0f)
            }
            if (callout) {
                val lc = s.borderColor ?: s.textColor
                V5Pdf.stroke(cs, lc); V5Pdf.fill(cs, lc); cs.setLineWidth(s.borderWidth.coerceAtLeast(0.8f))
                val t = P(s.tip!!.x - ox, s.tip.y - oy); val k = P(knee!!.x - ox, knee.y - oy); val e = P(edge!!.x - ox, edge.y - oy)
                cs.moveTo(e.x.toFloat(), e.y.toFloat()); cs.lineTo(k.x.toFloat(), k.y.toFloat()); cs.lineTo(t.x.toFloat(), t.y.toFloat()); cs.stroke()
                V5Pdf.drawEnd(cs, s.lineEnd, k, t, s.borderWidth.coerceAtLeast(0.8f))
            }
            // text
            val inner = 3f
            val probe = if (V5Pdf.latinOnly(s.text)) PDType1Font.HELVETICA else arabicFont()
            val lines = wrap(s.text, probe, s.fontSize, (s.box.w - inner * 2).coerceAtLeast(10f))
            V5Pdf.fill(cs, s.textColor)
            var y = by0 + s.box.h - inner - s.fontSize
            for (line in lines) {
                if (y < by0 - s.fontSize * 0.2f) break
                if (line.isNotEmpty()) {
                    val font = V5Pdf.fontFor(line, arabicFont)
                    val w = V5Pdf.width(font, line, s.fontSize)
                    val lineRtl = s.rtl || ReflowBlocks.isRtl(line)
                    val x = when {
                        s.align == 1 -> bx0 + (s.box.w - w) / 2
                        (s.align == 2) != lineRtl -> bx0 + s.box.w - inner - w
                        else -> bx0 + inner
                    }
                    cs.beginText(); cs.setFont(font, s.fontSize); cs.newLineAtOffset(x, y)
                    cs.showText(if (font is PDType1Font) line else ArabicShaper.shape(line)); cs.endText()
                }
                y -= s.fontSize * 1.2f
            }
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    /** Tick / cross / dot / question marks as stamps with our drawing (size = box side in points). */
    fun writeMark(doc: PDDocument, page: PDPage, kind: MarkKind, center: P, size: Float, color: Int, author: String) {
        val h = size / 2
        val rect = PDRectangle(center.x.toFloat() - h, center.y.toFloat() - h, size, size)
        val an = PDAnnotationRubberStamp()
        V5Pdf.common(an, color, 1f, author, "", when (kind) { MarkKind.TICK -> "Check"; MarkKind.CROSS -> "Cross"; MarkKind.DOT -> "Dot"; MarkKind.QUESTION -> "Question" })
        an.name = when (kind) { MarkKind.TICK -> "SDTick"; MarkKind.CROSS -> "SDCross"; MarkKind.DOT -> "SDDot"; MarkKind.QUESTION -> "SDQuestion" }
        an.rectangle = rect
        val f = V5Pdf.form(doc, rect)
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.stroke(cs, color); V5Pdf.fill(cs, color)
            cs.setLineWidth((size / 8f).coerceAtLeast(1f)); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
            val m = size * 0.15f
            when (kind) {
                MarkKind.TICK -> { cs.moveTo(m, size * 0.5f); cs.lineTo(size * 0.4f, m); cs.lineTo(size - m, size - m); cs.stroke() }
                MarkKind.CROSS -> { cs.moveTo(m, m); cs.lineTo(size - m, size - m); cs.moveTo(m, size - m); cs.lineTo(size - m, m); cs.stroke() }
                MarkKind.DOT -> { V5Pdf.circle(cs, h, h, size * 0.22f); cs.fill() }
                MarkKind.QUESTION -> V5Pdf.label(cs, "?", h, h - size * 0.3f, 0.0, size * 0.85f, color, PDType1Font.HELVETICA_BOLD, plate = false)
            }
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    /** Highlight of a rectangular area (images, tables, scanned text): Highlight with one rectangle quad. */
    fun writeAreaHighlight(doc: PDDocument, page: PDPage, box: Box, color: Int, opacity: Float, author: String, note: String = "") {
        val an = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
        V5Pdf.common(an, color, opacity, author, note, "Area Highlight")
        an.rectangle = box.pd()
        an.quadPoints = floatArrayOf(box.x0, box.y1, box.x1, box.y1, box.x0, box.y0, box.x1, box.y0)
        val f = V5Pdf.form(doc, box.pd())
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.alpha(cs, opacity, BlendMode.MULTIPLY); V5Pdf.fill(cs, color); cs.addRect(0f, 0f, box.w, box.h); cs.fill()
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    /** Line or arrow with dash style and both line endings. */
    fun writeLine(doc: PDDocument, page: PDPage, a: P, b: P, color: Int, width: Float, opacity: Float, dash: DashStyle, start: LineEnd, end: LineEnd, author: String) {
        val rect = V5Pdf.bounds(listOf(a, b), width * 4 + 8f)
        val an = PDAnnotationLine()
        V5Pdf.common(an, color, opacity, author, "", if (start != LineEnd.NONE || end != LineEnd.NONE) "Arrow" else "Line")
        an.rectangle = rect
        an.line = floatArrayOf(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        an.startPointEndingStyle = start.pdf; an.endPointEndingStyle = end.pdf
        an.borderStyle = V5Pdf.border(width, dash)
        an.interiorColor = V5Pdf.rgb(color)
        val f = V5Pdf.form(doc, rect)
        val la = P(a.x - rect.lowerLeftX, a.y - rect.lowerLeftY); val lb = P(b.x - rect.lowerLeftX, b.y - rect.lowerLeftY)
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.alpha(cs, opacity); V5Pdf.stroke(cs, color); V5Pdf.fill(cs, color); cs.setLineWidth(width); cs.setLineCapStyle(1)
            dash.scaled(width)?.let { cs.setLineDashPattern(it, 0f) }
            cs.moveTo(la.x.toFloat(), la.y.toFloat()); cs.lineTo(lb.x.toFloat(), lb.y.toFloat()); cs.stroke()
            V5Pdf.drawEnd(cs, start, lb, la, width); V5Pdf.drawEnd(cs, end, la, lb, width)
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    /**
     * Restyle a stored annotation (Line, PolyLine, Polygon, Square, Circle, Ink): width, dash and — for lines
     * and polylines — both line endings; then PdfBox rebuilds its appearance. Returns false when unsupported.
     */
    fun restyle(doc: PDDocument, pageIndex: Int, index: Int, width: Float, dash: DashStyle, start: LineEnd?, end: LineEnd?, color: Int?): Boolean {
        val an: PDAnnotation = doc.getPage(pageIndex).annotations.getOrNull(index) ?: return false
        val sub = an.subtype
        if (sub !in setOf("Line", "PolyLine", "Polygon", "Square", "Circle", "Ink")) return false
        val m = an as? PDAnnotationMarkup ?: PDAnnotationMarkup(an.cosObject)
        val cos: COSDictionary = an.cosObject
        cos.setItem(COSName.BS, V5Pdf.border(width, dash))
        if ((sub == "Line" || sub == "PolyLine") && (start != null || end != null)) {
            val old = cos.getDictionaryObject(V5Pdf.LE) as? COSArray
            val s0 = start?.pdf ?: (old?.getName(0) ?: "None"); val e0 = end?.pdf ?: (old?.getName(1) ?: "None")
            cos.setItem(V5Pdf.LE, V5Pdf.names(s0, e0))
        }
        color?.let { m.color = V5Pdf.rgb(it) }
        m.setModifiedDate(java.util.Calendar.getInstance())
        cos.removeItem(COSName.AP)
        runCatching { an.constructAppearances(doc) }
        return true
    }
}
