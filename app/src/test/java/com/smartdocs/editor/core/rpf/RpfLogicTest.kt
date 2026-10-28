package com.smartdocs.editor.core.rpf

import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.reader.view.PageSpread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocReadingTest {
    @Test fun roundTrip() {
        val d = DocReading(order = "raw", mode = "visible", layout = "FACING", zoom = 1.5f, fit = "page", direction = "rtl")
        assertEquals(d, DocReading.decode(d.encode()))
    }

    @Test fun defaultsEncodeEmptyAndBadValuesAreDropped() {
        assertEquals("", DocReading().encode())
        assertTrue(DocReading.decode("").isDefault)
        assertEquals(DocReading(), DocReading.decode("o=sideways;z=999;d=up;l=bad layout;junk"))
    }

    @Test fun overrides() {
        assertEquals("infer", DocReading.orderOf(DocReading(), "infer"))
        assertEquals("raw", DocReading.orderOf(DocReading(order = "raw"), "infer"))
        assertEquals(true, DocReading.rtlOf(DocReading(direction = "rtl")))
        assertEquals(false, DocReading.rtlOf(DocReading(direction = "ltr")))
        assertNull(DocReading.rtlOf(DocReading()))
        assertEquals(4, DocReading.readLimit(DocReading(mode = "visible"), 3, 10))
        assertEquals(10, DocReading.readLimit(DocReading(), 3, 10))
        assertEquals(10, DocReading.readLimit(DocReading(mode = "visible"), 9, 10))
    }
}

class TabGroupOpsTest {
    private val a = "file:///a.pdf"; private val b = "file:///b.pdf"; private val c = "file:///c.pdf"

    @Test fun createAssignAndPrune() {
        var g = TabGroupOps.create(emptyList(), "Work", listOf(a, b))
        assertEquals(1, g.size); assertEquals(listOf(a, b), g[0].uris)
        g = TabGroupOps.create(g, "Home", listOf(b))
        assertEquals(listOf(a), g.first { it.name == "Work" }.uris)
        g = TabGroupOps.assign(g, g.first { it.name == "Home" }.id, a)
        // Work lost its last member and disappears
        assertEquals(listOf("Home"), g.map { it.name })
        assertEquals(listOf(b, a), g[0].uris)
        g = TabGroupOps.unassign(TabGroupOps.unassign(g, a), b)
        assertTrue(g.isEmpty())
    }

    @Test fun closedGroupsKeepTheirTabs() {
        var g = TabGroupOps.create(emptyList(), "W", listOf(a, b))
        val id = g[0].id
        g = TabGroupOps.setClosed(g, id, true)
        g = TabGroupOps.onTabClosed(g, a)
        assertEquals(listOf(a, b), g[0].uris)
        assertNull(TabGroupOps.groupOf(g, a))
        g = TabGroupOps.setClosed(g, id, false)
        g = TabGroupOps.onTabClosed(g, a)
        assertEquals(listOf(b), g[0].uris)
    }

    @Test fun stripOrderCollapseAndWheel() {
        var g = TabGroupOps.create(emptyList(), "W", listOf(b, c))
        val e = TabGroupOps.stripEntries(g, listOf(a, b, c), a)
        assertEquals(listOf(StripEntry.Header(g[0], 2), StripEntry.Tab(b, g[0].id), StripEntry.Tab(c, g[0].id), StripEntry.Tab(a, null)), e)
        assertEquals(c, TabGroupOps.neighbour(e, b, 1))
        assertEquals(b, TabGroupOps.neighbour(e, a, 1))      // wraps
        assertEquals(c, TabGroupOps.neighbour(e, a, -1))
        g = TabGroupOps.toggleCollapsed(g, g[0].id)
        val col = TabGroupOps.stripEntries(g, listOf(a, b, c), c)
        // the current document keeps its tab inside a collapsed group
        assertEquals(listOf(StripEntry.Header(g[0], 2), StripEntry.Tab(c, g[0].id), StripEntry.Tab(a, null)), col)
        assertNull(TabGroupOps.neighbour(listOf(StripEntry.Tab(a, null)), a, 1))
    }
}

class ViewMathTest {
    private fun eq(e: Frac, f: Frac) { assertArrayEquals(floatArrayOf(e.left, e.top, e.right, e.bottom), floatArrayOf(f.left, f.top, f.right, f.bottom), 1e-5f) }

    @Test fun unrotate() {
        val top = Frac(0f, 0f, 1f, 0.25f)        // top quarter of the page as shown
        eq(top, ViewMath.unrotate(top, 0))
        eq(Frac(0f, 0f, 0.25f, 1f), ViewMath.unrotate(top, 90))      // shown 90° clockwise: the top was the left edge
        eq(Frac(0f, 0.75f, 1f, 1f), ViewMath.unrotate(top, 180))
        eq(Frac(0.75f, 0f, 1f, 1f), ViewMath.unrotate(top, 270))
        eq(Frac(0.75f, 0f, 1f, 1f), ViewMath.unrotate(top, -90))
    }

    @Test fun userSpaceAndVisible() {
        val u = ViewMath.toUserSpace(Frac(0f, 0f, 0.5f, 0.5f), 10f, 20f, 200f, 400f)
        assertArrayEquals(floatArrayOf(10f, 220f, 110f, 420f), u, 1e-4f)
        eq(Frac(0.5f, 0f, 1f, 0.5f), ViewMath.visibleFrac(-100f, 0f, 100f, 400f, 300f, 200f)!!)
        assertNull(ViewMath.visibleFrac(400f, 0f, 500f, 100f, 300f, 200f))
    }

    @Test fun deepWindowStaysOnThePage() {
        val (c, f) = ViewMath.deepWindow(100f, 100f, 10f, 0f, 1f, 200f, 100f)
        assertEquals(0.1f, c.first, 1e-5f); assertEquals(0.95f, c.second, 1e-5f)
        eq(Frac(0f, 0.9f, 0.2f, 1f), f)
    }

    @Test fun pageSize() {
        assertEquals("210 × 297 mm", ViewMath.pageSizeLabel(595.28f, 841.89f, "mm"))
        assertEquals("8.5 × 11 in", ViewMath.pageSizeLabel(612f, 792f, "in"))
        assertEquals("21 × 29.7 cm", ViewMath.pageSizeLabel(595.28f, 841.89f, "cm"))
        assertEquals("612 × 792 pt", ViewMath.pageSizeLabel(612f, 792f, "pt"))
    }
}

class PageSpreadThreeUpTest {
    @Test fun facingThree() {
        val s = PageSpread(ViewMode.FACING, 3)
        assertEquals(3, s.perItem)
        assertEquals(3, s.itemCount(7))
        assertEquals(listOf(6), s.pages(2, 7))
        assertEquals(1, s.pageToItem(5))
        assertEquals(3, s.step(0, 1, 7))
    }

    @Test fun coverThree() {
        val s = PageSpread(ViewMode.COVER, 3)
        assertEquals(listOf(0), s.pages(0, 8))
        assertEquals(listOf(1, 2, 3), s.pages(1, 8))
        assertEquals(listOf(4, 5, 6), s.pages(2, 8))
        assertEquals(listOf(7), s.pages(3, 8))
        assertEquals(4, s.itemCount(8))
        assertEquals(2, s.pageToItem(6))
    }

    @Test fun twoUpUnchanged() {
        val s = PageSpread(ViewMode.COVER)
        assertEquals(1 + 9 / 2, s.itemCount(9))
        assertEquals(listOf(1, 2), s.pages(1, 9))
    }
}
