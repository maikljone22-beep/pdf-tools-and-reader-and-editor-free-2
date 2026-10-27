package com.smartdocs.editor.core.tools5

import android.content.Context
import android.print.WebPdfWriter
import com.smartdocs.editor.core.office.OfficeHtml
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class UnsupportedSource(val ext: String) : Exception("unsupported: $ext")

/** Input kinds of the any-format → PDF converter. */
enum class SourceKind { PDF, IMAGE, TEXT, MARKDOWN, HTML, DOCX, XLSX, PPTX, EPUB, EML, UNKNOWN }

object ToPdf {

    fun kindOf(name: String): SourceKind = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> SourceKind.PDF
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif" -> SourceKind.IMAGE
        "txt", "csv", "tsv", "log", "json", "xml", "kt", "java", "py", "js", "c", "cpp", "h", "ini", "yaml", "yml" -> SourceKind.TEXT
        "md", "markdown" -> SourceKind.MARKDOWN
        "html", "htm", "xhtml" -> SourceKind.HTML
        "docx" -> SourceKind.DOCX
        "xlsx" -> SourceKind.XLSX
        "pptx" -> SourceKind.PPTX
        "epub" -> SourceKind.EPUB
        "eml" -> SourceKind.EML
        else -> SourceKind.UNKNOWN
    }

    /** Mime types accepted by the picker / share filter. */
    val pickerMimes = arrayOf(
        "application/pdf", "image/*", "text/*", "application/epub+zip", "message/rfc822",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/octet-stream",
    )

    /** Print an HTML string to [out] through the WebView (main thread). */
    suspend fun html(ctx: Context, html: String, out: File, setup: WebPdfWriter.Setup = WebPdfWriter.Setup()): File {
        withContext(Dispatchers.Main) { WebPdfWriter.convertHtml(ctx, html, out, setup) }
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty PDF")
        return out
    }

    suspend fun url(ctx: Context, url: String, out: File, setup: WebPdfWriter.Setup = WebPdfWriter.Setup(), disableLinks: Boolean = false): File {
        com.smartdocs.editor.core.data.NpfNet.check()   // nav-prefs: offline mode
        var u = url.trim(); if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        withContext(Dispatchers.Main) { WebPdfWriter.convertUrl(ctx, u, out, setup) }
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty PDF")
        if (disableLinks) withContext(Dispatchers.IO) { WebCss.stripLinks(out) }
        return out
    }

    /** Header labels for e-mails ("from" → "من" …), set by the UI from string resources. */
    @Volatile var emlLabels: Map<String, String> = emptyMap()

    /** Convert one file of any supported kind to a PDF at [out]. */
    suspend fun convert(ctx: Context, src: File, out: File): File {
        val kind = kindOf(src.name)
        when (kind) {
            SourceKind.PDF -> withContext(Dispatchers.IO) { src.copyTo(out, overwrite = true) }
            SourceKind.IMAGE -> PdfOps.imagesToPdf(listOf(src), out, fitA4 = true)
            SourceKind.TEXT -> html(ctx, withContext(Dispatchers.IO) { TextHtml.fromText(readText(src), src.nameWithoutExtension).html }, out)
            SourceKind.MARKDOWN -> html(ctx, withContext(Dispatchers.IO) { MarkdownHtml.read(src).html }, out)
            SourceKind.HTML -> html(ctx, withContext(Dispatchers.IO) { readText(src) }, out)
            SourceKind.DOCX -> html(ctx, withContext(Dispatchers.IO) { OfficeHtml.docx(OoxmlReader.readDocx(src), src.nameWithoutExtension) }, out)
            SourceKind.XLSX -> html(ctx, withContext(Dispatchers.IO) { OfficeHtml.xlsx(OoxmlReader.readXlsx(src)) }, out)
            SourceKind.PPTX -> html(ctx, withContext(Dispatchers.IO) { OfficeHtml.pptx(OoxmlReader.readPptx(src), forPrint = true) }, out)
            SourceKind.EPUB -> html(ctx, withContext(Dispatchers.IO) { EpubReader.read(src).html }, out)
            SourceKind.EML -> {
                val doc = withContext(Dispatchers.IO) { EmlReader.read(src.readBytes(), src.nameWithoutExtension, emlLabels) }
                html(ctx, doc.html, out)
                if (doc.attachments.isNotEmpty()) withContext(Dispatchers.IO) {
                    val tmp = File(out.parentFile, ".${out.name}.att")
                    PDDocument.load(out).use { pd -> Embed.addFiles(pd, doc.attachments); pd.save(tmp) }
                    if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                }
            }
            SourceKind.UNKNOWN -> throw UnsupportedSource(src.extension)
        }
        // Foxit add-in setting "preserve document properties": title / author / subject / keywords of the Office file
        if (kind == SourceKind.DOCX || kind == SourceKind.XLSX || kind == SourceKind.PPTX)
            withContext(Dispatchers.IO) { runCatching { OfficeProps.applyTo(out, OfficeProps.read(src)) } }
        return out
    }

    /** Text with a charset guess: UTF-8 (with/without BOM), UTF-16 BOM, else windows-1256 (Arabic legacy). */
    fun readText(f: File): String {
        val b = f.readBytes()
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE).drop(1)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE).drop(1)
        val dec = Charsets.UTF_8.newDecoder()
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString().removePrefix("\uFEFF") }
            .getOrElse { String(b, runCatching { charset("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)) }
    }

    /** One entry of a combine job: the file plus its bookmark title. */
    class Item(val file: File, val title: String)

    /**
     * Combine mixed files into one PDF in the given order, one bookmark per file (pointing at its first page).
     * [onProgress] gets (done, total). Temporary PDFs are deleted afterwards.
     */
    suspend fun combine(ctx: Context, items: List<Item>, out: File, tempDir: File, bookmarks: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): File {
        val parts = ArrayList<Pair<File, String>>()
        val temps = ArrayList<File>()
        try {
            items.forEachIndexed { i, it ->
                coroutineContext.ensureActive()
                onProgress(i, items.size)
                val pdf = if (kindOf(it.file.name) == SourceKind.PDF) it.file else File(tempDir, "t5c_${i}_${System.nanoTime()}.pdf").also { t -> temps += t; convert(ctx, it.file, t) }
                parts += pdf to it.title
            }
            onProgress(items.size, items.size)
            withContext(Dispatchers.IO) {
                val counts = parts.map { (f, _) -> PDDocument.load(f).use { it.numberOfPages } }
                val m = PDFMergerUtility()
                parts.forEach { (f, _) -> m.addSource(f) }
                val tmp = File(tempDir, "t5c_merged_${System.nanoTime()}.pdf"); temps += tmp
                m.destinationFileName = tmp.absolutePath
                m.mergeDocuments(null)
                PDDocument.load(tmp).use { doc ->
                    if (bookmarks) {
                        val outline = PDDocumentOutline()
                        var start = 0
                        parts.forEachIndexed { i, (_, title) ->
                            if (counts[i] > 0 && start < doc.numberOfPages) {
                                val item = PDOutlineItem()
                                item.title = title
                                item.setDestination(doc.getPage(start))
                                outline.addLast(item)
                            }
                            start += counts[i]
                        }
                        doc.documentCatalog.documentOutline = outline
                    }
                    doc.documentInformation.creator = "SmartDocs"
                    doc.save(out)
                }
            }
            return out
        } finally { temps.forEach { runCatching { it.delete() } } }
    }
}
