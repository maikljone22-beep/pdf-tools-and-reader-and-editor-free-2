package com.smartdocs.editor.core.convert

import java.io.OutputStream
import java.io.OutputStreamWriter

enum class TargetFormat(val ext: String) { DOCX("docx"), XLSX("xlsx"), PPTX("pptx"), HTML("html"), TXT("txt") }

/** Options of one conversion. [pages] are zero-based page indexes in output order. */
data class ConvertOptions(
    val format: TargetFormat,
    val pages: List<Int>,
    /** XLSX: all pages in one sheet instead of one sheet per page. */
    val xlsxSingleSheet: Boolean = false,
    /** PPTX: editable text boxes/tables/images (true) or one picture per page (false). */
    val pptxEditable: Boolean = true,
    val detectTables: Boolean = true,
    /** DOCX / HTML / PPTX(editable): carry the page images over. */
    val includeImages: Boolean = true,
    /** Worksheet name for a page number (1-based), e.g. "صفحة 3". */
    val sheetName: (Int) -> String = { "Page $it" },
    /** XLSX single-sheet mode: the worksheet name. */
    val singleSheetName: String = "Sheet1",
)

/** Where page content comes from (PdfBox on Android, desktop PdfBox in the test harness). */
interface PageSource {
    val pageCount: Int
    /** Page size in points (crop box, rotation applied). */
    fun pageSize(index: Int): Pair<Float, Float>
    fun content(index: Int, withImages: Boolean): PageContent
    /** Page picture for "image per page" slides: JPEG/PNG bytes and their extension, or null. */
    fun render(index: Int, scale: Float): Pair<ByteArray, String>?
}

data class ConvertStats(val pages: Int, val textPages: Int, val tables: Int, val images: Int)

class ConvertCancelled : RuntimeException("cancelled")

/** Format-independent driver: extract → analyse → write, page by page, with progress and cancellation. */
object ConvertPipeline {

    fun run(
        src: PageSource,
        opts: ConvertOptions,
        out: OutputStream,
        title: String,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): ConvertStats {
        val pages = opts.pages.filter { it in 0 until src.pageCount }.ifEmpty { (0 until src.pageCount).toList() }
        val total = pages.size
        var textPages = 0; var tables = 0; var images = 0
        fun layout(i: Int, withImages: Boolean): PageLayout {
            val l = LayoutAnalyzer.analyze(src.content(i, withImages), opts.detectTables)
            if (l.hasText) textPages++
            tables += l.blocks.count { it is TableBlock }
            images += l.blocks.count { it is ImageBlock }
            return l
        }
        fun step(k: Int) { if (cancelled()) throw ConvertCancelled(); progress(k, total) }
        progress(0, total)
        when (opts.format) {
            TargetFormat.DOCX -> DocxBuilder(out, title).use { b ->
                pages.forEachIndexed { k, i -> b.addPage(layout(i, opts.includeImages)); step(k + 1) }
                b.finish()
            }
            TargetFormat.XLSX -> XlsxBuilder(out, title).use { b ->
                if (opts.xlsxSingleSheet) {
                    val all = pages.mapIndexed { k, i -> layout(i, false).also { step(k + 1) } }
                    b.addSheet(opts.singleSheetName, all)
                } else pages.forEachIndexed { k, i -> b.addSheet(opts.sheetName(i + 1), listOf(layout(i, false))); step(k + 1) }
                b.finish()
            }
            TargetFormat.PPTX -> {
                val (w0, h0) = src.pageSize(pages.first())
                PptxBuilder(out, title, w0, h0).use { b ->
                    pages.forEachIndexed { k, i ->
                        if (opts.pptxEditable) b.addEditableSlide(layout(i, opts.includeImages))
                        else {
                            val (w, h) = src.pageSize(i)
                            val pic = src.render(i, 2f)
                            if (pic != null) b.addImageSlide(pic.first, pic.second, w, h)
                            else b.addEditableSlide(layout(i, true))
                        }
                        step(k + 1)
                    }
                    b.finish()
                }
            }
            TargetFormat.HTML -> {
                val w = OutputStreamWriter(out, Charsets.UTF_8)
                // the document direction is decided from the first page; pages stream afterwards
                val first = layout(pages.first(), opts.includeImages); step(1)
                HtmlTxtBuilder.htmlHead(w, title, first.rtl)
                HtmlTxtBuilder.htmlPage(w, first, true, opts.includeImages)
                for (k in 1 until pages.size) { HtmlTxtBuilder.htmlPage(w, layout(pages[k], opts.includeImages), false, opts.includeImages); step(k + 1) }
                HtmlTxtBuilder.htmlTail(w)
                w.flush()
                out.close()
            }
            TargetFormat.TXT -> {
                val w = OutputStreamWriter(out, Charsets.UTF_8)
                pages.forEachIndexed { k, i ->
                    if (k > 0) w.write("\n\u000C\n")
                    w.write(HtmlTxtBuilder.text(layout(i, false)))
                    step(k + 1)
                }
                w.flush(); out.close()
            }
        }
        return ConvertStats(total, textPages, tables, images)
    }
}
