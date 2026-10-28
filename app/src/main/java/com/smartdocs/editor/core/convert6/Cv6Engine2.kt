package com.smartdocs.editor.core.convert6

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.office.OfficeHtml
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.office5.CsvIO
import com.smartdocs.editor.core.office5.XlsxIO5
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.tools5.EpubReader
import com.smartdocs.editor.core.tools5.ExportPlus
import com.smartdocs.editor.core.tools5.ImageExportOptions
import com.smartdocs.editor.core.tools5.ImageFormat
import com.smartdocs.editor.core.tools5.OcrPlan
import com.smartdocs.editor.core.tools5.OcrRunner
import com.smartdocs.editor.core.tools5.ToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Markdown file, or (with pictures) a ZIP holding the .md and an images/ folder. Pictures are read one at a time
 * while the ZIP is written (file-backed pictures of a PDF → Markdown export stay out of memory, audit B10).
 */
internal fun writeMarkdown(doc: FlowDoc, base: String, out: (String, String) -> File): File {
    val imgs = ArrayList<Pair<String, FBlock.Image>>()
    val md = FlowMarkdown.write(doc) { i, img -> val n = "images/img$i." + (Images6.sniffExt(img.head) ?: img.ext); imgs += n to img; n }
    if (imgs.isEmpty()) return out(base, "md").also { it.writeText(md) }
    val zip = out(base + "_md", "zip")
    ZipOutputStream(zip.outputStream().buffered()).use { z ->
        z.putNextEntry(ZipEntry(FileNamesInZip.entry(base) + ".md")); z.write(md.toByteArray(Charsets.UTF_8)); z.closeEntry()
        imgs.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b.bytes); z.closeEntry() }
    }
    return zip
}

/** ZIP entry names: no path separators or control characters, length-capped. Pure. */
internal object FileNamesInZip {
    fun entry(base: String): String = com.smartdocs.editor.core.files.FileNames.clean(base, 150, "document")
}

/**
 * Hub conversions that do not start from a PDF: to PDF, images, Office ↔ Office / text, books, OCR of pictures.
 * Outputs, work folders, passwords and batches come from the [engine] (journal, cleanup, per-file failures).
 */
internal class Cv6Engine2(private val ctx: Context, private val docs: DocumentRepository, private val engine: Cv6Engine) {

    private val tempDir get() = docs.tempDir
    private fun out(base: String, ext: String) = engine.outFile(base, ext)

    /** One result per input: a failing input is reported and the others go on (convert audit A-H5 / C1). */
    private suspend fun each(inputs: List<File>, p: (Int, Int) -> Unit, block: suspend (File) -> Cv6Result): Cv6Result =
        engine.batch(inputs, p) { _, f -> coroutineContext.ensureActive(); block(f) }

    private fun files(vararg f: File) = Cv6Result(f.toList())

    private suspend fun write(base: String, ext: String, w: (java.io.OutputStream) -> Unit): File = withContext(Dispatchers.IO) {
        out(base, ext).also { f -> f.outputStream().buffered().use(w) }
    }

    suspend fun run(conv: Conv, inputs: List<File>, o: Cv6Opts, p: (Int, Int) -> Unit, progress: (Stage, Int, Int) -> Unit): Cv6Result = when (conv.id) {
        "word_pdf", "excel_pdf", "ppt_pdf", "images_pdf", "tiff_pdf", "text_pdf", "md_pdf", "html_pdf", "epub_pdf", "eml_pdf", "any_pdf" -> toPdf(conv, inputs, o, p)
        "img_convert" -> each(inputs, p) { f -> image(f, o.imgFormat, o.quality, 100, 0, "") }
        "heic_jpg" -> each(inputs, p) { f -> image(f, ImgFmt.JPG, o.quality, 100, 0, "") }
        "img_resize" -> each(inputs, p) { f -> image(f, o.imgFormat, o.quality, if (o.maxSide > 0) 100 else o.resizePercent, o.maxSide, "_small") }
        "img_txt", "img_docx", "img_ocr_pdf" -> ocrImages(conv, inputs, o, progress)
        "docx_images", "pptx_images" -> each(inputs, p) { f -> Cv6Result(pageImages(f, o, p)) }
        "pptx_handouts" -> each(inputs, p) { f -> files(handouts(conv, f, o, p)) }
        "pptx_odp" -> each(inputs, p) { f -> files(pptxToOdp(f, o, p)) }
        "xlsx_csv" -> each(inputs, p) { f -> toCsv(f, o) }
        "csv_xlsx" -> each(inputs, p) { f ->
            // off the main thread, the text decoded once (convert audit C6); values keep their type / format (C18)
            withContext(Dispatchers.IO) {
                val text = ToPdf.readText(f)
                val rtl = Text6.rtlDominant(text.take(4000))
                val delim = if (f.extension.equals("tsv", true)) '\t' else com.smartdocs.editor.core.openall.CsvSniff.delimiter(text, f.extension)
                val x = CsvIO.toXlsxFromText(text, delim, f.nameWithoutExtension, rtl)
                files(out(f.nameWithoutExtension, "xlsx").also { XlsxIO5.save(x, it) })
            }
        }
        "xlsx_ods" -> each(inputs, p) { f ->
            val sheets = withContext(Dispatchers.IO) { ToPdf6.readSheetsInfo(f) }
            val list = sheets.list.map { (n, rows) -> n to ToPdf6.trimSheet(rows) }
            val o2 = write(f.nameWithoutExtension, "ods") { OdfWriter.ods(list, it, Text6.rtlDominant(list.firstOrNull()?.second?.take(50)?.flatten()?.joinToString(" ").orEmpty())) }
            Cv6Result(listOf(o2), sheets.cut.joinToString("\n") { ctx.getString(R.string.cvf_info_sheet_cut, it) }.ifBlank { null })
        }
        "epub_txt" -> each(inputs, p) { f ->
            // text only: the book's pictures are not even read (convert audit C21)
            files(withContext(Dispatchers.IO) { out(f.nameWithoutExtension, "txt").also { o -> o.bufferedWriter().use { w -> EpubReader.readBook(f, maxImageBytes = 0, totalImageBytes = 0).chapters.forEach { c -> w.write(HtmlFlow.plain(c)); w.write("\n\n") } } } })
        }
        "txt_docx", "md_docx", "md_html", "md_epub", "html_docx", "docx_txt", "docx_html", "docx_md", "docx_odt", "odt_docx", "docx_epub", "epub_docx" ->
            each(inputs, p) { f -> flowConvert(conv.id, f) }
        else -> throw IllegalArgumentException("no engine for " + conv.id)   // a new catalog id must get a branch (audit C31)
    }

    private suspend fun image(f: File, fmt: ImgFmt?, quality: Int, percent: Int, maxSide: Int, suffix: String): Cv6Result {
        var capped = false
        val outs = ImageIo6.convertImage(f, docs.docsDir, fmt, quality, percent, maxSide, suffix, out = { b, e -> out(b, e) }, capped = { capped = true })
        return Cv6Result(outs, if (capped) ctx.getString(R.string.cvf_info_capped, ImageIo6.KEEP_MAX) else null)
    }

    // ---- to PDF ----

    private suspend fun toPdf(conv: Conv, inputs: List<File>, o: Cv6Opts, p: (Int, Int) -> Unit): Cv6Result {
        val notes = ArrayList<String>()
        suspend fun one(f: File, target: File): File =
            if (conv.id == "md_pdf") ToPdf6.flowToPdf(ctx, withContext(Dispatchers.IO) { MdFlow.parse(ToPdf.readText(f), f.nameWithoutExtension, resolve = ToPdf6.siblingResolver(f)) }, target)
            else ToPdf6.convert(ctx, f, target, o.setup, o.quality) { notes += it }
        if (inputs.size > 1 && o.merge) {
            val target = out(inputs.first().nameWithoutExtension + "_" + ctx.getString(R.string.cv6_combined_suffix), "pdf")
            if (conv.id == "images_pdf" || conv.id == "tiff_pdf") {
                val skipped = ArrayList<String>()
                ImageIo6.imagesToPdf(inputs, target, o.setup, o.quality, p) { skipped += it }
                return Cv6Result(listOf(target), if (skipped.isNotEmpty()) ctx.getString(R.string.cvf_info_skipped, skipped.joinToString(", ")) else null)
            }
            if (conv.id == "md_pdf") {
                val temps = inputs.mapIndexed { i, f -> p(i, inputs.size); engine.track(File(tempDir, "cv6md_${i}_${System.nanoTime()}.pdf")).also { one(f, it) } }
                withContext(Dispatchers.IO) { ToPdf.merge(temps, target, tempDir) }
                return Cv6Result(listOf(target))
            }
            ToPdf6.combine(ctx, inputs, target, tempDir, o.setup, o.quality, p, passwords = { engine.pwOf(it) }, info = { notes += it })
            return Cv6Result(listOf(target), notes.distinct().joinToString("\n").ifBlank { null })
        }
        return each(inputs, p) { f ->
            notes.clear()
            val r = if (FileKind6.of(f) == Kind6.PDF && conv.id == "any_pdf") {
                // a PDF among "any → PDF" inputs: copied as it is (its password is not needed)
                out(f.nameWithoutExtension, "pdf").also { t -> withContext(Dispatchers.IO) { f.copyTo(t, overwrite = true) } }
            } else one(f, out(f.nameWithoutExtension, "pdf"))
            Cv6Result(listOf(r), notes.distinct().joinToString("\n").ifBlank { null })
        }
    }

    // ---- Office / text / books ----

    private suspend fun flowConvert(id: String, f: File): Cv6Result = withContext(Dispatchers.IO) {
        val base = f.nameWithoutExtension
        val kind = FileKind6.of(f)
        val notes = ArrayList<String>()
        if (kind == Kind6.DOC && FileKind6.legacyVariant(f.inputStream().use { s -> ByteArray(8).let { b -> b.copyOf(maxOf(0, s.read(b))) } }) == FileKind6.Legacy.CFB)
            notes += ctx.getString(R.string.cvf_info_doc_text)   // Word 97: text only (convert audit C37)
        val doc: FlowDoc = when (id) {
            "txt_docx" -> FlowDoc.fromText(ToPdf.readText(f), base)
            "md_docx", "md_html", "md_epub" -> if (kind == Kind6.MARKDOWN || f.extension.equals("md", true)) MdFlow.parse(ToPdf.readText(f), base, resolve = ToPdf6.siblingResolver(f)) else FlowDoc.fromText(ToPdf.readText(f), base)
            "html_docx" -> HtmlFlow.parse(ToPdf.readHtml(f), base)
            else -> ToPdf6.readWordLike(f, kind)
        }
        var dropped = 0
        val file = when (id) {
            "docx_txt" -> out(base, "txt").also { it.writeText(FlowText.write(doc)) }
            "docx_html" -> out(base, "html").also { it.writeText(if (kind == Kind6.DOCX) OfficeHtml.docx(OoxmlReader.readDocx(f), base) else FlowHtml.write(doc)) }
            "md_html" -> out(base, "html").also { it.writeText(FlowHtml.write(doc)) }
            "docx_md" -> writeMarkdown(doc, base) { b, e -> out(b, e) }
            "docx_odt" -> out(base, "odt").also { t -> t.outputStream().buffered().use { OdfWriter.odt(doc, it) } }
            "docx_epub", "md_epub" -> out(base, "epub").also { t -> dropped = t.outputStream().buffered().use { EpubWriter.write(doc, it) } }
            "odt_docx", "txt_docx", "md_docx", "html_docx", "epub_docx" -> out(base, "docx").also { t -> dropped = t.outputStream().buffered().use { FlowDocx.write(doc, it) } }
            else -> throw IllegalArgumentException("no writer for $id")
        }
        if (dropped > 0) notes += ctx.getString(R.string.cvf_info_images_dropped, dropped)
        Cv6Result(listOf(file), notes.joinToString("\n").ifBlank { null })
    }

    /** [f] as a PDF (converted to a temp file unless it is one) and whether it is a temp copy. */
    private suspend fun asPdf(f: File): Pair<File, Boolean> {
        if (FileKind6.of(f) == Kind6.PDF) return f to false
        val t = engine.track(File(tempDir, "cv6p_${System.nanoTime()}.pdf"))
        ToPdf6.convert(ctx, f, t)
        return t to true
    }

    /** Pages of a document as pictures in a unique work folder, then zipped or moved into Documents (audit C40). */
    private suspend fun pageImages(f: File, o: Cv6Opts, p: (Int, Int) -> Unit): List<File> {
        val (pdf, temp) = asPdf(f)
        try {
            val fmt = when (o.imgFormat) { ImgFmt.PNG -> ImageFormat.PNG; ImgFmt.WEBP -> ImageFormat.WEBP; else -> ImageFormat.JPG }
            val dir = engine.workDir(f.nameWithoutExtension + "_images")
            val pages = ExportPlus.images(ctx, pdf, null, ImageExportOptions(fmt, o.dpi, false, o.quality), dir, p)
            // ExportPlus names pages after the PDF: rename to the source document
            val named = pages.map { x -> val n = File(dir, x.name.replace(pdf.nameWithoutExtension, f.nameWithoutExtension)); if (n != x && x.renameTo(n)) n else x }
            return engine.packFiles(named, o.zip, f.nameWithoutExtension + "_images")
        } finally { if (temp) pdf.delete() }
    }

    /**
     * Handouts: a PDF input uses its password (convert audit C4: the password was never passed), the reading
     * direction is judged on the first pages only and the N-up pass reports progress (C22).
     */
    private suspend fun handouts(conv: Conv, f: File, o: Cv6Opts, p: (Int, Int) -> Unit): File {
        val (pdf, temp) = asPdf(f)
        val pw = if (temp) null else engine.pwOf(f)
        try {
            return engine.withPdfPassword(f, pw) {
                if (!temp) engine.security(conv, f, pw)
                val rtl = withContext(Dispatchers.IO) { runCatching { Text6.rtlDominant(firstPagesText(pdf, pw, 5)) }.getOrDefault(false) }
                PdfTransforms6.nUp(pdf, pw, o.nUp, out(f.nameWithoutExtension + "_" + o.nUp + "up", "pdf"), rtl, p)
            }
        } finally { if (temp) pdf.delete() }
    }

    private fun firstPagesText(pdf: File, pw: String?, n: Int): String = PdfOps.loadRaw(pdf, pw).use { d ->
        com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = 1; endPage = minOf(n, d.numberOfPages) }.getText(d).take(4000)
    }

    /** PowerPoint → ODP picture slides, streamed slide by slide and letterboxed per page shape (audit C39 / B26). */
    private suspend fun pptxToOdp(f: File, o: Cv6Opts, p: (Int, Int) -> Unit): File {
        val (pdf, temp) = asPdf(f)
        try { return Export7Run.odp(ctx, pdf, null, emptyList(), maxOf(o.dpi, 150), out(f.nameWithoutExtension, "odp"), p) }
        finally { if (temp) pdf.delete() }
    }

    private suspend fun toCsv(f: File, o: Cv6Opts): Cv6Result = withContext(Dispatchers.IO) {
        val base = f.nameWithoutExtension
        var cut: List<String> = emptyList()
        val sheets: List<Pair<String, String>> = if (FileKind6.of(f) == Kind6.XLSX) {
            val w = XlsxIO5.load(f)
            w.book.sheets.map { s -> s.name to CsvIO.toText(w, s, ',') }
        } else ToPdf6.readSheetsInfo(f).also { cut = it.cut }.list.map { (n, rows) -> n to Csv6.write(ToPdf6.trimSheet(rows)) }
        fun save(name: String, text: String) = out(name, "csv").also { it.outputStream().use { s -> s.write(Csv6.BOM); s.write(text.toByteArray(Charsets.UTF_8)) } }
        val notes = ArrayList<String>()
        cut.forEach { notes += ctx.getString(R.string.cvf_info_sheet_cut, it) }
        val outs = if (!o.csvAllSheets || sheets.size <= 1) {
            // only the first sheet: say that there are more (convert audit C41)
            if (sheets.size > 1) notes += ctx.getString(R.string.cvf_info_first_sheet, sheets.size)
            listOf(save(base, sheets.firstOrNull()?.second.orEmpty()))
        } else {
            val dir = engine.workDir(base + "_csv")
            val files = sheets.map { (n, t) -> ImageIo6.unique(dir, base + "_" + ImageNaming.sanitize(n, 40).ifEmpty { "sheet" }, "csv").also { it.outputStream().use { s -> s.write(Csv6.BOM); s.write(t.toByteArray(Charsets.UTF_8)) } } }
            listOf(engine.zip(files, out(base + "_csv", "zip")))
        }
        Cv6Result(outs, notes.joinToString("\n").ifBlank { null })
    }

    // ---- OCR of pictures ----

    private suspend fun ocrImages(conv: Conv, inputs: List<File>, o: Cv6Opts, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        val engineOcr = OcrEngine(ctx.applicationContext)
        try {
            if (conv.id == "img_ocr_pdf") {
                val pdfs = ArrayList<Pair<File, File>>()   // (input, its searchable temp PDF)
                val r = engine.batch(inputs, { d, t -> progress(Stage.OCR, d, t) }) { k, f ->
                    // every picture (TIFF: every page) as a PDF first, then OCR — TIFF and extensionless pictures work (audit C8)
                    val pic = engine.track(File(tempDir, "cv6i_${k}_${System.nanoTime()}.pdf"))
                    ImageIo6.imagesToPdf(listOf(f), pic, PageSetup(PageSize6.IMAGE))
                    val t = engine.track(File(tempDir, "cv6o_${k}_${System.nanoTime()}.pdf"))
                    val made = OcrRunner.ocrFile(ctx, engineOcr, pic, null, OcrPlan(langs = o.ocrLangs), t) ?: pic
                    pdfs += f to made
                    Cv6Result(emptyList())
                }
                if (pdfs.isEmpty()) return Cv6Result(emptyList(), null, r.failures)
                val outs = if (o.merge && pdfs.size > 1) {
                    val target = out(inputs.first().nameWithoutExtension + "_ocr", "pdf")
                    withContext(Dispatchers.IO) { ToPdf.merge(pdfs.map { it.second }, target, tempDir) }
                    listOf(target)
                } else pdfs.map { (src, t) -> out(src.nameWithoutExtension + "_ocr", "pdf").also { d -> withContext(Dispatchers.IO) { if (!t.renameTo(d)) t.copyTo(d, overwrite = true) } } }
                return Cv6Result(outs, null, r.failures)
            }
            var langs = o.ocrLangs
            val texts = ArrayList<Pair<File, String>>()
            val r = engine.batch(inputs, { d, t -> progress(Stage.OCR, d, t) }) { _, f ->
                val parts = ArrayList<String>()
                ImageIo6.forEachPage(f, 3000) { _, b ->
                    // the picture clean-up runs off the main thread (convert audit C14)
                    val clean = withContext(Dispatchers.Default) { OcrRunner.preClean(b) }
                    try {
                        val l = langs ?: OcrRunner.detectLanguages(engineOcr, clean).also { langs = it }
                        engineOcr.ensureLanguages(l)
                        if (!engineOcr.init(l)) throw UserMessage(ctx.getString(R.string.cv6_err_ocr))
                        parts += engineOcr.recognize(clean, wantWords = false).text
                    } finally { clean.recycle() }
                    true
                }
                texts += f to parts.joinToString("\n\u000C\n")
                Cv6Result(emptyList())
            }
            if (texts.isEmpty()) return Cv6Result(emptyList(), null, r.failures)
            val groups: List<Pair<String, String>> = if (o.merge && texts.size > 1) listOf(texts.first().first.nameWithoutExtension + "_ocr" to texts.joinToString("\n\u000C\n") { it.second })
                else texts.map { (f, t) -> f.nameWithoutExtension + "_ocr" to t }
            val outs = withContext(Dispatchers.IO) {
                groups.map { (base, text) ->
                    if (conv.id == "img_txt") out(base, "txt").also { it.writeText(text) }
                    else out(base, "docx").also { t -> t.outputStream().buffered().use { FlowDocx.write(FlowDoc.fromText(text, base), it) } }
                }
            }
            return Cv6Result(outs, if (texts.all { it.second.isBlank() }) ctx.getString(R.string.cv6_info_no_text) else null, r.failures)
        } finally { engineOcr.close() }
    }
}
