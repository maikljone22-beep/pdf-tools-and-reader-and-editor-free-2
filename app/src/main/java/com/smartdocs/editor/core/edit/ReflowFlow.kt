package com.smartdocs.editor.core.edit

import com.smartdocs.editor.core.view5.CBox
import com.smartdocs.editor.core.view5.ReflowColumns
import kotlin.math.abs
import kotlin.math.max

/** A text block of the flow (display space, top > bottom), from [TextBlock] without Android types. */
data class FlowSrc(
    val page: Int, val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float,
    val size: Float, val color: Int, val bold: Boolean, val italic: Boolean, val rtl: Boolean,
    val family: FontFamily = FontFamily.SANS, val align: TextAlign = TextAlign.LEFT, val firstBaseline: Float = top - size,
    /** Baseline distance of a multi-line block (null for one line). */
    val lineHeight: Float? = null,
)

/** An area the flowing text is written into: one column run of one page (display space). */
data class FlowFrame(val page: Int, val left: Float, val right: Float, val firstBaseline: Float, val bottom: Float)

/**
 * The text of a page range as one flow (§14d reflow editing): [text] has one paragraph per line, a paragraph
 * that crosses a column or page break stays one; [runs] keep bold / italic / size / colour of blocks that differ
 * from [base]; [frames] are the column areas in reading order the text is written back into.
 */
data class Flow(val pages: IntRange, val text: String, val runs: List<RunStyle>, val base: EditTextStyle, val rtl: Boolean, val frames: List<FlowFrame>)

/** Pure building and splitting of [Flow]s (unit tested); [TextEdit5.reflowRange] does the PDF part. */
object ReflowFlow {

    private fun isArabic(c: Char) = c.code in 0x0590..0x08FF || c.code in 0xFB50..0xFEFF

    private val terminal = setOf('.', '!', '?', ':', ';', '؟', '۔', '؛', '"', '”', '»', ')')

    /** Joins two line parts: a Latin hyphen at the break goes before a lowercase letter, stays (no space) before others. */
    private fun joinAt(sb: StringBuilder, prevLatin: Boolean, next: Char) {
        if (sb.endsWith("-") && prevLatin) { if (next.isLowerCase()) sb.setLength(sb.length - 1) } else sb.append(' ')
    }

    /** Text of one block as one paragraph line (its own line breaks joined, Latin hyphens removed). */
    fun oneLine(text: String): String {
        val sb = StringBuilder()
        text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
            if (sb.isNotEmpty()) {
                val prevLatin = sb.length >= 2 && sb[sb.length - 2].isLetter() && !isArabic(sb[sb.length - 2])
                joinAt(sb, prevLatin, l.first())
            }
            sb.append(l)
        }
        return sb.toString()
    }

    /** True when [b] continues [a] across a column or page break: the sentence is not finished and the look matches. */
    fun continues(a: FlowSrc, b: FlowSrc): Boolean {
        val t = a.text.trimEnd()
        if (t.isEmpty() || t.last() in terminal) return false
        if (abs(a.size - b.size) > 0.12f * max(a.size, b.size)) return false
        return a.bold == b.bold && a.italic == b.italic
    }

    /** The body style: the size / look of most of the text. */
    fun bodyOf(blocks: List<FlowSrc>): FlowSrc = blocks.groupBy { Triple((it.size * 2).toInt(), it.bold, it.italic) }
        .maxBy { (_, v) -> v.sumOf { it.text.length } }.value.maxBy { it.text.length }

    /**
     * Groups of [blocks] of one page in column reading order (a frame each). A group is split again where the
     * blocks leave a tall gap (a picture between paragraphs), so the text never flows over it.
     */
    fun pageGroups(blocks: List<FlowSrc>, rtl: Boolean): List<List<FlowSrc>> {
        val boxes = blocks.map { CBox(it.left, it.right, it.top, it.bottom, it.text.length) }
        return ReflowColumns.groups(boxes, rtl).flatMap { g ->
            val out = ArrayList<List<FlowSrc>>(); var cur = ArrayList<FlowSrc>()
            for (i in g) {
                val b = blocks[i]; val prev = cur.lastOrNull()
                if (prev != null && prev.bottom - b.top > max(36f, 3f * max(prev.size, b.size) * 1.25f)) { out += cur; cur = ArrayList() }
                cur += b
            }
            if (cur.isNotEmpty()) out += cur
            out
        }
    }

    /** Builds the flow of [byPage] (the upright, readable blocks of every page of [pages]). */
    fun build(pages: IntRange, byPage: Map<Int, List<FlowSrc>>): Flow {
        val all = pages.flatMap { byPage[it].orEmpty() }
        require(all.isNotEmpty()) { "no text" }
        val rtl = all.count { it.rtl } * 2 > all.size
        val frames = ArrayList<FlowFrame>()
        val ordered = ArrayList<FlowSrc>()
        for (p in pages) {
            val bs = byPage[p].orEmpty()
            if (bs.isEmpty()) continue
            for (g in pageGroups(bs, rtl)) {
                frames += FlowFrame(p, g.minOf { it.left }, g.maxOf { it.right }, g.first().firstBaseline, g.minOf { it.bottom })
                ordered += g
            }
        }
        val body = bodyOf(all)
        val base = EditTextStyle(size = body.size, color = body.color, bold = body.bold, italic = body.italic, align = body.align, family = body.family,
            lineHeight = all.filter { abs(it.size - body.size) < 0.05f * body.size }.mapNotNull { it.lineHeight }.sorted().let { it.getOrNull(it.size / 2) },
            paraSpacing = body.size * 0.4f)
        val sb = StringBuilder(); val runs = ArrayList<RunStyle>()
        var prev: FlowSrc? = null
        for (b in ordered) {
            val t = oneLine(b.text)
            if (t.isEmpty()) continue
            val p = prev
            if (p != null) {
                if (continues(p, b)) {
                    val prevLatin = sb.length >= 2 && sb[sb.length - 2].isLetter() && !isArabic(sb[sb.length - 2])
                    joinAt(sb, prevLatin, t.first())
                } else sb.append('\n')
            }
            val start = sb.length
            sb.append(t)
            runOf(b, base, start, sb.length)?.let { runs += it }
            prev = b
        }
        return Flow(pages, sb.toString(), merge(runs), base, rtl, frames)
    }

    /** Style run of a block that looks different from [base], or null. */
    fun runOf(b: FlowSrc, base: EditTextStyle, start: Int, end: Int): RunStyle? {
        val size = b.size.takeIf { abs(it - base.size) > 0.05f * base.size }
        val r = RunStyle(start, end, bold = b.bold.takeIf { it != base.bold }, italic = b.italic.takeIf { it != base.italic },
            color = b.color.takeIf { it != base.color }, size = size)
        return r.takeIf { it.bold != null || it.italic != null || it.color != null || it.size != null }
    }

    /** Joins touching runs with the same look (a paragraph made of several blocks of one style). */
    fun merge(runs: List<RunStyle>): List<RunStyle> {
        val out = ArrayList<RunStyle>()
        for (r in runs.sortedBy { it.start }) {
            val l = out.lastOrNull()
            if (l != null && r.start - l.end <= 1 && l.copy(start = 0, end = 0) == r.copy(start = 0, end = 0)) out[out.size - 1] = l.copy(end = r.end)
            else out += r
        }
        return out
    }

    /**
     * How many lines fit [boxHeight] (first baseline to the last one, display points): every line advances by
     * max(line height, 1.2 × its biggest size) plus [paraSpacing] after a paragraph's last line, as [RichLayout] draws.
     * [maxSizes] / [lastOfPara] describe the laid-out lines. At least one line always fits.
     */
    fun fit(maxSizes: List<Float>, lastOfPara: List<Boolean>, style: EditTextStyle, boxHeight: Float): Int {
        val baseLh = (style.lineHeight ?: (style.size * 1.25f)) * style.lineSpacing
        var used = 0f; var k = 0
        while (k < maxSizes.size) {
            if (k > 0) {
                val add = max(baseLh, maxSizes[k] * 1.2f * style.lineSpacing) + if (lastOfPara[k - 1]) style.paraSpacing else 0f
                if (used + add > boxHeight) break
                used += add
            }
            k++
        }
        return k
    }

    /**
     * Where [text] is cut after its first [k] laid-out lines ([lineTexts]: the logical text of every line): the end
     * of the head and the start of the tail (the space or line break between them is dropped).
     */
    fun cut(text: String, lineTexts: List<String>, k: Int): Pair<Int, Int> {
        if (k >= lineTexts.size) return text.length to text.length
        var start = 0; var end = 0
        for (i in 0 until k) {
            val l = lineTexts[i]
            val at = if (l.isEmpty()) start else text.indexOf(l, start).takeIf { it >= 0 } ?: start
            end = (at + l.length).coerceAtMost(text.length)
            // the next line starts after the space or line break the layout consumed
            start = end
            if (start < text.length && (text[start] == ' ' || text[start] == '\n')) start++
        }
        return end to start
    }

    /** [runs] for the part [from]..[to] of the text, rebased to start at 0. */
    fun slice(runs: List<RunStyle>, from: Int, to: Int): List<RunStyle> =
        runs.mapNotNull { r -> val s = maxOf(r.start, from) - from; val e = minOf(r.end, to) - from; if (e > s) r.copy(start = s, end = e) else null }
}
