package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/** A text of the document a spell check can correct: a stored comment's /Contents or a text field's value. */
data class DocText(val id: String, val page: Int, val kind: String, val text: String)

/**
 * Texts the whole-document spell check walks (Foxit "Check Spelling"): comments' /Contents (not FreeText, whose
 * appearance would go stale) and text form field values. Pure PDFBox (unit tested).
 */
object DocTexts {
    private val SKIP = setOf("Link", "Widget", "Popup", "FreeText")

    fun collect(doc: PDDocument): List<DocText> {
        val out = ArrayList<DocText>()
        for (p in 0 until doc.numberOfPages) {
            runCatching { doc.getPage(p).annotations }.getOrNull()?.forEachIndexed { i, a ->
                val sub = a.subtype ?: return@forEachIndexed
                if (sub in SKIP) return@forEachIndexed
                val c = a.contents?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                out.add(DocText("a:$p:$i", p, sub, c))
            }
        }
        val form = doc.documentCatalog.acroForm
        form?.fieldTree?.forEach { f ->
            if (f is PDTextField) {
                val v = f.value?.takeIf { it.isNotBlank() } ?: return@forEach
                val page = f.widgets.firstOrNull()?.page?.let { pg -> doc.pages.indexOf(pg) } ?: -1
                out.add(DocText("f:" + f.fullyQualifiedName, page, "Field", v))
            }
        }
        return out
    }

    /** Write corrected texts (ids from [collect]); returns how many changed. */
    fun apply(doc: PDDocument, changed: Map<String, String>): Int {
        var n = 0
        for ((id, text) in changed) {
            when {
                id.startsWith("a:") -> {
                    val (p, i) = id.removePrefix("a:").split(':').map { it.toInt() }
                    val a = runCatching { doc.getPage(p).annotations.getOrNull(i) }.getOrNull() ?: continue
                    a.contents = text
                    // a rich-text twin would still show the old words
                    a.cosObject.removeItem(COSName.getPDFName("RC"))
                    n++
                }
                id.startsWith("f:") -> {
                    val f = doc.documentCatalog.acroForm?.getField(id.removePrefix("f:")) as? PDTextField ?: continue
                    runCatching { f.value = text }.onSuccess { n++ }
                }
            }
        }
        return n
    }
}
