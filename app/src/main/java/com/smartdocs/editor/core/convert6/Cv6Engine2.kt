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

/** Markdown file, or (with pictures) a ZIP holding the .md and an images/ folder. */
internal fun writeMarkdown(doc: FlowDoc, base: String, out: (String, String) -> File): File {
    val imgs = ArrayList<Pair<String, ByteArray>>()
    val md = FlowMarkdown.write(doc) { i, img -> val n = "images/img$i." + (Images6.sniffExt(img.bytes) ?: img.ext); imgs += n to img.bytes; n }
    if (imgs.isEmpty()) return out(base, "md").also { it.writeText(md) }
    val zip = out(base + "_md", "zip")
    ZipOutputStream(zip.outputStream().buffered()).use { z ->
        z.putNextEntry(ZipEntry("$base.md")); z.write(md.toByteArray(Charsets.UTF_8)); z.closeEntry()
        imgs.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }
    return zip
}

/** Hub conversions that do not start from a PDF: to PDF, images, Office ↔ Office / text, books, OCR of pictures. */
internal class Cv6Engine2(private val ctx: Context, private val docs: DocumentRepository, private val out: (String, String) -> File) {

    private val tempDir get() = docs.tempDir

    private fun zip(files: List<File>, zip: File): File {
        ZipOutputStream(zip.outputStream().buffered()).use { z -> files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() } }
        return zip
    }

    /** One output per input, with per-file progress. */
    private suspend fun each(inputs: List<File>, p: (Int, Int) -> Unit, block: suspend (File) -> List<File>): Cv6Result {
        val outs = ArrayList<File>()
        inputs.forEachIndexed { k, f -> coroutineContext.ensureActive(); p(k, inputs.size); outs += block(f) }
        p(inputs.size, inputs.size)
        return Cv6Result(outs)
    }

    private suspend fun write(base: String, ext: String, w: (java.io.OutputStream) -> Unit): File = withContext(Dispatchers.IO) {
        out(base, ext).also { f -> f.outputStream().buffered().use(w) }
    }

    suspend fun run(conv: Conv, inputs: List<File>, o: Cv6Opts, p: (Int, Int) -> Unit, progress: (Stage, Int, Int) -> Unit): Cv6Result = when (conv.id) {
        "word_pdf", "excel_pdf", "ppt_pdf", "images_pdf", "tiff_pdf", "text_pdf", "md_pdf", "html_pdf", "epub_pdf", "eml_pdf", "any_pdf" -> toPdf(conv, inputs, o, p)
        "img_convert" -> each(inputs, p) { f -> ImageIo6.convertImage(f, docs.docsDir, o.imgFormat, o.quality) }
        "heic_jpg" -> each(inputs, p) { f -> ImageIo6.convertImage(f, docs.docsDir, ImgFmt.JPG, o.quality) }
        "img_resize" -> each(inputs, p) { f -> ImageIo6.convertImage(f, docs.docsDir, o.imgFormat, o.quality, if (o.maxSide > 0) 100 else o.resizePercent, o.maxSide, "_small") }
        "img_txt", "img_docx", "img_ocr_pdf" -> ocrImages(conv, inputs, o, progress)
        "docx_images", "pptx_images" -> each(inputs, p) { f -> pageImages(f, o) }
        "pptx_handouts" -> each(inputs, p) { f -> listOf(handouts(f, o)) }
        "pptx_odp" -> each(inputs, p) { f -> listOf(pptxToOdp(f, o)) }
        "xlsx_csv" -> each(inputs, p) { f -> toCsv(f, o) }
        "csv_xlsx" -> each(inputs, p) { f ->
            val w = withContext(Dispatchers.IO) { CsvIO.load(f) }
            val rtl = Text6.rtlDominant(ToPdf.readText(f).take(4000))
            val x = CsvIO.toXlsx(w, rtl)
            listOf(withContext(Dispatchers.IO) { out(f.nameWithoutExtension, "xlsx").also { XlsxIO5.save(x, it) } })
        }
        "xlsx_ods" -> each(inputs, p) { f ->
            val sheets = withContext(Dispatchers.IO) { ToPdf6.readSheets(f) }
            listOf(write(f.nameWithoutExtension, "ods") { OdfWriter.ods(sheets, it, Text6.rtlDominant(sheets.firstOrNull()?.second?.take(50)?.flatten()?.joinToString(" ").orEmpty())) })
        }
        "epub_txt" -> each(inputs, p) { f -> listOf(withContext(Dispatchers.IO) { out(f.nameWithoutExtension, "txt").also { it.writeText(HtmlFlow.plain(EpubReader.read(f).html)) } }) }
        else -> each(inputs, p) { f -> listOf(flowConvert(conv.id, f)) }
    }

    // ---- to PDF ----

    private suspend fun toPdf(conv: Conv, inputs: List<File>, o: Cv6Opts, p: (Int, Int) -> Unit): Cv6Result {
        suspend fun one(f: File, target: File): File =
            if (conv.id == "md_pdf") ToPdf6.flowToPdf(ctx, withContext(Dispatchers.IO) { MdFlow.parse(ToPdf.readText(f), f.nameWithoutExtension) }, target)
            else ToPdf6.convert(ctx, f, target, o.setup, o.quality)
        if (inputs.size > 1 && o.merge) {
            val target = out(inputs.first().nameWithoutExtension + "_" + ctx.getString(R.string.cv6_combined_suffix), "pdf")
            if (conv.id == "images_pdf" || conv.id == "tiff_pdf") return Cv6Result(listOf(ImageIo6.imagesToPdf(inputs, target, o.setup, o.quality, p)))
            if (conv.id == "md_pdf") {
                val temps = inputs.mapIndexed { i, f -> p(i, inputs.size); File(tempDir, "cv6md_${i}_${System.nanoTime()}.pdf").also { one(f, it) } }
                try { withContext(Dispatchers.IO) { PdfOps.merge(temps, target) } } finally { temps.forEach { it.delete() } }
                return Cv6Result(listOf(target))
            }
            return Cv6Result(listOf(ToPdf6.combine(ctx, inputs, target, tempDir, o.setup, o.quality, p)))
        }
        return each(inputs, p) { f -> listOf(one(f, out(f.nameWithoutExtension, "pdf"))) }
    }

    // ---- Office / text / books ----

    private suspend fun flowConvert(id: String, f: File): File = withContext(Dispatchers.IO) {
        val base = f.nameWithoutExtension
        val kind = FileKind6.of(f)
        val doc: FlowDoc = when (id) {
            "txt_docx" -> FlowDoc.fromText(ToPdf.readText(f), base)
            "md_docx", "md_html", "md_epub" -> if (kind == Kind6.MARKDOWN || f.extension.equals("md", true)) MdFlow.parse(ToPdf.readText(f), base) else FlowDoc.fromText(ToPdf.readText(f), base)
            "html_docx" -> HtmlFlow.parse(ToPdf.readText(f), base)
            else -> ToPdf6.readWordLike(f, kind)
        }
        when (id) {
            "docx_txt" -> out(base, "txt").also { it.writeText(FlowText.write(doc)) }
            "docx_html" -> out(base, "html").also { it.writeText(if (kind == Kind6.DOCX) OfficeHtml.docx(OoxmlReader.readDocx(f), base) else FlowHtml.write(doc)) }
            "md_html" -> out(base, "html").also { it.writeText(FlowHtml.write(doc)) }
            "docx_md" -> writeMarkdown(doc, base, out)
            "docx_odt" -> out(base, "odt").also { t -> t.outputStream().buffered().use { OdfWriter.odt(doc, it) } }
            "docx_epub", "md_epub" -> out(base, "epub").also { t -> t.outputStream().buffered().use { EpubWriter.write(doc, it) } }
            else -> out(base, "docx").also { t -> t.outputStream().buffered().use { FlowDocx.write(doc, it) } }   // odt_docx, txt_docx, md_docx, html_docx, epub_docx
        }
    }

    private suspend fun asPdf(f: File): Pair<File, Boolean> {
        if (FileKind6.of(f) == Kind6.PDF) return f to false
        val t = File(tempDir, "cv6p_${System.nanoTime()}.pdf")
        ToPdf6.convert(ctx, f, t)
        return t to true
    }

    private suspend fun pageImages(f: File, o: Cv6Opts): List<File> {
        val (pdf, temp) = asPdf(f)
        try {
            val fmt = when (o.imgFormat) { ImgFmt.PNG -> ImageFormat.PNG; ImgFmt.WEBP -> ImageFormat.WEBP; else -> ImageFormat.JPG }
            val dir = File(docs.docsDir, f.nameWithoutExtension + "_images").also { it.mkdirs() }
            val files = ExportPlus.images(ctx, pdf, null, ImageExportOptions(fmt, o.dpi, false, o.quality), dir)
            // ExportPlus names pages after the PDF: rename to the source document
            val named = files.map { x -> val n = File(dir, x.name.replace(pdf.nameWithoutExtension, f.nameWithoutExtension)); if (n != x && x.renameTo(n)) n else x }
            return if (o.zip && named.size > 1) listOf(zip(named, out(f.nameWithoutExtension + "_images", "zip")).also { if (dir.listFiles().isNullOrEmpty()) dir.delete() }) else named
        } finally { if (temp) pdf.delete() }
    }

    private suspend fun handouts(f: File, o: Cv6Opts): File {
        val (pdf, temp) = asPdf(f)
        try {
            val rtl = runCatching { Text6.rtlDominant(PdfOps.extractText(pdf).take(4000)) }.getOrDefault(false)
            return PdfTransforms6.nUp(pdf, null, o.nUp, out(f.nameWithoutExtension + "_" + o.nUp + "up", "pdf"), rtl) { _, _ -> }
        } finally { if (temp) pdf.delete() }
    }

    private suspend fun pptxToOdp(f: File, o: Cv6Opts): File {
        val (pdf, temp) = asPdf(f)
        try {
            val slides = ArrayList<OdfWriter.Slide>()
            var size: Pair<Float, Float>? = null
            ImageIo6.pagePictures(ctx, pdf, null, emptyList(), maxOf(o.dpi, 150)) { _, w, h, jpeg -> if (size == null) size = w to h; slides += OdfWriter.Slide("", emptyList(), jpeg) }
            val (w, h) = size ?: (960f to 540f)
            return write(f.nameWithoutExtension, "odp") { OdfWriter.odp(slides, it, false, 28f, 28f * h / w) }
        } finally { if (temp) pdf.delete() }
    }

    private suspend fun toCsv(f: File, o: Cv6Opts): List<File> = withContext(Dispatchers.IO) {
        val base = f.nameWithoutExtension
        val sheets: List<Pair<String, String>> = if (FileKind6.of(f) == Kind6.XLSX) {
            val w = XlsxIO5.load(f)
            w.book.sheets.map { s -> s.name to CsvIO.toText(w, s, ',') }
        } else ToPdf6.readSheets(f).map { (n, rows) -> n to Csv6.write(rows) }
        fun save(name: String, text: String) = out(name, "csv").also { it.outputStream().use { s -> s.write(Csv6.BOM); s.write(text.toByteArray(Charsets.UTF_8)) } }
        if (!o.csvAllSheets || sheets.size <= 1) listOf(save(base, sheets.firstOrNull()?.second.orEmpty()))
        else {
            val files = sheets.map { (n, t) -> save(base + "_" + ImageNaming.sanitize(n, 40).ifEmpty { "sheet" }, t) }
            listOf(zip(files, out(base + "_csv", "zip")))
        }
    }

    // ---- OCR of pictures ----

    private suspend fun ocrImages(conv: Conv, inputs: List<File>, o: Cv6Opts, progress: (Stage, Int, Int) -> Unit): Cv6Result {
        val engine = OcrEngine(ctx.applicationContext)
        try {
            if (conv.id == "img_ocr_pdf") {
                val pdfs = ArrayList<File>()
                inputs.forEachIndexed { k, f ->
                    progress(Stage.OCR, k, inputs.size)
                    val t = File(tempDir, "cv6o_${k}_${System.nanoTime()}.pdf")
                    val made = OcrRunner.ocrFile(ctx, engine, f, null, OcrPlan(langs = o.ocrLangs), t)
                        ?: ImageIo6.imagesToPdf(listOf(f), t, PageSetup(PageSize6.IMAGE))
                    pdfs += made
                }
                progress(Stage.OCR, inputs.size, inputs.size)
                return if (o.merge && pdfs.size > 1) {
                    val target = out(inputs.first().nameWithoutExtension + "_ocr", "pdf")
                    try { withContext(Dispatchers.IO) { PdfOps.merge(pdfs, target) } } finally { pdfs.forEach { it.delete() } }
                    Cv6Result(listOf(target))
                } else Cv6Result(pdfs.mapIndexed { i, t -> out(inputs[i].nameWithoutExtension + "_ocr", "pdf").also { d -> t.copyTo(d, overwrite = true); t.delete() } })
            }
            var langs = o.ocrLangs
            val texts = ArrayList<String>()
            for ((k, f) in inputs.withIndex()) {
                coroutineContext.ensureActive()
                progress(Stage.OCR, k, inputs.size)
                val parts = ArrayList<String>()
                for (b in withContext(Dispatchers.IO) { ImageIo6.decodeAll(f, 3000) }) {
                    val clean = OcrRunner.preClean(b); b.recycle()
                    val l = langs ?: OcrRunner.detectLanguages(engine, clean).also { langs = it }
                    engine.ensureLanguages(l)
                    if (!engine.init(l)) throw IllegalStateException(ctx.getString(R.string.cv6_err_ocr))
                    parts += engine.recognize(clean, wantWords = false).text
                    clean.recycle()
                }
                texts += parts.joinToString("\n\u000C\n")
            }
            progress(Stage.OCR, inputs.size, inputs.size)
            val groups: List<Pair<String, String>> = if (o.merge && inputs.size > 1) listOf(inputs.first().nameWithoutExtension + "_ocr" to texts.joinToString("\n\u000C\n"))
                else inputs.mapIndexed { i, f -> f.nameWithoutExtension + "_ocr" to texts[i] }
            val outs = withContext(Dispatchers.IO) {
                groups.map { (base, text) ->
                    if (conv.id == "img_txt") out(base, "txt").also { it.writeText(text) }
                    else out(base, "docx").also { t -> t.outputStream().buffered().use { FlowDocx.write(FlowDoc.fromText(text, base), it) } }
                }
            }
            return Cv6Result(outs, if (texts.all { it.isBlank() }) ctx.getString(R.string.cv6_info_no_text) else null)
        } finally { engine.close() }
    }
}
