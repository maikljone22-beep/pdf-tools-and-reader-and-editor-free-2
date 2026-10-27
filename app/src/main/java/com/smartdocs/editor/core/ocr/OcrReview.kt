package com.smartdocs.editor.core.ocr

import android.content.Context
import android.graphics.Rect
import com.smartdocs.editor.core.pdf.OcrLayerCleanup
import com.smartdocs.editor.core.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foxit "Find OCR Suspects" / "All Suspects" on a document OCRed earlier: the stored words ([OcrStore]) are corrected
 * and each changed page gets its invisible layer written again. Also the Nitro zone OCR (one area of a page).
 */
object OcrReview {

    /** Stored OCR words of every page that has them (page index → page). */
    suspend fun load(src: File, password: String?): Map<Int, OcrPage> = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc -> doc.pages.mapIndexedNotNull { i, p -> OcrStore.get(p)?.let { i to it } }.toMap() }
    }

    /** Suspects: words under [minConf] that have letters or digits (index into the page's words). */
    fun suspects(page: OcrPage, minConf: Float): List<Int> =
        page.words.indices.filter { page.words[it].confidence < minConf && page.words[it].text.any { c -> c.isLetterOrDigit() } }

    /**
     * Applies [edits] (word index → new text, null = "not text": the word goes) to [page]; a corrected or accepted
     * word gets confidence 100 so it is no longer a suspect.
     */
    fun apply(page: OcrPage, edits: Map<Int, String?>): OcrPage {
        val words = page.words.mapIndexedNotNull { i, w ->
            if (i !in edits) w else edits[i]?.trim()?.takeIf { it.isNotEmpty() }?.let { w.copy(text = it, confidence = 100f) }
        }
        return page.copy(text = words.joinToString(" ") { it.text }, words = words)
    }

    /** Writes [pages] (page index → corrected words) over their old invisible layers into [out]. */
    suspend fun rewrite(ctx: Context, src: File, password: String?, out: File, pages: Map<Int, OcrPage>) = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            val w = SearchablePdfWriter(ctx, doc)
            pages.forEach { (pi, page) ->
                if (pi !in 0 until doc.numberOfPages) return@forEach
                val p = doc.getPage(pi)
                OcrLayerCleanup.removeOurs(p, latinOnly = true)
                OcrStore.remove(p)
                w.addLayer(pi, page)
            }
            doc.save(out)
        }
        out
    }

    /**
     * Zone OCR: [crop] was recognised from the area starting at pixel ([x0], [y0]) of a page image [fullW]×[fullH];
     * the words are moved to page-image coordinates so they can join the page's layer.
     */
    fun placeZone(crop: OcrPage, x0: Int, y0: Int, fullW: Int, fullH: Int): OcrPage =
        crop.copy(words = crop.words.map { w -> w.copy(box = Rect(w.box.left + x0, w.box.top + y0, w.box.right + x0, w.box.bottom + y0)) },
            imageWidth = fullW, imageHeight = fullH)
}
