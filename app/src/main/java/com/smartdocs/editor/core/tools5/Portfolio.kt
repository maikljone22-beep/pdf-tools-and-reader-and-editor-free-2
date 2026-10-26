package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Calendar

/** Document-level embedded files (name tree). */
object Embed {

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain"; "csv" -> "text/csv"
        "html", "htm" -> "text/html"; "xml" -> "application/xml"; "zip" -> "application/zip"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    private fun spec(doc: PDDocument, name: String, mime: String, bytes: ByteArray, description: String?): PDComplexFileSpecification {
        val ef = PDEmbeddedFile(doc, ByteArrayInputStream(bytes))
        ef.subtype = mime
        ef.size = bytes.size
        ef.creationDate = Calendar.getInstance(); ef.modDate = Calendar.getInstance()
        return PDComplexFileSpecification().apply {
            file = name; fileUnicode = name
            if (!description.isNullOrBlank()) fileDescription = description
            embeddedFile = ef; embeddedFileUnicode = ef
        }
    }

    fun all(doc: PDDocument): Map<String, PDComplexFileSpecification> {
        val tree = doc.documentCatalog.names?.embeddedFiles ?: return emptyMap()
        fun collect(n: PDNameTreeNode<PDComplexFileSpecification>, m: MutableMap<String, PDComplexFileSpecification>) {
            n.names?.let { m.putAll(it) }
            n.kids?.forEach { collect(it, m) }
        }
        return LinkedHashMap<String, PDComplexFileSpecification>().also { collect(tree, it) }
    }

    /** Add files (name, mime, bytes) to the document name tree; names are made unique. */
    fun addFiles(doc: PDDocument, files: List<Attachment>, descriptions: Map<String, String> = emptyMap()) {
        if (files.isEmpty()) return
        val names = doc.documentCatalog.names ?: PDDocumentNameDictionary(doc.documentCatalog).also { doc.documentCatalog.names = it }
        val map = LinkedHashMap(all(doc))
        files.forEach { a ->
            var key = a.name.ifBlank { "file" }; var n = 2
            val stem = key.substringBeforeLast('.'); val ext = key.substringAfterLast('.', "")
            while (map.containsKey(key)) key = stem + "_" + (n++) + if (ext.isNotEmpty()) ".$ext" else ""
            map[key] = spec(doc, key, a.mime, a.bytes, descriptions[a.name])
        }
        val tree = PDEmbeddedFilesNameTreeNode()
        tree.names = map
        names.embeddedFiles = tree
    }
}

class PortfolioPart(val name: String, val size: Long, val mime: String, val description: String)

/** PDF portfolios (collections): detection, component list / extraction, creation with a cover page. */
object Portfolio {

    private val COLLECTION = COSName.getPDFName("Collection")

    suspend fun isPortfolio(src: File, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching { PdfOps.load(src, password).use { it.documentCatalog.cosObject.containsKey(COLLECTION) } }.getOrDefault(false)
    }

    suspend fun parts(src: File, password: String? = null): List<PortfolioPart> = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            Embed.all(doc).map { (k, s) ->
                val ef = s.embeddedFile
                PortfolioPart(s.fileUnicode ?: s.file ?: k, ef?.size?.toLong() ?: 0L, ef?.subtype ?: Embed.mimeOf(k), s.fileDescription.orEmpty())
            }
        }
    }

    suspend fun extract(src: File, name: String, dir: File, password: String? = null): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        PdfOps.load(src, password).use { doc ->
            val s = Embed.all(doc).entries.firstOrNull { (k, v) -> k == name || v.fileUnicode == name || v.file == name }?.value ?: return@use null
            val ef = s.embeddedFileUnicode ?: s.embeddedFile ?: return@use null
            val out = File(dir, TextNorm.safeFileName(name.substringBeforeLast('.'), 80).ifBlank { "part" } + "." + name.substringAfterLast('.', "bin"))
            out.outputStream().use { it.write(ef.toByteArray()) }
            out
        }
    }

    /**
     * New portfolio: a cover page listing the parts (title + file names) and every file embedded, with a
     * /Collection dictionary (details view) so desktop viewers open it as a portfolio. [initial] names the part shown first.
     */
    suspend fun create(ctx: Context, title: String, files: List<File>, out: File, initial: String? = null, coverHeader: String = "") = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val arabic: PDFont = runCatching { PdfOps.arabicFont(ctx, doc) }.getOrDefault(PDType1Font.HELVETICA)
            fun fontFor(s: String) = if (ArabicShaper.isArabic(s) && arabic !is PDType1Font) arabic else PDType1Font.HELVETICA
            fun safe(s: String, f: PDFont) = if (f is PDType1Font) s.filter { it.code in 32..126 } else s
            PDPageContentStream(doc, page).use { cs ->
                val w = page.mediaBox.width
                var y = page.mediaBox.height - 90f
                fun line(text: String, size: Float) {
                    val f = fontFor(text)
                    val shown = safe(if (ArabicShaper.isArabic(text)) ArabicShaper.shape(text) else text, f)
                    if (shown.isBlank()) { y -= size * 1.6f; return }
                    val tw = runCatching { f.getStringWidth(shown) / 1000f * size }.getOrDefault(0f)
                    cs.beginText(); cs.setFont(f, size)
                    cs.newLineAtOffset(if (ArabicShaper.isArabic(text)) w - 60f - tw else 60f, y)
                    cs.showArabic(text, shown); cs.endText()
                    y -= size * 1.6f
                }
                line(title, 22f)
                if (coverHeader.isNotBlank()) line(coverHeader, 12f)
                y -= 10f
                files.take(40).forEach { line("• " + it.name, 11f) }
            }
            Embed.addFiles(doc, files.map { Attachment(it.name, Embed.mimeOf(it.name), it.readBytes()) })
            val coll = COSDictionary()
            coll.setItem(COSName.TYPE, COLLECTION)
            coll.setItem(COSName.getPDFName("View"), COSName.getPDFName("D"))
            val first = initial ?: files.firstOrNull()?.name
            if (first != null) coll.setItem(COSName.getPDFName("D"), COSString(first))
            doc.documentCatalog.cosObject.setItem(COLLECTION, coll)
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("PageMode"), COSName.getPDFName("UseAttachments"))
            doc.documentInformation.title = title
            doc.documentInformation.creator = "SmartDocs"
            doc.save(out)
        }
        out
    }
}
