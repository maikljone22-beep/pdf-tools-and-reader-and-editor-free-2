package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.nve.UrlFinder
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.redact.RedactApplier
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.RedactStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Optional removal of printed, selectable link text, followed by link/action removal.
 * Images without a text layer require the manual redaction tool; they must not be guessed at.
 */
object LinkRemoval {
    data class Report(val annotations: Int, val textAreas: Int, val pages: Int)

    suspend fun apply(ctx: Context, src: File, out: File, password: String?, webOnly: Boolean,
                      pages: Set<Int>, eraseText: Boolean): Report = withContext(Dispatchers.IO) {
        val pageCount = PageBoxOps.pageInfos(src, password).size
        val selected = pages.filter { it in 0 until pageCount }.toSet()
        require(selected.isNotEmpty())
        val cleaned = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.link-text.pdf")
        try {
            val marks = if (eraseText) {
                val links = LinkOps.list(src, password).filter { it.page in selected &&
                    (!webOnly || it.spec.kind == LinkKind.URI || it.spec.kind == LinkKind.MAIL) }
                val session = PdfSession.open(ctx, src, password)
                try {
                    selected.flatMap { page ->
                        ensureActive()
                        val text = session.text(page)
                        val boxes = session.charBoxes(page)
                        val urls = AutoLinks.specs(UrlFinder.find(text).map { it.second }, boxes) { _, rect, quads ->
                            LinkSpec(rect, quads = quads)
                        }
                        val rects = (urls.flatMap { it.quads.ifEmpty { listOf(it.rect) } } +
                            links.filter { it.page == page }.flatMap { it.spec.quads.ifEmpty { listOf(it.spec.rect) } })
                            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
                        rects.mapIndexed { i, rect ->
                            RedactMark("link-text-$page-$i", page, listOf(rect), RedactStyle(outline = null))
                        }
                    }
                } finally { session.closeAsync() }
            } else emptyList()
            val input = if (marks.isNotEmpty()) {
                RedactApplier.apply(ctx, src, cleaned, password, marks, RedactApplier.Options(removeTextOnly = true))
                cleaned
            } else src
            if (eraseText) {
                // Do not report text removal as successful if an address had no usable character boxes
                // or could not be rewritten. The caller keeps the previous working copy on failure.
                val verification = PdfSession.open(ctx, input, password)
                try {
                    for (page in selected) {
                        ensureActive()
                        check(UrlFinder.find(verification.text(page)).isEmpty()) {
                            ctx.getString(com.smartdocs.editor.R.string.e5_links_erase_incomplete)
                        }
                    }
                } finally { verification.closeAsync() }
            }
            val count = LinkOps.removeAll(input, out, password, webOnly, selected)
            Report(count, marks.size, selected.size)
        } finally { cleaned.delete() }
    }
}
