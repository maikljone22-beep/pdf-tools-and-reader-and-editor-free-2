package com.smartdocs.editor.core.edit

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }
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
    /**
     * The text cannot be read back at all (most glyphs have no Unicode mapping, or only private-use codes): the
     * editor must not show [text] as editable; offer OCR or replacing the whole block instead.
     */
    val unreadable: Boolean = false,
    /** NVE-text: rotation of the text row (degrees, ccw); 0 for upright text. [rect] is then the display bounding box. */
    val angle: Float = 0f,
    /** NVE-text: for rotated blocks, the block rect in its own upright frame (rotate by [angle] to get display space). */
    val frameRect: RectF? = null,
) {
    /** NVE-text: drawn (partly) inside a form XObject. */
    val inForm: Boolean get() = ops.any { FormOps.isForm(it) }
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

data class PageModel(
    val page: Int, val width: Float, val height: Float, val rotation: Int, val blocks: List<TextBlock>, val images: List<ImageItem>,
    /** PDF user space → display space (6 affine values, see [Affine.values]), e.g. to place PDFium character boxes. */
    val toDisplay: FloatArray = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f),
)

/** Result of analysing a page inside an open document: the model plus live PdfBox objects needed to edit it. */
internal class PageAnalysis(val model: PageModel, val fonts: Map<Int, PDFont?>, val scanner: PageScanner, val frame: PageFrame)

/** Builds [PageModel]s: glyph runs → lines → paragraphs, by baseline and proximity. */
object PageTextModel {
    /**
     * NVE-text: grouping options (edit preferences). [lineMode]: every line is its own block (no paragraphs);
     * [wordGap]: gap between glyphs, in font sizes, read as a word space; [columnGap]: gap that splits a line in two blocks.
     */
    class Options(val lineMode: Boolean = false, val wordGap: Float = 0.22f, val columnGap: Float = 1.8f)
    @Volatile var options = Options()

    /** Analyse page [page] of [file] (read only). */
    suspend fun load(file: File, password: String?, page: Int): PageModel = withContext(Dispatchers.IO) {
        EditIo.load(file, password).use { doc -> analyze(doc, page).model }
    }

    /**
     * audit 5 C-8: analyse several [pages] of [file] with ONE load (find & replace used to load the whole document
     * once per page). Returns (page, model) pairs; a page that fails to analyse is skipped, but errors (out of
     * memory) and cancellation propagate.
     */
    suspend fun loadPages(file: File, password: String?, pages: List<Int>, onPage: (Int) -> Unit = {}): List<Pair<Int, PageModel>> = withContext(Dispatchers.IO) {
        EditIo.load(file, password).use { doc ->
            pages.filter { it in 0 until doc.numberOfPages }.mapNotNull { p ->
                ensureActive()
                onPage(p)
                try { p to analyze(doc, p).model } catch (e: Exception) { null }
            }
        }
    }

    internal fun analyze(doc: PDDocument, pageIndex: Int): PageAnalysis {
        val page = doc.getPage(pageIndex)
        val frame = PageFrame.of(page)
        val scanner = PageScanner(page, frame)
        runCatching { scanner.processPage(page) }
        val fonts = HashMap<Int, PDFont?>()
        val blocks = ArrayList(buildBlocks(scanner.glyphs, fonts, 0))
        // NVE-text: rotated rows, grouped in their own upright frame, reported with their display bounding box
        for ((angle, gl) in scanner.rotated) {
            val rot = Affine.rotateAround(angle, 0f, 0f)
            buildBlocks(gl, fonts, blocks.size).forEach { b ->
                blocks += b.copy(rect = Affine.box(rot, b.rect.left, b.rect.bottom, b.rect.right, b.rect.top), angle = angle, frameRect = b.rect)
            }
        }
        val images = scanner.images.mapIndexed { i, im ->
            ImageItem(i, im.op, im.name, im.inline, im.boxDisplay, Affine.values(im.ctm), im.pixelW, im.pixelH)
        }
        return PageAnalysis(PageModel(pageIndex, frame.width, frame.height, frame.rotation, blocks, images, Affine.values(frame.toDisplay)), fonts, scanner, frame)
    }

    // ---------------- grouping ----------------

    private class Line(val glyphs: MutableList<RawGlyph>, var baseline: Float, var size: Float)

    /** A glyph with the combining marks drawn over it (tashkeel), in visual order. */
    private class GlyphUnit(val base: RawGlyph, val text: String, val marks: StringBuilder = StringBuilder())

    private class Segment(val glyphs: List<RawGlyph>) {
        val baseline = glyphs.map { it.y }.average().toFloat()
        val size = glyphs.map { it.size }.sorted()[glyphs.size / 2]
        val left = glyphs.minOf { it.x }
        val right = glyphs.maxOf { it.x + it.w }
        val text: String
        val rtl: Boolean
        val lossy = glyphs.any { it.text.isEmpty() }
        /** Glyphs whose text could not be decoded at all. */
        val undecoded = glyphs.count { it.text.isEmpty() }
        init {
            val sorted = glyphs.sortedBy { it.x }
            // presentation forms → letters; cp1256 read as cp1252 ("ÇáÓáÇã") → Arabic (same length, char by char)
            val texts = demojibake(sorted.map { normalize(it.text) })
            val ar = texts.sumOf { t -> t.count { isArabicChar(it) } }
            val lat = texts.sumOf { t -> t.count { it.isLetter() && !isArabicChar(it) } }
            rtl = ar > 0 && ar >= lat
            // attach marks to the glyph they sit on, so they stay after their letter in logical order
            fun isMark(t: String) = t.isNotEmpty() && t.all { Bidi.isArabicMark(it) }
            val units = ArrayList<GlyphUnit>()
            for ((i, g) in sorted.withIndex()) if (!isMark(texts[i])) units += GlyphUnit(g, texts[i])
            for ((i, g) in sorted.withIndex()) {
                val t = texts[i]
                if (!isMark(t)) continue
                if (units.isEmpty()) { units += GlyphUnit(g, t); continue }
                // the letter under the mark: marks have no advance (the scanner gives them a nominal width), so their
                // origin is what counts: on the letter's box, or at the letter's start for unpositioned marks
                val cx = g.x + 0.01f
                val eps = 0.1f * g.size
                val host = units.filter { u -> cx >= u.base.x - eps && cx <= u.base.x + u.base.w + eps }
                    .minByOrNull { u -> if (cx >= u.base.x) cx - u.base.x else (u.base.x - cx) * 4f }
                    ?: units.minBy { u -> kotlin.math.abs(u.base.x + u.base.w / 2f - cx) }
                host.marks.append(t)
            }
            units.sortBy { it.base.x }
            val sb = StringBuilder()
            var prevEnd = Float.NaN
            for (u in units) {
                val g = u.base
                // one glyph for several Arabic letters (lam-alef, ﷲ): its text is logical, the line is visual → reverse it
                val arabicGlyph = u.text.any { isArabicChar(it) } && u.text.none { it.isLetterOrDigit() && !isArabicChar(it) }
                val t = if (arabicGlyph) u.marks.toString() + u.text.reversed() else u.text + u.marks
                if (!prevEnd.isNaN() && g.x - prevEnd > options.wordGap * g.size && sb.isNotEmpty() && sb.last() != ' ' && !t.startsWith(" ")) sb.append(' ')
                sb.append(t)
                prevEnd = max(if (prevEnd.isNaN()) g.x + g.w else prevEnd, g.x + g.w)
            }
            val visual = sb.toString().replace(Regex(" {2,}"), " ").trim()
            text = if (ar > 0) Bidi.visualToLogical(visual, rtl) else visual
        }
    }

    /** [ArabicTextFix.demojibake] decides on the whole line; apply its per-character decoding to each glyph. */
    private fun demojibake(texts: List<String>): List<String> {
        val sep = '\u0001'
        val joined = texts.joinToString(sep.toString())
        val fixed = com.smartdocs.editor.core.search.ArabicTextFix.demojibake(joined)
        if (fixed === joined || fixed == joined) return texts
        val parts = fixed.split(sep)
        return if (parts.size == texts.size) parts else texts
    }

    private fun buildBlocks(all: List<RawGlyph>, fontsOut: MutableMap<Int, PDFont?>, idBase: Int): List<TextBlock> {
        val opt = options
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
                if (cur.isNotEmpty() && g.x - end > opt.columnGap * max(g.size, l.size)) { segments += Segment(cur); cur = ArrayList() }
                cur += g
                end = if (end.isNaN() || cur.size == 1) g.x + g.w else max(end, g.x + g.w)
            }
            if (cur.isNotEmpty()) segments += Segment(cur)
        }
        // 3. paragraphs: consecutive lines with regular spacing, similar size, overlapping horizontally
        val paragraphs = ArrayList<MutableList<Segment>>()
        for (s in segments.sortedByDescending { it.baseline }) {
            val p = if (opt.lineMode) null else paragraphs.lastOrNull { para ->
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
        val pageRtl = segments.count { it.rtl } * 2 > segments.size
        val order = if (pageRtl) compareByDescending<List<Segment>> { it.first().baseline }.thenByDescending { s -> s.maxOf { it.right } }
            else compareByDescending<List<Segment>> { it.first().baseline }.thenBy { s -> s.minOf { it.left } }
        return groups.map { idx -> idx.flatMap { paragraphs[it] }.sortedByDescending { it.baseline } }
            .sortedWith(order)
            .mapIndexed { id, segs -> toBlock(idBase + id, segs, fontsOut) }
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
        val align = detectAlign(segs, left, right, size, rtl)
        val undecoded = segs.sumOf { it.undecoded }
        val unreadable = (gl.size >= 2 && undecoded * 10 >= gl.size * 3) || com.smartdocs.editor.core.search.ArabicTextFix.isGarbage(text) ||
            (text.isBlank() && gl.isNotEmpty())
        return TextBlock(id, text, RectF(left, top, right, bottom), ops, name.substringAfter('+'), reusable, family, bold, italic,
            (size * 2).let { kotlin.math.round(it) / 2f }, dominantColor(gl), rtl, align, segs.first().baseline, lineHeight, segs.size, segs.any { it.lossy }, unreadable)
    }

    /**
     * Alignment of a paragraph from its line edges: centred when the line centres agree, right-aligned when the right
     * edges agree (or for Arabic when both edges agree: justified Arabic), left-aligned when the left edges agree.
     * One-line blocks follow the text direction.
     */
    private fun detectAlign(segs: List<Segment>, left: Float, right: Float, size: Float, rtl: Boolean): TextAlign {
        if (segs.size < 2) return if (rtl) TextAlign.RIGHT else TextAlign.LEFT
        val tol = size * 0.6f
        // the last line of a justified paragraph is shorter: judge the edges on all lines, the raggedness without the last
        val leftsAgree = segs.all { abs(it.left - left) < tol }
        val rightsAgree = segs.all { abs(it.right - right) < tol }
        val centred = segs.all { abs((it.left + it.right) / 2 - (left + right) / 2) < tol }
        return when {
            leftsAgree && rightsAgree -> if (rtl) TextAlign.RIGHT else TextAlign.LEFT
            rightsAgree -> TextAlign.RIGHT
            leftsAgree -> TextAlign.LEFT
            centred -> TextAlign.CENTER
            rtl -> TextAlign.RIGHT
            else -> TextAlign.LEFT
        }
    }

    private fun dominantColor(gl: List<RawGlyph>): Int = gl.groupingBy { it.color }.eachCount().maxByOrNull { it.value }?.key ?: 0

    internal fun isArabicChar(c: Char) = c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    /** Presentation forms (often what Arabic PDFs map glyphs to) back to base letters. */
    private fun normalize(t: String): String {
        if (t.none { it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }) return t
        val sb = StringBuilder()
        for (c in t) {
            if (c.code == 0xFEFF) continue
            if (c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF') {
                val n = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKC)
                sb.append(if (c.code in 0xFE70..0xFE7F) n.trim() else n)
            } else sb.append(c)
        }
        return sb.toString()
    }
}
