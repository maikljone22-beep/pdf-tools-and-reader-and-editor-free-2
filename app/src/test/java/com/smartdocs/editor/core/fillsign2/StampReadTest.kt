package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.pdf.FillSignKeys
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** nitro-fillsign2: saved signature / image stamps are recognised and read back; paste at a point. */
class StampReadTest {

    private fun stamp(doc: PDDocument, name: String?, sig: Boolean, images: Int): PDAnnotationRubberStamp = PDAnnotationRubberStamp().apply {
        rectangle = PDRectangle(10f, 10f, 50f, 20f)
        if (name != null) this.name = name
        val ap = PDAppearanceStream(doc).apply { bBox = PDRectangle(0f, 0f, 50f, 20f); resources = PDResources() }
        repeat(images) { ap.resources.add(PDImageXObject(PDStream(doc), PDResources())) }
        appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        if (sig) cosObject.setBoolean(FillSignKeys.SIG, true)
    }

    @Test fun recognisesOurImageStamps() {
        PDDocument().use { doc ->
            assertNotNull(StampRead.imageOf(stamp(doc, "Image", sig = false, images = 1)))
            assertNotNull(StampRead.imageOf(stamp(doc, null, sig = true, images = 1)))
            assertTrue(StampRead.isOurImageStamp(stamp(doc, "Approved", sig = true, images = 0)))
            // a foreign stamp, an appearance without an image or with several is not read back
            assertNull(StampRead.imageOf(stamp(doc, "Approved", sig = false, images = 1)))
            assertFalse(StampRead.isOurImageStamp(stamp(doc, "Approved", sig = false, images = 1)))
            assertNull(StampRead.imageOf(stamp(doc, "Image", sig = false, images = 0)))
            assertNull(StampRead.imageOf(stamp(doc, "Image", sig = false, images = 2)))
        }
    }

    @Test fun signatureReferenceIsStableAndPathFree() {
        val a = StampRead.refOf("/data/user/0/app/files/signatures/sig_1.png")
        assertEquals(16, a.length)
        assertFalse(a.contains("sig_1"))
        assertEquals(a, StampRead.refOf("C:\\other\\sig_1.png"))          // only the file name counts
        assertNotEquals(a, StampRead.refOf("/x/sig_2.png"))
        assertEquals("/s/sig_1.png", StampRead.resolve(a, listOf("/s/sig_0.png", "/s/sig_1.png")))
        assertNull(StampRead.resolve(a, listOf("/s/sig_0.png")))
        assertNull(StampRead.resolve(null, listOf("/s/sig_1.png")))
    }

    @Test fun rotationOfAppearanceMatrix() {
        assertEquals(0, StampRead.rotationOf(1f, 0f))
        assertEquals(270, StampRead.rotationOf(0f, 1f))     // 90 degrees counter-clockwise in PDF = 270 clockwise
        assertEquals(90, StampRead.rotationOf(0f, -1f))
        assertEquals(180, StampRead.rotationOf(-1f, 0f))
        assertEquals(0, StampRead.rotationOf(2f, 0f))       // scaling only
    }

    @Test fun pasteAtPointIsCentredAndClamped() {
        val b = MultiPage.Box(0f, 0f, 100f, 40f)
        assertEquals(MultiPage.Box(250f, 380f, 350f, 420f), MultiPage.boxAt(b, 300f, 400f, 600f, 800f))
        assertEquals(MultiPage.Box(0f, 0f, 100f, 40f), MultiPage.boxAt(b, 5f, 5f, 600f, 800f))
        assertEquals(MultiPage.Box(500f, 760f, 600f, 800f), MultiPage.boxAt(b, 599f, 799f, 600f, 800f))
    }
}
