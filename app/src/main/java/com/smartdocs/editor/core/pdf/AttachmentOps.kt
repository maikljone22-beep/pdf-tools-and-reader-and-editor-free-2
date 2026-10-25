package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** An embedded file: document-level (name tree) or page-level (file attachment annotation). */
data class Attachment(val name: String, val size: Long, val page: Int, val description: String, val mime: String)

/** Embedded-file attachments: list, extract, add (document-level or pinned to a page). */
object AttachmentOps {

    private fun load(src: File, password: String?): PDDocument = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)

    suspend fun list(src: File, password: String? = null): List<Attachment> = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val out = ArrayList<Attachment>()
            doc.documentCatalog.names?.embeddedFiles?.let { tree ->
                collect(tree).forEach { (name, spec) ->
                    val ef = spec.embeddedFile
                    out += Attachment(spec.filename ?: name, ef?.size?.toLong() ?: 0L, -1, spec.fileDescription ?: "", ef?.subtype ?: "")
                }
            }
            doc.pages.forEachIndexed { pi, page ->
                page.annotations.filterIsInstance<PDAnnotationFileAttachment>().forEach { an ->
                    val spec = an.file as? PDComplexFileSpecification ?: return@forEach
                    val ef = spec.embeddedFile
                    out += Attachment(spec.filename ?: "attachment", ef?.size?.toLong() ?: 0L, pi, an.contents ?: "", ef?.subtype ?: "")
                }
            }
            out
        }
    }

    private fun collect(node: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification>): Map<String, PDComplexFileSpecification> {
        val m = LinkedHashMap<String, PDComplexFileSpecification>()
        node.names?.let { m.putAll(it) }
        node.kids?.forEach { m.putAll(collect(it)) }
        return m
    }

    /** Extract an attachment by name (and page, -1 for document-level) into [dir]. */
    suspend fun extract(src: File, att: Attachment, dir: File, password: String? = null): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        load(src, password).use { doc ->
            val spec: PDComplexFileSpecification? = if (att.page < 0) {
                doc.documentCatalog.names?.embeddedFiles?.let { collect(it) }?.values?.firstOrNull { (it.filename ?: "") == att.name }
            } else {
                doc.getPage(att.page).annotations.filterIsInstance<PDAnnotationFileAttachment>().map { it.file as? PDComplexFileSpecification }
                    .firstOrNull { it?.filename == att.name }
            }
            val ef = spec?.embeddedFile ?: return@use null
            val target = File(dir, att.name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
            target.outputStream().use { o -> o.write(ef.toByteArray()) }
            target
        }
    }

    /** Add a file as a document-level attachment, or pin it to [page] at [x],[y] (points) when page >= 0. */
    suspend fun add(src: File, attachment: File, out: File, page: Int = -1, x: Float = 40f, y: Float = 40f, description: String = "", mime: String = "application/octet-stream", password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val spec = PDComplexFileSpecification()
            spec.file = attachment.name
            spec.fileUnicode = attachment.name
            spec.fileDescription = description
            val ef = PDEmbeddedFile(doc, attachment.inputStream())
            ef.subtype = mime
            ef.size = attachment.length().toInt()
            ef.creationDate = Calendar.getInstance()
            ef.modDate = Calendar.getInstance()
            spec.embeddedFile = ef
            spec.embeddedFileUnicode = ef
            if (page < 0) {
                val names = doc.documentCatalog.names ?: PDDocumentNameDictionary(doc.documentCatalog).also { doc.documentCatalog.names = it }
                val tree = names.embeddedFiles ?: PDEmbeddedFilesNameTreeNode()
                val map = LinkedHashMap<String, PDComplexFileSpecification>(tree.names ?: emptyMap())
                var key = attachment.name; var n = 2
                while (map.containsKey(key)) { key = "${attachment.nameWithoutExtension}_$n.${attachment.extension}"; n++ }
                map[key] = spec
                tree.names = map
                names.embeddedFiles = tree
            } else {
                val an = PDAnnotationFileAttachment()
                an.file = spec
                an.attachmentName = PDAnnotationFileAttachment.ATTACHMENT_NAME_PAPERCLIP
                an.rectangle = PDRectangle(x, y, 18f, 24f)
                an.contents = description.ifBlank { attachment.name }
                an.cosObject.setItem(COSName.getPDFName("T"), com.tom_roush.pdfbox.cos.COSString("SmartDocs"))
                doc.getPage(page).annotations.add(an)
            }
            doc.save(out)
        }
    }
}
