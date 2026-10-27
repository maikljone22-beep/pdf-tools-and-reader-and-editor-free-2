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

    @Test fun code128PatternTableIsConsistent() {
        assertEquals(107, NpfBarcode.C128.size)
        NpfBarcode.C128.forEachIndexed { i, p -> assertEquals("symbol $i", if (i == 106) 13 else 11, p.sumOf { it - '0' }) }
        val w = NpfBarcode.code128("AB")!!
        // start + 2 data + checksum = 4 symbols of 6 elements, stop has 7
        assertEquals(4 * 6 + 7, w.size)
        assertEquals(4 * 11 + 13, w.sum())
        assertNull(NpfBarcode.code128("é"))
    }

    @Test fun code39() {
        assertEquals(44, NpfBarcode.C39.length); assertEquals(44, NpfBarcode.C39_PAT.size)
        NpfBarcode.C39_PAT.forEach { p -> assertEquals(9, p.length); assertEquals(p, 3, p.count { it == 'w' }) }
        assertEquals(NpfBarcode.C39_PAT.toSet().size, NpfBarcode.C39_PAT.size)
        val w = NpfBarcode.code39("A1")!!
        assertEquals(4 * 9 + 3, w.size)       // *A1* plus 3 gaps
        assertNull(NpfBarcode.code39("a*b"))
    }

    @Test fun barcodeBoxes() {
        val bars = NpfBarcode.boxes("code128B", "123", 0f, 0f, 200f, 40f)
        assertNotNull(bars)
        assertTrue(bars!!.all { it is XItem.Box && it.x >= 0f && it.x + it.w <= 200.01f })
        assertNull(NpfBarcode.boxes("pdf417", "x", 0f, 0f, 10f, 10f))
        val qr = NpfBarcode.boxes("QRCode", "hello", 0f, 0f, 100f, 100f)
        assertTrue(qr != null && qr.isNotEmpty())
    }
}
