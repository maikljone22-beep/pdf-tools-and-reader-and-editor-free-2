package com.smartdocs.editor.core.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactMathTest {
    @Test fun keepLastDigits() {
        val t = "Tel 777123456 end"
        val r = RedactMath.keepLast(t, 4..12, 4)!!
        assertEquals("77712", t.substring(r.first, r.last + 1))
        val card = "4111 1111 1111 1234"
        val c = RedactMath.keepLast(card, card.indices, 4)!!
        assertEquals("4111 1111 1111", card.substring(c.first, c.last + 1))
        assertNull(RedactMath.keepLast("1234", 0..3, 4))
        assertEquals(0..3, RedactMath.keepLast("abcd", 0..3, 0))
    }

    @Test fun glyphHitByCentreOrHalfArea() {
        val mark = Box(100f, 100f, 200f, 120f)
        assertTrue(RedactMath.glyphHit(Box(150f, 105f, 156f, 115f), listOf(mark)))
        assertFalse(RedactMath.glyphHit(Box(201f, 105f, 207f, 115f), listOf(mark)))
        // straddling the edge: centre inside → hit; centre outside (40% covered) → kept
        assertTrue(RedactMath.glyphHit(Box(194f, 100f, 204f, 120f), listOf(mark)))
        assertFalse(RedactMath.glyphHit(Box(196f, 100f, 206f, 120f), listOf(mark)))
    }

    @Test fun enclosedObjects() {
        val marks = listOf(Box(0f, 0f, 100f, 100f))
        assertTrue(RedactMath.enclosed(Box(10f, 10f, 90f, 90f), marks))
        assertFalse(RedactMath.enclosed(Box(10f, 10f, 110f, 90f), marks))
        assertTrue(RedactMath.touches(Box(90f, 90f, 110f, 110f), marks))
    }

    @Test fun pageSpecAndParity() {
        assertEquals(listOf(0, 1, 2, 4), RedactMath.pages("1-3, 5", 10))
        assertEquals(listOf(0, 2, 4), RedactMath.pages("", 6, RedactMath.PageParity.ODD))
        assertEquals(listOf(1, 3, 5), RedactMath.pages("", 6, RedactMath.PageParity.EVEN))
        assertEquals(listOf(7, 8, 9), RedactMath.pages("8-", 10))
        assertEquals(emptyList<Int>(), RedactMath.pages("20", 10))
    }

    @Test fun affineMap() {
        // 90° rotation of a 600-wide page: x' = y, y' = 600 - x  (a=0 b=-1 c=1 d=0 e=0 f=600)
        val m = floatArrayOf(0f, -1f, 1f, 0f, 0f, 600f)
        assertEquals(Box(10f, 500f, 30f, 590f), RedactMath.map(m, Box(10f, 10f, 100f, 30f)))
    }

    @Test fun overlaySizing() {
        assertEquals(14.4f, RedactMath.autoFontSize(1f, 1000f, 20f), 0.01f)
        assertTrue(RedactMath.autoFontSize(10f, 50f, 40f) < 5f)
        assertEquals(3, RedactMath.repeatCount(30f, 100f, 5f))
    }
}
