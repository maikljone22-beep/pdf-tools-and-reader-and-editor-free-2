package com.smartdocs.editor.core.edit

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TextAlign { LEFT, CENTER, RIGHT }
enum class FontFamily { SANS, SERIF, MONO }

/**
 * An editable block of text (one line or a paragraph) found on a page. Geometry is in display space
 * (see [PageFrame]): [rect] uses the PDF convention top > bottom. [ops] are the indexes of the top-level
 * text-showing operators that draw it; they belong to this block only.
 */
data class TextBlock(
    val id: Int,
    val text: String,
    val rect: RectF,
    val ops: IntArray,
    val fontName: String,
    val fontReusable: Boolean,
    val family: FontFamily,
    val bold: Boolean,
    val italic: Boolean,
    val size: Float,
    val color: Int,
    val rtl: Boolean,
    val align: TextAlign,
    val firstBaseline: Float,
    val lineHeight: Float,
    val lineCount: Int,
    /** Glyphs without a Unicode mapping were found: the extracted text may be incomplete. */
    val lossy: Boolean,
) {
    override fun equals(other: Any?) = other is TextBlock && other.id == id && other.ops.contentEquals(ops) && other.text == text
    override fun hashCode() = id * 31 + ops.contentHashCode()
}

/** An image painted by the page content. [rect] is its bounding box in display space; [matrix] the placement (user space). */
data class ImageItem(
    val id: Int,
    val op: Int,
    val name: String?,
    val inline: Boolean,
    val rect: RectF,
    val matrix: FloatArray,
    val pixelW: Int,
    val pixelH: Int,
) {
    override fun equals(other: Any?) = other is ImageItem && other.id == id && other.op == op
    override fun hashCode() = id * 31 + op
}

data class PageModel(val page: Int, val width: Float, val height: Float, val rotation: Int, val blocks: List<TextBlock>, val images: List<ImageItem>)

/** Result of analysing a page inside an open document: the model plus live PdfBox objects needed to edit it. */
internal class PageAnalysis(val model: PageModel, val fonts: Map<Int, PDFont?>, val scanner: PageScanner, val frame: PageFrame)

/** Builds [PageModel]s: glyph runs → lines → paragraphs, by baseline and proximity. */
object PageTextModel {

    /** Analyse page [page] of [file] (read only). */
    suspend fun load(file: File, password: String?, page: Int): PageModel = withContext(Dispatchers.IO) {
        EditIo.load(file, password).use { doc -> analyze(doc, page).model }
    }

    internal fun analyze(doc: PDDocument, pageIndex: Int): PageAnalysis {
        val page = doc.getPage(pageIndex)
        val frame = PageFrame.of(page)
        val scanner = PageScanner(page, frame)
        runCatching { scanner.processPage(page) }
        val fonts = HashMap<Int, PDFont?>()
        val blocks = buildBlocks(scanner.glyphs, fonts)
        val images = scanner.images.mapIndexed { i, im ->
            ImageItem(i, im.op, im.name, im.inline, im.boxDisplay, Affine.values(im.ctm), im.pixelW, im.pixelH)
        }
        return PageAnalysis(PageModel(pageIndex, frame.width, frame.height, frame.rotation, blocks, images), fonts, scanner, frame)
    }

    // ---------------- grouping ----------------

    private class Line(val glyphs: MutableList<RawGlyph>, var baseline: Float, var size: Float)

    private class Segment(val glyphs: List<RawGlyph>) {
        val baseline = glyphs.map { it.y }.average().toFloat()
        val size = glyphs.map { it.size }.sorted()[glyphs.size / 2]
        val left = glyphs.minOf { it.x }
        val right = glyphs.maxOf { it.x + it.w }
        val text: String
        val rtl: Boolean
        val lossy = glyphs.any { it.text.isEmpty() }
        init {
            val sb = StringBuilder()
            var prevEnd = Float.NaN
            for (g in glyphs) {
                val t = normalize(g.text)
                if (!prevEnd.isNaN() && g.x - prevEnd > 0.22f * g.size && sb.isNotEmpty() && sb.last() != ' ' && !t.startsWith(" ")) sb.append(' ')
                sb.append(t)
                prevEnd = max(if (prevEnd.isNaN()) g.x + g.w else prevEnd, g.x + g.w)
            }
            val visual = sb.toString().replace(Regex(" {2,}"), " ").trim()
            val ar = visual.count { isArabicChar(it) }
            val lat = visual.count { it.isLetter() && !isArabicChar(it) }
            rtl = ar > 0 && ar >= lat
            text = if (rtl) Bidi.visualToLogical(visual) else visual
        }
    }

    private fun buildBlocks(all: List<RawGlyph>, fontsOut: MutableMap<Int, PDFont?>): List<TextBlock> {
        val glyphs = all.filter { it.text.isNotBlank() || it.text.isEmpty() }
        if (glyphs.isEmpty()) return emptyList()
        // 1. lines by baseline
        val lines = ArrayList<Line>()
        for (g in glyphs.sortedWith(compareByDescending<RawGlyph> { it.y }.thenBy { it.x })) {
            val line = lines.lastOrNull { l -> abs(l.baseline - g.y) <= 0.3f * max(l.size, g.size) && g.size / l.size in 0.6f..1.65f }
            if (line != null) line.glyphs += g else lines += Line(mutableListOf(g), g.y, g.size)
        }
        // 2. split lines into segments at wide gaps (columns, table cells)
        val segments = ArrayList<Segment>()
        for (l in lines) {
            val sorted = l.glyphs.sortedBy { it.x }
            var cur = ArrayList<RawGlyph>()
            var end = Float.NaN
            for (g in sorted) {
                if (cur.isNotEmpty() && g.x - end > 1.8f * max(g.size, l.size)) { segments += Segment(cur); cur = ArrayList() }
                cur += g
                end = if (end.isNaN() || cur.size == 1) g.x + g.w else max(end, g.x + g.w)
            }
            if (cur.isNotEmpty()) segments += Segment(cur)
        }
        // 3. paragraphs: consecutive lines with regular spacing, similar size, overlapping horizontally
        val paragraphs = ArrayList<MutableList<Segment>>()
        for (s in segments.sortedByDescending { it.baseline }) {
            val p = paragraphs.lastOrNull { para ->
                val last = para.last()
                val gap = last.baseline - s.baseline
                val overlap = min(last.right, s.right) - max(last.left, s.left)
                gap > 0.5f * s.size && gap <= 1.75f * max(s.size, last.size) && s.size / last.size in 0.85f..1.18f &&
                    last.rtl == s.rtl && overlap > 0.4f * min(last.right - last.left, s.right - s.left) &&
                    dominantColor(last.glyphs) == dominantColor(s.glyphs)
            }
            if (p != null) p += s else paragraphs += mutableListOf(s)
        }
        // 4. merge paragraphs that share an operator (an operator must be removed as a whole)
        val parent = IntArray(paragraphs.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val owner = HashMap<Int, Int>()
        paragraphs.forEachIndexed { i, para -> para.flatMap { it.glyphs }.forEach { g -> owner[g.op]?.let { o -> parent[find(o)] = find(i) } ?: run { owner[g.op] = i } } }
        val groups = paragraphs.indices.groupBy { find(it) }.values
        return groups.map { idx -> idx.flatMap { paragraphs[it] }.sortedByDescending { it.baseline } }
            .sortedWith(compareByDescending<List<Segment>> { it.first().baseline }.thenBy { it.first().left })
            .mapIndexed { id, segs -> toBlock(id, segs, fontsOut) }
    }

    private fun toBlock(id: Int, segs: List<Segment>, fontsOut: MutableMap<Int, PDFont?>): TextBlock {
        val gl = segs.flatMap { it.glyphs }
        val size = gl.map { it.size }.sorted()[gl.size / 2]
        val font = gl.groupingBy { it.font }.eachCount().maxByOrNull { it.value }?.key
        fontsOut[id] = font
        val name = runCatching { font?.name }.getOrNull() ?: ""
        val lname = name.lowercase()
        val desc = runCatching { font?.fontDescriptor }.getOrNull()
        val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { lname.contains(it) } ||
            (desc?.let { it.isForceBold || it.fontWeight >= 600f } ?: false)
        val italic = lname.contains("italic") || lname.contains("oblique") || (desc?.isItalic ?: false) || gl.map { it.shear }.average() > 0.15
        val family = when {
            listOf("courier", "mono", "consol").any { lname.contains(it) } || (desc?.isFixedPitch ?: false) -> FontFamily.MONO
            listOf("times", "serif", "roman", "georgia", "garamond", "minion", "cambria", "book").any { lname.contains(it) } && !lname.contains("sans") -> FontFamily.SERIF
            desc?.isSerif == true && !lname.contains("sans") -> FontFamily.SERIF
            else -> FontFamily.SANS
        }
        val subset = name.length > 7 && name[6] == '+' && name.substring(0, 6).all { it in 'A'..'Z' }
        val reusable = font != null && font !is PDType3Font && !subset && runCatching { !font.isDamaged && !font.isVertical }.getOrDefault(false)
        val rtl = segs.count { it.rtl } * 2 >= segs.size && segs.any { it.rtl }
        val left = segs.minOf { it.left }; val right = segs.maxOf { it.right }
        val top = segs.first().baseline + segs.first().size * 0.8f
        val bottom = segs.last().baseline - segs.last().size * 0.25f
        val lineHeight = if (segs.size > 1) (segs.first().baseline - segs.last().baseline) / (segs.size - 1) else size * 1.2f
        val ops = gl.map { it.op }.distinct().sorted().toIntArray()
        val text = segs.joinToString(" ") { it.text }.trim()
        val align = when {
            segs.size > 1 && segs.all { abs((it.left + it.right) / 2 - (left + right) / 2) < size * 0.6f } &&
                segs.any { abs(it.left - left) > size } -> TextAlign.CENTER
            rtl -> TextAlign.RIGHT
            else -> TextAlign.LEFT
        }
        return TextBlock(id, text, RectF(left, top, right, bottom), ops, name.substringAfter('+'), reusable, family, bold, italic,
            (size * 2).let { kotlin.math.round(it) / 2f }, dominantColor(gl), rtl, align, segs.first().baseline, lineHeight, segs.size, segs.any { it.lossy })
    }

    private fun dominantColor(gl: List<RawGlyph>): Int = gl.groupingBy { it.color }.eachCount().maxByOrNull { it.value }?.key ?: 0

    internal fun isArabicChar(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    /** Presentation forms (often what Arabic PDFs map glyphs to) back to base letters. */
    private fun normalize(t: String): String {
        if (t.none { it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }) return t
        val sb = StringBuilder()
        for (c in t) {
            if (c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿') sb.append(java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKC))
            else sb.append(c)
        }
        return sb.toString()
    }
}

/** Minimal visual ⇄ logical reordering for one line of right-to-left text with embedded left-to-right runs. */
object Bidi {
    private val mirrored = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    private fun isLtr(c: Char) = (c.isLetterOrDigit() && !PageTextModel.isArabicChar(c)) || c in '٠'..'٩'

    /** Reverse the run order and the characters of right-to-left runs; left-to-right runs keep their order. */
    fun visualToLogical(s: String): String {
        val runs = ArrayList<Pair<Boolean, StringBuilder>>()
        for (c in s) {
            val ltr = isLtr(c) || (c == '.' || c == ',' || c == ':' || c == '/' || c == '-') && runs.lastOrNull()?.first == true
            if (runs.isEmpty() || runs.last().first != ltr) runs += ltr to StringBuilder()
            runs.last().second.append(c)
        }
        // trailing punctuation inside an LTR run that is followed by RTL text belongs to the RTL side
        return runs.asReversed().joinToString("") { (ltr, sb) -> if (ltr) sb.toString() else sb.reversed().map { mirrored[it] ?: it }.joinToString("") }
    }
}
