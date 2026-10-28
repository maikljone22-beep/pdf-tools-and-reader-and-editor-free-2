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

    /** Load for reading: large streams spill to a temp file instead of the heap (convert audit C10 / A-H6). */
    fun load(src: File, password: String?): PDDocument = PDDocument.load(src, password.orEmpty(), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20))

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

    /** Layouts of [pages] (zero-based; empty = all), running headers / footers removed, with progress. [title] when the PDF has none. */
    suspend fun pages(src: File, password: String?, pages: List<Int>, withImages: Boolean, detectTables: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }, title: String = src.nameWithoutExtension): PdfPages {
        val out = ArrayList<PageLayout>()
        val meta = eachPage(src, password, pages, withImages, detectTables, onProgress) { l, _ -> out += l }
        return PdfPages(meta.first.ifBlank { title }, meta.second, out, meta.third)
    }

    /**
     * Analyses [pages] one at a time and hands each layout (with the outline's chapter titles) to [each]; nothing is
     * kept here, so PDF → EPUB / Markdown of an image-heavy book no longer holds every page's pictures (audit B10).
     * Returns (the PDF's /Title or "", author, chapters).
     */
    suspend fun eachPage(src: File, password: String?, pages: List<Int>, withImages: Boolean, detectTables: Boolean, onProgress: (Int, Int) -> Unit,
                         each: (PageLayout, Map<Int, List<String>>) -> Unit): Triple<String, String, Map<Int, List<String>>> = withContext(Dispatchers.IO) {
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
            val chapters = chapters(doc)
            list.forEachIndexed { k, i ->
                coroutineContext.ensureActive()
                onProgress(k, list.size)
                val c = ex.extract(i, withImages)
                each(if (furniture != null) LayoutAnalyzer.analyze(c, detectTables, furniture) else LayoutAnalyzer.analyze(c, detectTables), chapters)
            }
            onProgress(list.size, list.size)
            val info = doc.documentInformation
            Triple(info?.title?.takeIf { it.isNotBlank() }.orEmpty(), info?.author.orEmpty(), chapters)
        }
    }

    /**
     * PDF → [FlowDoc] page by page. [detectTables] is the sheet's "Detect tables" switch (audit B19). [title] is used
     * when the PDF has no /Title (the source's name, not the OCR temp copy's — audit B23). With [spillDir] the pictures
     * are written to temp files there and the document refers to them (audit B10); the caller deletes the folder.
     */
    suspend fun flow(src: File, password: String?, pages: List<Int>, withImages: Boolean, pageBreaks: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> },
                     detectTables: Boolean = true, title: String = src.nameWithoutExtension, spillDir: File? = null): FlowDoc {
        val blocks = ArrayList<FBlock>()
        var first = true
        var spilled = 0
        val meta = eachPage(src, password, pages, withImages, detectTables, onProgress) { layout, chapters ->
            val part = LayoutFlow.fromLayouts("", listOf(layout), chapters, withImages, false)
            if (!first && pageBreaks) blocks += FBlock.PageBreak
            first = false
            part.blocks.forEach { b ->
                if (b is FBlock.Image && spillDir != null) {
                    spillDir.mkdirs()
                    val f = File(spillDir, "p" + layout.index + "_" + (++spilled) + "." + b.ext)
                    f.writeBytes(b.bytes)
                    blocks += FBlock.Image(null, b.ext, b.widthPt, b.heightPt, b.alt, f)
                } else blocks += b
            }
        }
        return FlowDoc(meta.first.ifBlank { title }, blocks, meta.second)
    }

    /**
     * The PDF a Word conversion reads (PDF_TO_WORD_r5). [file] is [src] when nothing had to change; [ocrPages] pages got
     * a fresh OCR layer; [repaired]: fonts with a wrong ToUnicode map were repaired. Close it to delete temporary copies.
     */
    class WordInput(val file: File, val ocrPages: Int, val repaired: Boolean, val ocrUnavailable: Boolean, private val parts: List<AutoCloseable>,
                    /** The document's text is mostly Arabic-script (judged on the recognised copy when OCR ran). */
                    val rtl: Boolean = false) : AutoCloseable {
        override fun close() { parts.forEach { runCatching { it.close() } } }
    }

    /**
     * Prepares [src] for PDF → Word:
     *  1. always a repaired copy ([RepairedInput]: Arabic fonts with a wrong ToUnicode map fixed, so Word gets real letters
     *     instead of «اٌىُُّبء»). With [ocr] the app's own earlier OCR layer is removed from the pages of [pages] too
     *     (older builds wrote it reversed, with border junk; it is replaced by a fresh one below);
     *  2. with [ocr], the pages that need it ([MixedPageOcr.plan]: no text, unusable text, pictures of text such as Word's
     *     2x2 soft-mask word images, scanned regions) are recognised with the current pipeline ([OcrEngine.recognize]:
     *     second readings and junk cleanup) and get an invisible layer of the words that are not on the page yet
     *     ([MixedPageOcr.dropExisting]). Digital pages are not recognised at all.
     * OCR that cannot run (no language data offline) leaves the repaired copy: the conversion goes on without it.
     * [pages]: zero-based pages to convert (empty = all). [langs] null = from the document's text.
     */
    suspend fun wordInput(ctx: Context, src: File, password: String?, pages: List<Int>, langs: String?, tempDir: File, ocr: Boolean, onProgress: (Int, Int) -> Unit): WordInput {
        val input = com.smartdocs.editor.core.ocr.RepairedInput.of(tempDir, src, password, rerun = if (!ocr) emptyList() else pages.ifEmpty { null })
        if (!ocr) return WordInput(input.file, 0, input.repaired, false, listOf(input))
        val out = File(tempDir, src.nameWithoutExtension + "_wordocr_" + System.nanoTime() + ".pdf")
        var engine: OcrEngine? = null
        try {
            val session = com.smartdocs.editor.core.pdf.PdfSession.open(ctx, input.file, password)
            try {
                val range = pages.filter { it in 0 until session.pageCount }.ifEmpty { (0 until session.pageCount).toList() }
                val plan = com.smartdocs.editor.core.ocr.MixedPageOcr.plan(session, input.file, password, range)
                if (plan.pages.isEmpty()) return WordInput(input.file, 0, input.repaired, false, listOf(input))
                val sample = StringBuilder()
                for (pi in range) { if (sample.length > 20000) break; sample.append(runCatching { session.text(pi) }.getOrDefault("")).append('\n') }
                val lang = langs ?: com.smartdocs.editor.core.tools5.LangDetect.forOcr(sample.toString()).joinToString("+").ifBlank { "ara+eng" }
                // a page of pictures has no text to judge by: the OCR languages decide then
                val rtl = com.smartdocs.editor.core.convert.BidiText.strongCounts(sample.toString()).let { it[0] > it[1] || (it[0] + it[1] < 200 && "ara" in lang) }
                val e = OcrEngine(ctx.applicationContext).also { engine = it }
                val ready = try { e.ensureLanguages(lang); e.init(lang) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { false }
                if (!ready) return WordInput(input.file, 0, input.repaired, true, listOf(input))
                val writer = withContext(Dispatchers.IO) { com.smartdocs.editor.core.ocr.SearchablePdfWriter(ctx, input.file, password) }
                try {
                    plan.pages.forEachIndexed { n, pi ->
                        coroutineContext.ensureActive()
                        onProgress(n, plan.pages.size)
                        // the reader OCR's render: 300 dpi, at most 3500 px, no annotations
                        val (w, h) = session.pageSize(pi)
                        var scale = 300f / 72f
                        if (maxOf(w, h) * scale > 3500f) scale = 3500f / maxOf(w, h)
                        val bmp = session.render(pi, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), annotations = false)
                        session.invalidateCache()
                        val page = try { e.recognize(bmp, wantWords = true) } finally { bmp.recycle() }
                        writer.addLayer(pi, com.smartdocs.editor.core.ocr.MixedPageOcr.dropExisting(session, pi, page, plan))
                    }
                    onProgress(plan.pages.size, plan.pages.size)
                    writer.save(out)
                } finally { writer.close() }
                return WordInput(out, plan.pages.size, input.repaired, false, listOf(AutoCloseable { out.delete() }, input), rtl)
            } finally { session.closeAsync() }
        } catch (x: kotlinx.coroutines.CancellationException) {
            out.delete(); input.close(); throw x
        } catch (x: Exception) {
            out.delete()
            return WordInput(input.file, 0, input.repaired, true, listOf(input))
        } finally { engine?.close() }
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
        // the temp copy is deleted when OCR fails, is cancelled or had nothing to do (audit B24)
        var ok = false
        return try { OcrRunner.ocrFile(ctx, engine, src, password, OcrPlan(langs = langs), out, onProgress).also { ok = it != null } }
        finally { engine.close(); if (!ok) out.delete() }
    }
}
