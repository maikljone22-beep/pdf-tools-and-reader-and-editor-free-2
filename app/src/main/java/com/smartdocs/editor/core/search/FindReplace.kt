package com.smartdocs.editor.core.search

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.PageTextModel
import com.smartdocs.editor.core.edit.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * A text block of the page content that contains the query. [rect] is display space (see
 * [com.smartdocs.editor.core.edit.PageFrame]); [ranges] are in [text]. [fontWarning]: the block's own font cannot
 * be reused, so the rewritten block is drawn with a substitute font.
 */
data class ReplaceMatch(val page: Int, val blockId: Int, val text: String, val rect: RectF, val ranges: List<IntRange>, val fontWarning: Boolean, val lossy: Boolean)

/**
 * Find & replace in the real page content, with the content-edit engine (MODULE_edit): [find] matches inside
 * [PageTextModel] blocks; [apply] rewrites each selected block with [ContentEdit.replaceText] and looks the block up
 * again after every rewrite, because operator indexes move. The replacement is literal text.
 */
object FindReplace {

    suspend fun find(file: File, password: String?, query: String, options: SearchOptions, pages: List<Int>, onPage: (Int) -> Unit = {}): List<ReplaceMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val out = ArrayList<ReplaceMatch>()
        for (p in pages) {
            onPage(p)
            val model = runCatching { PageTextModel.load(file, password, p) }.getOrNull() ?: continue
            for (b in model.blocks) {
                val r = TextMatcher.find(b.text, query, options)
                if (r.isNotEmpty()) out += ReplaceMatch(p, b.id, b.text, RectF(b.rect), r, !b.fontReusable, b.lossy)
            }
        }
        out
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
    ): Int = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        var cur = src
        var replaced = 0
        val temps = ArrayList<File>()
        try {
            selected.sortedWith(compareBy<ReplaceMatch> { it.page }.thenBy { it.blockId }).forEachIndexed { i, m ->
                val model = runCatching { PageTextModel.load(cur, password, m.page) }.getOrNull() ?: return@forEachIndexed
                val block = locate(model.blocks, m) ?: return@forEachIndexed
                val ranges = TextMatcher.find(block.text, query, options)
                if (ranges.isEmpty()) return@forEachIndexed
                val next = File(workDir, "fr_${System.nanoTime()}.pdf").also { temps += it }
                val style = EditTextStyle(size = block.size, color = block.color, bold = block.bold, italic = block.italic, align = block.align, family = block.family)
                ContentEdit.replaceText(context, cur, next, password, m.page, block.ops, replaceIn(block.text, ranges, replacement), style)
                cur = next
                replaced += ranges.size
                progress(i + 1, selected.size)
            }
            if (cur == src) src.copyTo(out, overwrite = true) else cur.copyTo(out, overwrite = true)
        } finally { temps.forEach { runCatching { it.delete() } } }
        replaced
    }
}
