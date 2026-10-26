package com.smartdocs.editor.core.pageflip

import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.reader.view.PageSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadMapTest {
    @Test fun sameMappingAsTheReader() {
        val pairs = listOf(PfSpread.SINGLE to ViewMode.SINGLE, PfSpread.TWO to ViewMode.FACING, PfSpread.TWO_COVER to ViewMode.COVER)
        for ((mine, theirs) in pairs) for (count in 0..9) {
            val a = SpreadMap(mine); val b = PageSpread(theirs)
            assertEquals("$mine $count", b.itemCount(count), a.itemCount(count))
            for (p in 0 until count) assertEquals(b.pageToItem(p), a.pageToItem(p))
            for (i in 0 until a.itemCount(count)) {
                assertEquals(b.firstPage(i), a.firstPage(i))
                assertEquals(b.pages(i, count), a.pages(i, count))
            }
        }
    }

    @Test fun sidesLtrRtlAndCover() {
        val two = SpreadMap(PfSpread.TWO)
        assertEquals(listOf(0 to PageSide.LEFT, 1 to PageSide.RIGHT), two.sides(0, 5, rtl = false))
        assertEquals(listOf(0 to PageSide.RIGHT, 1 to PageSide.LEFT), two.sides(0, 5, rtl = true))
        assertEquals(listOf(4 to PageSide.LEFT), two.sides(2, 5, rtl = false))      // lone last page = left-hand page
        val cover = SpreadMap(PfSpread.TWO_COVER)
        assertEquals(listOf(0 to PageSide.RIGHT), cover.sides(0, 6, rtl = false))   // closed book: cover on the right
        assertEquals(listOf(0 to PageSide.LEFT), cover.sides(0, 6, rtl = true))     // Arabic book: cover on the left
        assertEquals(listOf(1 to PageSide.LEFT, 2 to PageSide.RIGHT), cover.sides(1, 6, rtl = false))
        assertEquals(listOf(5 to PageSide.LEFT), cover.sides(3, 6, rtl = false))
        assertEquals(listOf(3 to PageSide.CENTER), SpreadMap(PfSpread.SINGLE).sides(3, 6, rtl = true))
    }

    @Test fun coverTurns() {
        val cover = SpreadMap(PfSpread.TWO_COVER)
        assertTrue(cover.isCoverTurn(0, 6))          // opening
        assertTrue(cover.isCoverTurn(2, 6))          // closing onto the lone back cover (page 5)
        assertFalse(cover.isCoverTurn(1, 6))
        assertFalse(cover.isCoverTurn(2, 7))         // 7 pages: last spread is full, no back cover alone
        assertFalse(SpreadMap(PfSpread.TWO).isCoverTurn(0, 6))
    }
}

class FlipMathTest {
    @Test fun dragDirectionRespectsRtl() {
        assertEquals(1, FlipMath.dragDelta(-50f, 3f, PfAxis.HORIZONTAL, rtl = false))
        assertEquals(-1, FlipMath.dragDelta(50f, 3f, PfAxis.HORIZONTAL, rtl = false))
        assertEquals(1, FlipMath.dragDelta(50f, 3f, PfAxis.HORIZONTAL, rtl = true))
        assertEquals(-1, FlipMath.dragDelta(-50f, 3f, PfAxis.HORIZONTAL, rtl = true))
        assertEquals(1, FlipMath.dragDelta(5f, -60f, PfAxis.VERTICAL, rtl = true))
        assertEquals(-1, FlipMath.dragDelta(5f, 60f, PfAxis.VERTICAL, rtl = false))
        assertTrue(FlipMath.alongAxis(30f, 5f, PfAxis.HORIZONTAL))
        assertFalse(FlipMath.alongAxis(30f, 5f, PfAxis.VERTICAL))
    }

    @Test fun edgeZones() {
        assertEquals(-1, FlipMath.zoneOf(10f, 1000f, 0.16f, rtl = false))
        assertEquals(1, FlipMath.zoneOf(990f, 1000f, 0.16f, rtl = false))
        assertEquals(0, FlipMath.zoneOf(500f, 1000f, 0.16f, rtl = false))
        assertEquals(1, FlipMath.zoneOf(10f, 1000f, 0.16f, rtl = true))      // RTL: next is on the left
        assertEquals(-1, FlipMath.zoneOf(990f, 1000f, 0.16f, rtl = true))
        assertEquals(1f, FlipMath.grabY(0.95f), 0f)
        assertEquals(0f, FlipMath.grabY(0.05f), 0f)
        assertEquals(0.5f, FlipMath.grabY(0.5f), 0f)
    }

    @Test fun commitDecision() {
        assertTrue(FlipMath.shouldCommit(0.6f, 0f))
        assertFalse(FlipMath.shouldCommit(0.4f, 0f))
        assertTrue(FlipMath.shouldCommit(0.1f, 3f))       // quick flick
        assertFalse(FlipMath.shouldCommit(0.8f, -3f))     // flicked back
    }

    @Test fun velocityShortensTheTurn() {
        val slow = FlipMath.durationMs(1f, 0f, 600, 1f)
        val fast = FlipMath.durationMs(1f, 8f, 600, 1f)
        assertEquals(600, slow)
        assertTrue(fast < slow)
        assertTrue(FlipMath.durationMs(0.2f, 0f, 600, 1f) < slow)
        assertTrue(FlipMath.durationMs(1f, 0f, 600, 2f) < slow)
        assertEquals(0, FlipMath.durationMs(1f, 0f, 600, 1f, animatorScale = 0f))
        assertEquals(0, FlipMath.durationMs(1f, 0f, 0, 1f))
        assertTrue(FlipMath.stiffness(2f) > FlipMath.stiffness(1f))
    }

    @Test fun stackLeavesAreStaggered() {
        for (n in 1..4) {
            assertEquals(0f, FlipMath.stackLeaf(0f, 0, n), 0f)
            assertEquals(1f, FlipMath.stackLeaf(1f, n - 1, n), 0.0001f)
            for (i in 1 until n) assertTrue(FlipMath.stackLeaf(0.3f, i, n) <= FlipMath.stackLeaf(0.3f, i - 1, n))
        }
        assertEquals(1, FlipMath.stackSize(1)); assertEquals(4, FlipMath.stackSize(-40))
    }

    @Test fun scrubberRtl() {
        assertEquals(0, FlipMath.pageAt(0f, 10, rtl = false))
        assertEquals(9, FlipMath.pageAt(0f, 10, rtl = true))
        for (p in 0 until 10) for (rtl in listOf(false, true)) assertEquals(p, FlipMath.pageAt(FlipMath.fractionOf(p, 10, rtl), 10, rtl))
        assertEquals(3, FlipMath.rifflePages(75f, 24f))
        assertEquals(0f, FlipMath.stackEdgePx(0, 6f), 0f)
        assertTrue(FlipMath.stackEdgePx(1000, 6f) <= 6f)
    }
}

class BoundedLruTest {
    @Test fun evictsLeastRecentlyUsed() {
        val evicted = ArrayList<String>()
        val lru = BoundedLru<String, Int>(10, { it.toLong() }) { k, _ -> evicted += k }
        lru.put("a", 4); lru.put("b", 4)
        lru["a"]                                    // a is now the most recent
        lru.put("c", 4)
        assertEquals(listOf("b"), evicted)
        assertEquals(8L, lru.bytes)
        assertNull(lru["b"])
        lru.put("big", 50)                          // the newest entry is kept even above the budget
        assertTrue(lru.contains("big"))
        assertEquals(1, lru.size)
    }

    @Test fun replaceRemoveAndClear() {
        val evicted = ArrayList<Pair<String, Int>>()
        val lru = BoundedLru<String, Int>(100, { it.toLong() }) { k, v -> evicted += k to v }
        lru.put("a", 1); lru.put("a", 2)
        assertEquals(listOf("a" to 1), evicted)
        lru.put("p1", 3); lru.put("p9", 3)
        lru.removeIf { it.startsWith("p") && it != "p1" }
        assertEquals(setOf("a", "p1"), lru.keys)
        lru.clear()
        assertEquals(0L, lru.bytes); assertEquals(0, lru.size)
        lru.put("x", 30); lru.maxBytes = 10
        assertEquals(0, lru.size)                   // shrinking the budget trims everything above it
    }
}

class PageFlipPrefsTest {
    @Test fun roundTrip() {
        val p = PageFlipPrefs(layout = PfLayout.PAGED, axis = PfAxis.VERTICAL, spread = PfSpread.TWO_COVER, effect = PfEffect.CUBE,
            direction = PfDirection.RTL, speed = 1.5f, intensity = 0.3f, shadows = false, curvature = 0.9f, thickness = false,
            marginDp = 20f, spring = false, bouncy = true, haptics = false, reducedMotion = true, edgeZones = false, edgeZone = 0.2f, peek = false)
        assertEquals(p, PageFlipPrefs.decode(p.encode()))
        assertEquals(PageFlipPrefs(), PageFlipPrefs.decode(PageFlipPrefs().encode()))
    }

    @Test fun toleratesGarbageAndClamps() {
        assertEquals(PageFlipPrefs(), PageFlipPrefs.decode(null))
        assertEquals(PageFlipPrefs(), PageFlipPrefs.decode("nonsense;;=;effect=WARP;speed=abc"))
        val p = PageFlipPrefs.decode("speed=99;intensity=-3;zone=1;future=1;effect=BOOK")
        assertEquals(PageFlipPrefs.SPEED_MAX, p.speed, 0f)
        assertEquals(0f, p.intensity, 0f)
        assertEquals(0.3f, p.edgeZone, 0f)
        assertEquals(PfEffect.BOOK, p.effect)
    }

    @Test fun effectiveEffect() {
        assertEquals(PfEffect.NONE, PageFlipPrefs(effect = PfEffect.CURL).effectiveEffect(systemAnimationsOff = true))
        assertEquals(PfEffect.FADE, PageFlipPrefs(effect = PfEffect.CUBE, reducedMotion = true).effectiveEffect(false))
        assertEquals(PfEffect.SLIDE, PageFlipPrefs(effect = PfEffect.CURL, axis = PfAxis.VERTICAL).effectiveEffect(false))
        assertEquals(PfEffect.CUBE, PageFlipPrefs(effect = PfEffect.CUBE, axis = PfAxis.VERTICAL).effectiveEffect(false))
    }
}

class ScriptDirectionTest {
    @Test fun detectsArabicAndLatin() {
        assertEquals(true, ScriptDirection.detect("هذا نص عربي طويل بما يكفي للكشف عن الاتجاه"))
        assertEquals(false, ScriptDirection.detect("This is a long enough English sentence for detection"))
        assertNull(ScriptDirection.detect("PDF 2024"))
        // mostly Arabic with some Latin words is still RTL
        val mixed = "التقرير السنوي للشركة PDF Editor مع جداول"
        assertEquals(true, ScriptDirection.detect(mixed, 10))
        assertTrue(ScriptDirection.isRtlChar('א'))
        assertFalse(ScriptDirection.isRtlChar('a'))
    }
}
