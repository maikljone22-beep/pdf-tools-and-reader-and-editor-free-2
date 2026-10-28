package com.smartdocs.editor.core.convert6

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert.ConvertEngine
import com.smartdocs.editor.core.convert.ConvertOptions
import com.smartdocs.editor.core.convert.TargetFormat
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.tools5.ExportPlus
import com.smartdocs.editor.core.tools5.ImageExportOptions
import com.smartdocs.editor.core.tools5.ImageFormat
import com.smartdocs.editor.core.tools5.OptimizeOptions
import com.smartdocs.editor.core.tools5.Optimizer
import com.smartdocs.editor.core.tools5.PdfA
import com.smartdocs.editor.core.tools5.PdfAOptions
import com.smartdocs.editor.core.tools5.ToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Options of one hub run (sheet values; each conversion reads the ones it shows). */
data class Cv6Opts(
    val range: String = "",
    val autoOcr: Boolean = true,
    val tables: Boolean = true,
    val includeImages: Boolean = true,
    val singleSheet: Boolean = false,
    val pptEditable: Boolean = true,
    /** Word: keep page layout (page breaks, positioned look) or plain text flow. */
    val keepLayout: Boolean = true,
    /** null = keep the source format (image converter). */
    val imgFormat: ImgFmt? = ImgFmt.JPG,
    val dpi: Int = 150,
    val quality: Int = 85,
    /** Page images: one ZIP (true) or separate files. */
    val zip: Boolean = true,
    val gray: Boolean = false,
    val setup: PageSetup = PageSetup(),
    /** Several inputs → one output (true) or one output per input. */
    val merge: Boolean = true,
    val nUp: Int = 4,
    val resizePercent: Int = 50,
    val maxSide: Int = 0,
    val csvAllSheets: Boolean = false,
    val ocrLangs: String? = null,
    val url: String = "",
    val text: String = "",
    val pdfaStrict: Boolean = false,
    /** To-PDF: embed the original file(s) in the result (applied by the convert hub after [Cv6Engine.run]; nav-attach). */
    val attachSource: Boolean = false,
    /** TIFF: black & white (1 bit). */
    val bw: Boolean = false,
    /** Excel 2003 XML: one worksheet per table (else per page). */
    val perTable: Boolean = true,
)

enum class Stage { WORK, OCR }

class Cv6Result(val files: List<File>, val info: String? = null)

/** Runs a hub [Conv] on its inputs. Outputs go to the documents folder; temporary files are cleaned up. */
class Cv6Engine(private val ctx: Context, private val docs: DocumentRepository) {

    private val outDir get() = docs.docsDir
    private val tempDir get() = docs.tempDir
    private fun out(base: String, ext: String) = docs.uniqueFile(outDir, base, ext)

    suspend fun run(conv: Conv, inputs: List<File>, o: Cv6Opts, password: String?, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        val p: (Int, Int) -> Unit = { d, t -> progress(Stage.WORK, d, t) }
        val src = inputs.firstOrNull()
        return when (conv.id) {
            // ---- no-file sources ----
            "url_pdf" -> {
                val host = runCatching { android.net.Uri.parse(if (o.url.contains("://")) o.url else "https://" + o.url).host }.getOrNull()?.ifBlank { null } ?: "webpage"
                Cv6Result(listOf(ToPdf.url(ctx, o.url, out(host, "pdf"))))
            }
            "clipboard_pdf" -> {
                val doc = if (Regex("""(?m)^(#{1,6}\s|[-*]\s|\d+\.\s|\|)""").containsMatchIn(o.text)) MdFlow.parse(o.text) else FlowDoc.fromText(o.text, "")
                val title = o.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40)?.let { ImageNaming.sanitize(it) }?.ifEmpty { null } ?: "clipboard"
                Cv6Result(listOf(ToPdf6.flowToPdf(ctx, FlowDoc(title, doc.blocks), out(title, "pdf"))))
            }
            else -> {
                requireNotNull(src) { "no input" }
                if (conv.groups.first() == Cv6Group.FROM_PDF || conv.id.startsWith("ocr_pdf") || conv.id == "pdf_searchable") fromPdf(conv, inputs, o, password, progress)
                else other(conv, inputs, o, p, progress)
            }
        }
    }

    // ------------------------------------------------------------------ from PDF

    private suspend fun pagesOf(f: File, pw: String?, range: String): List<Int> {
        if (range.isBlank()) return emptyList()
        val n = withContext(Dispatchers.IO) { ConvertEngine.pageCount(f, pw) }
        return PdfOps.parseRange(range, n).ifEmpty { throw IllegalArgumentException(ctx.getString(R.string.cv6_err_range)) }
    }

    /** Batch over PDFs; scanned inputs get a searchable copy first when [ocr] says so. */
    private suspend fun fromPdf(conv: Conv, inputs: List<File>, o: Cv6Opts, password: String?, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        val outs = ArrayList<File>(); val infos = ArrayList<String>()
        inputs.forEachIndexed { k, f0 ->
            val pw = if (k == 0) password else null
            val multi = inputs.size > 1
            val p: (Int, Int) -> Unit = { d, t -> if (multi) progress(Stage.WORK, k * 100 + (if (t > 0) d * 100 / t else 0), inputs.size * 100) else progress(Stage.WORK, d, t) }
            val forceOcr = conv.id.startsWith("ocr_pdf")
            val wantsOcr = forceOcr || (Opt.AUTO_OCR in conv.opts && o.autoOcr && !(conv.id == "pdf_pptx" && !o.pptEditable))
            val ocr = if (wantsOcr && FileKind6.of(f0) == Kind6.PDF) PdfFlow6.ocrCopy(ctx, f0, pw, o.ocrLangs, tempDir, forceOcr) { d, t -> progress(Stage.OCR, d, t) } else null
            val f = ocr ?: f0
            val fpw = if (ocr != null) null else pw
            try {
                val r = onePdf(conv, f, f0.nameWithoutExtension, fpw, o, p)
                outs += r.files; r.info?.let { infos += it }
            } finally { ocr?.delete() }
        }
        return Cv6Result(outs, infos.distinct().joinToString("\n").ifBlank { null })
    }

    private suspend fun office(target: TargetFormat, f: File, base: String, pw: String?, o: Cv6Opts, p: (Int, Int) -> Unit): Cv6Result {
        val pages = pagesOf(f, pw, o.range)
        val out = out(base, target.ext)
        val opts = ConvertOptions(format = target, pages = pages, xlsxSingleSheet = o.singleSheet, pptxEditable = o.pptEditable, detectTables = o.tables, includeImages = o.includeImages,
            sheetName = { n -> ctx.getString(R.string.cv_sheet_name).format(n) }, singleSheetName = ctx.getString(R.string.cv_all_pages_sheet))
        val stats = ConvertEngine.convert(ctx, f, pw, opts, out, p) { false }
        val scanned = stats.textPages == 0 && !(target == TargetFormat.PPTX && !o.pptEditable)
        return Cv6Result(listOf(out), if (scanned) ctx.getString(R.string.cv_scanned_warning) else ctx.getString(R.string.cv6_info_pages, stats.pages, stats.tables, stats.images))
    }

    private fun zipFiles(files: List<File>, zip: File, folder: File? = null): File {
        ZipOutputStream(zip.outputStream().buffered()).use { z -> files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() } }
        folder?.let { d -> if (d.listFiles().isNullOrEmpty()) d.delete() }
        return zip
    }

    private suspend fun onePdf(conv: Conv, f: File, base: String, pw: String?, o: Cv6Opts, p: (Int, Int) -> Unit): Cv6Result = when (conv.id) {
        "pdf_docx", "ocr_pdf_docx" -> if (o.keepLayout) office(TargetFormat.DOCX, f, base, pw, o, p) else {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.range), o.includeImages, pageBreaks = false, onProgress = p)
            val out = out(base, "docx"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { FlowDocx.write(doc, it) } }; Cv6Result(listOf(out))
        }
        "pdf_xlsx", "ocr_pdf_xlsx" -> office(TargetFormat.XLSX, f, base, pw, o, p)
        "pdf_pptx" -> office(TargetFormat.PPTX, f, base, pw, o, p)
        "pdf_html" -> office(TargetFormat.HTML, f, base, pw, o, p)
        "pdf_txt", "ocr_pdf_txt" -> office(TargetFormat.TXT, f, base, pw, o.copy(includeImages = false), p)
        "pdf_images" -> {
            val fmt = when (o.imgFormat) { ImgFmt.PNG -> ImageFormat.PNG; ImgFmt.WEBP -> ImageFormat.WEBP; else -> ImageFormat.JPG }
            val dir = File(outDir, base + "_images").also { it.mkdirs() }
            val files = ExportPlus.images(ctx, f, pw, ImageExportOptions(fmt, o.dpi, o.gray, o.quality, pagesOf(f, pw, o.range), zip = false), dir, p)
            if (o.zip && files.size > 1) Cv6Result(listOf(zipFiles(files, out(base + "_images", "zip"), dir))) else Cv6Result(files)
        }
        "pdf_long_image" -> {
            val fmt = o.imgFormat ?: ImgFmt.JPG
            Cv6Result(listOf(ImageIo6.longImage(ctx, f, pw, pagesOf(f, pw, o.range), fmt, o.dpi, o.quality, out(base + "_long", fmt.ext), p)))
        }
        "pdf_rtf" -> Cv6Result(listOf(ExportPlus.rtf(f, pw, out(base, "rtf"), pagesOf(f, pw, o.range), p)))
        "pdf_md" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.range), o.includeImages, pageBreaks = false, onProgress = p)
            withContext(Dispatchers.IO) { Cv6Result(listOf(writeMarkdown(doc, base, this@Cv6Engine::out))) }
        }
        "pdf_epub" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.range), o.includeImages, pageBreaks = false, onProgress = p)
            val out = out(base, "epub"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { EpubWriter.write(doc, it) } }
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_chapters, doc.chapters().size))
        }
        "pdf_csv" -> {
            val pages = PdfFlow6.pages(f, pw, pagesOf(f, pw, o.range), false, true, p)
            val tables = LayoutFlow.tables(pages.layouts)
            val out = out(base, "csv")
            withContext(Dispatchers.IO) { out.outputStream().use { s -> s.write(Csv6.BOM); s.write(Csv6.writeTables(tables).toByteArray(Charsets.UTF_8)) } }
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_tables, pages.layouts.sumOf { l -> l.blocks.count { it is com.smartdocs.editor.core.convert.TableBlock } }))
        }
        "pdf_odt" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.range), o.includeImages, pageBreaks = true, onProgress = p)
            val out = out(base, "odt"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { OdfWriter.odt(doc, it) } }; Cv6Result(listOf(out))
        }
        "pdf_ods" -> {
            val pages = PdfFlow6.pages(f, pw, pagesOf(f, pw, o.range), false, true, p)
            val sheets = pages.layouts.mapNotNull { l ->
                val rows = LayoutFlow.tables(listOf(l)).flatMap { t -> t + listOf(emptyList()) }
                if (rows.isEmpty()) null else ctx.getString(R.string.cv_sheet_name).format(l.index + 1) to rows
            }
            val out = out(base, "ods"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { OdfWriter.ods(sheets, it, pages.layouts.firstOrNull()?.rtl == true) } }; Cv6Result(listOf(out))
        }
        "pdf_odp" -> {
            val slides = ArrayList<OdfWriter.Slide>()
            var size: Pair<Float, Float>? = null
            ImageIo6.pagePictures(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi) { _, w, h, jpeg -> if (size == null) size = w to h; slides += OdfWriter.Slide("", emptyList(), jpeg); p(slides.size, slides.size + 1) }
            val (w, h) = size ?: (842f to 595f)
            val wCm = 28f; val hCm = wCm * h / w
            val out = out(base, "odp"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { OdfWriter.odp(slides, it, false, wCm, hCm) } }; Cv6Result(listOf(out))
        }
        "pdf_svg" -> {
            val dir = File(outDir, base + "_svg").also { it.mkdirs() }
            val files = ImageIo6.svgPages(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi, dir, p)
            if (o.zip && files.size > 1) Cv6Result(listOf(zipFiles(files, out(base + "_svg", "zip"), dir))) else Cv6Result(files)
        }
        "pdf_pdfa" -> {
            val out = out(base + "_PDFA", "pdf")
            val (report, _) = PdfA.convert(ctx, f, pw, out, PdfAOptions(flattenTransparency = o.pdfaStrict))
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_pdfa, report.errors))
        }
        "pdf_gray" -> Cv6Result(listOf(PdfTransforms6.grayscale(f, pw, out(base + "_gray", "pdf"), tempDir, p)))
        "pdf_compress" -> {
            val out = out(base + "_small", "pdf")
            val r = Optimizer.optimize(f, pw, out, OptimizeOptions(imageDpi = o.dpi, jpegQuality = o.quality, grayImages = o.gray), p)
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_size, DocumentRepository.formatSize(r.before.fileSize), DocumentRepository.formatSize(out.length())))
        }
        "pdf_searchable" -> {
            val out = out(base + "_ocr", "pdf")
            val engine = com.smartdocs.editor.core.ocr.OcrEngine(ctx.applicationContext)
            val made = try { com.smartdocs.editor.core.tools5.OcrRunner.ocrFile(ctx, engine, f, pw, com.smartdocs.editor.core.tools5.OcrPlan(langs = o.ocrLangs), out, p) } finally { engine.close() }
            if (made == null) { withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }; Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_has_text)) } else Cv6Result(listOf(out))
        }
        "pdf_flatten" -> { val out = out(base + "_flat", "pdf"); PdfOps.flatten(ctx, f, out, o.dpi, pw); Cv6Result(listOf(out)) }
        "pdf_tiff" -> Cv6Result(listOf(Export7Run.tiff(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi, if (o.bw) TiffColor.BW else if (o.gray) TiffColor.GRAY else TiffColor.RGB, out(base, "tif"), p)))
        "pdf_bmp" -> {
            val dir = File(outDir, base + "_bmp").also { it.mkdirs() }
            val files = Export7Run.bmp(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi, dir, base, p)
            if (o.zip && files.size > 1) Cv6Result(listOf(zipFiles(files, out(base + "_bmp", "zip"), dir))) else Cv6Result(files)
        }
        "pdf_ps", "pdf_eps" -> {
            val eps = conv.id == "pdf_eps"
            val files = Export7Run.ps(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi, false, eps, this::out, base, p)
            if (eps && o.zip && files.size > 1) Cv6Result(listOf(zipFiles(files, out(base + "_eps", "zip")))) else Cv6Result(files)
        }
        "pdf_xps" -> Cv6Result(listOf(Export7Run.xps(ctx, f, pw, pagesOf(f, pw, o.range), o.dpi, out(base, "xps"), p)))
        "pdf_xml" -> Cv6Result(listOf(Export7Run.xml(f, pw, pagesOf(f, pw, o.range), out(base, "xml"))))
        "pdf_access_txt" -> Cv6Result(listOf(Export7Run.accessibleText(f, pw, pagesOf(f, pw, o.range), out(base + "_accessible", "txt"),
            ctx.getString(R.string.pc_figure), ctx.getString(R.string.pc_page_heading))))
        "pdf_html_set" -> Cv6Result(listOf(Export7Run.htmlSet(f, pw, pagesOf(f, pw, o.range), o.includeImages, out(base + "_html", "zip"), p)))
        "pdf_xml2003" -> {
            val (x, n) = Export7Run.xml2003(f, pw, pagesOf(f, pw, o.range), o.perTable, { ctx.getString(R.string.cv_sheet_name).format(it) }, out(base, "xml"), p)
            Cv6Result(listOf(x), ctx.getString(R.string.pc_sheets_n, n))
        }
        else -> throw IllegalArgumentException(conv.id)
    }

    // ------------------------------------------------------------------ everything else

    private suspend fun other(conv: Conv, inputs: List<File>, o: Cv6Opts, p: (Int, Int) -> Unit, progress: (Stage, Int, Int) -> Unit): Cv6Result =
        Cv6Engine2(ctx, docs, this::outFile).run(conv, inputs, o, p, progress)

    internal fun outFile(base: String, ext: String): File = out(base, ext)
    internal fun zip(files: List<File>, zip: File, folder: File?): File = zipFiles(files, zip, folder)
}
