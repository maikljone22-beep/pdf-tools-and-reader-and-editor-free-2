package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-comments-forms: superscript / subscript text boxes (Foxit 24.102). */
class TextScriptTest {
    @Test fun scriptStyleRoundTrips() {
        for (script in 0..2) {
            val c = COSDictionary()
            AnnotStyle.applyRich(c, "x2", AnnotStyle.Rich(12f, 0x112233, false, false, script = script), false)
            assertEquals(script, AnnotStyle.readRich(c)!!.script)
            assertEquals(12f, AnnotStyle.readRich(c)!!.fontSize, 0.01f)
        }
        assertTrue(AnnotStyle.defaultStyle(AnnotStyle.Rich(10f, 0, false, false, script = 2)).contains("vertical-align:sub"))
    }

    @Test fun sizesAndShifts() {
        assertEquals(12f, AnnotStyle.scriptSize(12f, 0), 0f)
        assertEquals(7.8f, AnnotStyle.scriptSize(12f, 1), 0.001f)
        assertTrue(AnnotStyle.scriptRise(12f, 1) > 0f)
        assertTrue(AnnotStyle.scriptRise(12f, 2) < 0f)
        assertEquals(0f, AnnotStyle.scriptRise(12f, 0), 0f)
    }
}
