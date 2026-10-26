package com.smartdocs.editor.core.convert

import kotlin.math.abs

/**
 * Text decorations and backgrounds recovered from page graphics (pure Kotlin):
 *  - underline / strike-through: a short horizontal rule under the baseline / through the middle of a word;
 *    table grid lines (rules touching a vertical rule) and full-width separators are ignored;
 *  - highlight: a filled box behind a word (about one line high) → run shading;
 *  - paragraph shading: a filled box enclosing a whole paragraph;
 *  - table cell fills: the smallest fill covering most of a cell.
 */
object Decorations {

    /** Near-white fills are page backgrounds, not shading. */
    fun isBackground(rgb: Int): Boolean {
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        return r >= 248 && g >= 248 && b >= 248
    }

    private class Rule(val y: Float, val x0: Float, val x1: Float)

    /** Horizontal rules that may decorate text: not grid lines, not page-wide separators. */
    private fun textRules(segments: List<Segment>, pageWidth: Float): List<Rule> {
        val vs = segments.filter { it.vertical }
        return segments.filter { it.horizontal }.mapNotNull { s ->
            val x0 = minOf(s.x1, s.x2); val x1 = maxOf(s.x1, s.x2); val y = (s.y1 + s.y2) / 2f
            if (x1 - x0 > 0.8f * pageWidth) return@mapNotNull null
            val grid = vs.any { v ->
                val vx = (v.x1 + v.x2) / 2f; val vy0 = minOf(v.y1, v.y2); val vy1 = maxOf(v.y1, v.y2)
                (abs(vx - x0) <= 2f || abs(vx - x1) <= 2f) && y >= vy0 - 2f && y <= vy1 + 2f
            }
            if (grid) null else Rule(y, x0, x1)
        }
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0f)

    /** Marks underline / strike / highlight on the words of [lines]. */
    fun decorateWords(lines: List<Line>, segments: List<Segment>, fills: List<Fill>, pageWidth: Float) {
        val rules = textRules(segments, pageWidth)
        val highlights = fills.filter { !isBackground(it.color) }
        for (line in lines) {
            val lineRules = rules.filter { r -> r.x0 >= line.x0 - 2f * line.fontSize && r.x1 <= line.x1 + 2f * line.fontSize }
            for (w in line.visual) {
                val ww = (w.x1 - w.x0).coerceAtLeast(0.5f)
                val base = w.glyphs.map { it.baseline }.sorted()[w.glyphs.size / 2]
                val fs = w.fontSize
                val h = w.bottom - w.top
                for (r in lineRules) {
                    if (overlap(w.x0, w.x1, r.x0, r.x1) < 0.6f * ww) continue
                    if (r.y >= base - 0.08f * fs && r.y <= base + 0.3f * fs) w.underline = true
                    else if (r.y >= w.top + 0.3f * h && r.y <= w.top + 0.8f * h) w.strike = true
                }
                val cx = (w.x0 + w.x1) / 2f; val cy = (w.top + w.bottom) / 2f
                highlights.filter { f ->
                    f.rect.contains(cx, cy) && f.rect.h <= 2.6f * fs && f.rect.h >= 0.6f * h && f.rect.w <= 0.9f * pageWidth
                }.minByOrNull { it.rect.w * it.rect.h }?.let { w.bg = it.color }
            }
        }
    }

    /** Shading of a paragraph: the smallest fill that encloses it but is not just one line's highlight. */
    fun shadeParagraph(p: ParagraphBlock, fills: List<Fill>) {
        val r = p.rect
        p.shade = fills.filter { f ->
            !isBackground(f.color) && f.rect.contains(r.x0, r.y0, 3f) && f.rect.contains(r.x1, r.y1, 3f) &&
                (p.lines.size > 1 || f.rect.h > 2.6f * p.fontSize)
        }.minByOrNull { it.rect.w * it.rect.h }?.color
    }

    /** Fill of each cell: the smallest fill covering ≥ 70 % of it (and not much larger than the table itself). */
    fun fillCells(t: TableBlock, fills: List<Fill>) {
        if (fills.isEmpty()) return
        val tr = t.rect
        val cands = fills.filter { f -> !isBackground(f.color) && f.rect.w * f.rect.h <= 1.2f * tr.w * tr.h + 1f }
        for (c in t.cells) {
            val area = (c.rect.w * c.rect.h).coerceAtLeast(1f)
            c.fill = cands.filter { f ->
                overlap(f.rect.x0, f.rect.x1, c.rect.x0, c.rect.x1) * overlap(f.rect.y0, f.rect.y1, c.rect.y0, c.rect.y1) >= 0.7f * area
            }.minByOrNull { it.rect.w * it.rect.h }?.color
        }
    }

    /** 0xRRGGBB → "RRGGBB". */
    fun hex(rgb: Int): String = String.format("%06X", rgb and 0xFFFFFF)
}
