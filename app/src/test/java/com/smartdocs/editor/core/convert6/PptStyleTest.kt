package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.Align
import com.smartdocs.editor.core.office.Inline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert-2: .ppt text styling (StyleTextPropAtom): alignment, bold / italic, size, RGB colour. */
class PptStyleTest {
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    @Test fun runsBecomeStyledParagraphs() {
        // text "Hi\rYou" (6 chars) → runs cover 7
        val pf = le32(3) + le16(0) + le32(0x800) + le16(1) +                       // "Hi\r": centred
            le32(4) + le16(0) + le32(0x800 or 0x1000) + le16(2) + le16(100)        // "You" + end: right, line spacing skipped
        val cf = le32(2) + le32(0x1 or 0x20000 or 0x40000) + le16(1) + le16(24) + byteArrayOf(0x11, 0x22, 0x33, 0xFE.toByte()) +   // "Hi": bold 24 #112233
            le32(5) + le32(0x2) + le16(2)                                                                                            // "\rYou" + end: italic
        val b = pf + cf
        val st = PptStyle.parse(b, 0, b.size, 6)!!
        assertEquals(listOf(Align.CENTER, Align.RIGHT), st.paras.map { it.align })
        val paras = PptStyle.paragraphs("Hi\rYou", st)
        assertEquals(2, paras.size)
        val hi = paras[0].runs.single() as Inline.Text
        assertTrue(hi.style.bold); assertEquals(24f, hi.style.sizePt); assertEquals("112233", hi.style.color)
        assertEquals(Align.CENTER, paras[0].align)
        val you = paras[1].runs.single() as Inline.Text
        assertEquals("You", you.text); assertTrue(you.style.italic); assertEquals(Align.RIGHT, paras[1].align)
    }

    @Test fun brokenAtomsFallBackToPlainText() {
        assertNull(PptStyle.parse(le32(3) + le16(0), 0, 6, 2))                       // cut short
        val plain = PptStyle.paragraphs("a\rb", null)
        assertEquals(listOf("a", "b"), plain.map { (it.runs.single() as Inline.Text).text })
    }
}
