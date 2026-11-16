package com.smartdocs.editor.core.edit5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.redact.*
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class BulkLinkAction { REPLACE_TARGET, REPLACE_WITH_TEXT, ADD }
data class BulkLinkSpec(
    val action: BulkLinkAction = BulkLinkAction.REPLACE_TARGET,
    val pages: Set<Int> = emptySet(), val allPages: Boolean = true,
    val linkContains: String = "", val textContains: String = "",
    val target: String = "", val text: String = "",
    val style: E5TextStyle = E5TextStyle(size = 14f),
    val opacity: Float = 1f, val x: Float = 0.5f, val y: Float = 0.05f,
)

object BulkLinkOps {
    suspend fun selectedPages(ctx: Context, src: File, password: String?, spec: BulkLinkSpec): Set<Int> {
        var selected = if (spec.allPages) PageBoxOps.pageInfos(src, password).indices.toSet() else spec.pages
        if (spec.linkContains.isNotBlank()) {
            val matching = LinkOps.list(src, password).filter { it.spec.uri.contains(spec.linkContains, true) || it.spec.contents.contains(spec.linkContains, true) }.map { it.page }.toSet()
            selected = selected.intersect(matching)
        }
        if (spec.textContains.isNotBlank() && selected.isNotEmpty()) {
            val session = PdfSession.open(ctx, src, password)
            try { selected = selected.filter { session.text(it).contains(spec.textContains, true) }.toSet() }
            finally { session.closeAsync() }
        }
        return selected
    }

    suspend fun apply(ctx: Context, src: File, out: File, password: String?, spec: BulkLinkSpec): Int = withContext(Dispatchers.IO) {
        val pages = selectedPages(ctx, src, password, spec)
        require(pages.isNotEmpty()) { ctx.getString(com.smartdocs.editor.R.string.bulk_links_no_pages) }
        if (spec.action == BulkLinkAction.REPLACE_TARGET) {
            val targets = LinkOps.list(src, password).filter { it.page in pages }.groupBy { it.page }.mapValues { it.value.map { l -> l.index }.toSet() }
            return@withContext LinkOps.replaceTargets(src, out, password, targets, spec.target)
        }
        require(spec.text.isNotBlank() || spec.target.isNotBlank())
        val originalLinks = if (spec.action == BulkLinkAction.REPLACE_WITH_TEXT) LinkOps.list(src, password).filter { it.page in pages } else emptyList()
        if (spec.action == BulkLinkAction.REPLACE_WITH_TEXT && originalLinks.isEmpty()) {
            src.copyTo(out, overwrite = true)
            return@withContext 0
        }
        val cleaned = File(out.parentFile, ".bulk_links_${System.nanoTime()}.pdf")
        try {
            val input = if (spec.action == BulkLinkAction.REPLACE_WITH_TEXT) {
                val marks = originalLinks.mapIndexed { i, l -> RedactMark("bulk-link-$i", l.page, listOf(l.spec.rect), RedactStyle(fill = 0xFFFFFF, outline = null)) }
                RedactApplier.apply(ctx, src, cleaned, password, marks)
                cleaned
            } else src
            var count = 0
            E5Io.edit(input, out, password) { doc ->
                val fonts = E5Fonts(ctx, doc)
                for (p in pages) {
                    if (p !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(p)
                    val info = PageInfo.of(page)
                    val frame = DecorExtras.frameOf(page, false)
                    val positions = if (spec.action == BulkLinkAction.REPLACE_WITH_TEXT)
                        originalLinks.filter { it.page == p }.map { info.rectToDisplay(it.spec.rect) }
                    else listOf(RectF(info.displayW * spec.x, info.displayH * spec.y, info.displayW * spec.x, info.displayH * spec.y))
                    val annots = page.annotations
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(frame.toUser)
                        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = spec.opacity.coerceIn(0f, 1f); strokingAlphaConstant = spec.opacity.coerceIn(0f, 1f)
                        })
                        for (position in positions) {
                            val text = spec.text.ifBlank { spec.target }
                            val drawStyle = if (spec.style.bold && com.smartdocs.editor.core.pdf.ArabicShaper.isArabic(text)) spec.style.copy(outline = spec.style.color) else spec.style
                            var line = E5Line(fonts, text, drawStyle)
                            if (line.width > info.displayW - 8f) line = E5Line(fonts, line.logical, drawStyle.copy(size = spec.style.size * (info.displayW - 8f) / line.width))
                            val x = (if (spec.action == BulkLinkAction.ADD) position.left - line.width / 2f else position.left).coerceIn(4f, (info.displayW - line.width - 4f).coerceAtLeast(4f))
                            val y = position.bottom.coerceIn(4f, (info.displayH - spec.style.size).coerceAtLeast(4f))
                            line.draw(cs, x, y)
                            if (spec.action == BulkLinkAction.ADD && spec.target.isNotBlank()) {
                                annots += PDAnnotationLink().apply {
                                    rectangle = E5Geom.rect(info.rectToUser(RectF(x, y + spec.style.size, x + line.width, y - spec.style.size * 0.2f)))
                                    action = PDActionURI().apply { uri = LinkDetect.normalizeUrl(spec.target) }
                                    setPage(page)
                                    cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.BORDER, com.tom_roush.pdfbox.cos.COSArray().apply { repeat(3) { add(com.tom_roush.pdfbox.cos.COSInteger.ZERO) } })
                                }
                            }
                            count++
                        }
                        cs.restoreGraphicsState()
                    }
                    page.annotations = annots
                }
            }
            count
        } finally { cleaned.delete() }
    }
}
