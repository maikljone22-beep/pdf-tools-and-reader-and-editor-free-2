package com.smartdocs.editor.core.tools5

import android.graphics.Bitmap
import android.graphics.Matrix
import com.smartdocs.editor.core.ocr.OcrEngine
import kotlin.math.sqrt

/**
 * Scanner essentials: the page turned the right way up. Text lines make the row profile of dark pixels vary much
 * more than the column profile; a page lying sideways shows the opposite (pure, tested). Which of the two remaining
 * turns is upright (0 / 180, or 90 / 270) is decided by OCR confidence when the language data is on the phone.
 */
object ScanOrient {

    /** Rows-vs-columns profile ratio of dark pixels; > 1 = text lines run across, < 1 = they run up and down. Null = too little ink. */
    fun lineRatio(px: IntArray, w: Int, h: Int): Float? {
        if (w < 16 || h < 16) return null
        var sum = 0L
        val lum = IntArray(px.size) { val c = px[it]; ((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).also { v -> sum += v } }
        val t = (sum / px.size * 0.6).toInt()
        val rows = DoubleArray(h); val cols = DoubleArray(w); var ink = 0
        // a margin is left out: page edges and shadows are not text
        val mx = w / 12; val my = h / 12
        for (y in my until h - my) for (x in mx until w - mx) if (lum[y * w + x] < t) { rows[y]++; cols[x]++; ink++ }
        if (ink < (w - 2 * mx) * (h - 2 * my) / 200) return null
        fun cv(a: DoubleArray, from: Int, to: Int): Double {
            val n = to - from; var m = 0.0; for (i in from until to) m += a[i]; m /= n
            if (m <= 0.0) return 0.0
            var v = 0.0; for (i in from until to) { val d = a[i] - m; v += d * d }
            return sqrt(v / n) / m
        }
        val r = cv(rows, my, h - my); val c = cv(cols, mx, w - mx)
        return if (c <= 0.0) null else (r / c).toFloat()
    }

    /** True when the text runs up and down (the page lies sideways). */
    fun sideways(px: IntArray, w: Int, h: Int): Boolean = (lineRatio(px, w, h) ?: 1f) < 0.7f

    private fun turned(b: Bitmap, deg: Int): Bitmap = if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    private suspend fun score(engine: OcrEngine, b: Bitmap): Float =
        runCatching { engine.recognize(b, wantWords = true).words.filter { w -> w.text.count { it.isLetter() } >= 2 }.sumOf { (it.confidence * it.text.length).toDouble() }.toFloat() }.getOrDefault(0f)

    /**
     * The clockwise turn (0 / 90 / 180 / 270) that makes [page] upright. [engine] already initialised with languages;
     * without it only nothing is changed (a sideways page cannot be told from its mirror turn without reading it).
     */
    suspend fun turnFor(page: Bitmap, engine: OcrEngine?): Int {
        val w = page.width; val h = page.height
        val px = IntArray(w * h); page.getPixels(px, 0, w, 0, 0, w, h)
        val side = sideways(px, w, h)
        if (engine == null) return 0
        val a = if (side) 90 else 0; val b = a + 180
        val ba = turned(page, a); val bb = turned(page, b)
        try {
            val sa = score(engine, ba); val sb = score(engine, bb)
            // only a clear winner turns the page
            return when { sb > sa * 1.3f && sb > 50f -> b % 360; sa > 50f || !side -> a; else -> 0 }
        } finally { if (ba !== page) ba.recycle(); if (bb !== page) bb.recycle() }
    }
}
