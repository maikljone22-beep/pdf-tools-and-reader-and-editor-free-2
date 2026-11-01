package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Scanner S2: corners a person dragged, and corners kept in saved scan projects. */
class ScanCropTest {
    @Test fun normalizeReordersDraggedCorners() {
        // corners given BR, TL, BL, TR
        val q = PageQuad.Quad(floatArrayOf(0.9f, 0.1f, 0.1f, 0.9f), floatArrayOf(0.9f, 0.1f, 0.9f, 0.1f))
        val n = PageQuad.normalize(q)
        assertNotNull(n)
        assertArrayEquals(floatArrayOf(0.1f, 0.9f, 0.9f, 0.1f), n!!.x, 1e-6f)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f), n.y, 1e-6f)
    }

    @Test fun collapsedOrTinyCornersAreRefused() {
        assertNull(PageQuad.normalize(PageQuad.Quad(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))))
        assertNull(PageQuad.normalize(PageQuad.Quad(floatArrayOf(0.5f, 0.55f, 0.55f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.55f, 0.55f))))
    }

    @Test fun projectsKeepTheCrop() {
        val crop = floatArrayOf(0.1f, 0.9f, 0.88f, 0.12f, 0.05f, 0.07f, 0.93f, 0.95f)
        val text = ScanProjects.encode("x", listOf(listOf("a.jpg" to PageEdit(crop = crop), "b.jpg" to PageEdit())))
        val back = ScanProjects.decode(text).second[0]
        assertArrayEquals(crop, back[0].second.crop, 1e-4f)
        assertNull(back[1].second.crop)
        // an old manifest line (no crop field) still reads
        assertEquals(1, ScanProjects.decode("0|a.jpg|AUTO|0.0|1.0|0|false||false\n").second.size)
    }

    @Test fun croppedPagesAreDecodedLarger() {
        assertEquals(2400, ScanImage.loadDim(2400, null))
        // a page covering 70 % of the photo: decoded at 2400 / 0.7, capped at 1.5×
        assertEquals(3428, ScanImage.loadDim(2400, floatArrayOf(0.1f, 0.8f, 0.8f, 0.1f, 0.1f, 0.1f, 0.6f, 0.6f)))
        assertEquals(3600, ScanImage.loadDim(2400, floatArrayOf(0.4f, 0.6f, 0.6f, 0.4f, 0.4f, 0.4f, 0.6f, 0.6f)))
    }
}
