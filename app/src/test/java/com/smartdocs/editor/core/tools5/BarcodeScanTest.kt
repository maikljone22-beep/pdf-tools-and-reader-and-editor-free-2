package com.smartdocs.editor.core.tools5

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeScanTest {
    private fun qr(text: String, size: Int): IntArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    }

    @Test fun readsAQrCodeOnAPage() {
        val size = 240; val w = 400; val h = 320
        val page = IntArray(w * h) { 0xFFF4F0E8.toInt() }
        val code = qr("https://smartdocs.example/doc/42", size)
        for (y in 0 until size) for (x in 0 until size) page[(y + 40) * w + x + 80] = code[y * size + x]
        val found = BarcodeScan.decode(page, w, h)
        assertEquals(1, found.size)
        assertEquals("QR_CODE", found[0].format)
        assertEquals("https://smartdocs.example/doc/42", found[0].text)
        assertTrue(BarcodeScan.isLink(found[0].text))
    }

    @Test fun readsASidewaysOneToo() {
        val size = 200
        val code = qr("Hello", size)
        // rotate 90° clockwise
        val rot = IntArray(size * size) { k -> val x = k % size; val y = k / size; code[(size - 1 - x) * size + y] }
        assertEquals("Hello", BarcodeScan.decode(rot, size, size).single().text)
    }
}
