package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert-2: web page on one single long page (row 93). */
class WebLongPageTest {
    @Test fun paperHeightFollowsTheContent() {
        // A4 width 8270 mils, 500 margins: printable 7270; content 4000 css px tall for 980 css px wide
        val h = WebCss.longPageMils(8270, 500, 4000, 980f)
        assertEquals((7270 * (4000 / 980f) * 1.03f + 1000).toInt(), h)
        assertEquals(200_000, WebCss.longPageMils(8270, 500, 400_000, 980f))     // capped at 200 in
        assertEquals(8270, WebCss.longPageMils(8270, 500, 0, 980f))
        assertEquals(4135, WebCss.longPageMils(8270, 500, 10, 980f))            // never shorter than half the width
    }
}
