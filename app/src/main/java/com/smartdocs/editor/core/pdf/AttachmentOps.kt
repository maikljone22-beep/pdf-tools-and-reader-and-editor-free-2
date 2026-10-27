package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.cos.COSUpdateInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * An embedded file: document-level (name tree, [page] = -1, [key] = its name-tree key) or page-level (file attachment
 * annotation on zero-based [page], [annotIndex] = its index in the page's /Annots). [created] / [modified] are epoch
 * millis from the embedded file's /Params (0 = unknown).
 */
data class Attachment(
    val name: String, val size: Long, val page: Int, val description: String, val mime: String,
    val key: String = "", val annotIndex: Int = -1, val created: Long = 0L, val modified: Long = 0L,
) {
    val isDocumentLevel: Boolean get() = page < 0
}

/**
 * Embedded-file attachments: list, extract, add (document-level or pinned to a page), delete one, edit its
 * description. Changes to a signed file are appended as an incremental update ([SignedSave]); a certified file
 * that forbids the change throws [SignedDocumentException].
 */
object AttachmentOps {

    private val FILE_ATTACHMENT = COSName.getPDFName("FileAttachment")
    private val EMBEDDED_FILES = COSName.getPDFName("EmbeddedFiles")
    private val POPUP = COSName.getPDFName("Popup")

    private fun load(src: File, password: String?): PDDocument = PdfOps.loadRaw(src, password)

    private fun embeddedRoot(doc: PDDocument): COSDictionary? =
        (doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(EMBEDDED_FILES) as? COSDictionary

    private fun annotsOf(doc: PDDocument, page: Int): COSArray? = doc.getPage(page).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray

    private fun isFileAttachment(d: COSDictionary?) = d != null && d.getCOSName(COSName.SUBTYPE) == FILE_ATTACHMENT

    private fun millis(c: () -> Calendar?): Long = runCatching { c()?.timeInMillis }.getOrNull() ?: 0L

    private fun describe(spec: PDComplexFileSpecification?, fallbackName: String, page: Int, description: String, key: String, index: Int): Attachment {
        val ef = spec?.embeddedFileUnicode ?: spec?.embeddedFile
        return Attachment(
            name = spec?.fileUnicode ?: spec?.filename ?: fallbackName, size = ef?.size?.toLong()?.takeIf { it > 0 } ?: ef?.length?.toLong() ?: 0L,
            page = page, description = description, mime = ef?.subtype ?: "", key = key, annotIndex = index,
            created = millis { ef?.creationDate }, modified = millis { ef?.modDate },
        )
    }

    suspend fun list(src: File, password: String? = null): List<Attachment> = withContext(Dispatchers.IO) {
        load(src, password).use { listIn(it) }
    }

    /** Every attachment of an open document: document-level first, then per page. */
    fun listIn(doc: PDDocument): List<Attachment> {
        val out = ArrayList<Attachment>()
        embeddedRoot(doc)?.let { root ->
            NameTreeCos.entries(root).forEach { e ->
                val spec = (e.value as? COSDictionary)?.let { PDComplexFileSpecification(it) }
                out += describe(spec, e.key, -1, spec?.fileDescription ?: "", e.key, -1)
            }
        }
        for (pi in 0 until doc.numberOfPages) {
            val annots = annotsOf(doc, pi) ?: continue
            for (i in 0 until annots.size()) {
                val d = annots.getObject(i) as? COSDictionary
                if (!isFileAttachment(d)) continue
                val an = PDAnnotationFileAttachment(d)
                val spec = runCatching { an.file as? PDComplexFileSpecification }.getOrNull()
                out += describe(spec, "attachment", pi, an.contents ?: spec?.fileDescription ?: "", "", i)
            }
        }
        return out
    }

    /** The file specification of [att] in [doc] (by key / annotation index, then by name). */
    private fun specOf(doc: PDDocument, att: Attachment): PDComplexFileSpecification? {
        if (att.isDocumentLevel) {
            val root = embeddedRoot(doc) ?: return null
            val entries = NameTreeCos.entries(root)
            val e = entries.firstOrNull { att.key.isNotEmpty() && it.key == att.key }
                ?: entries.firstOrNull { (it.value as? COSDictionary)?.let { d -> PDComplexFileSpecification(d).let { s -> (s.fileUnicode ?: s.filename) == att.name } } == true }
            return (e?.value as? COSDictionary)?.let { PDComplexFileSpecification(it) }
        }
        return annotationOf(doc, att)?.let { runCatching { PDAnnotationFileAttachment(it).file as? PDComplexFileSpecification }.getOrNull() }
    }

    /** The annotation dictionary of a page-level [att] (its index first, then the first one with that file name). */
    private fun annotationOf(doc: PDDocument, att: Attachment): COSDictionary? {
        if (att.page !in 0 until doc.numberOfPages) return null
        val annots = annotsOf(doc, att.page) ?: return null
        (annots.getObject(att.annotIndex.coerceAtLeast(0)) as? COSDictionary)?.takeIf { att.annotIndex >= 0 && isFileAttachment(it) && nameOf(it) == att.name }?.let { return it }
        for (i in 0 until annots.size()) {
            val d = annots.getObject(i) as? COSDictionary
            if (isFileAttachment(d) && nameOf(d!!) == att.name) return d
        }
        return null
    }

    private fun nameOf(annot: COSDictionary): String {
        val spec = runCatching { PDAnnotationFileAttachment(annot).file as? PDComplexFileSpecification }.getOrNull()
        return spec?.fileUnicode ?: spec?.filename ?: "attachment"
    }

    /** Extract [att] into [dir]. Returns the written file, or null when it has no embedded data. */
    suspend fun extract(src: File, att: Attachment, dir: File, password: String? = null): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        load(src, password).use { doc ->
            val spec = specOf(doc, att)
            val ef = spec?.embeddedFileUnicode ?: spec?.embeddedFile ?: return@use null
            val target = File(dir, safeName(att.name))
            target.outputStream().use { o -> o.write(com.smartdocs.editor.core.redact.EmbeddedFileCrypt.bytes(doc, ef.cosObject)) }   // attachments-only encryption (search5b)
            target
        }
    }

    fun safeName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "attachment" }

    /** Add a file as a document-level attachment, or pin it to [page] at [x],[y] (points) when page >= 0. */
    suspend fun add(src: File, attachment: File, out: File, page: Int = -1, x: Float = 40f, y: Float = 40f, description: String = "", mime: String = "application/octet-stream", password: String? = null) =
        addAll(src, listOf(attachment to mime), out, page, x, y, description, password)

    /** Add several files (file, mime) in one save; see [add]. Returns the keys / names used. */
    suspend fun addAll(src: File, files: List<Pair<File, String>>, out: File, page: Int = -1, x: Float = 40f, y: Float = 40f, description: String = "", password: String? = null): List<String> = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val touched = ArrayList<COSBase>()
            val pages = ArrayList<Int>()
            val used = ArrayList<String>()
            files.forEachIndexed { n, (attachment, mime) ->
                val spec = newSpec(doc, attachment, mime, description)
                if (page < 0) {
                    val catalog = doc.documentCatalog.cosObject
                    val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: COSDictionary().also { catalog.setItem(COSName.NAMES, it) }
                    val root = names.getDictionaryObject(EMBEDDED_FILES) as? COSDictionary ?: COSDictionary().also { names.setItem(EMBEDDED_FILES, it) }
                    val key = NameTreeCos.uniqueKey(NameTreeCos.entries(root).map { it.key }.toSet(), attachment.name)
                    touched += names; touched += NameTreeCos.put(root, key, spec.cosObject)
                    used += key
                } else {
                    val an = PDAnnotationFileAttachment()
                    an.file = spec
                    an.attachmentName = PDAnnotationFileAttachment.ATTACHMENT_NAME_PAPERCLIP
                    an.rectangle = PDRectangle(x + n * 22f, y, 18f, 24f)
                    an.contents = description.ifBlank { attachment.name }
                    an.cosObject.setItem(COSName.T, COSString("SmartDocs"))
                    an.cosObject.setItem(COSName.P, doc.getPage(page).cosObject)
                    doc.getPage(page).annotations.add(an)
                    pages += page
                    used += attachment.name
                }
            }
            save(doc, out, password, pages, touched, documentLevel = page < 0)
            used
        }
    }

    /**
     * "Attach source file" of the Create-PDF flows: embeds [sources] (the originals) into the new [pdf], in place.
     * [description] is stored with each file. Never used on signed files (the PDF was just created).
     */
    suspend fun attachSources(pdf: File, sources: List<File>, description: String) = withContext(Dispatchers.IO) {
        val list = sources.filter { it.isFile && it.absolutePath != pdf.absolutePath }
        if (list.isEmpty()) return@withContext
        val tmp = File(pdf.parentFile, ".${pdf.nameWithoutExtension}.src.pdf")
        try {
            addAll(pdf, list.map { it to com.smartdocs.editor.core.tools5.Embed.mimeOf(it.name) }, tmp, description = description)
            if (!tmp.renameTo(pdf)) { tmp.copyTo(pdf, overwrite = true); tmp.delete() }
        } finally { tmp.delete() }
    }

    /**
     * Which sources go into which created PDF: one output gets every source, as many outputs as sources pairs them
     * in order, otherwise each output gets the sources whose name it starts with.
     */
    fun pairSources(outputs: List<File>, sources: List<File>): Map<File, List<File>> = when {
        outputs.isEmpty() || sources.isEmpty() -> emptyMap()
        outputs.size == 1 -> mapOf(outputs[0] to sources)
        outputs.size == sources.size -> outputs.indices.associate { outputs[it] to listOf(sources[it]) }
        else -> outputs.associateWith { o -> sources.filter { s -> o.nameWithoutExtension.startsWith(s.nameWithoutExtension) } }.filterValues { it.isNotEmpty() }
    }

    private fun newSpec(doc: PDDocument, attachment: File, mime: String, description: String): PDComplexFileSpecification {
        val spec = PDComplexFileSpecification()
        spec.file = attachment.name
        spec.fileUnicode = attachment.name
        if (description.isNotBlank()) spec.fileDescription = description
        val ef = attachment.inputStream().use { PDEmbeddedFile(doc, it) }
        ef.subtype = mime
        ef.size = attachment.length().toInt()
        ef.creationDate = Calendar.getInstance().apply { timeInMillis = attachment.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() }
        ef.modDate = Calendar.getInstance().apply { timeInMillis = attachment.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() }
        spec.embeddedFile = ef
        spec.embeddedFileUnicode = ef
        return spec
    }

    /** Remove one attachment: its name-tree entry, or its file attachment annotation (with its popup). */
    suspend fun delete(src: File, att: Attachment, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            if (att.isDocumentLevel) {
                val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: error("no attachments")
                val root = embeddedRoot(doc) ?: error("no attachments")
                val key = att.key.ifEmpty { NameTreeCos.entries(root).firstOrNull { e -> (e.value as? COSDictionary)?.let { PDComplexFileSpecification(it).let { s -> (s.fileUnicode ?: s.filename) == att.name } } == true }?.key }
                    ?: error("attachment not found")
                val touched = NameTreeCos.remove(root, key) ?: error("attachment not found")
                save(doc, out, password, emptyList(), touched + names, documentLevel = true)
            } else {
                val annot = annotationOf(doc, att) ?: error("attachment not found")
                val annots = annotsOf(doc, att.page)!!
                val popup = annot.getDictionaryObject(POPUP) as? COSDictionary
                for (i in annots.size() - 1 downTo 0) {
                    val d = annots.getObject(i)
                    if (d === annot || (popup != null && d === popup)) annots.remove(i)
                }
                save(doc, out, password, listOf(att.page), listOf(annots), documentLevel = false)
            }
        }
    }

    /** Replace the description: /Desc of a document-level file, /Contents (and /Desc) of a file attachment annotation. */
    suspend fun setDescription(src: File, att: Attachment, text: String, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            if (att.isDocumentLevel) {
                val root = embeddedRoot(doc) ?: error("no attachments")
                val e = NameTreeCos.entries(root).firstOrNull { it.key == att.key } ?: NameTreeCos.entries(root).firstOrNull { (it.value as? COSDictionary)?.let { d -> PDComplexFileSpecification(d).let { s -> (s.fileUnicode ?: s.filename) == att.name } } == true }
                val spec = e?.value as? COSDictionary ?: error("attachment not found")
                if (text.isBlank()) spec.removeItem(COSName.DESC) else spec.setString(COSName.DESC, text)
                save(doc, out, password, emptyList(), e.path + spec, documentLevel = true)
            } else {
                val annot = annotationOf(doc, att) ?: error("attachment not found")
                if (text.isBlank()) annot.removeItem(COSName.CONTENTS) else annot.setString(COSName.CONTENTS, text)
                annot.setString(COSName.M, com.tom_roush.pdfbox.util.DateConverter.toString(Calendar.getInstance()))
                (annot.getDictionaryObject(COSName.FS) as? COSDictionary)?.let { fs -> if (text.isBlank()) fs.removeItem(COSName.DESC) else fs.setString(COSName.DESC, text) }
                save(doc, out, password, listOf(att.page), listOfNotNull(annot, annot.getDictionaryObject(COSName.FS)), documentLevel = false)
            }
        }
    }

    /**
     * Save after an attachment change. Signed file: incremental update of [touched] objects and [pages]; a certified
     * file forbids document-level changes at any level and page-level ones below P=3. Otherwise a full save that keeps
     * the source's protection.
     */
    private fun save(doc: PDDocument, out: File, password: String?, pages: List<Int>, touched: List<COSBase?>, documentLevel: Boolean) {
        if (SignedSave.isSigned(doc)) {
            if (documentLevel && SignedSave.certifyLevel(doc) != null)
                throw SignedDocumentException(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, "this file is certified and does not allow attachment changes; save as a copy")
            if (!documentLevel) SignedSave.requireAnnotationsAllowed(doc)
            touched.forEach { t ->
                (t as? COSUpdateInfo)?.isNeedToBeUpdated = true
                // name-tree arrays are often indirect objects of their own (PdfBox writes new arrays that way)
                (t as? COSDictionary)?.let { d -> listOf(COSName.NAMES, COSName.KIDS, COSName.LIMITS).forEach { k -> (d.getDictionaryObject(k) as? COSUpdateInfo)?.isNeedToBeUpdated = true } }
            }
            doc.documentCatalog.cosObject.isNeedToBeUpdated = true
            SignedSave.saveIncremental(doc, pages.distinct().map { doc.getPage(it) }, out)
        } else {
            PdfOps.reprotect(doc, password)
            doc.save(out)
        }
    }
}
