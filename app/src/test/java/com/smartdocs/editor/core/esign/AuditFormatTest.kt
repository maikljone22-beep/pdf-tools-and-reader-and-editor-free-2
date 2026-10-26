package com.smartdocs.editor.core.esign

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AuditFormatTest {
    private val abc = AuditFormat.hex(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()))

    @Test fun hexOfKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
        assertTrue(AuditFormat.isSha256(abc))
        assertFalse(AuditFormat.isSha256(abc.dropLast(1)))
    }

    @Test fun groupedInTwoLines() {
        val g = AuditFormat.grouped(abc)
        assertEquals(2, g.size)
        assertEquals("ba7816bf 8f01cfea 414140de 5dae2223", g[0])
        assertEquals("b00361a3 96177a9c b410ff61 f20015ad", g[1])
    }

    @Test fun groupingAcceptsUppercaseAndSpaces() {
        assertEquals(AuditFormat.grouped(abc), AuditFormat.grouped(AuditFormat.grouped(abc).joinToString("\n").uppercase()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupingRejectsNonHash() { AuditFormat.grouped("xyz") }

    @Test fun matchIgnoresFormatting() {
        assertTrue(AuditFormat.matches(abc, AuditFormat.grouped(abc).joinToString(" \n").uppercase()))
        assertFalse(AuditFormat.matches(abc, abc.replace('a', 'b')))
    }

    @Test fun shortAndUrls() {
        assertEquals("ba7816bf…f20015ad", AuditFormat.short(abc))
        assertEquals("https://x.dev/v/$abc", AuditFormat.verifyUrl("https://x.dev/", abc.uppercase()))
    }

    @Test fun utcTimestamp() { assertEquals("1970-01-02 00:00:01 UTC", AuditFormat.utc(86_401_000L)) }

    @Test fun geometryUnrotated() {
        val g = PageGeom(0f, 0f, 600f, 800f, 0)
        val m = g.uprightMatrix(0.1f, 0.1f, 0.5f, 0.25f)
        assertArrayEquals(floatArrayOf(300f, 0f, 0f, 200f, 60f, 520f), m, 1e-3f)
    }

    @Test fun geometryRotatedPagesKeepImageUpright() {
        for (rot in listOf(90, 180, 270)) {
            val g = PageGeom(10f, 20f, 600f, 800f, rot)
            val m = g.uprightMatrix(0f, 0f, 1f, 1f)
            // image x axis must run along the displayed width, y axis along the displayed height (|det| = area)
            val det = m[0] * m[3] - m[1] * m[2]
            assertEquals(600f * 800f, kotlin.math.abs(det), 1e-1f)
            // corners land inside the crop box
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) -> (m[0] * u + m[2] * v + m[4]) to (m[1] * u + m[3] * v + m[5]) }
            corners.forEach { (x, y) -> assertTrue("rot $rot x=$x", x in 9.9f..610.1f); assertTrue("rot $rot y=$y", y in 19.9f..820.1f) }
        }
        // 90: displayed top-left = unrotated bottom-left
        assertEquals(10f to 20f, PageGeom(10f, 20f, 600f, 800f, 90).toUser(0f, 0f))
        // 270: displayed top-left = unrotated top-right
        assertEquals(610f to 820f, PageGeom(10f, 20f, 600f, 800f, 270).toUser(0f, 0f))
        assertEquals(800f, PageGeom(0f, 0f, 600f, 800f, 90).displayW, 0f)
    }

    @Test fun clampKeepsFieldOnPage() {
        val r = PageGeom.clampField(0.95f, -0.2f, 0.3f, 0.001f)
        assertArrayEquals(floatArrayOf(0.7f, 0f, 0.3f, 0.02f), r, 1e-5f)
    }
}
