package com.smartdocs.editor.core.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** audit 5 OCR-3 / OCR-4: stored OCR words are rescaled between render sizes and words recognised again replace old ones. */
class OcrStoreMergeTest {

    @Test fun rescaleBetweenRenderSizes() {
        // page OCR at 3500 px, zone OCR at 4000 px of the same page (old caps): the old words move with the size
        assertArrayEquals(intArrayOf(400, 800, 800, 1200), OcrStore.rescale(intArrayOf(350, 700, 700, 1050), 3500, 2450, 4000, 2800))
        // same size: unchanged copy
        val b = intArrayOf(1, 2, 3, 4)
        val r = OcrStore.rescale(b, 100, 200, 100, 200)
        assertArrayEquals(b, r)
        r[0] = 9
        assertEquals(1, b[0])
        // a broken stored size is not divided by zero
        assertArrayEquals(intArrayOf(1, 2, 3, 4), OcrStore.rescale(intArrayOf(1, 2, 3, 4), 0, 0, 100, 100))
    }

    @Test fun oldWordsRecognisedAgainAreDropped() {
        val old = listOf(intArrayOf(0, 0, 100, 20), intArrayOf(200, 0, 300, 20), intArrayOf(0, 100, 100, 120))
        // re-OCR of the first two words (slightly different boxes) and nothing at the third
        val new = listOf(intArrayOf(2, 1, 98, 21), intArrayOf(205, 0, 300, 22))
        assertEquals(listOf(2), OcrStore.keepOld(old, new))
        // a zone that only touches a word keeps it
        assertEquals(listOf(0, 1, 2), OcrStore.keepOld(old, listOf(intArrayOf(90, 0, 120, 20))))
        // nothing new: everything stays; an empty box goes
        assertEquals(listOf(0, 1, 2), OcrStore.keepOld(old, emptyList()))
        assertEquals(emptyList<Int>(), OcrStore.keepOld(listOf(intArrayOf(5, 5, 5, 9)), emptyList()))
    }

    @Test fun oneRenderCapForPageAndZone() {
        // US Letter at 300 dpi stays under the cap
        assertEquals(300f / 72f, OcrPipeline.renderScale(612f, 792f), 1e-4f)
        // A3 at 300 dpi is capped: the longer side becomes exactly MAX_RENDER_PX
        val k = OcrPipeline.renderScale(842f, 1191f)
        assertEquals(OcrPipeline.MAX_RENDER_PX, 1191f * k, 0.5f)
        // the batch dpi is honoured below the cap
        assertEquals(200f / 72f, OcrPipeline.renderScale(595f, 842f, 200), 1e-4f)
    }
}
