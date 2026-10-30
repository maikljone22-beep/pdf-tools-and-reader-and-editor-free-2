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
    /** Fill colour 0xRRGGBB (0 = black). */
    val color: Int = 0,
    /** Common font family name ([FontMap]), null = unknown. */
    val font: String? = null,
    /**
     * Text of an OCR layer, not typeset text: invisible (rendering mode 3/7) or the visible re-typed words of a page an older
     * OCR build rebuilt ([OcrText]). Its size, baseline and box come from recognised word boxes, so they are normalised
     * per line, it never counts as a duplicate of real text, and the OCR junk rules apply to it.
     */
    val ocr: Boolean = false,
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

/**
 * An image drawn on the page with its placement and encoded bytes (`ext` = "png" or "jpeg"). [maskText]: a tiny
 * solid-colour image drawn through a soft mask that holds glyphs (Word "text with effects"): a picture of text.
 */
class PageImage(val x: Float, val top: Float, val w: Float, val h: Float, val bytes: ByteArray, val ext: String, val maskText: Boolean = false)

/** A filled (shaded) rectangle painted on the page, colour 0xRRGGBB. */
class Fill(val rect: Rect, val color: Int)

/** Raw extracted content of a page. */
class PageContent(
    val index: Int,
    val width: Float,
    val height: Float,
    val glyphs: List<Glyph>,
    val segments: List<Segment> = emptyList(),
    val images: List<PageImage> = emptyList(),
    val fills: List<Fill> = emptyList(),
    /** The page is one an older OCR build rebuilt (page picture + visible re-typed OCR words, [OcrText.ourVisibleLayer]). */
    val rebuiltByOcr: Boolean = false,
    /** Markup annotations with a note (FINAL_CHECK_2 #5: DOCX export with comments). */
    val comments: List<PageComment> = emptyList(),
)

/** A PDF comment (markup annotation with text) in page frame coordinates (x right, y down). [kind]: the annotation subtype. */
data class PageComment(val rect: Rect, val author: String, val date: String, val text: String, val kind: String)

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
    /** Majority glyph colour / font family. */
    val color: Int = glyphs.groupingBy { it.color }.eachCount().maxByOrNull { it.value }!!.key
    val font: String? = glyphs.mapNotNull { it.font }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    /** Set by [Decorations]: a rule drawn under / through the word, and a highlight box behind it. */
    var underline = false
    var strike = false
    var bg: Int? = null
    /** Recognised (OCR) text, see [Glyph.ocr]. */
    val ocr: Boolean = glyphs.count { it.ocr } * 2 > glyphs.size
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
    /** Every word is recognised (OCR) text. */
    val ocr: Boolean get() = words.all { it.ocr }
    /** No strong letter at all (digits, punctuation, symbols): such a line takes the direction of its paragraph. */
    val neutral: Boolean get() = BidiText.strongCounts(text).let { it[0] == 0 && it[1] == 0 }
}

enum class Align { START, CENTER, END }

sealed class Block {
    abstract val rect: Rect
}

/** A paragraph (or heading when [headingLevel] in 1..3). */
class ParagraphBlock(val lines: List<Line>, val rtl: Boolean, val headingLevel: Int, val align: Align) : Block() {
    override val rect: Rect = lines.map { it.rect }.reduce { a, b -> a.union(b) }
    val fontSize get() = lines.map { it.fontSize }.sorted()[lines.size / 2]
    /** Paragraph shading from a box painted behind it (0xRRGGBB), set by [Decorations]. */
    var shade: Int? = null
}

/** A table cell. [row]/[col] are grid indexes in VISUAL order (col 0 = leftmost). */
class TableCell(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int, val rect: Rect, val lines: List<Line>) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val rtl: Boolean get() = lines.count { it.rtl } * 2 > lines.size
    val bold: Boolean get() = lines.isNotEmpty() && lines.all { it.bold }
    /** Cell background colour from the PDF (0xRRGGBB), set by [Decorations]. */
    var fill: Int? = null
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
class PageLayout(
    val index: Int, val width: Float, val height: Float, val blocks: List<Block>, val bodyFontSize: Float,
    /** Running header / footer lines taken out of [blocks] ([PageFurniture]). */
    val header: List<Line> = emptyList(),
    val footer: List<Line> = emptyList(),
    /** The PDF painted its own fills (cell / paragraph backgrounds): writers skip synthetic header shading. */
    val hasFills: Boolean = false,
    /** See [PageContent.rebuiltByOcr]. */
    val rebuiltByOcr: Boolean = false,
    /** Recognised (OCR) characters kept on the page. */
    val ocrChars: Int = 0,
    /** The page's comments ([PageContent.comments]). */
    val comments: List<PageComment> = emptyList(),
) {
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
