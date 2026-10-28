package com.smartdocs.editor.core.tools5

import android.content.Context
import android.print.WebPdfWriter
import com.smartdocs.editor.core.convert6.Cv6NeedPassword
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.Kind6
import com.smartdocs.editor.core.convert6.RestrictedInput
import com.smartdocs.editor.core.convert6.ToPdf6
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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

/** Input kinds of the any-format → PDF converter. [OFFICE]: Word 97, ODT / ODS / ODP, RTF, XLS, CSV (converted by the hub engine). */
enum class SourceKind { PDF, IMAGE, TEXT, MARKDOWN, HTML, DOCX, XLSX, PPTX, EPUB, EML, OFFICE, UNKNOWN }

/**
 * Any-format → PDF entry point of Tools → To PDF, Share-to-PDF, Combine, the reader and the virtual printer. Since the
 * convert audit (A-M8) every caller goes through the hub's one engine ([ToPdf6.convert]: content detection by magic
 * bytes, the same readers and print settings); this object keeps the WebView printing, the web-page converter and the
 * combiner.
 */
object ToPdf {

    private fun kind6(k: Kind6): SourceKind = when {
        k == Kind6.PDF -> SourceKind.PDF
        k.isImage -> SourceKind.IMAGE
        k == Kind6.TEXT -> SourceKind.TEXT
        k == Kind6.MARKDOWN -> SourceKind.MARKDOWN
        k == Kind6.HTML -> SourceKind.HTML
        k == Kind6.DOCX -> SourceKind.DOCX
        k == Kind6.XLSX -> SourceKind.XLSX
        k == Kind6.PPTX -> SourceKind.PPTX
        k == Kind6.EPUB -> SourceKind.EPUB
        k == Kind6.EML -> SourceKind.EML
        k == Kind6.DOC || k == Kind6.ODT || k == Kind6.RTF || k == Kind6.XLS || k == Kind6.ODS || k == Kind6.CSV || k == Kind6.ODP -> SourceKind.OFFICE
        else -> SourceKind.UNKNOWN
    }

    /** Kind by the file name (pickers, folder walks); see [kindOf] with a file for the content-based decision. */
    fun kindOf(name: String): SourceKind = kind6(FileKind6.byExtension(name))

    /** Kind by content (magic bytes first): a PDF named "file.bin" is a PDF (convert audit A-L12). */
    fun kindOf(f: File): SourceKind = kind6(FileKind6.of(f)).let { if (it == SourceKind.UNKNOWN && FileKind6.isEncryptedOffice(f)) SourceKind.OFFICE else it }

    /** Mime types accepted by the picker / share filter. */
    val pickerMimes = arrayOf(
        "application/pdf", "image/*", "text/*", "application/epub+zip", "message/rfc822",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword", "application/vnd.ms-excel", "application/rtf",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation",
        "application/octet-stream",
    )

    /** Print an HTML string to [out] through the WebView (scripts off, no navigation). */
    suspend fun html(ctx: Context, html: String, out: File, setup: WebPdfWriter.Setup = WebPdfWriter.Setup(javaScript = false)): File {
        withContext(Dispatchers.Main) { WebPdfWriter.convertHtml(ctx, html, out, setup) }
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty PDF")
        return out
    }

    /**
     * Prints [parts] (each a complete HTML page) one after the other and joins them into [out] (convert audit A-C1 /
     * A-H2 / A-M1 / A-M7 / A-M10: a huge book, sheet or text file is printed in pieces the WebView can lay out instead
     * of one giant page). One part is printed straight into [out].
     */
    suspend fun htmlParts(ctx: Context, parts: Sequence<String>, out: File, tempDir: File, setup: WebPdfWriter.Setup = WebPdfWriter.Setup(javaScript = false), onPart: (Int) -> Unit = {}): File {
        val it = parts.iterator()
        if (!it.hasNext()) throw IllegalStateException("empty PDF")
        val first = it.next()
        if (!it.hasNext()) return html(ctx, first, out, setup)
        val temps = ArrayList<File>()
        try {
            var page: String? = first
            var n = 0
            while (page != null) {
                coroutineContext.ensureActive()
                onPart(n)
                val t = File(tempDir, "t5p_${n++}_${System.nanoTime()}.pdf").also { temps += it }
                html(ctx, page, t, setup)
                page = if (it.hasNext()) it.next() else null
            }
            withContext(Dispatchers.IO) { merge(temps, out, tempDir) }
            return out
        } finally { temps.forEach { runCatching { it.delete() } } }
    }

    /** PDFs joined into [out] with temp-file memory (convert audit A-H6: the merge ran in the heap). */
    fun merge(parts: List<File>, out: File, tempDir: File) {
        val m = PDFMergerUtility()
        parts.forEach { m.addSource(it) }
        val tmp = File(tempDir, "t5m_${System.nanoTime()}.pdf")
        try {
            m.destinationFileName = tmp.absolutePath
            m.mergeDocuments(MemoryUsageSetting.setupMixed(48L shl 20).setTempDir(tempDir))
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true) }
        } finally { tmp.delete() }
    }

    suspend fun url(ctx: Context, url: String, out: File, setup: WebPdfWriter.Setup = WebPdfWriter.Setup(), disableLinks: Boolean = false): File {
        com.smartdocs.editor.core.data.NpfNet.check()   // nav-prefs: offline mode
        var u = url.trim(); if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        try { withContext(Dispatchers.Main) { WebPdfWriter.convertUrl(ctx, u, out, setup) } }
        catch (e: WebPdfWriter.TimeoutException) { throw com.smartdocs.editor.core.convert6.UserMessage(ctx.getString(com.smartdocs.editor.R.string.cv6_err_web_timeout)) }
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty PDF")
        if (disableLinks) withContext(Dispatchers.IO) { WebCss.stripLinks(out) }
        return out
    }

    /** Header labels for e-mails ("from" → "من" …), set by the UI from string resources. */
    @Volatile var emlLabels: Map<String, String> = emptyMap()

    /** Convert one file of any supported kind to a PDF at [out] (the hub engine, detection by content). */
    suspend fun convert(ctx: Context, src: File, out: File): File = ToPdf6.convert(ctx, src, out)

    /** Text with a charset guess: UTF-8 (with/without BOM), UTF-16 BOM, else windows-1256 (Arabic legacy). */
    fun readText(f: File): String {
        val b = f.readBytes()
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE).drop(1)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE).drop(1)
        val dec = Charsets.UTF_8.newDecoder()
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString().removePrefix(0xFEFF.toChar().toString()) }
            .getOrElse { String(b, runCatching { charset("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)) }
    }

    /** Charset of a text file judged on its first megabyte (BOM, valid UTF-8, else windows-1256) and the BOM length. */
    fun textCharset(f: File): Pair<java.nio.charset.Charset, Int> {
        val head = f.inputStream().use { s -> ByteArray(1 shl 20).let { b -> var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r <= 0) break; n += r }; b.copyOf(n) } }
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        val bom = if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        // a multi-byte character may be cut at the end of the sample: ignore the last 4 bytes
        val sample = java.nio.ByteBuffer.wrap(head, bom, maxOf(0, head.size - bom - if (head.size == 1 shl 20) 4 else 0))
        val ok = runCatching { Charsets.UTF_8.newDecoder().decode(sample) }.isSuccess
        return (if (ok) Charsets.UTF_8 else runCatching { charset("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)) to bom
    }

    /**
     * Lines of a (possibly huge) text file in chunks of about [maxChars] characters, read as it goes (convert audit
     * A-M10: a 100 MB log was read into memory three times).
     */
    fun textChunks(f: File, maxChars: Int = 600_000): Sequence<String> = sequence {
        val (cs, skip) = textCharset(f)
        f.inputStream().use { ins ->
            var s = 0L; while (s < skip) { val k = ins.skip(skip - s); if (k <= 0) break; s += k }
            val r = ins.bufferedReader(cs)
            val sb = StringBuilder()
            while (true) {
                val line = r.readLine() ?: break
                sb.append(line).append('\n')
                if (sb.length >= maxChars) { yield(sb.toString()); sb.setLength(0) }
            }
            if (sb.isNotEmpty()) yield(sb.toString())
        }
    }

    /**
     * HTML of a file: an MHT / MHTML web archive (MIME) gives its HTML part with its pictures inlined (convert audit
     * A-L4 / C42: the MIME source was printed as text); other files are decoded like text.
     */
    fun readHtml(f: File): String {
        val head = f.inputStream().use { s -> ByteArray(2048).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
        val h = String(head, Charsets.ISO_8859_1).trimStart().lowercase()
        val mime = h.startsWith("mime-version:") || h.startsWith("from:") || h.startsWith("content-type:") || h.startsWith("subject:") || h.startsWith("date:") ||
            ((f.extension.equals("mht", true) || f.extension.equals("mhtml", true)) && h.contains("content-type:"))
        if (!mime) return readText(f)
        val a = com.smartdocs.editor.core.openall.MhtReader.read(f.readBytes())
        var html = a.html
        // pictures by Content-Location or cid → data URIs (printing has no network / resource server)
        html = Regex("""(?i)(src|href)\s*=\s*(["'])([^"']+)\2""").replace(html) { m ->
            val part = a.resource(m.groupValues[3])?.takeIf { it.type.startsWith("image/") && it.data.size <= (4 shl 20) } ?: return@replace m.value
            m.groupValues[1] + "=" + m.groupValues[2] + "data:" + part.type + ";base64," + java.util.Base64.getEncoder().encodeToString(part.data) + m.groupValues[2]
        }
        return html
    }

    /** One entry of a combine job: the file, its bookmark title and its password (a protected PDF). */
    class Item(val file: File, val title: String, val password: String? = null)

    /**
     * A PDF part ready to be merged: [f] itself, or an unlocked temp copy of a protected PDF opened with [pw] (the merge
     * cannot read encrypted parts). A part whose owner forbids assembling is refused (convert audit item 8).
     * [Cv6NeedPassword] when the password is missing or wrong (convert audit A-H4: the password was never used).
     */
    fun mergeablePdf(f: File, pw: String?, tempDir: File, temps: MutableList<File>, signed: (File) -> Unit = {}): File {
        val doc = try { PdfOps.loadRaw(f, pw) } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw Cv6NeedPassword(f, pw != null, e) }
        doc.use { d ->
            if (runCatching { d.signatureDictionaries.isNotEmpty() }.getOrDefault(false)) signed(f)
            if (!d.isEncrypted) return f
            val perm = d.currentAccessPermission
            if (!perm.isOwnerPermission && !perm.canAssembleDocument()) throw RestrictedInput(f.name)
            val t = File(tempDir, "t5u_${System.nanoTime()}.pdf").also { temps += it }
            d.isAllSecurityToBeRemoved = true
            d.save(t)
            return t
        }
    }

    /**
     * Combine mixed files into one PDF in the given order, one bookmark per file (pointing at its first page). Kinds
     * come from the content (a PDF named oddly stays a PDF), protected PDFs use their [Item.password], the merge runs
     * with temp-file memory (convert audit A-H4 / A-H6 / A-L12). [signed] is told about signed inputs (their signatures
     * do not survive, audit A-L14). [onProgress] gets (done, total). Temporary PDFs are deleted afterwards.
     */
    suspend fun combine(ctx: Context, items: List<Item>, out: File, tempDir: File, bookmarks: Boolean = true, signed: (File) -> Unit = {},
                        onProgress: (Int, Int) -> Unit = { _, _ -> }): File {
        val parts = ArrayList<Pair<File, String>>()
        val temps = ArrayList<File>()
        try {
            items.forEachIndexed { i, it ->
                coroutineContext.ensureActive()
                onProgress(i, items.size)
                val pdf = if (FileKind6.of(it.file) == Kind6.PDF) withContext(Dispatchers.IO) { mergeablePdf(it.file, it.password, tempDir, temps, signed) }
                    else File(tempDir, "t5c_${i}_${System.nanoTime()}.pdf").also { t -> temps += t; convert(ctx, it.file, t) }
                parts += pdf to it.title
            }
            onProgress(items.size, items.size)
            withContext(Dispatchers.IO) {
                val counts = parts.map { (f, _) -> PdfOps.loadRaw(f, null).use { it.numberOfPages } }
                val tmp = File(tempDir, "t5c_merged_${System.nanoTime()}.pdf"); temps += tmp
                merge(parts.map { it.first }, tmp, tempDir)
                coroutineContext.ensureActive()
                PdfOps.loadRaw(tmp, null).use { doc ->
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

