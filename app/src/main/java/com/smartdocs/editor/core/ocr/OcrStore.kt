package com.smartdocs.editor.core.ocr

import android.graphics.Rect
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The recognised words of a page kept in the PDF (page key /SDOcrWords, a Flate stream of JSON), so OCR suspects can be
 * reviewed later in an existing document (Foxit "Find OCR Suspects" / "All Suspects"): text, box in pixels of the
 * recognised image, confidence, line. [SearchablePdfWriter.addLayer] stores them; [OcrReview] rewrites the layer.
 */
object OcrStore {
    private val KEY = COSName.getPDFName("SDOcrWords")

    /** Pure JSON form (unit-tested). */
    fun encode(page: OcrPage): String = JSONObject().put("w", page.imageWidth).put("h", page.imageHeight).put("words", JSONArray().apply {
        page.words.forEach { w -> put(JSONArray().put(w.text).put(w.box.left).put(w.box.top).put(w.box.right).put(w.box.bottom).put(w.confidence.toDouble()).put(w.line)) }
    }).toString()

    /** Words as (text, l, t, r, b, conf, line) without android Rect, for tests and callers that only need numbers. */
    fun decodeRaw(json: String): Triple<Int, Int, List<Array<Any>>> {
        val o = JSONObject(json); val a = o.getJSONArray("words")
        return Triple(o.getInt("w"), o.getInt("h"), (0 until a.length()).map { i ->
            val e = a.getJSONArray(i)
            arrayOf<Any>(e.getString(0), e.getInt(1), e.getInt(2), e.getInt(3), e.getInt(4), e.getDouble(5).toFloat(), e.optInt(6, -1))
        })
    }

    fun decode(json: String): OcrPage {
        val (w, h, rows) = decodeRaw(json)
        val words = rows.map { r -> OcrWord(r[0] as String, Rect(r[1] as Int, r[2] as Int, r[3] as Int, r[4] as Int), r[5] as Float, r[6] as Int) }
        return OcrPage(words.joinToString(" ") { it.text }, words, w, h)
    }

    fun put(doc: PDDocument, page: PDPage, ocr: OcrPage) {
        val s = doc.document.createCOSStream()
        s.createOutputStream(COSName.FLATE_DECODE).use { it.write(encode(ocr).toByteArray(Charsets.UTF_8)) }
        page.cosObject.setItem(KEY, s)
    }

    fun get(page: PDPage): OcrPage? = runCatching {
        val s = page.cosObject.getDictionaryObject(KEY) as? COSStream ?: return null
        decode(s.createInputStream().use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrNull()

    fun has(page: PDPage) = page.cosObject.containsKey(KEY)

    fun remove(page: PDPage) = page.cosObject.removeItem(KEY)
}
