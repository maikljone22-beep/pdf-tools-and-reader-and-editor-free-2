package com.smartdocs.editor.core.view5

import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drawing scale kept in the document (Nitro 7.47 / 7.48 / 7.51, Foxit 25.10 / 25.12): a page /VP viewport
 * (ISO 32000 12.9.2) covering the page with a /Measure /RL dictionary, its /Name holding the scale description.
 * Reading falls back to the /Measure of the first measurement annotation on the page ("use the scale from the
 * document"). Pure PdfBox (unit tested).
 */
object ScaleStore {
    private val VP = COSName.getPDFName("VP")
    private val MEASURE = COSName.getPDFName("Measure")
    private val R = COSName.getPDFName("R")

    data class PageScale(val scale: MeasureScale, val label: String, val description: String)

    fun write(doc: PDDocument, pages: Collection<Int>, scale: MeasureScale, precision: Int, description: String) {
        pages.filter { it in 0 until doc.numberOfPages }.forEach { pi ->
            val page = doc.getPage(pi)
            val box = page.mediaBox
            val vp = COSDictionary().apply {
                setName(COSName.TYPE, "Viewport")
                setItem(COSName.BBOX, COSArray().apply { listOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY).forEach { add(COSFloat(it)) } })
                if (description.isNotBlank()) setString(COSName.NAME, description)
                setItem(MEASURE, MeasureWriter.measureDict(scale, precision))
            }
            page.cosObject.setItem(VP, COSArray().apply { add(vp) })
        }
    }

    /** Remove the stored scale of [pages]. */
    fun clear(doc: PDDocument, pages: Collection<Int>) { pages.filter { it in 0 until doc.numberOfPages }.forEach { doc.getPage(it).cosObject.removeItem(VP) } }

    private fun fromMeasure(m: COSDictionary?, desc: String): PageScale? {
        val label = (m?.getDictionaryObject(R) as? COSString)?.string ?: return null
        val s = MeasureScale.parse(label) ?: return null
        return PageScale(s, label, desc)
    }

    fun read(doc: PDDocument, page: Int): PageScale? {
        if (page !in 0 until doc.numberOfPages) return null
        val p = doc.getPage(page)
        (p.cosObject.getDictionaryObject(VP) as? COSArray)?.let { arr ->
            for (i in 0 until arr.size()) {
                val vp = arr.getObject(i) as? COSDictionary ?: continue
                fromMeasure(vp.getDictionaryObject(MEASURE) as? COSDictionary, vp.getString(COSName.NAME) ?: "")?.let { return it }
            }
        }
        runCatching { p.annotations }.getOrNull()?.forEach { an ->
            fromMeasure(an.cosObject.getDictionaryObject(MEASURE) as? COSDictionary, "")?.let { return it }
        }
        return null
    }

    /** Scale of every page that has one. */
    fun readAll(doc: PDDocument): Map<Int, PageScale> = (0 until doc.numberOfPages).mapNotNull { i -> read(doc, i)?.let { i to it } }.toMap()

    /** Several different scales in one document: the multiple-scale warning of Nitro 7.48. */
    fun hasMultiple(all: Map<Int, PageScale>): Boolean = all.values.map { it.scale.label() }.distinct().size > 1

    suspend fun readAll(src: File, pw: String?): Map<Int, PageScale> = withContext(Dispatchers.IO) {
        runCatching { (if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)).use { readAll(it) } }.getOrDefault(emptyMap())
    }

    suspend fun write(src: File, out: File, pw: String?, pages: Collection<Int>?, scale: MeasureScale, precision: Int, description: String) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val ps = pages ?: (0 until doc.numberOfPages).toList()
            write(doc, ps, scale, precision, description)
            SignedSave.saveEdited(doc, out, pw, ps)
        }
    }
}
