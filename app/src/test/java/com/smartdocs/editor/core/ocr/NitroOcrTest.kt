package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.tools5.ScanFilters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Nitro §13 / Foxit §5 OCR gaps: pre-processing maths, settings, stored words, model pins, scan filters. */
class NitroOcrTest {

    /** A white page with dark text-like lines rising by [deg] to the right (image y down). */
    private fun page(w: Int, h: Int, deg: Double): IntArray {
        val g = IntArray(w * h) { 255 }
        val t = Math.tan(Math.toRadians(deg))
        for (row in 0 until 10) {
            val y0 = 30 + row * 25
            for (x in 20 until w - 20) {
                if ((x / 7) % 4 == 3) continue                 // gaps between "words"
                val yc = (y0 - (x - w / 2) * t).toInt()
                for (dy in 0 until 6) { val y = yc + dy; if (y in 0 until h) g[y * w + x] = 20 }
            }
        }
        return g
    }

    @Test fun skewIsMeasured() {
        for (deg in listOf(0.0, 3.0, -4.5, 7.0)) {
            val a = OcrPrep.skewAngle(page(500, 320, deg), 500, 320)
            assertEquals("deg $deg", deg, a.toDouble(), 0.35)
        }
    }

    @Test fun otsuSplitsTwoLevels() {
        val g = IntArray(1000) { if (it < 300) 30 else 220 }
        val t = OcrPrep.otsu(g)
        assertTrue(t in 30 until 220)
        assertArrayEquals(intArrayOf(0, 255), OcrPrep.binarize(intArrayOf(30, 220), t))
    }

    @Test fun medianRemovesSpecks() {
        val w = 5; val h = 5
        val g = IntArray(w * h) { 255 }; g[12] = 0                 // one black speck in the middle
        assertEquals(255, OcrPrep.median3(g, w, h)[12])
    }

    @Test fun orientationNeedsAWinner() {
        assertEquals(180, OcrPrep.bestOrientation(mapOf(0 to 10f, 90 to 3f, 180 to 50f, 270 to 2f)))
        assertEquals(0, OcrPrep.bestOrientation(mapOf(0 to 5f, 90 to 5f, 180 to 5f, 270 to 5f)))
    }

    @Test fun tuningRoundTrip() {
        val t = OcrTuning(fast = true, deskew = true, orientation = true, threshold = OcrThreshold.FIXED, thresholdValue = 140,
            smooth = true, numericOnly = true, outputDpi = 150, outputQuality = 60)
        assertEquals(t, OcrTuning.fromJson(t.toJson()))
        assertEquals(OcrTuning(), OcrTuning.fromJson("nope"))
        assertTrue(t.changesPages && t.filtersImage)
    }

    @Test fun storedWordsJson() {
        val json = """{"w":1000,"h":1400,"words":[["مرحبا",10,20,110,60,91.5,0],["٨٩٠",120,20,170,60,42.0,0]]}"""
        val (w, h, rows) = OcrStore.decodeRaw(json)
        assertEquals(1000, w); assertEquals(1400, h); assertEquals(2, rows.size)
        assertEquals("٨٩٠", rows[1][0]); assertEquals(42f, rows[1][5] as Float, 0.001f); assertEquals(120, rows[1][1])
    }

    @Test fun modelsArePinned() {
        assertTrue(OcrModels.BEST.size > 150 && OcrModels.FAST.size > 150)
        assertEquals(12603724L, OcrModels.BEST["ara"]!!.size)              // the same pack as the SHA-256 pin
        assertEquals(OcrEngine.PINNED["ara"]!!.size, OcrModels.BEST["ara"]!!.size)
        assertTrue("script/Latin" in OcrModels.BEST && "jpn" in OcrModels.FAST)
        assertTrue(OcrModels.languages().none { it.startsWith("script/") } && "osd" !in OcrModels.BEST)
        // git blob id of an empty file is the well-known e69de29…
        val empty = File.createTempFile("blob", ".bin"); try { assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", OcrModels.gitBlobId(empty)) } finally { empty.delete() }
        val hello = File.createTempFile("blob", ".txt"); try { hello.writeText("hello\n"); assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", OcrModels.gitBlobId(hello)) } finally { hello.delete() }
    }

    @Test fun scanFilters() {
        val w = 6; val h = 6
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        assertArrayEquals(flat, ScanFilters.descreen(flat, w, h))
        assertArrayEquals(flat, ScanFilters.sharpen(flat, w, h))
        // an edge gets steeper
        val edge = IntArray(w * h) { i -> if (i % w < 3) 0xFF404040.toInt() else 0xFFC0C0C0.toInt() }
        val sh = ScanFilters.sharpen(edge, w, h)
        assertTrue((sh[2] and 0xFF) < 0x40 && (sh[3] and 0xFF) > 0xC0)
        // black and white: dark half → 0 bits
        val bits = ScanFilters.toBilevel(IntArray(16 * 4) { i -> if (i % 16 < 8) 0xFF101010.toInt() else -1 }, 16, 4)
        assertTrue(Integer.bitCount(bits[0].toInt() and 0xFF) < 8); assertEquals(0xFF, bits[1].toInt() and 0xFF)   // the window sees only ink at the very edge
    }
}
