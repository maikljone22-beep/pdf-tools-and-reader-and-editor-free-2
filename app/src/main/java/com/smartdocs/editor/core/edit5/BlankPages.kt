package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Paper styles for new blank pages. GRAPH_MM = millimetre graph paper (1/5/10 mm lines), MUSIC = 5-line staves. */
enum class PaperStyle { BLANK, LINED, NARROW, GRID, GRAPH_MM, DOTS, ISOMETRIC, MUSIC, CORNELL }

object BlankPages {
    private const val MM = 72f / 25.4f

    /** Insert [count] pages of [style] at [at] (0..n). [size] null = the neighbouring page's size. */
    suspend fun insert(src: File, out: File, password: String?, at: Int, count: Int, size: Pair<Float, Float>?, landscape: Boolean, style: PaperStyle, lineColor: Int = 0xB0BEC5, paper: Int = 0xFFFFFF, rtl: Boolean = true) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val n = doc.numberOfPages
            val ref = if (n > 0) PageInfo.of(doc.getPage(at.coerceIn(0, n - 1))) else null
            var w = size?.first ?: ref?.displayW ?: 595.28f
            var h = size?.second ?: ref?.displayH ?: 841.89f
            if (size != null && landscape != (w > h)) { val t = w; w = h; h = t }
            var pos = at.coerceIn(0, n)
            // audit 5 E-5: the other pages keep their labels
            PageLabelRemap.keep(doc) { repeat(count.coerceIn(1, 500)) { PageOps5.insertAt(doc, page(doc, w, h, style, lineColor, paper, rtl), pos++) } }
        }
    }

    /** A new document of [count] styled pages. */
    suspend fun create(out: File, count: Int, w: Float, h: Float, style: PaperStyle, lineColor: Int = 0xB0BEC5, paper: Int = 0xFFFFFF, rtl: Boolean = true) = withContext(Dispatchers.IO) {
        PDDocument().use { doc -> repeat(count.coerceIn(1, 500)) { doc.addPage(page(doc, w, h, style, lineColor, paper, rtl)) }; E5Io.save(doc, out) }
    }

    fun page(doc: PDDocument, w: Float, h: Float, style: PaperStyle, lineColor: Int, paper: Int, rtl: Boolean): PDPage {
        val page = PDPage(PDRectangle(w, h))
        PDPageContentStream(doc, page).use { cs ->
            if (paper != 0xFFFFFF) { val p = E5Io.rgb(paper); cs.setNonStrokingColor(p[0], p[1], p[2]); cs.addRect(0f, 0f, w, h); cs.fill() }
            val c = E5Io.rgb(lineColor)
            fun stroke(width: Float, shade: Float = 1f) { cs.setStrokingColor(c[0] * shade, c[1] * shade, c[2] * shade); cs.setLineWidth(width) }
            fun hLines(step: Float, top: Float, bottom: Float, left: Float, right: Float) { var y = top; while (y > bottom) { cs.moveTo(left, y); cs.lineTo(right, y); y -= step }; cs.stroke() }
            when (style) {
                PaperStyle.BLANK -> Unit
                PaperStyle.LINED -> { stroke(0.5f); hLines(24f, h - 72f, 40f, 40f, w - 40f) }
                PaperStyle.NARROW -> {
                    stroke(0.4f); hLines(18f, h - 60f, 36f, 36f, w - 36f)
                    // margin line on the binding side opposite to the writing start
                    cs.setStrokingColor(0.9f, 0.45f, 0.45f); cs.setLineWidth(0.6f)
                    val x = if (rtl) w - 60f else 60f; cs.moveTo(x, h - 36f); cs.lineTo(x, 24f); cs.stroke()
                }
                PaperStyle.GRID -> {
                    stroke(0.4f); val step = 5 * MM
                    var y = 0f; while (y <= h) { cs.moveTo(0f, y); cs.lineTo(w, y); y += step }
                    var x = 0f; while (x <= w) { cs.moveTo(x, 0f); cs.lineTo(x, h); x += step }
                    cs.stroke()
                }
                PaperStyle.GRAPH_MM -> {
                    val m = 10 * MM
                    val gw = ((w - 2 * m) / (10 * MM)).toInt() * 10; val gh = ((h - 2 * m) / (10 * MM)).toInt() * 10
                    val ox = (w - gw * MM) / 2f; val oy = (h - gh * MM) / 2f
                    for (pass in 0..2) {
                        when (pass) { 0 -> stroke(0.15f, 1f); 1 -> stroke(0.35f, 0.85f); else -> stroke(0.6f, 0.7f) }
                        for (i in 0..gw) { val major = i % 10 == 0; val mid = i % 5 == 0
                            if ((pass == 2 && major) || (pass == 1 && mid && !major) || (pass == 0 && !mid)) { val x = ox + i * MM; cs.moveTo(x, oy); cs.lineTo(x, oy + gh * MM) } }
                        for (j in 0..gh) { val major = j % 10 == 0; val mid = j % 5 == 0
                            if ((pass == 2 && major) || (pass == 1 && mid && !major) || (pass == 0 && !mid)) { val y = oy + j * MM; cs.moveTo(ox, y); cs.lineTo(ox + gw * MM, y) } }
                        cs.stroke()
                    }
                }
                PaperStyle.DOTS -> {
                    cs.setNonStrokingColor(c[0] * 0.8f, c[1] * 0.8f, c[2] * 0.8f); val step = 5 * MM
                    var y = step; while (y < h) { var x = step; while (x < w) { cs.addRect(x - 0.6f, y - 0.6f, 1.2f, 1.2f); x += step }; y += step }
                    cs.fill()
                }
                PaperStyle.ISOMETRIC -> {
                    stroke(0.3f); val step = 5 * MM; val k = Math.tan(Math.toRadians(30.0)).toFloat()
                    var x = 0f; while (x <= w) { cs.moveTo(x, 0f); cs.lineTo(x, h); x += step * 0.866f }
                    var b = -w * k; while (b <= h) { cs.moveTo(0f, b); cs.lineTo(w, b + w * k); cs.moveTo(0f, b + w * k); cs.lineTo(w, b); b += step }
                    cs.stroke()
                }
                PaperStyle.MUSIC -> {
                    stroke(0.5f, 0.6f); val space = 7f; val staff = space * 4; val gap = 46f
                    var top = h - 60f
                    while (top - staff > 40f) {
                        for (l in 0..4) { val y = top - l * space; cs.moveTo(40f, y); cs.lineTo(w - 40f, y) }
                        top -= staff + gap
                    }
                    cs.stroke()
                }
                PaperStyle.CORNELL -> {
                    stroke(0.4f); hLines(22f, h - 110f, 150f, 30f, w - 30f)
                    stroke(0.9f, 0.6f)
                    val cue = if (rtl) w - 170f else 170f
                    cs.moveTo(cue, h - 90f); cs.lineTo(cue, 140f)      // cue column
                    cs.moveTo(30f, h - 90f); cs.lineTo(w - 30f, h - 90f) // title area
                    cs.moveTo(30f, 140f); cs.lineTo(w - 30f, 140f)     // summary
                    cs.stroke()
                }
            }
        }
        return page
    }
}
