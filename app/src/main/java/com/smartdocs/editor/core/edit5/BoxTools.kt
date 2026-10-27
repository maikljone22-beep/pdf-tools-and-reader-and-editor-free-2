package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Units for crop margins and paper sizes (Nitro / Foxit let the user type mm or inches). */
enum class BoxUnit(val perPt: Float) {
    PT(1f), MM(25.4f / 72f), IN(1f / 72f);

    fun toPt(v: Float) = v / perPt
    fun fromPt(pt: Float) = pt * perPt
}

/** Crop helpers that do not need Android types (JVM-tested). Rects are [left, bottom, right, top] in points. */
object BoxTools {

    /** Every box a page defines; MEDIA and CROP always, the others only when the page sets them. */
    fun pageBoxes(page: PDPage): Map<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>()
        fun put(k: String, r: com.tom_roush.pdfbox.pdmodel.common.PDRectangle) { out[k] = floatArrayOf(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY) }
        put("MEDIA", page.mediaBox); put("CROP", page.cropBox)
        val d = page.cosObject
        if (d.containsKey(COSName.BLEED_BOX)) put("BLEED", page.bleedBox)
        if (d.containsKey(COSName.TRIM_BOX)) put("TRIM", page.trimBox)
        if (d.containsKey(COSName.ART_BOX)) put("ART", page.artBox)
        return out
    }

    suspend fun readBoxes(src: File, password: String?, page: Int): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc -> if (page in 0 until doc.numberOfPages) pageBoxes(doc.getPage(page)) else emptyMap() }
    }

    /**
     * "Constrain proportions": shrink the drawn rect [l, b, r, t] to the [ratio] (width / height), keeping the
     * corner the drag started from ([anchorLeft], [anchorBottom]) in place.
     */
    fun constrain(l: Float, b: Float, r: Float, t: Float, ratio: Float, anchorLeft: Boolean = true, anchorBottom: Boolean = false): FloatArray {
        if (ratio <= 0f) return floatArrayOf(l, b, r, t)
        val w = kotlin.math.abs(r - l); val h = kotlin.math.abs(t - b)
        if (w == 0f || h == 0f) return floatArrayOf(l, b, r, t)
        val (nw, nh) = if (w / h > ratio) h * ratio to h else w to w / ratio
        val x0 = minOf(l, r); val x1 = maxOf(l, r); val y0 = minOf(b, t); val y1 = maxOf(b, t)
        val nl = if (anchorLeft) x0 else x1 - nw
        val nb = if (anchorBottom) y0 else y1 - nh
        return floatArrayOf(nl, nb, nl + nw, nb + nh)
    }

    /** Even cut positions (fractions) for [parts] tiles. */
    fun evenCuts(parts: Int): List<Float> = (1 until parts.coerceAtLeast(1)).map { it / parts.toFloat() }

    /**
     * Moves cut [i] of [cuts] to [v], keeping every tile at least [minGap] wide (cuts stay sorted, inside 0..1).
     */
    fun moveCut(cuts: List<Float>, i: Int, v: Float, minGap: Float = 0.05f): List<Float> {
        if (i !in cuts.indices) return cuts
        val lo = (cuts.getOrNull(i - 1) ?: 0f) + minGap
        val hi = (cuts.getOrNull(i + 1) ?: 1f) - minGap
        return cuts.toMutableList().also { it[i] = if (lo > hi) cuts[i] else v.coerceIn(lo, hi) }
    }

    /** Other height for a new width with proportions locked. */
    fun lockedHeight(newW: Float, oldW: Float, oldH: Float): Float = if (oldW <= 0f) oldH else newW * oldH / oldW
}
