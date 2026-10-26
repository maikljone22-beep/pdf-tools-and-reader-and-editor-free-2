package com.smartdocs.editor.core.convert6

import android.content.Context
import com.smartdocs.editor.core.convert.ConvertPipeline
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.PageContent
import com.smartdocs.editor.core.convert.PageLayout
import com.smartdocs.editor.core.convert.PageSource
import com.smartdocs.editor.core.convert.PdfContentExtractor
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.tools5.AutoOcr
import com.smartdocs.editor.core.tools5.OcrPlan
import com.smartdocs.editor.core.tools5.OcrRunner
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Analysed pages of a PDF plus its outline, for the reflowable writers (Markdown, EPUB, ODT, CSV …). */
class PdfPages(val title: String, val author: String, val layouts: List<PageLayout>, val chapters: Map<Int, List<String>>)

object PdfFlow6 {

    fun load(src: File, password: String?): PDDocument = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)

    /** Outline entries (top two levels) → zero-based page index → titles. */
    fun chapters(doc: PDDocument): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, MutableList<String>>()
        fun walk(node: PDOutlineNode, depth: Int) {
            if (depth > 1) return
            var item = node.firstChild
            var guard = 0
            while (item != null && guard++ < 5000) {
                val cur = item
                val page = runCatching { cur.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                val t = cur.title?.trim().orEmpty()
                if (page >= 0 && t.isNotEmpty() && depth == 0) out.getOrPut(page) { ArrayList() } += t
                walk(cur, depth + 1)
                item = cur.nextSibling
            }
        }
        doc.documentCatalog.documentOutline?.let { walk(it, 0) }
        return out
    }

    /** Layouts of [pages] (zero-based; empty = all), running headers / footers removed, with progress. */
    suspend fun pages(src: File, password: String?, pages: List<Int>, withImages: Boolean, detectTables: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): PdfPages = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val ex = PdfContentExtractor(doc)
            val list = pages.filter { it in 0 until doc.numberOfPages }.ifEmpty { (0 until doc.numberOfPages).toList() }
            val source = object : PageSource {
                override val pageCount = ex.pageCount
                override fun pageSize(index: Int) = ex.pageSize(index)
                override fun content(index: Int, withImages: Boolean): PageContent = ex.extract(index, withImages)
                override fun render(index: Int, scale: Float): Pair<ByteArray, String>? = null
            }
            val furniture = if (list.size >= 2) runCatching { ConvertPipeline.detectFurniture(source, list) }.getOrNull() else null
            val out = ArrayList<PageLayout>()
            list.forEachIndexed { k, i ->
                coroutineContext.ensureActive()
                onProgress(k, list.size)
                val c = ex.extract(i, withImages)
                out += if (furniture != null) LayoutAnalyzer.analyze(c, detectTables, furniture) else LayoutAnalyzer.analyze(c, detectTables)
            }
            onProgress(list.size, list.size)
            val info = doc.documentInformation
            PdfPages(info?.title?.takeIf { it.isNotBlank() } ?: src.nameWithoutExtension, info?.author.orEmpty(), out, chapters(doc))
        }
    }

    suspend fun flow(src: File, password: String?, pages: List<Int>, withImages: Boolean, pageBreaks: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): FlowDoc {
        val p = pages(src, password, pages, withImages, true, onProgress)
        return LayoutFlow.fromLayouts(p.title, p.layouts, p.chapters, withImages, pageBreaks, p.author)
    }

    /**
     * A searchable copy for scanned input. [force] = OCR every page without a text layer (scanned → editable);
     * otherwise only when at least half of the pages are scanned ([AutoOcr]). Returns null when nothing was done.
     */
    suspend fun ocrCopy(ctx: Context, src: File, password: String?, langs: String?, tempDir: File, force: Boolean, onProgress: (Int, Int) -> Unit): File? {
        // OCR failure (offline, no language pack) → convert the original; a cancel must still stop the whole job
        if (!force) return try { AutoOcr.ocrIfScanned(ctx, src, password, langs, tempDir, onProgress) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val out = File(tempDir, src.nameWithoutExtension + "_ocr_" + System.currentTimeMillis() + ".pdf")
        val engine = OcrEngine(ctx.applicationContext)
        return try { OcrRunner.ocrFile(ctx, engine, src, password, OcrPlan(langs = langs), out, onProgress) } finally { engine.close() }
    }
}
