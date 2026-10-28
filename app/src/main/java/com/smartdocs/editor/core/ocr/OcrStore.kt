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

    /**
     * audit 5 OCR-4: box (left, top, right, bottom) of an image [fromW]×[fromH] in pixels of an image [toW]×[toH] of the
     * same page (another render size). Plain Kotlin (unit-tested).
     */
    fun rescale(box: IntArray, fromW: Int, fromH: Int, toW: Int, toH: Int): IntArray {
        if (fromW <= 0 || fromH <= 0 || (fromW == toW && fromH == toH)) return box.copyOf()
        val kx = toW.toDouble() / fromW; val ky = toH.toDouble() / fromH
        return intArrayOf(Math.round(box[0] * kx).toInt(), Math.round(box[1] * ky).toInt(), Math.round(box[2] * kx).toInt(), Math.round(box[3] * ky).toInt())
    }

    /**
     * audit 5 OCR-3: indexes of the [old] stored boxes that stay next to the [new] ones — an old word mostly covered
     * (more than [maxCovered] of its area) by new words was recognised again (re-OCR, zone OCR of the same area) and goes,
     * so the review lists every word once. Boxes are (left, top, right, bottom) in the same pixels. Plain Kotlin (unit-tested).
     */
    fun keepOld(old: List<IntArray>, new: List<IntArray>, maxCovered: Float = 0.5f): List<Int> = old.indices.filter { i ->
        val o = old[i]
        val area = (o[2] - o[0]).toLong() * (o[3] - o[1])
        if (area <= 0L) return@filter false
        var covered = 0L
        for (n in new) {
            val w = minOf(o[2], n[2]) - maxOf(o[0], n[0]); val h = minOf(o[3], n[3]) - maxOf(o[1], n[1])
            if (w > 0 && h > 0) covered += w.toLong() * h
        }
        covered.toDouble() / area <= maxCovered
    }

    /**
     * The stored words of a page after a new layer [page] was added: [prev] (stored earlier, rescaled to the new image
     * size — audit 5 OCR-4, the words are no longer dropped when the render sizes differ) without the words [page]
     * recognised again (OCR-3), then the new words.
     */
    fun merge(prev: OcrPage?, page: OcrPage): OcrPage {
        if (prev == null || prev.words.isEmpty()) return page
        val boxes = prev.words.map { w -> rescale(intArrayOf(w.box.left, w.box.top, w.box.right, w.box.bottom), prev.imageWidth, prev.imageHeight, page.imageWidth, page.imageHeight) }
        val keep = keepOld(boxes, page.words.map { intArrayOf(it.box.left, it.box.top, it.box.right, it.box.bottom) })
        val kept = keep.map { i -> val b = boxes[i]; prev.words[i].copy(box = Rect(b[0], b[1], b[2], b[3])) }
        return page.copy(words = kept + page.words)
    }
}
