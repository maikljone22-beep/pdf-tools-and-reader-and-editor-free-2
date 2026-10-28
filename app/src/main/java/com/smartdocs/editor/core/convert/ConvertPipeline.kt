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
    /**
     * Running headers / footers: DOCX writes them as a real Word header and footer (page number → PAGE field),
     * HTML / TXT / XLSX drop them, PPTX keeps them on each slide.
     */
    val detectHeaders: Boolean = true,
    /** DOCX: flowing text (no page breaks, running headers / footers left out) instead of one Word page per PDF page. */
    val docxFlowing: Boolean = false,
    /** DOCX "precisely laid out": paragraphs, pictures and tables placed where they are on the PDF page (frames). */
    val docxExact: Boolean = false,
    /** Detected running headers / footers are deleted (DOCX writes no Word header / footer for them). */
    val dropFurniture: Boolean = false,
    /** TXT: keep the PDF's line breaks (else one line per paragraph) and wrap lines at this many characters (0 = no). */
    val txtKeepLines: Boolean = false,
    val txtWrap: Int = 0,
    /** XLSX: one sheet row per line of multi-line table cells; no merged cells. */
    val xlsxSplitLines: Boolean = false,
    val xlsxNoMerge: Boolean = false,
    /** DOCX: character spacing added to every run (points) and pictures anchored at their place. */
    val docxCharSpacing: Float = 0f,
    val docxAnchorImages: Boolean = false,
    /** Tables from text alignment too (else lined tables only). */
    val streamTables: Boolean = true,
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

/** [ocrPages]: pages whose text is (partly) recognised; [rebuiltPages]: zero-based pages an older OCR build rebuilt. */
data class ConvertStats(val pages: Int, val textPages: Int, val tables: Int, val images: Int, val ocrPages: Int = 0, val rebuiltPages: List<Int> = emptyList())

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
        var textPages = 0; var tables = 0; var images = 0; var ocrPages = 0
        val rebuilt = ArrayList<Int>()
        val furniture = if (opts.detectHeaders && opts.format != TargetFormat.PPTX && pages.size >= 2) detectFurniture(src, pages) else PageFurniture.NONE
        fun layout(i: Int, withImages: Boolean): PageLayout {
            val l = LayoutAnalyzer.analyze(src.content(i, withImages), opts.detectTables, furniture, opts.streamTables)
            if (l.hasText) textPages++
            if (l.ocrChars > 0) ocrPages++
            if (l.rebuiltByOcr) rebuilt += i
            tables += l.blocks.count { it is TableBlock }
            images += l.blocks.count { it is ImageBlock }
            return l
        }
        fun step(k: Int) { if (cancelled()) throw ConvertCancelled(); progress(k, total) }
        progress(0, total)
        when (opts.format) {
            TargetFormat.DOCX -> DocxBuilder(out, title, furniture, opts.docxFlowing, exact = opts.docxExact, dropFurniture = opts.dropFurniture,
                charSpacing = opts.docxCharSpacing, anchorImages = opts.docxAnchorImages).use { b ->
                pages.forEachIndexed { k, i -> b.addPage(layout(i, opts.includeImages)); step(k + 1) }
                b.finish()
            }
            TargetFormat.XLSX -> XlsxBuilder(out, title, opts.xlsxSplitLines, opts.xlsxNoMerge).use { b ->
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
                    w.write(HtmlTxtBuilder.text(layout(i, false), opts.txtKeepLines, opts.txtWrap))
                    step(k + 1)
                }
                w.flush(); out.close()
            }
        }
        return ConvertStats(total, textPages, tables, images, ocrPages, rebuilt)
    }

    /** Samples up to 8 of [pages] (spread over the document) to find running headers and footers. */
    fun detectFurniture(src: PageSource, pages: List<Int>): PageFurniture {
        val n = minOf(8, pages.size)
        val picks = (0 until n).map { k -> pages[(k.toLong() * (pages.size - 1) / maxOf(1, n - 1)).toInt()] }.distinct()
        val samples = picks.mapNotNull { i ->
            runCatching {
                val c = src.content(i, false)
                val lines = LayoutAnalyzer.buildLines(c.glyphs)
                PageFurniture.Companion.Sample(lines, c.height, LayoutAnalyzer.bodyFontSize(lines))
            }.getOrNull()
        }
        return PageFurniture.detect(samples)
    }
}
