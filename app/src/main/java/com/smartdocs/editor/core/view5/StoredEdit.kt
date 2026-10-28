package com.smartdocs.editor.core.view5

import com.smartdocs.editor.core.annot.AnnotStyle
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import java.util.Calendar

/**
 * 24.27 / 25.18 / 25.19: geometry of comments already saved in the file, edited by dragging handles in the workbench:
 * the end points / vertices of measurements and lines, a measurement's label on its own, and the knee and tip of a
 * callout (/CL). The annotation keeps its dictionary (author, /NM, replies, keys of other editors): only its geometry,
 * value and appearance are replaced, so nothing else is lost.
 */
object StoredEdit {
    enum class Kind { MEASURE, CALLOUT, LINE }

    /** A stored annotation that can be edited: [pts] are its handles (callout: tip, knee), [label] the measurement label. */
    data class Editable(
        val page: Int, val index: Int, val kind: Kind, val pts: List<P>, val label: P? = null,
        val measurement: Measurement? = null, val text: TextBoxSpec? = null,
    )

    /** The new handle positions of an [Editable] (label: the new label position for measurements). */
    data class Change(val pts: List<P>, val label: P? = null)

    private fun nums(cos: COSDictionary, key: COSName): FloatArray? = (cos.getDictionaryObject(key) as? COSArray)?.let { a ->
        FloatArray(a.size()) { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
    }
    private fun pairs(f: FloatArray?): List<P> = f?.let { a -> (0 until a.size / 2).map { P(a[it * 2].toDouble(), a[it * 2 + 1].toDouble()) } } ?: emptyList()

    private fun rgbOf(cos: COSDictionary, key: COSName): Int? {
        val c = nums(cos, key) ?: return null
        fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return when (c.size) { 1 -> b(c[0]).let { (it shl 16) or (it shl 8) or it }; 3 -> (b(c[0]) shl 16) or (b(c[1]) shl 8) or b(c[2]); else -> null }
    }

    /** Arabic-Indic digits in a label: the rebuilt label keeps them. */
    private fun arabicDigits(s: String) = s.any { it in '٠'..'٩' }

    /** The editable annotations of [pageIndex]: our measurements (all but count), callouts and plain lines. */
    fun read(doc: PDDocument, pageIndex: Int): List<Editable> {
        val page = runCatching { doc.getPage(pageIndex) }.getOrNull() ?: return emptyList()
        val list = runCatching { page.annotations }.getOrNull() ?: return emptyList()
        val out = ArrayList<Editable>()
        list.forEachIndexed { i, an -> runCatching { readOne(an, pageIndex, i) }.getOrNull()?.let { out += it } }
        return out
    }

    internal fun readOne(an: PDAnnotation, page: Int, index: Int): Editable? {
        val cos = an.cosObject
        if (AnnotStyle.isLocked(cos)) return null                       // locked comments cannot move (7.58)
        val sub = an.subtype ?: return null
        MeasureWriter.typeOf(an)?.let { t -> return measure(cos, sub, t, page, index) }
        if (sub == "FreeText" && cos.getNameAsString(V5Pdf.IT) == "FreeTextCallout") return callout(cos, page, index)
        if (sub == "Line") {
            val l = nums(cos, COSName.L) ?: return null
            if (l.size < 4) return null
            return Editable(page, index, Kind.LINE, pairs(l).take(2))
        }
        return null
    }

    private fun measure(cos: COSDictionary, sub: String, t: MeasureType, page: Int, index: Int): Editable? {
        if (t == MeasureType.COUNT) return null
        // the clicked points: our private copy, else the geometry of the annotation
        val pts = pairs(nums(cos, MeasureWriter.SD_PTS)).takeIf { it.size >= 2 } ?: when (sub) {
            "Line" -> pairs(nums(cos, COSName.L)).take(2)
            "PolyLine", "Polygon" -> pairs(nums(cos, COSName.getPDFName("Vertices")))
            "Circle" -> {
                val r = nums(cos, COSName.RECT) ?: return null
                val rd = nums(cos, V5Pdf.RD) ?: floatArrayOf(0f, 0f, 0f, 0f)
                val l = minOf(r[0], r[2]) + rd[0]; val b = minOf(r[1], r[3]) + rd[1]; val rr = maxOf(r[0], r[2]) - rd[2]; val tt = maxOf(r[1], r[3]) - rd[3]
                val c = P((l + rr) / 2.0, (b + tt) / 2.0)
                listOf(c, P(rr.toDouble(), c.y))
            }
            else -> emptyList()
        }
        if (pts.size < MeasureCalc.minPoints(t)) return null
        val md = cos.getDictionaryObject(V5Pdf.MEASURE) as? COSDictionary
        val scale = (md?.getDictionaryObject(COSName.getPDFName("R")) as? COSString)?.string?.let { MeasureScale.parse(it) } ?: MeasureScale.IDENTITY_CM
        val precision = cos.getInt(MeasureWriter.SD_PRECISION, -1).takeIf { it in 0..6 } ?: run {
            val d = ((md?.getDictionaryObject(COSName.getPDFName("X")) as? COSArray)?.getObject(0) as? COSDictionary)?.getInt(COSName.getPDFName("D"), 100) ?: 100
            Math.round(Math.log10(d.coerceAtLeast(1).toDouble())).toInt().coerceIn(0, 6)
        }
        val (ll, lle, llo) = AnnotStyle.leaderOf(cos)
        val off = nums(cos, MeasureWriter.SD_LABEL_OFF)?.takeIf { it.size >= 2 }?.let { P(it[0].toDouble(), it[1].toDouble()) } ?: P(0.0, 0.0)
        val contents = cos.getString(COSName.CONTENTS) ?: ""
        val depth = (cos.getDictionaryObject(MeasureWriter.SD_DEPTH) as? COSNumber)?.doubleValue() ?: 0.0
        val value = MeasureCalc.value(t, pts, scale, depth)
        val m = Measurement(t, page, pts, value, MeasureCalc.label(t, value, scale, precision, arabicDigits(contents)), scale,
            color = rgbOf(cos, COSName.C) ?: 0xE53935, width = AnnotStyle.borderWidth(cos) ?: 1.5f, author = cos.getString(COSName.T) ?: "",
            precision = precision, dash = DashStyle.entries.getOrElse(AnnotStyle.dashIndexOf(cos)) { DashStyle.SOLID },
            fill = if (sub == "Polygon" || sub == "Circle") rgbOf(cos, COSName.getPDFName("IC")) else null,
            leader = ll, leaderExt = lle, leaderOffset = llo, customLabel = cos.getString(MeasureWriter.SD_CUSTOM), depth = depth, labelOffset = off)
        return Editable(page, index, Kind.MEASURE, pts, MeasureWriter.labelAt(m), measurement = m)
    }

    private fun callout(cos: COSDictionary, page: Int, index: Int): Editable? {
        val cl = nums(cos, V5Pdf.CL) ?: return null
        if (cl.size < 4) return null
        val tip = P(cl[0].toDouble(), cl[1].toDouble())
        val knee = P(cl[2].toDouble(), cl[3].toDouble())        // /CL: tip, knee[, edge]; with 4 numbers the knee is the edge
        val r = nums(cos, COSName.RECT) ?: return null
        val rd = nums(cos, V5Pdf.RD)?.takeIf { it.size >= 4 } ?: floatArrayOf(0f, 0f, 0f, 0f)
        val box = Box(minOf(r[0], r[2]) + rd[0], minOf(r[1], r[3]) + rd[1], maxOf(r[0], r[2]) - rd[2], maxOf(r[1], r[3]) - rd[3])
        if (box.w <= 1f || box.h <= 1f) return null
        val da = cos.getString(V5Pdf.DA) ?: ""
        val size = Regex("""([0-9.]+)\s+Tf""").find(da)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 1f } ?: 12f
        val textColor = Regex("""([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+rg""").find(da)?.groupValues?.let { g ->
            ((g[1].toFloat().coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g[2].toFloat().coerceIn(0f, 1f) * 255).toInt() shl 8) or (g[3].toFloat().coerceIn(0f, 1f) * 255).toInt()
        } ?: 0x212121
        val bw = AnnotStyle.borderWidth(cos) ?: 1f
        val q = cos.getInt(V5Pdf.Q, 0)
        val text = cos.getString(COSName.CONTENTS) ?: ""
        val rtl = ReflowBlocks.isRtl(text)
        val align = when (q) { 1 -> 1; 2 -> if (rtl) 0 else 2; else -> if (rtl) 2 else 0 }
        val le = (cos.getDictionaryObject(V5Pdf.LE) as? COSName)?.name ?: (cos.getDictionaryObject(V5Pdf.LE) as? COSArray)?.getName(0)
        val spec = TextBoxSpec(page, box, text, size, textColor, if (bw > 0f) rgbOf(cos, COSName.C) ?: textColor else null, bw.coerceAtLeast(0f),
            rgbOf(cos, COSName.getPDFName("IC")), DashStyle.entries.getOrElse(AnnotStyle.dashIndexOf(cos)) { DashStyle.SOLID }, rtl, align,
            tip, knee, LineEnd.of(le).takeIf { le != null } ?: LineEnd.OPEN_ARROW, author = cos.getString(COSName.T) ?: "",
            opacity = cos.getFloat(COSName.CA, 1f).coerceIn(0.05f, 1f))
        return Editable(page, index, Kind.CALLOUT, listOf(tip, knee), text = spec)
    }

    /** The measurement of [e] after [c]: new points, value, label and label offset. */
    fun changedMeasurement(e: Editable, c: Change): Measurement? {
        val m = e.measurement ?: return null
        val pts = c.pts.takeIf { it.size == m.pts.size } ?: m.pts
        val value = MeasureCalc.value(m.type, pts, m.scale, m.depth)
        val moved = m.copy(pts = pts, value = value, label = MeasureCalc.label(m.type, value, m.scale, m.precision, arabicDigits(m.label)))
        // the label keeps its offset from its anchor unless it was dragged itself
        val anchor = MeasureWriter.labelAnchor(moved)
        val off = c.label?.let { P(it.x - anchor.x, it.y - anchor.y) } ?: m.labelOffset
        return moved.copy(labelOffset = off)
    }

    /** Keys a rewrite replaces on the stored dictionary (geometry, value, appearance). */
    private val REPLACED = setOf("Rect", "AP", "L", "Vertices", "CL", "RD", "Contents", "LL", "LLE", "LLO", "Measure",
        "SDMeasureValue", "SDMeasurePts", "SDLabelOffset", "SDMeasureDepth", "SDMeasurePrecision", "SDMeasureLabel")

    /**
     * Apply [c] to stored annotation [e] of [page]: the annotation is drawn again into a scratch page and its geometry,
     * value and appearance are copied onto the stored dictionary (same object: /NM, replies, foreign keys stay).
     * Returns false when the annotation is no longer there.
     */
    fun apply(doc: PDDocument, page: PDPage, e: Editable, c: Change, font: () -> PDFont): Boolean {
        val list = runCatching { page.annotations }.getOrNull() ?: return false
        val stored = list.getOrNull(e.index) ?: return false
        val cos = stored.cosObject
        when (e.kind) {
            Kind.LINE -> {
                if (c.pts.size < 2) return false
                val a = c.pts[0]; val b = c.pts[1]
                (stored as? PDAnnotationLine ?: PDAnnotationLine(cos)).line = floatArrayOf(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
                val w = AnnotStyle.borderWidth(cos) ?: 1f
                val r = V5Pdf.bounds(listOf(a, b), w * 4 + 8f)
                stored.rectangle = r
                cos.removeItem(COSName.AP)
                runCatching { stored.constructAppearances(doc) }
            }
            Kind.MEASURE, Kind.CALLOUT -> {
                val scratch = PDPage(page.mediaBox)
                when (e.kind) {
                    Kind.MEASURE -> MeasureWriter.write(doc, scratch, changedMeasurement(e, c) ?: return false, font)
                    else -> {
                        val s = e.text ?: return false
                        CommentWriter5.writeTextBox(doc, scratch, s.copy(tip = c.pts.getOrNull(0) ?: s.tip, knee = c.pts.getOrNull(1) ?: s.knee), font)
                    }
                }
                val fresh = scratch.annotations.lastOrNull()?.cosObject ?: return false
                REPLACED.forEach { k -> val key = COSName.getPDFName(k); val v = fresh.getItem(key); if (v != null) cos.setItem(key, v) else if (k != "Contents") cos.removeItem(key) }
            }
        }
        cos.setString(COSName.M, com.tom_roush.pdfbox.util.DateConverter.toString(Calendar.getInstance()))
        return true
    }
}
