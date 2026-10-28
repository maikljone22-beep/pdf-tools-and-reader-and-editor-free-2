package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert: custom page size for images → PDF (row 27). */
class CustomPageSizeTest {
    @Test fun customSizeKeepsWidthAndHeightAsTyped() {
        val w = Images6.mmToPt(100f); val h = Images6.mmToPt(150f)
        val p = Images6.place(4000, 3000, PageSize6.CUSTOM, Orient6.AUTO, Fit6.CONTAIN, 0f, customW = w, customH = h)
        assertEquals(w, p.pageW, 0.01f); assertEquals(h, p.pageH, 0.01f)       // a landscape picture does not turn the page
        assertEquals(w, p.w, 0.01f)                                              // fitted to the width
        val l = Images6.place(4000, 3000, PageSize6.CUSTOM, Orient6.LANDSCAPE, Fit6.CONTAIN, 0f, customW = w, customH = h)
        assertEquals(h, l.pageW, 0.01f)
        // limits: 1 in .. 200 in
        assertEquals(72f, Images6.place(10, 10, PageSize6.CUSTOM, Orient6.PORTRAIT, Fit6.CONTAIN, 0f, customW = 5f, customH = 5f).pageW, 0.01f)
        assertEquals(100f, Images6.ptToMm(Images6.mmToPt(100f)), 0.001f)
    }
}
