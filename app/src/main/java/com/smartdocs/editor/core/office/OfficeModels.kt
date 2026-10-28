package com.smartdocs.editor.core.office

/*
 * Intermediate, renderer-independent models produced by the OOXML readers.
 * Pure Kotlin: no Android types so the parsers can be tested on a plain JVM.
 */

/** Embedded picture bytes with their mime type. */
class ImageData(val bytes: ByteArray, val mime: String)

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** "RRGGBB" without '#', or null for automatic. */
    val color: String? = null,
    /** "RRGGBB" background / highlight. */
    val background: String? = null,
    val sizePt: Float? = null,
    val font: String? = null,
    /** "superscript" / "subscript" / null. */
    val vertAlign: String? = null,
    val rtl: Boolean = false,
)

enum class Align { START, CENTER, END, JUSTIFY, LEFT, RIGHT }

sealed class Inline {
    data class Text(val text: String, val style: RunStyle, val link: String? = null) : Inline()
    class Image(val image: ImageData?, val widthEmu: Long, val heightEmu: Long, val alt: String? = null) : Inline()
    object LineBreak : Inline()
    object Tab : Inline()
    object PageBreak : Inline()
}

data class Paragraph(
    val inlines: List<Inline>,
    val align: Align? = null,
    /** true when the paragraph is explicitly right-to-left (w:bidi). */
    val rtl: Boolean = false,
    /** 0 = body text, 1..6 = heading level, -1 = Title. */
    val heading: Int = 0,
    /** list nesting level (0-based) or -1 when not in a list. */
    val listLevel: Int = -1,
    /** rendered list marker ("•", "1.", "أ-") or null. */
    val listMarker: String? = null,
    val pageBreakBefore: Boolean = false,
    /** index among the body-level paragraphs of document.xml (for editing), -1 inside tables/text boxes. */
    val bodyIndex: Int = -1,
    val indentTwips: Int = 0,
    val shading: String? = null,
) {
    val plainText: String get() = buildString {
        inlines.forEach { when (it) { is Inline.Text -> append(it.text); Inline.Tab -> append('\t'); Inline.LineBreak -> append('\n'); else -> {} } }
    }
}

data class TableCell(
    val blocks: List<Block>,
    val colSpan: Int = 1,
    var rowSpan: Int = 1,
    /** true for a cell swallowed by a vertical merge above (not rendered). */
    val mergedAway: Boolean = false,
    val shading: String? = null,
    val widthTwips: Int = 0,
)

data class TableRow(val cells: List<TableCell>, val header: Boolean = false)

sealed class Block {
    data class Para(val p: Paragraph) : Block()
    data class Table(val rows: List<TableRow>, val rtl: Boolean = false, val colWidthsTwips: List<Int> = emptyList()) : Block()
    object PageBreak : Block()
    object SectionBreak : Block()
}

/** Page of a Word section (twips): size, orientation and margins (`w:sectPr/w:pgSz`, `w:pgMar`). */
data class DocPage(val widthTwips: Int, val heightTwips: Int, val landscape: Boolean, val top: Int, val right: Int, val bottom: Int, val left: Int)

/**
 * A Word document. [header] / [footer]: the default header and footer of the last section; [notes]: footnotes and
 * endnotes (marker → blocks) in reference order; [page]: the last section's page setup (convert audit A-H10 / A-M4).
 */
data class DocModel(
    val blocks: List<Block>, val title: String? = null,
    val header: List<Block> = emptyList(), val footer: List<Block> = emptyList(),
    val notes: List<Pair<String, List<Block>>> = emptyList(), val page: DocPage? = null,
    /** Equations rendered as linear text, and charts / diagrams that could not be rendered. */
    val equations: Int = 0, val unrendered: Int = 0,
) {
    val plainText: String get() = buildString { appendBlocks(blocks, this) }

    private fun appendBlocks(bs: List<Block>, sb: StringBuilder) {
        bs.forEach { b ->
            when (b) {
                is Block.Para -> sb.append(b.p.listMarker?.let { "$it " } ?: "").append(b.p.plainText).append('\n')
                is Block.Table -> b.rows.forEach { r -> r.cells.filter { !it.mergedAway }.forEach { c -> appendBlocks(c.blocks, sb) } }
                else -> sb.append('\n')
            }
        }
    }
}

// ---------------- spreadsheet ----------------

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: String? = null,
    val fill: String? = null,
    val hAlign: String? = null,
    val wrap: Boolean = false,
    val numFmtId: Int = 0,
    val numFmtCode: String? = null,
)

data class SheetCell(
    /** 0-based column index. */
    val col: Int,
    /** Text shown to the user (number formats applied). */
    val display: String,
    /** Raw value as stored (shared string resolved, number as written). */
    val raw: String,
    /** "s" string, "n" number, "b" boolean, "e" error, "d" date, "" empty. */
    val type: String,
    val styleIndex: Int = 0,
    val formula: String? = null,
)

data class SheetRow(val index: Int, val cells: List<SheetCell>, val heightPt: Float? = null)

/** A merged range, 0-based inclusive. */
data class MergeRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

data class SheetModel(
    val name: String,
    val rows: List<SheetRow>,
    val merges: List<MergeRange>,
    /** column index -> width in Excel character units. */
    val colWidths: Map<Int, Float>,
    val rtl: Boolean,
    /** zip path of the worksheet part, used by the writer. */
    val partPath: String = "",
    val defaultColWidth: Float = 8.43f,
    val truncated: Boolean = false,
) {
    val maxCol: Int get() = rows.maxOfOrNull { r -> r.cells.maxOfOrNull { it.col } ?: -1 } ?: -1
    val maxRow: Int get() = rows.maxOfOrNull { it.index } ?: -1
    fun cell(row: Int, col: Int): SheetCell? = rows.firstOrNull { it.index == row }?.cells?.firstOrNull { it.col == col }
}

data class WorkbookModel(val sheets: List<SheetModel>, val styles: List<CellStyle>)

// ---------------- presentation ----------------

data class SlideParagraph(val runs: List<Inline>, val align: Align? = null, val rtl: Boolean = false, val level: Int = 0, val bullet: String? = null)

sealed class SlideShape {
    abstract val x: Long; abstract val y: Long; abstract val w: Long; abstract val h: Long

    data class TextBox(
        override val x: Long, override val y: Long, override val w: Long, override val h: Long,
        val paragraphs: List<SlideParagraph>,
        val fill: String? = null,
        val line: String? = null,
        val anchor: String? = null,
        val defaultSizePt: Float = 18f,
        val rotation: Float = 0f,
    ) : SlideShape()

    class Picture(override val x: Long, override val y: Long, override val w: Long, override val h: Long, val image: ImageData?) : SlideShape()

    data class Table(override val x: Long, override val y: Long, override val w: Long, override val h: Long, val rows: List<List<List<SlideParagraph>>>, val colWidths: List<Long>) : SlideShape()
}

data class SlideModel(val width: Long, val height: Long, val shapes: List<SlideShape>, val background: String? = null, val notes: String? = null,
                      /** Hidden in the slide show (PPTX `show="0"`). */
                      val hidden: Boolean = false)

data class PresentationModel(val slides: List<SlideModel>)

/** Raised when a file is not a readable OOXML package. */
class OfficeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
