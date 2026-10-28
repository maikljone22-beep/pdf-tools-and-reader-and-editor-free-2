package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterMarksTest {

    @Test fun noMarksNeedNoRoom() {
        assertEquals(0f, PrinterMarks.room(9f, reg = false, bleedMarks = false), 0f)
        assertTrue(PrinterMarks.room(9f, reg = true, bleedMarks = false) > 9f)
    }

    @Test fun bleedMarksSitOutsideTheBleedBox() {
        val l = PrinterMarks.layout(100f, 100f, 200f, 300f, 9f, reg = false, bleedMarks = true)
        assertEquals(8, l.bleed.size)
        assertTrue(l.reg.isEmpty())
        // every bleed mark lies outside the bleed box (91..309, 91..409) except its anchoring coordinate
        l.bleed.forEach { s ->
            val outX = minOf(s.x1, s.x2) >= 309f || maxOf(s.x1, s.x2) <= 91f
            val outY = minOf(s.y1, s.y2) >= 409f || maxOf(s.y1, s.y2) <= 91f
            assertTrue("$s", outX || outY)
        }
        // bottom-left horizontal mark is on the bleed line y = 91
        assertEquals(91f, l.bleed[0].y1, 0.001f)
    }

    @Test fun registrationTargetsCentredOnEachSide() {
        val l = PrinterMarks.layout(0f, 0f, 100f, 200f, 9f, reg = true, bleedMarks = false)
        assertEquals(4, l.reg.size)
        assertEquals(50f, l.reg[0].cx, 0f)
        assertTrue(l.reg[0].cy + l.reg[0].r < -9f)          // below the bleed
        assertTrue(l.reg[1].cy - l.reg[1].r > 209f)          // above the bleed
        assertEquals(100f, l.reg[2].cy, 0f)
    }

    @Test fun zeroBleedHasNoBleedMarks() {
        assertTrue(PrinterMarks.layout(0f, 0f, 10f, 10f, 0f, reg = false, bleedMarks = true).bleed.isEmpty())
    }

    @Test fun hairlinesAreWidened() {
        assertEquals(0.5f, PrinterMarks.widen(0f, 0.5f), 0f)
        assertEquals(2f, PrinterMarks.widen(2f, 0.5f), 0f)
        assertEquals(0.1f, PrinterMarks.widen(0.1f, 0f), 0f)
        val toks: MutableList<Any?> = mutableListOf(COSInteger.get(0), Operator.getOperator("w"), COSFloat(3f), Operator.getOperator("w"), COSFloat(0.1f), Operator.getOperator("Tw"))
        assertEquals(1, PrinterMarks.fixLineWidths(toks, 0.5f))
        assertEquals(0.5f, (toks[0] as COSNumber).floatValue(), 0.0001f)
        assertEquals(3f, (toks[2] as COSNumber).floatValue(), 0f)
        assertEquals(0.1f, (toks[4] as COSNumber).floatValue(), 0.0001f)   // Tw (word spacing) is not a line width
    }
}
