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

/** A finished measurement to write: [pts] are the clicked points (circle, diameter, radius: centre + a point on the edge). */
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
    /** Distance only: leader line length (negative = other side, "flip"), extension and offset (7.30 / 25.4 / 25.20). */
    val leader: Float = 0f,
    val leaderExt: Float = 0f,
    val leaderOffset: Float = 0f,
    /** User label shown instead of the value (25.14); the value stays in /Contents and the private value key. */
    val customLabel: String? = null,
    /** §41: depth of a volume / height of a polylength (real units, 0 = none). */
    val depth: Double = 0.0,
    /** 25.18: the label moved away from its place by this many points. */
    val labelOffset: P = P(0.0, 0.0),
    /** §41 polylength with a height: the wall area line shown under the length ("" = none). */
    val extraLabel: String = "",
)

/**
 * Writes measurements as real PDF measure annotations (ISO 32000 12.9 "Measurement properties"):
 * Line /IT /LineDimension, PolyLine /IT /PolyLineDimension, Polygon /IT /PolygonDimension, Circle,
 * each with a /Measure /RL dictionary carrying the scale (/R) and number formats (/X /D /A), our own
 * appearance stream and the value in /Contents. Angle = PolyLine of 3 vertices (/Subj Angle); Count = a
 * small Circle per point (/Subj Count). The value and type are also kept in private keys for reading back,
 * with the clicked points, depth, label offset and custom label (so a stored measurement can be edited).
 */
object MeasureWriter {
    val SD_PTS: COSName = COSName.getPDFName("SDMeasurePts")
    val SD_DEPTH: COSName = COSName.getPDFName("SDMeasureDepth")
    val SD_LABEL_OFF: COSName = COSName.getPDFName("SDLabelOffset")
    val SD_CUSTOM: COSName = COSName.getPDFName("SDMeasureLabel")
    val SD_PRECISION: COSName = COSName.getPDFName("SDMeasurePrecision")

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
            MeasureType.PERIMETER, MeasureType.AREA, MeasureType.ANGLE, MeasureType.VOLUME, MeasureType.POLYLENGTH -> poly(doc, page, m, font)
            MeasureType.CIRCLE, MeasureType.DIAMETER, MeasureType.RADIUS -> circle(doc, page, m, font)
            MeasureType.COUNT -> count(doc, page, m, font)
        }
    }

    /** The label text drawn on the page (custom label or the value). */
    fun shownLabel(m: Measurement): String = m.customLabel?.takeIf { it.isNotBlank() } ?: m.label

    /**
     * 25.18: where the label sits before its offset (page coordinates): the middle of the dimension line, the
     * middle of the last segment, the centroid of an area, beside the angle arc, the centre of a circle.
     */
    fun labelAnchor(m: Measurement): P {
        val p = m.pts
        if (p.isEmpty()) return P(0.0, 0.0)
        return when (m.type) {
            MeasureType.DISTANCE -> {
                if (p.size < 2) return p[0]
                if (m.leader != 0f) {
                    val g = com.smartdocs.editor.core.annot.AnnotStyle.leaderGeometry(p[0].x.toFloat(), p[0].y.toFloat(), p[1].x.toFloat(), p[1].y.toFloat(), m.leader, m.leaderExt, m.leaderOffset)
                    Geom.midpoint(P(g[0].toDouble(), g[1].toDouble()), P(g[2].toDouble(), g[3].toDouble())).let { P(it.x, it.y + 3) }
                } else Geom.midpoint(p[0], p[1]).let { P(it.x, it.y + 3) }
            }
            MeasureType.AREA, MeasureType.VOLUME -> Geom.centroid(p)
            MeasureType.ANGLE -> if (p.size < 3) p[0] else {
                val v = p[1]; val r = 26.0
                val a0 = atan2(p[0].y - v.y, p[0].x - v.x); val a1 = atan2(p[2].y - v.y, p[2].x - v.x)
                var d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI
                val mid = a0 + d / 2
                P(v.x + r * Math.cos(mid), v.y + r * Math.sin(mid))
            }
            MeasureType.CIRCLE, MeasureType.DIAMETER -> P(p[0].x, p[0].y - 4)
            MeasureType.RADIUS -> if (p.size < 2) p[0] else Geom.midpoint(p[0], p[1]).let { P(it.x, it.y + 4) }
            MeasureType.COUNT -> p[0]
            else -> if (p.size < 2) p[0] else Geom.midpoint(p[p.size - 2], p.last()).let { P(it.x, it.y + 8) }
        }
    }

    /** The label position on the page: anchor + offset (25.18). */
    fun labelAt(m: Measurement): P = labelAnchor(m).let { P(it.x + m.labelOffset.x, it.y + m.labelOffset.y) }

    private fun tag(an: PDAnnotationMarkup, m: Measurement, subject: String) {
        V5Pdf.common(an, m.color, 1f, m.author, m.customLabel?.takeIf { it.isNotBlank() }?.let { "$it (${m.label})" } ?: m.label, subject)
        val cos = an.cosObject
        cos.setItem(V5Pdf.MEASURE, measureDict(m.scale, m.precision))
        cos.setName(V5Pdf.SD_TYPE, m.type.name)
        cos.setItem(V5Pdf.SD_VALUE, COSFloat(m.value.toFloat()))
        // what the stored-measurement editor needs to rebuild it (25.19 / 25.18)
        cos.setItem(SD_PTS, COSArray().apply { m.pts.forEach { add(COSFloat(it.x.toFloat())); add(COSFloat(it.y.toFloat())) } })
        cos.setInt(SD_PRECISION, m.precision)
        if (m.depth > 0.0) cos.setItem(SD_DEPTH, COSFloat(m.depth.toFloat()))
        if (m.labelOffset.x != 0.0 || m.labelOffset.y != 0.0) cos.setItem(SD_LABEL_OFF, V5Pdf.floats(m.labelOffset.x.toFloat(), m.labelOffset.y.toFloat()))
        m.customLabel?.takeIf { it.isNotBlank() }?.let { cos.setString(SD_CUSTOM, it) }
    }

    /** Label plate size in points (to keep the label inside the annotation rectangle). */
    private fun labelPad(m: Measurement): Float = (shownLabel(m).length * 5.5f + 12f).coerceAtMost(220f)

    /** Bounds of the shape points with [pad], grown to hold the (moved) label. */
    private fun rectWithLabel(shape: List<P>, pad: Float, m: Measurement): PDRectangle {
        val lab = labelAt(m); val lp = labelPad(m)
        return V5Pdf.bounds(shape + listOf(P(lab.x - lp / 2, lab.y - 8), P(lab.x + lp / 2, lab.y + 14)), pad)
    }

    private fun distance(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val a = m.pts.getOrNull(0) ?: return; val b = m.pts.getOrNull(1) ?: return
        val pad = m.width * 4 + 14f + kotlin.math.abs(m.leader) + m.leaderExt
        val rect = rectWithLabel(listOf(a, b), pad, m)
        val an = PDAnnotationLine()
        tag(an, m, "Distance Measurement")
        an.rectangle = rect
        an.line = floatArrayOf(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        an.startPointEndingStyle = "Butt"; an.endPointEndingStyle = "Butt"
        an.borderStyle = V5Pdf.border(m.width, m.dash)
        an.setCaption(true)
        an.cosObject.setName(V5Pdf.IT, "LineDimension")
        com.smartdocs.editor.core.annot.AnnotStyle.setLeader(an.cosObject, m.leader, m.leaderExt, m.leaderOffset)
        val f = V5Pdf.form(doc, rect)
        val ox = rect.lowerLeftX.toDouble(); val oy = rect.lowerLeftY.toDouble()
        // with leader lines the dimension line itself is drawn LL away from the measured points
        val geo = com.smartdocs.editor.core.annot.AnnotStyle.leaderGeometry(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), m.leader, m.leaderExt, m.leaderOffset)
        val la = if (m.leader != 0f) P(geo[0] - ox, geo[1] - oy) else P(a.x - ox, a.y - oy)
        val lb = if (m.leader != 0f) P(geo[2] - ox, geo[3] - oy) else P(b.x - ox, b.y - oy)
        PDPageContentStream(doc, f).use { cs ->
            V5Pdf.stroke(cs, m.color); V5Pdf.fill(cs, m.color); cs.setLineWidth(m.width); cs.setLineCapStyle(1)
            if (m.leader != 0f) {
                cs.moveTo((geo[4] - ox).toFloat(), (geo[5] - oy).toFloat()); cs.lineTo((geo[6] - ox).toFloat(), (geo[7] - oy).toFloat())
                cs.moveTo((geo[8] - ox).toFloat(), (geo[9] - oy).toFloat()); cs.lineTo((geo[10] - ox).toFloat(), (geo[11] - oy).toFloat())
                cs.stroke()
            }
            m.dash.scaled(m.width)?.let { cs.setLineDashPattern(it, 0f) }
            cs.moveTo(la.x.toFloat(), la.y.toFloat()); cs.lineTo(lb.x.toFloat(), lb.y.toFloat()); cs.stroke()
            V5Pdf.drawEnd(cs, LineEnd.BUTT, lb, la, m.width); V5Pdf.drawEnd(cs, LineEnd.BUTT, la, lb, m.width)
            val at = labelAt(m)
            // a label dragged away from the line (25.18) is drawn level
            val angle = if (m.labelOffset.x == 0.0 && m.labelOffset.y == 0.0) atan2(lb.y - la.y, lb.x - la.x) else 0.0
            V5Pdf.label(cs, shownLabel(m), (at.x - ox).toFloat(), (at.y - oy).toFloat(), angle, 9f, m.color, V5Pdf.fontFor(shownLabel(m), font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun poly(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val pts = m.pts
        val closed = m.type == MeasureType.AREA || m.type == MeasureType.VOLUME
        if (pts.size < MeasureCalc.minPoints(m.type)) return
        val rect = rectWithLabel(pts, m.width + 30f, m)
        val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, if (closed) "Polygon" else "PolyLine") }
        tag(an, m, when (m.type) {
            MeasureType.AREA -> "Area Measurement"; MeasureType.VOLUME -> "Volume Measurement"; MeasureType.ANGLE -> "Angle"
            MeasureType.POLYLENGTH -> "Polylength Measurement"; else -> "Perimeter Measurement"
        })
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
            if (m.type == MeasureType.ANGLE) {
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
            }
            val at = labelAt(m)
            V5Pdf.label(cs, shownLabel(m), (at.x - ox).toFloat(), (at.y - oy).toFloat(), 0.0, 9f, m.color, V5Pdf.fontFor(shownLabel(m), font))
            if (m.extraLabel.isNotBlank()) V5Pdf.label(cs, m.extraLabel, (at.x - ox).toFloat(), (at.y - oy).toFloat() - 12f, 0.0, 8f, m.color, V5Pdf.fontFor(m.extraLabel, font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun circle(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        val c = m.pts.getOrNull(0) ?: return; val e = m.pts.getOrNull(1) ?: return
        val r = Geom.dist(c, e).coerceAtLeast(1.0)
        val pad = m.width + 2f
        val rect = rectWithLabel(listOf(P(c.x - r, c.y - r), P(c.x + r, c.y + r)), pad, m)
        val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Circle") }
        tag(an, m, when (m.type) { MeasureType.DIAMETER -> "Diameter Measurement"; MeasureType.RADIUS -> "Radius Measurement"; else -> "Circle Area Measurement" })
        an.rectangle = rect
        // /RD: the circle inside the (label-grown) rectangle
        an.cosObject.setItem(V5Pdf.RD, V5Pdf.floats((c.x - r - rect.lowerLeftX).toFloat(), (c.y - r - rect.lowerLeftY).toFloat(),
            (rect.upperRightX - (c.x + r)).toFloat(), (rect.upperRightY - (c.y + r)).toFloat()))
        an.borderStyle = V5Pdf.border(m.width, m.dash)
        an.cosObject.setName(V5Pdf.IT, "CircleDimension")
        m.fill?.let { an.interiorColor = V5Pdf.rgb(it) }
        val f = V5Pdf.form(doc, rect)
        val ox = rect.lowerLeftX.toDouble(); val oy = rect.lowerLeftY.toDouble()
        PDPageContentStream(doc, f).use { cs ->
            val cx = (c.x - ox).toFloat(); val cy = (c.y - oy).toFloat(); val rf = r.toFloat()
            if (m.fill != null) { cs.saveGraphicsState(); V5Pdf.alpha(cs, 0.25f); V5Pdf.fill(cs, m.fill); V5Pdf.circle(cs, cx, cy, rf); cs.fill(); cs.restoreGraphicsState() }
            V5Pdf.stroke(cs, m.color); cs.setLineWidth(m.width)
            m.dash.scaled(m.width)?.let { cs.setLineDashPattern(it, 0f) }
            V5Pdf.circle(cs, cx, cy, rf); cs.stroke()
            val ex = (e.x - ox).toFloat(); val ey = (e.y - oy).toFloat()
            when (m.type) {
                // diameter: the full line through the centre, with butt ends
                MeasureType.DIAMETER -> {
                    cs.setLineDashPattern(FloatArray(0), 0f)
                    val fx = 2 * cx - ex; val fy = 2 * cy - ey
                    cs.moveTo(fx, fy); cs.lineTo(ex, ey); cs.stroke()
                    V5Pdf.fill(cs, m.color)
                    V5Pdf.drawEnd(cs, LineEnd.CLOSED_ARROW, P(cx.toDouble(), cy.toDouble()), P(ex.toDouble(), ey.toDouble()), m.width)
                    V5Pdf.drawEnd(cs, LineEnd.CLOSED_ARROW, P(cx.toDouble(), cy.toDouble()), P(fx.toDouble(), fy.toDouble()), m.width)
                }
                MeasureType.RADIUS -> {
                    cs.setLineDashPattern(FloatArray(0), 0f)
                    cs.moveTo(cx, cy); cs.lineTo(ex, ey); cs.stroke()
                    V5Pdf.fill(cs, m.color); V5Pdf.circle(cs, cx, cy, m.width * 1.2f); cs.fill()
                    V5Pdf.drawEnd(cs, LineEnd.CLOSED_ARROW, P(cx.toDouble(), cy.toDouble()), P(ex.toDouble(), ey.toDouble()), m.width)
                }
                else -> { cs.setLineDashPattern(floatArrayOf(2f, 2f), 0f); cs.moveTo(cx, cy); cs.lineTo(ex, ey); cs.stroke() }
            }
            val at = labelAt(m)
            V5Pdf.label(cs, shownLabel(m), (at.x - ox).toFloat(), (at.y - oy).toFloat(), 0.0, 9f, m.color, V5Pdf.fontFor(shownLabel(m), font))
        }
        an.appearance = V5Pdf.ap(f)
        page.annotations.add(an)
    }

    private fun count(doc: PDDocument, page: PDPage, m: Measurement, font: () -> PDFont) {
        m.pts.forEachIndexed { i, p ->
            val r = 7f
            val rect = PDRectangle(p.x.toFloat() - r - 1, p.y.toFloat() - r - 1, 2 * r + 2, 2 * r + 2)
            val an = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Circle") }
            val one = m.copy(label = (i + 1).toString(), value = (i + 1).toDouble(), pts = listOf(p), labelOffset = P(0.0, 0.0))
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

    fun typeOf(an: PDAnnotation): MeasureType? {
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
