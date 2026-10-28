package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** fix/pdf39-convert: PowerPoint 97-2003 binary pieces (row 19): anchors, pictures, persist directory. */
class PptReaderTest {
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun rec(ver: Int, inst: Int, type: Int, body: ByteArray) = le16(ver or (inst shl 4)) + le16(type) + le32(body.size) + body

    @Test fun anchorsInBothForms() {
        val small = le16(100) + le16(50) + le16(300) + le16(200)          // top, left, right, bottom
        assertArrayEquals(intArrayOf(100, 50, 300, 200), PptReader.anchor(small, 0, 8))
        val big = le32(1000) + le32(500) + le32(3000) + le32(2000)
        assertArrayEquals(intArrayOf(1000, 500, 3000, 2000), PptReader.anchor(big, 0, 16))
        assertNull(PptReader.anchor(big, 0, 12))
    }

    @Test fun bitmapBlipsAreRead() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val b = rec(0, 0x46A, 0xF01D, ByteArray(16) + byteArrayOf(0xFF.toByte()) + jpeg)     // one UID + tag
        val img = PptReader.blip(b, 0)!!
        assertEquals("image/jpeg", img.mime); assertArrayEquals(jpeg, img.bytes)
        val png2 = rec(0, 0x6E1, 0xF01E, ByteArray(32) + byteArrayOf(0) + byteArrayOf(0x89.toByte(), 'P'.code.toByte()))   // two UIDs
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte()), PptReader.blip(png2, 0)!!.bytes)
        assertNull(PptReader.blip(rec(0, 0x3D4, 0xF01A, ByteArray(60)), 0))       // EMF: not read
        // DIB → BMP: 14-byte file header, pixel offset after header + palette
        val dib = le32(40) + le32(1) + le32(1) + le16(1) + le16(24) + ByteArray(24) + byteArrayOf(1, 2, 3, 0)
        val bmp = PptReader.dibToBmp(dib)!!
        assertEquals('B'.code.toByte(), bmp[0]); assertEquals(14 + dib.size, bmp.size)
        assertEquals(54, (bmp[10].toInt() and 0xFF) or ((bmp[11].toInt() and 0xFF) shl 8))
    }

    @Test fun persistDirectoryFollowsTheEditChain() {
        // stream: [PersistDirectoryAtom at 0][UserEditAtom at dirLen]
        val dir = rec(0, 0, 0x1772, le32(1 or (2 shl 20)) + le32(111) + le32(222))           // ids 1, 2 → 111, 222
        val userEdit = rec(0, 0, 0x0FF5, le32(0) + le16(0) + byteArrayOf(0, 3) + le32(0) + le32(0) + le32(1))
        val s = ByteArrayOutputStream().apply { write(dir); write(userEdit) }.toByteArray()
        val current = ByteArray(8) + le32(20) + le32(0) + le32(dir.size)                       // offsetToCurrentEdit
        assertEquals(mapOf(1 to 111, 2 to 222), PptReader.persistMap(s, current))
        assertEquals(emptyMap<Int, Int>(), PptReader.persistMap(s, null))
    }
}
