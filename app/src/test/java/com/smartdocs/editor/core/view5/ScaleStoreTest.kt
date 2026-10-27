package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Per-page drawing scale stored in /VP viewports (7.47 / 7.48 / 7.51, Foxit 25.10 / 25.12). */
class ScaleStoreTest {
    @Test fun writeReadAndMultipleWarning() {
        val d = PDDocument(); repeat(3) { d.addPage(PDPage()) }
        ScaleStore.write(d, listOf(0, 1), MeasureScale(1.0, MUnit.CM, 2.0, MUnit.M), 2, "Ground floor")
        ScaleStore.write(d, listOf(2), MeasureScale(1.0, MUnit.IN, 10.0, MUnit.FT), 1, "")
        val o = ByteArrayOutputStream(); d.save(o); d.close()
        PDDocument.load(o.toByteArray()).use { r ->
            val p0 = ScaleStore.read(r, 0)!!
            assertEquals("Ground floor", p0.description)
            assertEquals(2.0, p0.scale.length(72.0 / 2.54), 1e-6)       // 1 cm on paper = 2 m
            val all = ScaleStore.readAll(r)
            assertEquals(3, all.size)
            assertTrue(ScaleStore.hasMultiple(all))
            assertFalse(ScaleStore.hasMultiple(all.filterKeys { it < 2 }))
            ScaleStore.clear(r, listOf(2))
            assertNull(ScaleStore.read(r, 2))
        }
    }
}
