package com.smartdocs.editor.core.search

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a piece of searchable text lives. The ordinal is stored in the index: append only. */
enum class PartKind { TEXT, COMMENTS, BOOKMARK, FIELD, ATTACHMENT }

/** Searchable text that is not page content. [page] is -1 for document-level items. */
data class DocPart(val kind: PartKind, val page: Int, val text: String)

/** Comments, bookmarks, form field values and attachment names/descriptions (read with PdfBox). */
object DocParts {

    suspend fun load(file: File, password: String?): List<DocPart> = withContext(Dispatchers.IO) {
        runCatching { PdfOps.load(file, password).use { collect(it) } }.getOrDefault(emptyList())
    }

    fun collect(doc: PDDocument): List<DocPart> {
        val out = ArrayList<DocPart>()
        val pageOf = HashMap<COSDictionary, Int>()
        doc.pages.forEachIndexed { pi, page ->
            pageOf[page.cosObject] = pi
            runCatching { page.annotations }.getOrNull()?.forEach { a ->
                when (a.subtype) {
                    "Link", "Widget", "Popup", "Redact" -> {}
                    "FileAttachment" -> {
                        val spec = a.cosObject.getDictionaryObject(COSName.getPDFName("FS")) as? COSDictionary
                        val name = spec?.getString(COSName.UF) ?: spec?.getString(COSName.F) ?: ""
                        val t = listOf(name, a.contents ?: "").filter { it.isNotBlank() }.joinToString(" — ")
                        if (t.isNotBlank()) out += DocPart(PartKind.ATTACHMENT, pi, t)
                    }
                    else -> {
                        val subj = a.cosObject.getString(COSName.getPDFName("Subj")) ?: ""
                        val author = a.cosObject.getString(COSName.T) ?: ""
                        val t = listOf(a.contents ?: "", subj, author).filter { it.isNotBlank() }.joinToString(" — ")
                        if (t.isNotBlank()) out += DocPart(PartKind.COMMENTS, pi, t)
                    }
                }
            }
        }
        // bookmarks
        runCatching {
            doc.documentCatalog.documentOutline?.let { walk(doc, it, out, 0) }
        }
        // form fields: name and value, on the page of their first widget
        runCatching {
            doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
                val v = runCatching { f.valueAsString }.getOrDefault("")
                val label = f.alternateFieldName ?: f.partialName ?: ""
                if (v.isBlank() && label.isBlank()) return@forEach
                val page = f.widgets.firstOrNull()?.page?.cosObject?.let { pageOf[it] } ?: -1
                out += DocPart(PartKind.FIELD, page, listOf(label, v).filter { it.isNotBlank() }.joinToString(": "))
            }
        }
        // document-level attachments
        runCatching {
            val ef = (doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary
            if (ef != null) attachments(ef, out, 0)
        }
        // NVE-search: contents of attached / portfolio files (PDF text, plain-text files)
        runCatching { com.smartdocs.editor.core.nve.NveSearchCore.attachmentTexts(doc).forEach { (name, t) -> out += DocPart(PartKind.ATTACHMENT, -1, "$name: $t") } }
        return out
    }

    private fun walk(doc: PDDocument, node: PDOutlineNode, out: MutableList<DocPart>, depth: Int) {
        if (depth > 30 || out.size > 20_000) return
        var it: PDOutlineItem? = node.firstChild
        var guard = 0
        while (it != null && guard++ < 10_000) {
            val title = it.title ?: ""
            if (title.isNotBlank()) {
                val page = runCatching { it.findDestinationPage(doc)?.let { p -> doc.pages.indexOf(p) } }.getOrNull() ?: -1
                out += DocPart(PartKind.BOOKMARK, page, title)
            }
            walk(doc, it, out, depth + 1)
            it = it.nextSibling
        }
    }

    private fun attachments(node: COSDictionary, out: MutableList<DocPart>, depth: Int) {
        if (depth > 20) return
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) {
                val key = (arr.getObject(i) as? COSString)?.string ?: ""
                val spec = arr.getObject(i + 1) as? COSDictionary
                val name = spec?.getString(COSName.UF) ?: spec?.getString(COSName.F) ?: key
                val desc = spec?.getString(COSName.DESC) ?: ""
                out += DocPart(PartKind.ATTACHMENT, -1, listOf(name, desc).filter { it.isNotBlank() }.joinToString(" — "))
                i += 2
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) (kids.getObject(k) as? COSDictionary)?.let { attachments(it, out, depth + 1) } }
    }
}
