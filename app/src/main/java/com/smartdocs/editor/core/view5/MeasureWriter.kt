package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kotlin.math.atan2

/** A finished measurement to write: [pts] are the clicked points (circle: centre + a point on the edge). */
data class Measurement(
    val type: MeasureType,
    val page: Int,
    val pts: List<P>,
    val value: Double,
    val label: String,
    val scale: MeasureScale,
    val color: Int = 0xE53935,
    val width: Float = 1.5f,
    val author: String = "",
    val precision: Int = 2,
    val dash: DashStyle = DashStyle.SOLID,
    val fill: Int? = null,
)

/**
 * Writes measurements as real PDF measure annotations (ISO 32000 12.9 "Measurement properties"):
 * Line /IT /LineDimension, PolyLine /IT /PolyLineDimension, Polygon /IT /PolygonDimension, Circle,
 * each with a /Measure /RL dictionary carrying the scale (/R) and number formats (/X /D /A), our own
 * appearance stream and the value in /Contents. Angle = PolyLine of 3 vertices (/Subj Angle); Count = a
 * small Circle per point (/Subj Count). The value and type are also kept in private keys for reading back.
 */
object MeasureWriter {

    private fun numberFormat(unit: String, factor: Double, precision: Int) = COSDictionary().apply {
        setName(COSName.TYPE, "NumberFormat")
        setString(COSName.getPDFName("U"), unit)
        setItem(COSName.getPDFName("C"), COSFloat(factor.toFloat()))
        setInt(COSName.getPDFName("D"), Math.pow(10.0, precision.toDouble()).toInt())
    }

    fun measureDict(scale: MeasureScale, precision: Int): COSDictionary = COSDictionary().apply {
        setName(COSName.TYPE, "Measure"); setName(COSName.SUBTYPE, "RL")
        setString(COSName.getPDFName("R"), scale.label())
        val u = scale.realUnit.code
        setItem(COSName.getPDFName("X"), COSArray().apply { add(numberFormat(u, scale.factor, precision)) })
        setItem(COSName.getPDFName("D"), COSArray().apply { add(numberFormat(u, 1.0, precision)) })
        setItem(COSName.getPDFName("A"), COSArray().apply { add(numberFormat("sq $u", 1.0, precision)) })
        setItem(COSName.getPDFName("T"), COSArray().apply { add(numberFormat("°", 1.0, 1)) })
    }

    /** Add [m] to [page]. [font] draws the label (Arabic font when the label has Arabic digits). */
    fun write(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        when (m.type) {
            MeasureType.DISTANCE -> distance(doc, page, m, font)
            MeasureType.PERIMETER, MeasureType.AREA, MeasureType.ANGLE -> poly(doc, page, m, font)
            MeasureType.CIRCLE -> circle(doc, page, m, font)
            MeasureType.COUNT -> count(doc, page, m, font)
        }
    }

    private fun tag(an: PDAnnotationMarkup, m: Measurement, subject: String) {
        V5Pdf.common(an, m.color, 1f, m.author, m.label, subject)
        an.cosObject.setItem(V5Pdf.MEASURE, measureDict(m.scale, m.precision))
        an.cosObject.setName(V5Pdf.SD_TYPE, m.type.name)
        an.cosObject.setItem(V5Pdf.SD_VALUE, COSFloat(m.value.toFloat()))
    }

    private fun distance(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val a = m.pts.getOrNull(0) ?: return; val b = m.pts.getOrNull(1) ?: return
        val pad = m.width * 4 + 14f
        val rect = V5Pdf.bounds(listOf(a, b), pad)
        val an = PDAnnotationLine()
        tag(an, m, "Distance Measurement")
        an.rectangle = rect
        an.line = floatArrayOf(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        an.startPointEndingStyle = "Butt"; an.endPointEndingStyle = "Butt"
        an.borderStyle = V5Pdf.border(m.width, m.dash)
        an.setCaption(true)
        an.cosObject.setName(V5Pdf.IT, "LineDimension")
        val f = V5Pdf.form(doc, rect)
        val ox = rect.lowerLeftX.toDouble(); val oy = rect.lowerLeftY.toDouble()
        val la = P(a.x - ox, a.y - oy); val lb = P(b.x - ox, b.y - oy)
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.stroke(cs, m.color); V5Pdf.fill(cs, m.color); cs.setLineWidth(m.width); cs.setLineCapStyle(1)
            m.dash.scaled(m.width)?.let { cs.setLineDashPattern(it, 0f) }
            cs.moveTo(la.x.toFloat(), la.y.toFloat()); cs.lineTo(lb.x.toFloat(), lb.y.toFloat()); cs.stroke()
            V5Pdf.drawEnd(cs, LineEnd.BUTT, lb, la, m.width); V5Pdf.drawEnd(cs, LineEnd.BUTT, la, lb, m.width)
            val mid = Geom.midpoint(la, lb)
            V5Pdf.label(cs, m.label, mid.x.toFloat(), mid.y.toFloat() + 3f, atan2(lb.y - la.y, lb.x - la.x), 9f, m.color, V5Pdf.fontFor(m.label, font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun poly(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val pts = m.pts
        val closed = m.type == MeasureType.AREA
        if (pts.size < (if (closed || m.type == MeasureType.ANGLE) 3 else 2)) return
        val rect = V5Pdf.bounds(pts, m.width + 30f)
        val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, if (closed) "Polygon" else "PolyLine") }
        tag(an, m, when (m.type) { MeasureType.AREA -> "Area Measurement"; MeasureType.ANGLE -> "Angle"; else -> "Perimeter Measurement" })
        an.rectangle = rect
        an.vertices = FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x.toFloat() else pts[i / 2].y.toFloat() }
        an.borderStyle = V5Pdf.border(m.width, m.dash)
        if (m.type != MeasureType.ANGLE) an.cosObject.setName(V5Pdf.IT, if (closed) "PolygonDimension" else "PolyLineDimension")
        if (closed && m.fill != null) an.interiorColor = V5Pdf.rgb(m.fill)
        val f = V5Pdf.form(doc, rect)
        val ox = rect.lowerLeftX.toDouble(); val oy = rect.lowerLeftY.toDouble()
        val lp = pts.map { P(it.x - ox, it.y - oy) }
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.stroke(cs, m.color); cs.setLineWidth(m.width); cs.setLineJoinStyle(1); cs.setLineCapStyle(1)
            m.dash.scaled(m.width)?.let { cs.setLineDashPattern(it, 0f) }
            if (closed && m.fill != null) {
                cs.saveGraphicsState(); V5Pdf.alpha(cs, 0.25f); V5Pdf.fill(cs, m.fill)
                lp.forEachIndexed { i, p -> if (i == 0) cs.moveTo(p.x.toFloat(), p.y.toFloat()) else cs.lineTo(p.x.toFloat(), p.y.toFloat()) }
                cs.closePath(); cs.fill(); cs.restoreGraphicsState()
            }
            lp.forEachIndexed { i, p -> if (i == 0) cs.moveTo(p.x.toFloat(), p.y.toFloat()) else cs.lineTo(p.x.toFloat(), p.y.toFloat()) }
            if (closed) cs.closePath()
            cs.stroke()
            val at = when (m.type) {
                MeasureType.ANGLE -> {
                    // small arc at the vertex
                    val v = lp[1]; val r = 14f
                    val a0 = atan2(lp[0].y - v.y, lp[0].x - v.x); val a1 = atan2(lp[2].y - v.y, lp[2].x - v.x)
                    var d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI
                    val steps = 16
                    cs.setLineDashPattern(FloatArray(0), 0f)
                    for (k in 0..steps) {
                        val t = a0 + d * k / steps
                        val x = (v.x + r * Math.cos(t)).toFloat(); val y = (v.y + r * Math.sin(t)).toFloat()
                        if (k == 0) cs.moveTo(x, y) else cs.lineTo(x, y)
                    }
                    cs.stroke()
                    val mid = a0 + d / 2
                    P(v.x + (r + 12) * Math.cos(mid), v.y + (r + 12) * Math.sin(mid))
                }
                MeasureType.AREA -> Geom.centroid(lp)
                else -> Geom.midpoint(lp[lp.size - 2], lp.last()).let { P(it.x, it.y + 8) }
            }
            V5Pdf.label(cs, m.label, at.x.toFloat(), at.y.toFloat(), 0.0, 9f, m.color, V5Pdf.fontFor(m.label, font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun circle(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val c = m.pts.getOrNull(0) ?: return; val e = m.pts.getOrNull(1) ?: return
        val r = Geom.dist(c, e).toFloat().coerceAtLeast(1f)
        val pad = m.width + 2f
        val rect = PDRectangle(c.x.toFloat() - r - pad, c.y.toFloat() - r - pad, 2 * (r + pad), 2 * (r + pad))
        val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Circle") }
        tag(an, m, "Circle Area Measurement")
        an.rectangle = rect
        an.cosObject.setItem(V5Pdf.RD, V5Pdf.floats(pad, pad, pad, pad))
        an.borderStyle = V5Pdf.border(m.width, m.dash)
        an.cosObject.setName(V5Pdf.IT, "CircleDimension")
        m.fill?.let { an.interiorColor = V5Pdf.rgb(it) }
        val f = V5Pdf.form(doc, rect)
        PDPageContentStream(doc, f).use { cs ->
            val cx = r + pad; val cy = r + pad
            if (m.fill != null) { cs.saveGraphicsState(); V5Pdf.alpha(cs, 0.25f); V5Pdf.fill(cs, m.fill); V5Pdf.circle(cs, cx, cy, r); cs.fill(); cs.restoreGraphicsState() }
            V5Pdf.stroke(cs, m.color); cs.setLineWidth(m.width)
            m.dash.scaled(m.width)?.let { cs.setLineDashPattern(it, 0f) }
            V5Pdf.circle(cs, cx, cy, r); cs.stroke()
            cs.setLineDashPattern(floatArrayOf(2f, 2f), 0f)
            val ex = (e.x - c.x).toFloat() + cx; val ey = (e.y - c.y).toFloat() + cy
            cs.moveTo(cx, cy); cs.lineTo(ex, ey); cs.stroke()
            V5Pdf.label(cs, m.label, cx, cy - 4f, 0.0, 9f, m.color, V5Pdf.fontFor(m.label, font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun count(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        m.pts.forEachIndexed { i, p ->
            val r = 7f
            val rect = PDRectangle(p.x.toFloat() - r - 1, p.y.toFloat() - r - 1, 2 * r + 2, 2 * r + 2)
            val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Circle") }
            val one = m.copy(label = (i + 1).toString(), value = (i + 1).toDouble())
            tag(an, one, "Count")
            an.rectangle = rect
            an.interiorColor = V5Pdf.rgb(m.color)
            an.borderStyle = PDBorderStyleDictionary().apply { width = 1f }
            val f = V5Pdf.form(doc, rect)
            PDPageContentStream(doc, f).use { cs ->
                V5Pdf.fill(cs, m.color); V5Pdf.stroke(cs, 0xFFFFFF); cs.setLineWidth(1f)
                V5Pdf.circle(cs, r + 1, r + 1, r); cs.fillAndStroke()
                V5Pdf.label(cs, one.label, r + 1, r - 2f, 0.0, 8f, 0xFFFFFF, V5Pdf.fontFor(one.label, font), plate = false)
            }
            an.appearance = V5Pdf.ap(f)
            page.annotations.add(an)
        }
    }

    // ---------------- read back ----------------

    /** A measurement found on a page (ours or another viewer's /Measure annotation). */
    data class Found(val page: Int, val index: Int, val type: MeasureType, val value: Double?, val contents: String, val scale: String, val author: String)

    private fun typeOf(an: PDAnnotation): MeasureType? {
        val cos = an.cosObject
        cos.getNameAsString(V5Pdf.SD_TYPE)?.let { n -> MeasureType.entries.firstOrNull { it.name == n }?.let { return it } }
        val subj = cos.getString(V5Pdf.SUBJ) ?: ""
        if (subj == "Count") return MeasureType.COUNT
        if (subj == "Angle") return MeasureType.ANGLE
        val it = cos.getNameAsString(V5Pdf.IT)
        val hasMeasure = cos.containsKey(V5Pdf.MEASURE)
        return when {
            it == "LineDimension" || (hasMeasure && an.subtype == "Line") -> MeasureType.DISTANCE
            it == "PolyLineDimension" || (hasMeasure && an.subtype == "PolyLine") -> MeasureType.PERIMETER
            it == "PolygonDimension" || (hasMeasure && an.subtype == "Polygon") -> MeasureType.AREA
            it == "CircleDimension" || (hasMeasure && an.subtype == "Circle") -> MeasureType.CIRCLE
            else -> null
        }
    }

    fun list(doc: PDDocument): List<Found> {
        val out = ArrayList<Found>()
        doc.pages.forEachIndexed { pi, page ->
            runCatching { page.annotations }.getOrNull()?.forEachIndexed { i, an ->
                val t = typeOf(an) ?: return@forEachIndexed
                val cos = an.cosObject
                val v = (cos.getDictionaryObject(V5Pdf.SD_VALUE) as? COSNumber)?.doubleValue()
                    ?: Regex("""[-+]?[0-9]*\.?[0-9]+""").find(Digits.toLatin(an.contents ?: "").replace('٫', '.'))?.value?.toDoubleOrNull()
                val r = ((cos.getDictionaryObject(V5Pdf.MEASURE) as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("R")) as? COSString)?.string ?: ""
                out += Found(pi, i, t, v, an.contents ?: "", r, cos.getString(COSName.T) ?: "")
            }
        }
        return out
    }

    /** Scale label (/R) of the last measurement in the document, to restore the calibration. */
    fun lastScale(doc: PDDocument): MeasureScale? = list(doc).lastOrNull { it.scale.isNotBlank() }?.let { MeasureScale.parse(it.scale) }
}
