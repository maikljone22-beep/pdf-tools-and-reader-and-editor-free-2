package com.smartdocs.editor.core.view5

import kotlin.math.abs

/** A text line of a page in PDF space (y up): [size] is the median glyph height. */
data class RLine(val text: String, val size: Float, val top: Float, val bottom: Float, val left: Float, val right: Float)

/** An image placed on the page (PDF space, y up). */
data class RImage(val top: Float, val bottom: Float, val left: Float, val right: Float)

sealed interface RBlock {
    val page: Int
    data class Heading(override val page: Int, val text: String, val level: Int) : RBlock
    data class Para(override val page: Int, val text: String, val rtl: Boolean) : RBlock
    data class Image(override val page: Int, val img: RImage) : RBlock
    data class PageMark(override val page: Int) : RBlock
}

/**
 * Turns the lines and images of a page into reading blocks: headings (by glyph size relative to the
 * body text), paragraphs (lines joined while the gap and size stay similar), images in reading order,
 * page numbers and running headers / footers dropped. Pure, unit tested.
 */
object ReflowBlocks {

    fun isRtl(s: String): Boolean {
        var ar = 0; var lat = 0
        s.forEach { c -> if (c.code in 0x0590..0x08FF || c.code in 0xFB50..0xFEFF) ar++ else if (c.isLetter()) lat++ }
        return ar > lat
    }

    /** Median glyph size weighted by text length. */
    fun bodySize(lines: List<RLine>): Float {
        val ws = lines.filter { it.text.isNotBlank() && it.size > 0 }.sortedBy { it.size }
        if (ws.isEmpty()) return 10f
        val total = ws.sumOf { it.text.length }
        var acc = 0
        for (l in ws) { acc += l.text.length; if (acc * 2 >= total) return l.size }
        return ws.last().size
    }

    private val pageNumber = Regex("""^\s*(?:page|صفحة|ص)?\s*[\-–(]?\s*[0-9٠-٩]{1,4}\s*(?:/\s*[0-9٠-٩]{1,4})?\s*[\-–)]?\s*$""", RegexOption.IGNORE_CASE)

    /** True for lines that look like running headers / footers: page numbers in the top or bottom 7 % of the page. */
    fun isFurniture(l: RLine, pageH: Float): Boolean {
        val edge = l.bottom > pageH * 0.93f || l.top < pageH * 0.07f
        return edge && pageNumber.matches(l.text)
    }

    fun build(page: Int, lines: List<RLine>, images: List<RImage>, pageH: Float, bodyOverride: Float? = null): List<RBlock> {
        val body = bodyOverride ?: bodySize(lines)
        val kept = lines.filter { it.text.isNotBlank() && !isFurniture(it, pageH) }.sortedByDescending { it.top }
        // merge lines and images by their top edge
        data class Item(val top: Float, val line: RLine?, val img: RImage?)
        val items = (kept.map { Item(it.top, it, null) } + images.map { Item(it.top, null, it) }).sortedByDescending { it.top }
        val out = ArrayList<RBlock>()
        out += RBlock.PageMark(page)
        var buf = StringBuilder(); var prev: RLine? = null
        fun flush() {
            if (buf.isNotBlank()) { val t = buf.toString().trim(); out += RBlock.Para(page, t, isRtl(t)) }
            buf = StringBuilder(); prev = null
        }
        for (it in items) {
            val img = it.img
            if (img != null) { flush(); out += RBlock.Image(page, img); continue }
            val l = it.line ?: continue
            val ratio = l.size / body.coerceAtLeast(0.1f)
            if (ratio >= 1.25f && l.text.trim().length <= 140) {
                flush()
                val last = out.lastOrNull()
                val lvl = if (ratio >= 1.6f) 1 else 2
                // a heading split over two lines stays one heading
                if (last is RBlock.Heading && last.level == lvl) out[out.size - 1] = last.copy(text = last.text + " " + l.text.trim())
                else out += RBlock.Heading(page, l.text.trim(), lvl)
                continue
            }
            val p = prev
            val newPara = p == null || (p.bottom - l.top) > 1.0f * maxOf(p.size, l.size) || abs(p.size - l.size) > 0.2f * maxOf(p.size, l.size)
                    || p.text.trimEnd().let { s -> s.endsWith(":") && s.length < 50 }
            if (newPara) flush()
            val t = l.text.trim()
            if (buf.isNotEmpty()) {
                if (buf.endsWith("-") && !isRtl(t)) buf.setLength(buf.length - 1) else buf.append(' ')
            }
            buf.append(t); prev = l
        }
        flush()
        return out
    }
}
