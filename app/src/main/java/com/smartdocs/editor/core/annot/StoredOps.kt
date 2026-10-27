package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** In-place changes to stored annotations (no appearance rewrite): move, remove, read names. */
object StoredOps {

    private fun shiftArray(cos: COSDictionary, key: COSName, dx: Float, dy: Float) {
        val arr = cos.getDictionaryObject(key) as? COSArray ?: return
        val out = COSArray()
        for (i in 0 until arr.size()) {
            val v = (arr.getObject(i) as? COSNumber)?.floatValue() ?: 0f
            out.add(COSFloat(v + if (i % 2 == 0) dx else dy))
        }
        cos.setItem(key, out)
    }

    /**
     * Move annotations [indices] of [page] by (dx, dy) points: /Rect plus every geometry array. The appearance
     * stream follows /Rect (its BBox is mapped onto the rect), so custom appearances and threads stay intact.
     */
    suspend fun translate(src: File, out: File, pw: String?, page: Int, indices: List<Int>, dx: Float, dy: Float) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val list = doc.getPage(page).annotations
            indices.distinct().mapNotNull { list.getOrNull(it) }.forEach { an ->
                val cos = an.cosObject
                shiftArray(cos, COSName.RECT, dx, dy)
                listOf("QuadPoints", "L", "Vertices", "CL").forEach { shiftArray(cos, COSName.getPDFName(it), dx, dy) }
                (cos.getDictionaryObject(COSName.INKLIST) as? COSArray)?.let { ink ->
                    val n = COSArray()
                    for (i in 0 until ink.size()) {
                        val g = ink.getObject(i) as? COSArray ?: continue
                        val ng = COSArray()
                        for (k in 0 until g.size()) ng.add(COSFloat(((g.getObject(k) as? COSNumber)?.floatValue() ?: 0f) + if (k % 2 == 0) dx else dy))
                        n.add(ng)
                    }
                    cos.setItem(COSName.INKLIST, n)
                }
                (cos.getDictionaryObject(COSName.getPDFName("Popup")) as? COSDictionary)?.let { shiftArray(it, COSName.RECT, dx, dy) }
                an.setModifiedDate(java.util.Calendar.getInstance())
            }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** Remove only annotations [indices] of [page] (their replies stay, like a single delete). */
    suspend fun removeOnly(src: File, out: File, pw: String?, page: Int, indices: List<Int>) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val p = doc.getPage(page)
            val drop = indices.toSet()
            p.annotations = p.annotations.filterIndexed { i, _ -> i !in drop }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** /NM of every annotation of [page], by index. */
    fun namesOf(src: File, pw: String?, page: Int): Map<Int, String?> {
        val doc = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)
        return doc.use { d ->
            if (page !in 0 until d.numberOfPages) return@use emptyMap()
            d.getPage(page).annotations.mapIndexed { i, an -> i to an.annotationName }.toMap()
        }
    }
}
