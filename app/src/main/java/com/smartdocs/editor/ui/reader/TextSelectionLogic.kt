package com.smartdocs.editor.ui.reader

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for text selection built on PDFium character boxes (PDF space, origin bottom-left).
 * Character index i of [boxes] corresponds to character i of `session.text(page)`.
 */
object TextSelectionLogic {

    private fun valid(r: RectF) = abs(r.width()) > 0.01f && abs(r.height()) > 0.01f

    /** Index of the character under (or nearest to) a PDF point, within [tolerance] points. */
    fun charAt(boxes: List<RectF>, x: Float, y: Float, tolerance: Float = 12f): Int? {
        var best = -1; var bestD = Float.MAX_VALUE
        boxes.forEachIndexed { i, raw ->
            if (!valid(raw)) return@forEachIndexed
            val r = PageGeometry.normPdf(raw)
            val inside = x >= r.left && x <= r.right && y <= r.top && y >= r.bottom
            if (inside) { best = i; bestD = 0f; return@forEachIndexed }
            val dx = when { x < r.left -> r.left - x; x > r.right -> x - r.right; else -> 0f }
            val dy = when { y > r.top -> y - r.top; y < r.bottom -> r.bottom - y; else -> 0f }
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= tolerance * tolerance) best else null
    }

    /** Word boundaries around [index] in [text]. */
    fun wordAt(text: String, index: Int): IntRange {
        if (text.isEmpty()) return 0..0
        val i = index.coerceIn(0, text.length - 1)
        if (text[i].isWhitespace()) return i..i
        var s = i; var e = i
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < text.length - 1 && !text[e + 1].isWhitespace()) e++
        return s..e
    }

    /**
     * Merge the character boxes of [start]..[end] (inclusive) into one rect per text line.
     * Lines are detected by vertical overlap of consecutive boxes.
     */
    fun rects(boxes: List<RectF>, start: Int, end: Int): List<RectF> {
        val s = min(start, end).coerceAtLeast(0); val e = max(start, end).coerceAtMost(boxes.size - 1)
        if (s > e) return emptyList()
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in s..e) {
            val raw = boxes[i]; if (!valid(raw)) continue
            val r = PageGeometry.normPdf(raw)
            val c = cur
            if (c == null) { cur = RectF(r); continue }
            val overlap = min(c.top, r.top) - max(c.bottom, r.bottom)
            val h = min(c.top - c.bottom, r.top - r.bottom)
            if (overlap > h * 0.4f) {
                c.left = min(c.left, r.left); c.right = max(c.right, r.right)
                c.top = max(c.top, r.top); c.bottom = min(c.bottom, r.bottom)
            } else { out += c; cur = RectF(r) }
        }
        cur?.let { out += it }
        return out
    }

    fun text(pageText: String, start: Int, end: Int): String {
        if (pageText.isEmpty()) return ""
        val s = min(start, end).coerceIn(0, pageText.length - 1); val e = max(start, end).coerceIn(0, pageText.length - 1)
        // readable Arabic for copy/share/translate: presentation forms -> letters, visual order -> logical order
        return com.smartdocs.editor.core.search.ArabicTextFix.fix(pageText.substring(s, e + 1).replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** Characters whose boxes intersect a PDF rect — used by the "select by rectangle" markup gesture. */
    fun charsInRect(boxes: List<RectF>, rect: RectF): IntRange? {
        val r = PageGeometry.normPdf(rect)
        var first = -1; var last = -1
        boxes.forEachIndexed { i, raw ->
            if (!valid(raw)) return@forEachIndexed
            val b = PageGeometry.normPdf(raw)
            val hit = b.right > r.left && b.left < r.right && b.top > r.bottom && b.bottom < r.top
            if (hit) { if (first < 0) first = i; last = i }
        }
        return if (first < 0) null else first..last
    }
}
