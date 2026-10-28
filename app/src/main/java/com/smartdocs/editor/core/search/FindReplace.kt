package com.smartdocs.editor.core.search

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.PageTextModel
import com.smartdocs.editor.core.edit.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * A text block of the page content that contains the query. [rect] is display space (see
 * [com.smartdocs.editor.core.edit.PageFrame]); [ranges] are in [text]. [fontWarning]: the block's own font cannot
 * be reused, so the rewritten block is drawn with a substitute font.
 */
/** Result of an in-reader replace: occurrences replaced; [fontWarning] a substitute font was used; [lossy] text may be incomplete. */
data class ReplaceOutcome(val count: Int, val fontWarning: Boolean, val lossy: Boolean)

data class ReplaceMatch(val page: Int, val blockId: Int, val text: String, val rect: RectF, val ranges: List<IntRange>, val fontWarning: Boolean, val lossy: Boolean)

/**
 * audit 5 C-8: what [FindReplace.applyDetailed] did: occurrences [replaced], and selected blocks [skipped] because
 * they could no longer be located (or no longer matched) after the earlier rewrites.
 */
data class ApplyResult(val replaced: Int, val skipped: Int)

/**
 * Find & replace in the real page content, with the content-edit engine (MODULE_edit): [find] matches inside
 * [PageTextModel] blocks; [apply] rewrites each selected block with [ContentEdit.replaceText] and looks the block up
 * again after every rewrite, because operator indexes move. The replacement is literal text; Arabic replacements are
 * shaped and reordered by the edit engine's text writer ([com.smartdocs.editor.core.edit.Bidi.shapeVisual]) and drawn
 * with the app's Arabic font (the block's subset font lacks new glyphs), tagged with /ActualText.
 */
object FindReplace {

    suspend fun find(file: File, password: String?, query: String, options: SearchOptions, pages: List<Int>, onPage: (Int) -> Unit = {}): List<ReplaceMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val out = ArrayList<ReplaceMatch>()
        // audit 5 C-8: one load for all pages (was one full load per page searched)
        for ((p, model) in PageTextModel.loadPages(file, password, pages, onPage)) {
            for (b in model.blocks) {
                if (b.unreadable) continue          // undecodable glyphs: nothing reliable to match or rewrite
                val r = matches(b.text, query, options)
                if (r.isNotEmpty()) out += ReplaceMatch(p, b.id, b.text, RectF(b.rect), r, !b.fontReusable, b.lossy)
            }
        }
        out
    }

    /**
     * Matches of [query] in a block's text. Block text from [PageTextModel] is already in logical order (presentation
     * forms folded, visual lines reversed), so the reversed-query matches [TextMatcher] adds for raw PDFium text would
     * be false hits here: they are dropped (only for phrase search, the only mode that adds them).
     */
    fun matches(text: String, query: String, options: SearchOptions): List<IntRange> {
        val r = TextMatcher.find(text, query, options)
        if (r.isEmpty() || options.mode != SearchMode.PHRASE || options.stem || !ArabicNormalizer.hasArabic(query)) return r
        val q = ArabicNormalizer.foldQuery(query, options.norm)
        if (q.isEmpty()) return r
        return r.filter { range -> range.last < text.length && ArabicNormalizer.fold(text.substring(range.first, range.last + 1), options.norm).text.contains(q) }
    }

    /** [text] with every range replaced by [replacement] (ranges sorted, non-overlapping). */
    fun replaceIn(text: String, ranges: List<IntRange>, replacement: String): String {
        val sb = StringBuilder(text.length + 16)
        var pos = 0
        for (r in ranges.sortedBy { it.first }) {
            if (r.first < pos || r.last >= text.length) continue
            sb.append(text, pos, r.first).append(replacement)
            pos = r.last + 1
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    /** The block of the re-analysed page that is the same as [m] (same text, nearest position). */
    private fun locate(blocks: List<TextBlock>, m: ReplaceMatch): TextBlock? =
        blocks.filter { it.text == m.text }.minByOrNull { abs(it.rect.left - m.rect.left) + abs(it.rect.top - m.rect.top) }

    /**
     * Rewrites the [selected] blocks of [src] into [out]. Returns how many occurrences were replaced.
     * [progress] receives (done, total) after each block.
     */
    suspend fun apply(
        context: Context, src: File, out: File, password: String?, selected: List<ReplaceMatch>, query: String, options: SearchOptions,
        replacement: String, workDir: File, progress: (Int, Int) -> Unit = { _, _ -> },
    ): Int = applyDetailed(context, src, out, password, selected, query, options, replacement, workDir, progress).replaced

    /**
     * [apply] that also reports the blocks it had to skip (audit 5 C-8: they used to be skipped silently). The document
     * is loaded and saved once for the whole run (no per-block temporaries), and the run stops on cancellation.
     */
    suspend fun applyDetailed(
        context: Context, src: File, out: File, password: String?, selected: List<ReplaceMatch>, query: String, options: SearchOptions,
        replacement: String, workDir: File, progress: (Int, Int) -> Unit = { _, _ -> },
    ): ApplyResult = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        var replaced = 0
        var skipped = 0
        // audit 5 C-8: one load and one save for the whole run (was a full load + save per block); each block is
        // looked up again on its re-analysed page, because operator indexes move after a rewrite
        com.smartdocs.editor.core.edit.EditIo.load(src, password).use { doc ->
            selected.sortedWith(compareBy<ReplaceMatch> { it.page }.thenBy { it.blockId }).forEachIndexed { i, m ->
                ensureActive()
                progress(i, selected.size)
                val model = if (m.page !in 0 until doc.numberOfPages) null else try { PageTextModel.analyze(doc, m.page).model } catch (e: Exception) { null }
                val block = model?.let { locate(it.blocks, m) }
                val ranges = block?.takeIf { !it.unreadable }?.let { matches(it.text, query, options) }.orEmpty()
                if (block == null || ranges.isEmpty()) { skipped++; return@forEachIndexed }
                val style = EditTextStyle(size = block.size, color = block.color, bold = block.bold, italic = block.italic, align = block.align, family = block.family)
                try {
                    ContentEdit.replaceTextIn(context, doc, m.page, block.ops, replaceIn(block.text, ranges, replacement), style)
                } catch (e: com.smartdocs.editor.core.edit.StalePageException) { skipped++; return@forEachIndexed }
                replaced += ranges.size
                progress(i + 1, selected.size)
            }
            if (replaced > 0) com.smartdocs.editor.core.edit.EditIo.save(doc, out) else src.copyTo(out, overwrite = true)
        }
        ApplyResult(replaced, skipped)
    }

    /** Replaces every occurrence on every page of [src] into [out] (reader «استبدال الكل»). */
    suspend fun replaceAllIn(
        context: Context, src: File, out: File, password: String?, query: String, options: SearchOptions, replacement: String, workDir: File,
    ): ReplaceOutcome {
        val n = com.smartdocs.editor.core.pdf.PdfOps.pageCount(src, password)
        val found = find(src, password, query, options, (0 until n).toList())
        if (found.isEmpty()) return ReplaceOutcome(0, false, false)
        val count = apply(context, src, out, password, found, query, options, replacement, workDir)
        return ReplaceOutcome(count, found.any { it.fontWarning }, found.any { it.lossy })
    }

    /**
     * Replaces the single occurrence a reader search hit points at (reader «استبدال»). [hit] is the hit's rect in PDF
     * user space (like [com.smartdocs.editor.core.pdf.SearchHit.rects]); [before] are the rects of the hits that
     * come before it on the same page, so the right occurrence is picked when a block holds the query twice.
     */
    suspend fun replaceHit(
        context: Context, src: File, out: File, password: String?, page: Int, hit: RectF, before: List<RectF>,
        query: String, options: SearchOptions, replacement: String,
    ): ReplaceOutcome = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        val model = PageTextModel.load(src, password, page)
        val frame = com.smartdocs.editor.core.pdf.PdfOps.load(src, password).use { com.smartdocs.editor.core.edit.PageFrame.of(it.getPage(page)) }
        fun display(r: RectF) = com.smartdocs.editor.core.edit.Affine.apply(frame.toDisplay, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
        val candidates = model.blocks.filter { !it.unreadable }.mapNotNull { b -> matches(b.text, query, options).takeIf { it.isNotEmpty() }?.let { b to it } }
        val (x, y) = display(hit)
        val chosen = pickBlock(candidates.map { it.first.rect }, x, y) ?: return@withContext ReplaceOutcome(0, false, false)
        val (block, ranges) = candidates[chosen]
        val k = before.count { r -> val (bx, by) = display(r); pickBlock(candidates.map { it.first.rect }, bx, by) == chosen }
        val range = ranges[k.coerceIn(0, ranges.size - 1)]
        val style = EditTextStyle(size = block.size, color = block.color, bold = block.bold, italic = block.italic, align = block.align, family = block.family)
        ContentEdit.replaceText(context, src, out, password, page, block.ops, replaceIn(block.text, listOf(range), replacement), style)
        ReplaceOutcome(1, !block.fontReusable, block.lossy)
    }

    /** Index of the rect (display space, top > bottom) containing (x, y), else the nearest one within 24 pt. */
    fun pickBlock(rects: List<RectF>, x: Float, y: Float): Int? {
        if (rects.isEmpty()) return null
        rects.indexOfFirst { x >= minOf(it.left, it.right) - 1f && x <= maxOf(it.left, it.right) + 1f && y >= minOf(it.top, it.bottom) - 1f && y <= maxOf(it.top, it.bottom) + 1f }
            .let { if (it >= 0) return it }
        fun dist(r: RectF): Float {
            val dx = maxOf(minOf(r.left, r.right) - x, 0f, x - maxOf(r.left, r.right))
            val dy = maxOf(minOf(r.top, r.bottom) - y, 0f, y - maxOf(r.top, r.bottom))
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val best = rects.indices.minByOrNull { dist(rects[it]) } ?: return null
        return best.takeIf { dist(rects[it]) <= 24f }
    }
}
