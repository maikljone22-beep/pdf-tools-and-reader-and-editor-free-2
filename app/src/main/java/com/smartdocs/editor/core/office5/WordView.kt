package com.smartdocs.editor.core.office5

/*
 * Renderer-independent view of a WordDoc for the editor UI: paragraphs with character spans carrying the
 * effective formatting (docDefaults → paragraph style → character style → direct), list markers, pictures.
 */

class WSpan(val start: Int, val end: Int, val props: RunProps)

class WImage(val drawing: XElem, val offset: Int, val cx: Long, val cy: Long, val bytes: ByteArray?)

class WPara(
    val elem: XElem,
    val text: String,
    val spans: List<WSpan>,
    val align: PAlign?,
    val rtl: Boolean,
    /** 0 body, 1..9 heading, -1 title. */
    val heading: Int,
    val marker: String?,
    val listLevel: Int,
    val indentTwips: Int,
    val images: List<WImage>,
    val baseSizePt: Float,
    val styleKind: WordParts.StyleKind,
    val pageBreakBefore: Boolean,
)

class WCell(val elem: XElem, val paragraphs: List<WPara>, val span: Int, val mergedAway: Boolean, val shading: String?)

sealed class WBlock {
    abstract val elem: XElem
    class Para(val p: WPara) : WBlock() { override val elem get() = p.elem }
    class Table(override val elem: XElem, val rows: List<List<WCell>>, val rtl: Boolean, val gridCols: Int) : WBlock()
    class PageBreak(override val elem: XElem) : WBlock()
    /** Content controls and other body-level markup, shown read-only. */
    class Other(override val elem: XElem, val text: String) : WBlock()
}

object WordView {
    const val DEFAULT_SIZE = 11f

    fun build(d: WordDoc): List<WBlock> {
        d.numbering.reset()
        return d.blocks().map { b ->
            when (b.local) {
                "p" -> if (d.isPageBreak(b)) WBlock.PageBreak(b) else WBlock.Para(para(d, b))
                "tbl" -> table(d, b)
                else -> WBlock.Other(b, b.findAll("p").joinToString("\n") { WordRuns.text(it) })
            }
        }
    }

    fun table(d: WordDoc, t: XElem): WBlock.Table {
        val rows = WordTables.rows(t).map { tr ->
            WordTables.cells(tr).map { tc ->
                WCell(tc, WordTables.cellParagraphs(tc).map { para(d, it) }, WordTables.span(tc), WordTables.vMerge(tc) == "continue", WordTables.shading(tc))
            }
        }
        val grid = t.child("tblGrid")?.children("gridCol")?.size ?: 0
        return WBlock.Table(t, rows, WordTables.isRtl(t), grid.coerceAtLeast(rows.maxOfOrNull { r -> r.sumOf { it.span } } ?: 1))
    }

    /** View of one paragraph; [keepMarker] reuses a list marker instead of advancing the list counters. */
    fun para(d: WordDoc, p: XElem, keepMarker: String? = null, reuseMarker: Boolean = false): WPara {
        val sid = WordFormat.styleId(p)
        val chain = d.styles.chain(sid)
        val base = d.styles.runProps(sid)
        val heading = d.styles.headingLevel(sid)
        val spans = ArrayList<WSpan>(); val images = ArrayList<WImage>()
        val sb = StringBuilder()
        for (r in WordRuns.runs(p)) {
            val direct = WordFormat.read(r.child("rPr"))
            val charStyle = r.child("rPr")?.child("rStyle")?.attr("val")?.let { cs -> d.styles.chain(cs).asReversed().fold(RunProps()) { a, s -> a.over(s.run) } }
            val eff = base.over(charStyle).over(direct)
            val start = sb.length
            for (k in r.elems()) {
                if (k.local == "rPr") continue
                if (k.local == "drawing") {
                    val (cx, cy) = d.drawingSize(k)
                    images.add(WImage(k, sb.length, cx, cy, d.drawingImage(k)))
                }
                sb.append(WordRuns.pieceText(k))
            }
            if (sb.length > start) spans.add(WSpan(start, sb.length, eff))
        }
        val num = WordFormat.numbering(p) ?: chain.firstOrNull { it.numId != null }?.let { it.numId!! to it.ilvl }
        val marker = if (reuseMarker) keepMarker else num?.takeIf { it.first != "0" }?.let { d.numbering.next(it.first, it.second) }
        val align = WordFormat.align(p) ?: chain.firstNotNullOfOrNull { it.align }
        val rtl = WordFormat.bidi(p) ?: chain.firstNotNullOfOrNull { it.bidi }
            ?: (sb.isNotEmpty() && com.smartdocs.editor.core.office.hasRtlChars(sb.toString()) && !sb.any { it in 'A'..'z' })
        return WPara(
            p, sb.toString(), spans, align, rtl, heading, marker, num?.second ?: -1, WordFormat.indent(p), images,
            base.sizePt ?: DEFAULT_SIZE, d.styleKindOf(p), WordFormat.pageBreakBefore(p),
        )
    }

    /** Formatting at [offset] of a paragraph view (for the ribbon toggle states). */
    fun propsAt(v: WPara, start: Int, end: Int): RunProps {
        val hits = v.spans.filter { it.start < maxOf(end, start + 1) && it.end > start }.ifEmpty { v.spans.filter { it.end == start }.takeLast(1) }
        if (hits.isEmpty()) return RunProps()
        fun all(f: (RunProps) -> Boolean?) = hits.all { f(it.props) == true }
        return RunProps(
            bold = all { it.bold }, italic = all { it.italic }, underline = all { it.underline }, strike = all { it.strike },
            sizePt = hits.first().props.sizePt, color = hits.first().props.color, highlight = hits.first().props.highlight, font = hits.first().props.font,
        )
    }
}
