package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FieldNavigationTest {
    // boxes in display space: y up, top > bottom
    private fun item(id: String, page: Int, l: Float, t: Float, r: Float, b: Float, idx: Int = 0, stop: Boolean = true) =
        NavItem(id, page, Box5(l, t, r, b), idx, stop)

    private val page0 = listOf(
        item("name", 0, 50f, 700f, 250f, 680f, 3),
        item("surname", 0, 300f, 702f, 500f, 682f, 1),   // same row, slightly higher
        item("address", 0, 50f, 650f, 500f, 630f, 0),
        item("city", 0, 50f, 600f, 200f, 580f, 2),
        item("zip", 0, 300f, 598f, 400f, 578f, 4),
    )

    @Test fun rowsLeftToRight() {
        assertEquals(listOf("name", "surname", "address", "city", "zip"), FieldNavigation.order(page0))
    }

    @Test fun rowsRightToLeftOnArabicPages() {
        assertEquals(listOf("surname", "name", "address", "zip", "city"), FieldNavigation.order(page0, rtl = { true }))
    }

    @Test fun columnsWithTabsC() {
        assertEquals(listOf("name", "address", "city", "surname", "zip"), FieldNavigation.order(page0, tabs = { "C" }))
    }

    @Test fun pagesInOrderAndNonStopsSkipped() {
        val items = listOf(item("p1", 1, 50f, 700f, 100f, 680f), item("calc", 0, 50f, 500f, 100f, 480f, stop = false)) + page0
        val o = FieldNavigation.order(items)
        assertEquals("p1", o.last())
        assertFalse(o.contains("calc"))
    }

    @Test fun stepWrapsAndStartsAtEnds() {
        val o = listOf("a", "b", "c")
        assertEquals("b", FieldNavigation.step(o, "a", 1))
        assertEquals("a", FieldNavigation.step(o, "c", 1))
        assertEquals("c", FieldNavigation.step(o, "a", -1))
        assertEquals("a", FieldNavigation.step(o, null, 1))
        assertEquals("c", FieldNavigation.step(o, "zz", -1))
        assertNull(FieldNavigation.step(emptyList(), "a", 1))
        assertEquals(2, FieldNavigation.position(o, "b"))
        assertEquals(0, FieldNavigation.position(o, null))
    }

    @Test fun pageSpaceRotation() {
        val w = 595f; val h = 842f
        assertEquals(Box5(10f, 832f, 110f, 812f), PageSpace.rectToDisplay(10f, 812f, 110f, 832f, 0f, 0f, w, h, 0))
        // rotated 90 clockwise: the displayed page is h wide and w tall; the top-left corner goes to the top-right
        assertEquals(Box5(812f, 585f, 832f, 485f), PageSpace.rectToDisplay(10f, 812f, 110f, 832f, 0f, 0f, w, h, 90))
        // the crop-box origin is removed
        assertEquals(Box5(0f, 20f, 100f, 0f), PageSpace.rectToDisplay(50f, 50f, 150f, 70f, 50f, 50f, 300f, 300f, 0))
        assertEquals(Box5(585f, 842f, 595f, 832f), PageSpace.rectToDisplay(0f, 0f, 10f, 10f, 0f, 0f, w, h, 180))
        assertEquals(Box5(832f, 10f, 842f, 0f), PageSpace.rectToDisplay(0f, 0f, 10f, 10f, 0f, 0f, w, h, 270))
    }
}
