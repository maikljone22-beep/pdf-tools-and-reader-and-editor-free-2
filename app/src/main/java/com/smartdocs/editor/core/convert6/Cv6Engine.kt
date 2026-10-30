package com.smartdocs.editor.core.convert6

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert.ConvertEngine
import com.smartdocs.editor.core.convert.ConvertOptions
import com.smartdocs.editor.core.convert.TargetFormat
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.files.FileNames
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.security.SecurityInfoReader
import com.smartdocs.editor.core.tools5.ExportPlus
import com.smartdocs.editor.core.tools5.ImageExportOptions
import com.smartdocs.editor.core.tools5.ImageFormat
import com.smartdocs.editor.core.tools5.OptimizeOptions
import com.smartdocs.editor.core.tools5.Optimizer
import com.smartdocs.editor.core.tools5.PdfA
import com.smartdocs.editor.core.tools5.PdfAOptions
import com.smartdocs.editor.core.tools5.ToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Options of one hub run (sheet values; each conversion reads the ones it shows). */
data class Cv6Opts(
    val range: String = "",
    val autoOcr: Boolean = true,
    val tables: Boolean = true,
    val includeImages: Boolean = true,
    val singleSheet: Boolean = false,
    val pptEditable: Boolean = true,
    /**
     * Word: keep the page layout (a Word page per PDF page, running header / footer) or flowing text; null = automatic:
     * flowing for Arabic documents whose pages needed OCR (scanned or mixed pages), the page layout otherwise.
     */
    val keepLayout: Boolean? = null,
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
    /** Office → PDF: bookmarks from Word headings, Excel sheet names, PowerPoint slides. */
    val bookmarks: Boolean = true,
    /** To PDF: turn likely blanks (lines, boxes) into form fields (form field recognition on creation). */
    val recognizeFields: Boolean = false,
    /** PDF → Word text recovery (row 51): with [autoOcr], recognise every page instead of only the problem pages. */
    val recoverAllPages: Boolean = false,
    /** PDF → Word "precisely laid out" (row 46): text boxes at their PDF position; wins over [keepLayout]. */
    val exactLayout: Boolean = false,
    /** PowerPoint → PDF (row 103): speaker notes become note comments on their slide's page. */
    val speakerNotes: Boolean = true,
    /** Excel → PDF (row 103): cell comments become note comments. */
    val cellComments: Boolean = true,
    /** PowerPoint → PDF (row 103): slides to print (blank = all) and whether hidden slides are printed. */
    val slideRange: String = "",
    val hiddenSlides: Boolean = false,
    /** PDF → Word / Text / HTML running headers and footers: 0 automatic (Word header, dropped from text), 1 kept as text, 2 deleted. */
    val headerMode: Int = 0,
    /** PDF → Text: keep the PDF's line breaks; wrap at this many characters (0 = no). */
    val txtKeepLines: Boolean = false,
    val txtWrap: Int = 0,
    /** PDF → Excel (s6): split multi-line cells into rows; reduce merged cells. */
    val xlsxSplitLines: Boolean = false,
    val xlsxNoMerge: Boolean = false,
    /** PDF → Excel: the source's decimal / thousands separators (0 point, 1 comma, 2 space). */
    val xlsxSeparators: Int = 0,
    /** PDF → Word (s6): character spacing (pt) and anchored pictures. */
    val docxCharSpacing: Float = 0f,
    val docxAnchorImages: Boolean = false,
    /** PDF → Word: PDF comments become Word comments (FINAL_CHECK_2 #5). */
    val docxComments: Boolean = true,
    /** Table detection: also tables found from text alignment (false = lined tables only). */
    val streamTables: Boolean = true,
    /** s6 Batch "page range per file": a range for one input (its path → range); others use [range]. */
    val fileRanges: Map<String, String> = emptyMap(),
    /** s6 Batch / s5 "output folder": a folder (SAF tree URI) the outputs are also copied to; "" = Documents only. */
    val outputTree: String = "",
    /** FINAL_CHECK_2 #3: creation preset (CreatePreset) and a tagged PDF when creating one. */
    val preset: Int = CreatePreset.STANDARD,
    val tagged: Boolean = false,
    /** FINAL_CHECK_2 #9: Excel → PDF fit to page ([com.smartdocs.editor.core.office.ExcelFit] mode). */
    val excelFit: Int = com.smartdocs.editor.core.office.ExcelFit.AUTO,
)

enum class Stage { WORK, OCR }

/** Outputs of a run, notes for the result sheet, and the inputs that failed (a batch goes on after a failure). */
/** The page range of input [f]: its own (batch, per file) or the common one. */
fun Cv6Opts.rangeFor(f: File): String = fileRanges[f.path]?.takeIf { it.isNotBlank() } ?: range

class Cv6Result(val files: List<File>, val info: String? = null, val failures: List<Cv6Failure> = emptyList())

/**
 * Runs a hub [Conv] on its inputs. Outputs go to the documents folder; temporary files are cleaned up.
 *
 * Since the convert audit: every input of a batch is tried (failures are returned, not thrown — A-H5 / B02 / C1); each
 * protected PDF uses its own password from [run]'s map (B03 / C3 / A-H4); outputs are recorded in a journal while the
 * run is in progress and removed when it fails, is cancelled or the app dies (C2 / C11 / B15); multi-file outputs are
 * written in a temp folder and moved into Documents only when complete (B14 / B15 / C40); blocking conversions poll the
 * job for Cancel (B01); restricted PDFs are honoured (item 8 / C16).
 */
class Cv6Engine(private val ctx: Context, private val docs: DocumentRepository) {

    private val outDir get() = docs.docsDir
    private val tempDir get() = docs.tempDir
    private val created = java.util.Collections.synchronizedList(ArrayList<File>())
    private val journal = journalOf(ctx)
    private var passwords: Map<String, String> = emptyMap()
    private var defaultPassword: String? = null

    /** The password of input [f]: its own (asked for this file) or the one typed for the run. */
    internal fun pwOf(f: File): String? = passwords[f.absolutePath] ?: defaultPassword

    /** A new output in Documents (reserved at once, so two outputs of one run never get the same name). */
    private fun out(base: String, ext: String): File = docs.uniqueFile(outDir, base, ext).also { f ->
        runCatching { f.parentFile?.mkdirs(); f.createNewFile() }
        created += f; journal.add(f)
    }

    /** A temp folder for a multi-file output, deleted when the run ends. */
    internal fun workDir(name: String): File = File(tempDir, "cv6w_" + FileNames.clean(name, 40, "work") + "_" + System.nanoTime()).also { it.mkdirs(); created += it }

    /** Moves finished files from a work folder into Documents (unique names). */
    internal fun publish(files: List<File>): List<File> = files.map { f ->
        val dst = out(f.nameWithoutExtension, f.extension)
        if (!f.renameTo(dst)) { f.copyTo(dst, overwrite = true); f.delete() }
        dst
    }

    suspend fun run(conv: Conv, inputs: List<File>, o: Cv6Opts, password: String?, progress: (Stage, Int, Int) -> Unit): Cv6Result = run(conv, inputs, o, password, emptyMap(), progress)

    /**
     * [passwords]: absolute path → password of protected inputs (asked one by one); [password] is tried on every
     * protected input that has none.
     */
    suspend fun run(conv: Conv, inputs: List<File>, o: Cv6Opts, password: String?, passwords: Map<String, String>, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        this.passwords = passwords; defaultPassword = password?.ifEmpty { null }
        created.clear()
        try {
            val r = runInner(conv, inputs, o, progress)
            journal.finish(created.toList(), r.files)
            return r
        } catch (e: Throwable) {
            journal.finish(created.toList(), emptyList())
            throw e
        }
    }

    private suspend fun runInner(conv: Conv, inputs: List<File>, o: Cv6Opts, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        val p: (Int, Int) -> Unit = { d, t -> progress(Stage.WORK, d, t) }
        val src = inputs.firstOrNull()
        return when (conv.id) {
            // ---- no-file sources ----
            "url_pdf" -> {
                val host = runCatching { android.net.Uri.parse(if (o.url.contains("://")) o.url else "https://" + o.url).host }.getOrNull()?.ifBlank { null } ?: "webpage"
                val pdf = ToPdf.url(ctx, o.url, out(host, "pdf"))
                // row 93: bookmarks from the page's headings (detected by size in the printed pages)
                if (o.bookmarks) runCatching { OfficeOutline.applyDetected(pdf) }
                Cv6Result(listOf(pdf))
            }
            "clipboard_pdf" -> {
                // Markdown only with strong signs, and line breaks are always kept (convert audit A-M9)
                val doc = if (MdFlow.looksLikeMarkdown(o.text)) MdFlow.parse(o.text, hardBreaks = true) else FlowDoc.fromText(o.text, "")
                val title = o.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40)?.let { ImageNaming.sanitize(it) }?.ifEmpty { null } ?: "clipboard"
                Cv6Result(listOf(ToPdf6.flowToPdf(ctx, FlowDoc(title, doc.blocks), out(title, "pdf"))))
            }
            else -> {
                requireNotNull(src) { "no input" }
                if (conv.groups.first() == Cv6Group.FROM_PDF || conv.id.startsWith("ocr_pdf") || conv.id == "pdf_searchable") fromPdf(conv, inputs, o, progress)
                else Cv6Engine2(ctx, docs, this).run(conv, inputs, o, p, progress)
            }
        }
    }

    // ------------------------------------------------------------------ batches

    /**
     * Runs [block] for each input; a failing input is recorded, its partial outputs are deleted and the rest go on.
     * One input that fails throws (the hub shows its error or asks its password); in a batch the failures are returned.
     */
    internal suspend fun batch(inputs: List<File>, progress: (Int, Int) -> Unit = { _, _ -> }, block: suspend (index: Int, file: File) -> Cv6Result): Cv6Result {
        var mark = 0
        val outcome = Cv6Batch.each(inputs, onFailure = { _, _ -> synchronized(created) { created.subList(mark, created.size).toList() }.forEach { OutputJournal.deleteQuietly(it) } }) { k, f ->
            mark = created.size
            progress(k, inputs.size)
            block(k, f)
        }
        progress(inputs.size, inputs.size)
        if (outcome.done.isEmpty() && outcome.failures.size == 1 && inputs.size == 1) throw outcome.failures[0].error
        val multi = inputs.size > 1
        return Cv6Result(outcome.done.flatMap { it.second.files },
            Cv6Batch.infoLines(outcome.done.map { (f, r) -> f.name to r.info }.let { if (multi) it else it.take(1) }),
            outcome.failures)
    }

    /** [block] with the PDF password errors of [f] turned into [Cv6NeedPassword] (the hub asks for that file's password). */
    private suspend fun <T> pdfOf(f: File, pw: String?, block: suspend () -> T): T = try { block() }
        catch (e: ConvertEngine.PasswordNeeded) { throw Cv6NeedPassword(f, pw != null, e) }
        catch (e: com.smartdocs.editor.core.pdf.PdfSession.Companion.PasswordRequiredException) { throw Cv6NeedPassword(f, pw != null, e) }
        catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw Cv6NeedPassword(f, pw != null, e) }

    /** Refuses a conversion the PDF's owner does not allow (item 8 / C16); true when the file is encrypted. */
    private suspend fun checkSecurity(conv: Conv, f: File, pw: String?): Boolean {
        if (FileKind6.of(f) != Kind6.PDF || !Cv6Security.mayBeEncrypted(f)) return false
        return withContext(Dispatchers.IO) {
            pdfOf(f, pw) {
                PdfOps.loadRaw(f, pw).use { d ->
                    if (!d.isEncrypted) return@use false
                    if (!Cv6Security.allowed(conv.id, SecurityInfoReader.of(d))) throw RestrictedInput(f.name)
                    true
                }
            }
        }
    }

    /** [out] (written without encryption) protected like [src] again: same permissions and key length (item 8). */
    private suspend fun keepProtection(src: File, pw: String?, out: File) = withContext(Dispatchers.IO) {
        val policy = PdfOps.loadRaw(src, pw).use { d -> if (d.isEncrypted) PdfOps.keepProtection(d, pw) else null } ?: return@withContext
        val tmp = File(tempDir, "cv6k_${System.nanoTime()}.pdf")
        try {
            PdfOps.loadRaw(out, null).use { d -> if (!d.isEncrypted) { d.protect(policy); d.save(tmp) } }
            if (tmp.length() > 0) { if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true) }
        } finally { tmp.delete() }
    }

    // ------------------------------------------------------------------ from PDF

    private suspend fun pagesOf(f: File, pw: String?, range: String): List<Int> {
        if (range.isBlank()) return emptyList()
        if (!PdfOps.rangeSyntaxOk(range)) throw UserMessage(ctx.getString(R.string.cv6_err_range))
        val n = withContext(Dispatchers.IO) { pdfOf(f, pw) { ConvertEngine.pageCount(f, pw) } }
        // a range that selects nothing in this file: a note for this file, the batch goes on (audit B20)
        return PdfOps.parseRange(range, n).ifEmpty { throw InvalidRange(f.name) }
    }

    /** "Is the job still running?" for blocking library loops (convert audit B01: the hub passed `{ false }`). */
    private suspend fun cancelCheck(): () -> Boolean { val job = coroutineContext[Job]; return { job?.isActive == false } }

    /** Batch over PDFs; scanned inputs get a searchable copy first when [ocr] says so. */
    private suspend fun fromPdf(conv: Conv, inputs: List<File>, o: Cv6Opts, progress: (Stage, Int, Int) -> Unit): Cv6Result = batch(inputs) { k, f0 ->
        val pw = pwOf(f0)
        val multi = inputs.size > 1
        val p: (Int, Int) -> Unit = { d, t -> if (multi) progress(Stage.WORK, k * 100 + (if (t > 0) d * 100 / t else 0), inputs.size * 100) else progress(Stage.WORK, d, t) }
        val isPdf = FileKind6.of(f0) == Kind6.PDF
        val encrypted = if (isPdf) checkSecurity(conv, f0, pw) else false
        val forceOcr = conv.id.startsWith("ocr_pdf")
        val wantsOcr = forceOcr || (Opt.AUTO_OCR in conv.opts && o.autoOcr && !(conv.id == "pdf_pptx" && !o.pptEditable))
        pdfOf(f0, pw) {
            if ((conv.id == "pdf_docx" || conv.id == "ocr_pdf_docx") && isPdf) return@pdfOf word(f0, f0.nameWithoutExtension, pw, o, wantsOcr, p) { d, t -> progress(Stage.OCR, d, t) }
            // pictures (TIFF too) for "searchable PDF": a PDF of the pictures first, one OCR page per picture page (audit C8)
            if (conv.id == "pdf_searchable" && !isPdf) {
                val t = File(tempDir, "cv6s_${System.nanoTime()}.pdf").also { created += it }
                ImageIo6.imagesToPdf(listOf(f0), t, PageSetup(PageSize6.IMAGE))
                return@pdfOf onePdf(conv, t, f0.nameWithoutExtension, null, o, p, false)
            }
            val ocr = if (wantsOcr && isPdf) PdfFlow6.ocrCopy(ctx, f0, pw, o.ocrLangs, tempDir, forceOcr) { d, t -> progress(Stage.OCR, d, t) } else null
            val f = ocr ?: f0
            val fpw = if (ocr != null) null else pw
            val r = try { onePdf(conv, f, f0.nameWithoutExtension, fpw, o, p, encrypted && ocr == null, f0, pw) } finally { ocr?.delete() }
            // a signed source: the new PDF is an unsigned copy (convert audit C45)
            if (conv.id in setOf("pdf_gray", "pdf_compress", "pdf_flatten", "pdf_pdfa", "pdf_searchable") && withContext(Dispatchers.IO) { Cv6Security.hasSignature(f0) })
                Cv6Result(r.files, listOfNotNull(r.info, ctx.getString(R.string.cvf_info_signed, f0.name)).joinToString("\n"), r.failures)
            else r
        }
    }

    /**
     * PDF → Word (PDF_TO_WORD_r5): converts a repaired copy, with a fresh OCR layer on the pages that need one when [ocr]
     * ([PdfFlow6.wordInput]); flowing text or the page layout per [Cv6Opts.keepLayout].
     */
    private suspend fun word(f: File, base: String, pw: String?, o: Cv6Opts, ocr: Boolean, p: (Int, Int) -> Unit, ocrProgress: (Int, Int) -> Unit): Cv6Result {
        val pages = pagesOf(f, pw, o.rangeFor(f))
        val cancelled = cancelCheck()
        return PdfFlow6.wordInput(ctx, f, pw, pages, o.ocrLangs, tempDir, ocr, ocrProgress, allPages = ocr && o.recoverAllPages).use { inp ->
            val flowing = o.keepLayout?.not() ?: (inp.ocrPages > 0 && inp.rtl)
            val out = out(base, "docx")
            val opts = ConvertOptions(format = TargetFormat.DOCX, pages = pages, detectTables = o.tables, includeImages = o.includeImages, docxFlowing = flowing && !o.exactLayout,
                docxExact = o.exactLayout, detectHeaders = o.headerMode != 1, dropFurniture = o.headerMode == 2,
                docxCharSpacing = o.docxCharSpacing, docxAnchorImages = o.docxAnchorImages, docxComments = o.docxComments, streamTables = o.streamTables)
            val stats = ConvertEngine.convert(ctx, inp.file, pw, opts, out, p, cancelled)
            val info = ArrayList<String>()
            info += if (stats.textPages == 0) ctx.getString(R.string.cv_scanned_warning) else ctx.getString(R.string.cv6_info_pages, stats.pages, stats.tables, stats.images)
            if (inp.ocrPages > 0) info += ctx.getString(R.string.cv6_info_word_ocr, inp.ocrPages)
            if (inp.ocrUnavailable) info += ctx.getString(R.string.cv6_info_ocr_unavailable)
            if (stats.rebuiltPages.isNotEmpty()) info += ctx.getString(R.string.cv6_info_rebuilt, stats.rebuiltPages.joinToString("، ") { (it + 1).toString() })
            Cv6Result(listOf(out), info.joinToString("\n"))
        }
    }

    private suspend fun office(target: TargetFormat, f: File, base: String, pw: String?, o: Cv6Opts, p: (Int, Int) -> Unit): Cv6Result {
        val pages = pagesOf(f, pw, o.rangeFor(f))
        val out = out(base, target.ext)
        val opts = ConvertOptions(format = target, pages = pages, xlsxSingleSheet = o.singleSheet, pptxEditable = o.pptEditable, detectTables = o.tables, includeImages = o.includeImages,
            sheetName = { n -> ctx.getString(R.string.cv_sheet_name).format(n) }, singleSheetName = ctx.getString(R.string.cv_all_pages_sheet),
            detectHeaders = o.headerMode != 1, dropFurniture = o.headerMode == 2, txtKeepLines = o.txtKeepLines, txtWrap = o.txtWrap,
            xlsxSplitLines = o.xlsxSplitLines, xlsxNoMerge = o.xlsxNoMerge, xlsxSeparators = o.xlsxSeparators, streamTables = o.streamTables)
        val stats = ConvertEngine.convert(ctx, f, pw, opts, out, p, cancelCheck())
        val scanned = stats.textPages == 0 && !(target == TargetFormat.PPTX && !o.pptEditable)
        return Cv6Result(listOf(out), if (scanned) ctx.getString(R.string.cv_scanned_warning) else ctx.getString(R.string.cv6_info_pages, stats.pages, stats.tables, stats.images))
    }

    /** Files into one ZIP (unique entry names); the files are deleted. */
    private fun zipFiles(files: List<File>, zip: File): File {
        val names = ImageNaming.dedupe(files.map { it.name })
        ZipOutputStream(zip.outputStream().buffered()).use { z -> files.forEachIndexed { i, f -> z.putNextEntry(ZipEntry(names[i])); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() } }
        return zip
    }

    /** Several page files from [dir]: one ZIP when asked (and more than one), else moved into Documents. */
    private fun pack(files: List<File>, zip: Boolean, zipBase: String): List<File> =
        if (zip && files.size > 1) listOf(zipFiles(files, out(zipBase, "zip"))) else publish(files)

    private suspend fun onePdf(conv: Conv, f: File, base: String, pw: String?, o: Cv6Opts, p: (Int, Int) -> Unit, encrypted: Boolean = false,
                               src: File = f, srcPw: String? = pw): Cv6Result = when (conv.id) {
        // a PDF reaches word() from fromPdf directly; this branch only serves a mis-typed input
        "pdf_docx", "ocr_pdf_docx" -> word(f, base, pw, o, false, p) { _, _ -> }
        "pdf_xlsx", "ocr_pdf_xlsx" -> office(TargetFormat.XLSX, f, base, pw, o, p)
        "pdf_pptx" -> office(TargetFormat.PPTX, f, base, pw, o, p)
        "pdf_html" -> office(TargetFormat.HTML, f, base, pw, o, p)
        "pdf_txt", "ocr_pdf_txt" -> office(TargetFormat.TXT, f, base, pw, o.copy(includeImages = false), p)
        "pdf_images" -> {
            val fmt = when (o.imgFormat) { ImgFmt.PNG -> ImageFormat.PNG; ImgFmt.WEBP -> ImageFormat.WEBP; else -> ImageFormat.JPG }
            val dir = workDir(base + "_images")
            val files = ExportPlus.images(ctx, f, pw, ImageExportOptions(fmt, o.dpi, o.gray, o.bw, o.quality, pagesOf(f, pw, o.rangeFor(f)), zip = false), dir, p)
                .map { x -> File(dir, x.name.replace(f.nameWithoutExtension, base)).let { n -> if (n != x && x.renameTo(n)) n else x } }
            Cv6Result(pack(files, o.zip, base + "_images"))
        }
        "pdf_long_image" -> {
            val fmt = o.imgFormat ?: ImgFmt.JPG
            val parts = ImageIo6.longImage(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), fmt, o.dpi, o.quality, { i, n -> out(base + "_long" + (if (n > 1) "_" + (i + 1) else ""), fmt.ext) }, p)
            Cv6Result(parts, if (parts.size > 1) ctx.getString(R.string.cvf_info_long_parts, parts.size) else null)
        }
        "pdf_rtf" -> Cv6Result(listOf(ExportPlus.rtf(f, pw, out(base, "rtf"), pagesOf(f, pw, o.rangeFor(f)), p)))
        "pdf_md" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.rangeFor(f)), o.includeImages, pageBreaks = false, onProgress = p, detectTables = o.tables, title = base, spillDir = workDir("md"))
            withContext(Dispatchers.IO) { Cv6Result(listOf(writeMarkdown(doc, base, this@Cv6Engine::out))) }
        }
        "pdf_epub" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.rangeFor(f)), o.includeImages, pageBreaks = false, onProgress = p, title = base, spillDir = workDir("epub"))
            val out = out(base, "epub")
            val dropped = withContext(Dispatchers.IO) { out.outputStream().buffered().use { EpubWriter.write(doc, it) } }
            Cv6Result(listOf(out), listOfNotNull(ctx.getString(R.string.cv6_info_chapters, doc.chapters().size), if (dropped > 0) ctx.getString(R.string.cvf_info_images_dropped, dropped) else null).joinToString("\n"))
        }
        "pdf_csv" -> {
            val pages = PdfFlow6.pages(f, pw, pagesOf(f, pw, o.rangeFor(f)), false, true, p, title = base)
            val tables = LayoutFlow.tables(pages.layouts)
            val out = out(base, "csv")
            withContext(Dispatchers.IO) { out.outputStream().use { s -> s.write(Csv6.BOM); s.write(Csv6.writeTables(tables).toByteArray(Charsets.UTF_8)) } }
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_tables, pages.layouts.sumOf { l -> l.blocks.count { it is com.smartdocs.editor.core.convert.TableBlock } }))
        }
        "pdf_odt" -> {
            val doc = PdfFlow6.flow(f, pw, pagesOf(f, pw, o.rangeFor(f)), o.includeImages, pageBreaks = true, onProgress = p, title = base, spillDir = workDir("odt"))
            val out = out(base, "odt"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { OdfWriter.odt(doc, it) } }; Cv6Result(listOf(out))
        }
        "pdf_ods" -> {
            val pages = PdfFlow6.pages(f, pw, pagesOf(f, pw, o.rangeFor(f)), false, true, p, title = base)
            val sheets = LayoutFlow.sheetsPerPage(pages.layouts) { ctx.getString(R.string.cv_sheet_name).format(it) }
            val out = out(base, "ods"); withContext(Dispatchers.IO) { out.outputStream().buffered().use { OdfWriter.ods(sheets, it, pages.layouts.firstOrNull()?.rtl == true) } }; Cv6Result(listOf(out))
        }
        "pdf_odp" -> Cv6Result(listOf(Export7Run.odp(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, out(base, "odp"), p)))
        "pdf_svg" -> {
            val dir = workDir(base + "_svg")
            val files = ImageIo6.svgPages(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, dir, p).map { x -> File(dir, x.name.replace(f.nameWithoutExtension, base)).let { n -> if (n != x && x.renameTo(n)) n else x } }
            Cv6Result(pack(files, o.zip, base + "_svg"))
        }
        "pdf_pdfa" -> {
            val out = out(base + "_PDFA", "pdf")
            val (report, _) = PdfA.convert(ctx, f, pw, out, PdfAOptions(flattenTransparency = o.pdfaStrict), onProgress = p)
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_pdfa, report.errors))
        }
        "pdf_gray" -> {
            val out = out(base + "_gray", "pdf")
            val partly = PdfTransforms6.grayscale(f, pw, out, tempDir, ctx, p)
            if (encrypted) keepProtection(src, srcPw, out)
            Cv6Result(listOf(out), if (partly > 0) ctx.getString(R.string.cvf_info_gray_raster, partly) else null)
        }
        "pdf_compress" -> {
            val out = out(base + "_small", "pdf")
            val r = Optimizer.optimize(f, pw, out, OptimizeOptions(imageDpi = o.dpi, jpegQuality = o.quality, grayImages = o.gray), p)
            if (encrypted) keepProtection(src, srcPw, out)
            Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_size, DocumentRepository.formatSize(r.before.fileSize), DocumentRepository.formatSize(out.length())))
        }
        "pdf_searchable" -> {
            val out = out(base + "_ocr", "pdf")
            val engine = com.smartdocs.editor.core.ocr.OcrEngine(ctx.applicationContext)
            val made = try { com.smartdocs.editor.core.tools5.OcrRunner.ocrFile(ctx, engine, f, pw, com.smartdocs.editor.core.tools5.OcrPlan(langs = o.ocrLangs), out, p) } finally { engine.close() }
            if (made == null) { withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }; Cv6Result(listOf(out), ctx.getString(R.string.cv6_info_has_text)) }
            else { if (encrypted) keepProtection(src, srcPw, out); Cv6Result(listOf(out)) }
        }
        "pdf_flatten" -> {
            val out = out(base + "_flat", "pdf")
            PdfOps.flatten(ctx, f, out, o.dpi, pw, p)
            if (encrypted) keepProtection(src, srcPw, out)
            Cv6Result(listOf(out))
        }
        "pdf_tiff" -> Cv6Result(listOf(Export7Run.tiff(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, if (o.bw) TiffColor.BW else if (o.gray) TiffColor.GRAY else TiffColor.RGB, out(base, "tif"), p)))
        "pdf_bmp" -> {
            val dir = workDir(base + "_bmp")
            Cv6Result(pack(Export7Run.bmp(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, dir, base, p), o.zip, base + "_bmp"))
        }
        "pdf_ps" -> Cv6Result(Export7Run.ps(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, false, false, this::out, base, p))
        "pdf_eps" -> {
            val dir = workDir(base + "_eps")
            val files = Export7Run.ps(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, false, true, { b, e -> ImageIo6.unique(dir, b, e) }, base, p)
            Cv6Result(pack(files, o.zip, base + "_eps"))
        }
        "pdf_xps" -> Cv6Result(listOf(Export7Run.xps(ctx, f, pw, pagesOf(f, pw, o.rangeFor(f)), o.dpi, out(base, "xps"), p)))
        "pdf_xml" -> Cv6Result(listOf(Export7Run.xml(f, pw, pagesOf(f, pw, o.rangeFor(f)), out(base, "xml"), p)))
        "pdf_access_txt" -> Cv6Result(listOf(Export7Run.accessibleText(f, pw, pagesOf(f, pw, o.rangeFor(f)), out(base + "_accessible", "txt"),
            ctx.getString(R.string.pc_figure), ctx.getString(R.string.pc_page_heading))))
        "pdf_html_set" -> Cv6Result(listOf(Export7Run.htmlSet(f, pw, pagesOf(f, pw, o.rangeFor(f)), o.includeImages, out(base + "_html", "zip"), p, base, workDir("html"))))
        "pdf_xml2003" -> {
            val (x, n) = Export7Run.xml2003(f, pw, pagesOf(f, pw, o.rangeFor(f)), o.perTable, { ctx.getString(R.string.cv_sheet_name).format(it) }, out(base, "xml"), p)
            Cv6Result(listOf(x), ctx.getString(R.string.pc_sheets_n, n))
        }
        else -> throw IllegalArgumentException(conv.id)
    }

    internal fun outFile(base: String, ext: String): File = out(base, ext)
    internal fun zip(files: List<File>, zip: File): File = zipFiles(files, zip)
    internal fun packFiles(files: List<File>, zip: Boolean, zipBase: String): List<File> = pack(files, zip, zipBase)
    internal suspend fun <T> withPdfPassword(f: File, pw: String?, block: suspend () -> T): T = pdfOf(f, pw, block)
    internal suspend fun security(conv: Conv, f: File, pw: String?): Boolean = checkSecurity(conv, f, pw)
    internal fun track(f: File): File { created += f; return f }

    companion object {
        /** The journal of outputs being written (purged at start-up: a killed run leaves no half-written files). */
        fun journalOf(ctx: Context) = OutputJournal(File(ctx.applicationContext.filesDir, "cv6_pending.txt"))
    }
}
