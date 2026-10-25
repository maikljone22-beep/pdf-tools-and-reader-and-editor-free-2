package com.smartdocs.editor.core.convert

/*
 * Plain data model of the on-device PDF → Office converter.
 *
 * Everything in `core/convert` except PdfContentExtractor.kt / PdfImageCodec.kt / ConvertEngine.kt is plain
 * Kotlin with no Android or PdfBox imports, so the layout + writer code can be compiled and tested on a
 * desktop JVM (see MODULE_convert.md). All coordinates are PDF points with the origin at the TOP-LEFT of
 * the (crop box of the) page, y growing downwards.
 */

/** One positioned glyph as reported by the PDF text engine (visual position, logical Unicode). */
data class Glyph(
    val x: Float,
    val top: Float,
    val w: Float,
    val h: Float,
    val baseline: Float,
    val text: String,
    val fontSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    val right get() = x + w
    val cx get() = x + w / 2f
    val cy get() = top + h / 2f
}

/** A straight line segment painted on the page (stroked line or a thin filled rectangle). */
data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val horizontal get() = kotlin.math.abs(y1 - y2) < 1.5f && kotlin.math.abs(x2 - x1) >= 4f
    val vertical get() = kotlin.math.abs(x1 - x2) < 1.5f && kotlin.math.abs(y2 - y1) >= 4f
}

/** An image drawn on the page with its placement and encoded bytes (`ext` = "png" or "jpeg"). */
class PageImage(val x: Float, val top: Float, val w: Float, val h: Float, val bytes: ByteArray, val ext: String)

/** Raw extracted content of a page. */
class PageContent(
    val index: Int,
    val width: Float,
    val height: Float,
    val glyphs: List<Glyph>,
    val segments: List<Segment> = emptyList(),
    val images: List<PageImage> = emptyList(),
)

data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    fun contains(x: Float, y: Float, tol: Float = 0f) = x >= x0 - tol && x <= x1 + tol && y >= y0 - tol && y <= y1 + tol
    fun union(o: Rect) = Rect(minOf(x0, o.x0), minOf(y0, o.y0), maxOf(x1, o.x1), maxOf(y1, o.y1))
}

/** A word: glyphs closer than a space apart. [text] is in logical order. */
class Word(val glyphs: List<Glyph>, val text: String, val rtl: Boolean) {
    val x0 = glyphs.minOf { it.x }
    val x1 = glyphs.maxOf { it.right }
    val top = glyphs.minOf { it.top }
    val bottom = glyphs.maxOf { it.top + it.h }
    val fontSize = glyphs.map { it.fontSize }.sorted()[glyphs.size / 2]
    val bold = glyphs.count { it.bold } * 2 > glyphs.size
    val italic = glyphs.count { it.italic } * 2 > glyphs.size
}

/** A text line. [words] are in LOGICAL (reading) order; [visual] keeps them left → right. */
class Line(val visual: List<Word>, val words: List<Word>, val rtl: Boolean, val baseline: Float) {
    val x0 = visual.minOf { it.x0 }
    val x1 = visual.maxOf { it.x1 }
    val top = visual.minOf { it.top }
    val bottom = visual.maxOf { it.bottom }
    val fontSize: Float = run {
        // character-weighted median of word sizes
        val sizes = words.flatMap { w -> List(w.text.length.coerceAtLeast(1)) { w.fontSize } }.sorted()
        sizes[sizes.size / 2]
    }
    val bold = words.sumOf { if (it.bold) it.text.length else 0 } * 2 > words.sumOf { it.text.length }
    val text: String get() = words.joinToString(" ") { it.text }
    val rect get() = Rect(x0, top, x1, bottom)
}

enum class Align { START, CENTER, END }

sealed class Block {
    abstract val rect: Rect
}

/** A paragraph (or heading when [headingLevel] in 1..3). */
class ParagraphBlock(val lines: List<Line>, val rtl: Boolean, val headingLevel: Int, val align: Align) : Block() {
    override val rect: Rect = lines.map { it.rect }.reduce { a, b -> a.union(b) }
    val fontSize get() = lines.map { it.fontSize }.sorted()[lines.size / 2]
}

/** A table cell. [row]/[col] are grid indexes in VISUAL order (col 0 = leftmost). */
class TableCell(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int, val rect: Rect, val lines: List<Line>) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val rtl: Boolean get() = lines.count { it.rtl } * 2 > lines.size
    val bold: Boolean get() = lines.isNotEmpty() && lines.all { it.bold }
}

/** A detected table. [colX] has cols+1 boundaries, [rowY] rows+1 boundaries. */
class TableBlock(val colX: List<Float>, val rowY: List<Float>, val cells: List<TableCell>, val ruled: Boolean) : Block() {
    val rows get() = rowY.size - 1
    val cols get() = colX.size - 1
    override val rect: Rect get() = Rect(colX.first(), rowY.first(), colX.last(), rowY.last())
    /** Arabic dominates the table text → column 0 is read on the right. */
    val rtl: Boolean get() {
        var r = 0; var l = 0
        cells.forEach { c -> val k = BidiText.strongCounts(c.text); r += k[0]; l += k[1] }
        return r > l
    }
}

class ImageBlock(val image: PageImage) : Block() {
    override val rect = Rect(image.x, image.top, image.x + image.w, image.top + image.h)
}

/** Analysed page: blocks in reading order (top → bottom). */
class PageLayout(val index: Int, val width: Float, val height: Float, val blocks: List<Block>, val bodyFontSize: Float) {
    /** Strong RTL / LTR letter counts of the page text. */
    fun strongCounts(): IntArray {
        val rl = IntArray(2)
        fun add(s: String) { val k = BidiText.strongCounts(s); rl[0] += k[0]; rl[1] += k[1] }
        blocks.forEach { b ->
            when (b) {
                is ParagraphBlock -> b.lines.forEach { add(it.text) }
                is TableBlock -> b.cells.forEach { add(it.text) }
                else -> Unit
            }
        }
        return rl
    }

    val rtl: Boolean get() = strongCounts().let { it[0] > it[1] }
    val hasText get() = blocks.any { it is ParagraphBlock || it is TableBlock }
}
