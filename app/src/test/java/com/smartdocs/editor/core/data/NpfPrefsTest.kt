package com.smartdocs.editor.core.data

import com.smartdocs.editor.core.files.NpfSaveFolder
import com.smartdocs.editor.core.forms.NpfBarcode
import com.smartdocs.editor.core.forms.XItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** nav-prefs: pure helpers behind "More preferences". */
class NpfPrefsTest {

    @Test fun defaultsWithoutInit() {
        assertEquals("auto", NpfPrefs.defaultLayout)
        assertEquals(0, NpfPrefs.recentLimit)
        assertTrue(NpfPrefs.retainTools)
        assertFalse(NpfPrefs.offline)
        assertTrue(NpfNet.allowed())
        assertEquals(listOf(0.25f, 1f), NpfPrefs.floats("0.25, x, 1, 1.5"))
    }

    @Test fun digitsWhenTyping() {
        assertEquals("صفحة ١٢", NpfText.digits("صفحة 12", "arabic"))
        assertEquals("page 12", NpfText.digits("page ١٢", "latin"))
        assertEquals("a1", NpfText.digits("a1", "keep"))
    }

    @Test fun a11yMatrixMapsWhiteToBackgroundAndBlackToText() {
        val m = NpfText.a11yMatrix(0x000000, 0xFFFF00)
        fun apply(r: Float, g: Float, b: Float) = (0..2).map { ch -> m[ch * 5] * r + m[ch * 5 + 1] * g + m[ch * 5 + 2] * b + m[ch * 5 + 4] }
        val white = apply(255f, 255f, 255f); val black = apply(0f, 0f, 0f)
        assertEquals(0f, white[0], 0.6f); assertEquals(0f, white[1], 0.6f); assertEquals(0f, white[2], 0.6f)
        assertEquals(255f, black[0], 0.01f); assertEquals(255f, black[1], 0.01f); assertEquals(0f, black[2], 0.01f)
        assertEquals(1f, m[18], 0f)
    }

    @Test fun fontMapping() {
        val map = NpfText.parseFontMap("# comment\nHelvetica = Arial\nTraditional Arabic=Amiri\nbad line\n= x")
        assertEquals(2, map.size)
        assertEquals("Arial", NpfText.mappedFont(map, "ABCDEF+Helvetica"))
        assertEquals("Amiri", NpfText.mappedFont(map, "TraditionalArabic"))
        assertNull(NpfText.mappedFont(map, "Times"))
    }

    @Test fun gridSnap() {
        assertEquals(30f, NpfText.snap(31f, 10f, 0f), 1e-4f)
        assertEquals(35f, NpfText.snap(33f, 10f, 5f), 1e-4f)
        assertEquals(7f, NpfText.snap(7f, 0f, 0f), 1e-4f)
        assertEquals(72f, NpfText.ptPerUnit("in"), 1e-4f)
    }

    @Test fun saveFolderCandidates() {
        val docs = File("/x/Documents"); val scans = File(docs, "Scans")
        assertTrue(NpfSaveFolder.isOutput(File(docs, "a.pdf"), docs, scans))
        assertTrue(NpfSaveFolder.isOutput(File(scans, "scan 1.pdf"), docs, scans))
        assertFalse(NpfSaveFolder.isOutput(File(docs, ".a.pdf.import"), docs, scans))
        assertFalse(NpfSaveFolder.isOutput(File(File(docs, "Cloud/drive"), "a.pdf"), docs, scans))
        assertFalse(NpfSaveFolder.isCandidateName("x.part"))
        assertFalse(NpfSaveFolder.isCandidateName("noext"))
    }

    @Test fun moduleRuns() {
        assertTrue(NpfBarcode.runs(BooleanArray(3)).isEmpty())
        val r = NpfBarcode.runs(booleanArrayOf(false, true, true, false, true, false, false))
        assertEquals(listOf(2, 1, 1), r.toList())
    }

    @Test fun barcodeBoxes() {
        val bars = NpfBarcode.boxes("code128B", "123", 0f, 0f, 200f, 40f)
        assertNotNull(bars)
        assertTrue(bars!!.all { it is XItem.Box && it.x >= 0f && it.x + it.w <= 200.01f })
        // shared zxing encoder: the 2D and EAN/UPC symbologies now draw too
        for ((t, v) in listOf("pdf417" to "hello", "dataMatrix" to "hello", "ean13" to "590123412345", "upcA" to "03600029145", "code3Of9" to "A1", "interleaved2of5" to "1234"))
            assertTrue(t, NpfBarcode.boxes(t, v, 0f, 0f, 200f, 80f)?.isNotEmpty() == true)
        assertNull("not valid for EAN-13", NpfBarcode.boxes("ean13", "abc", 0f, 0f, 200f, 80f))
        assertNull("no encoder", NpfBarcode.boxes("postUSIMB", "1", 0f, 0f, 200f, 80f))
        val qr = NpfBarcode.boxes("QRCode", "hello", 0f, 0f, 100f, 100f)
        assertTrue(qr != null && qr.isNotEmpty())
    }
}
