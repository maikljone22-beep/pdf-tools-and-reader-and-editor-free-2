package com.smartdocs.editor.core.billing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures were signed by ai-proxy/license/worker.js (Node, WebCrypto Ed25519) with a throw-away key pair. */
class LicenseTokenTest {
    private val pub = "hkm/eU2jj3Oll7PmwMIJ+ch9NH3X56ZiLtuW9m5Kyxg="
    private val yearToken = "eyJ2IjoxLCJhY3RpdmUiOnRydWUsImlhdCI6MTc5Mjk4Mzg5ODg1MywiZXhwIjoxNzkzODQ3ODk4ODUzLCJsaWQiOiJjYWNhNDEyNGM1Zjg5MGRjZDQ3MmVjNTBmIiwia2luZCI6ImNvZGUiLCJwbGFuIjoieWVhciIsInByb2R1Y3QiOiJjb2RlOnllYXIiLCJkZXYiOiJhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYSIsInVudGlsIjoxNzk1NjUxMTk5OTk5LCJjayI6ImNvZGU6YWNhNDEyNGM1Zjg5MGRjZDQ3MmVjNTBmZTUzMjJjMGUifQ.GoN0wh0pnUmclpUy7l8HRwvYDaDorzijk-oQGi8knz4VjG3EYlCV30Clja2PQ7teWP8I0pSXen3ntGIVMi4nCA"
    private val lifeToken = "eyJ2IjoxLCJhY3RpdmUiOnRydWUsImlhdCI6MTc5Mjk4MzkwMDI1OCwiZXhwIjoxNzkzODQ3OTAwMjU4LCJsaWQiOiJjM2NhMGQ2ZTRiMTcwNjRmMmViNWUxN2RjIiwia2luZCI6ImNvZGUiLCJwbGFuIjoibGlmZXRpbWUiLCJwcm9kdWN0IjoiY29kZTpsaWZldGltZSIsImRldiI6ImFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIiwidW50aWwiOjAsImNrIjoiY29kZTozY2EwZDZlNGIxNzA2NGYyZWI1ZTE3ZGMyMzllYWM1OSJ9.5O5iGjAqor_ONYcmeD7WeK8uKX2od5ZHX8ZwJsaqDytxQf2I4vknycMYI27sQbulBafxvm2v6RA-61EN4dTvDw"
    private val device = "a".repeat(32)
    private val key get() = LicenseToken.publicKey(pub)!!

    @Test fun publicKeyDecodesBothAlphabets() {
        assertEquals(32, key.size)
        assertArrayEquals(key, LicenseToken.publicKey(pub.replace('+', '-').replace('/', '_').trimEnd('=')))
        assertNull(LicenseToken.publicKey("abc"))
    }

    @Test fun workerTokenVerifies() {
        val c = LicenseToken.verify(yearToken, key)
        assertNotNull(c)
        assertEquals("code", c!!.kind)
        assertEquals("year", c.plan)
        assertEquals(device, c.device)
        assertTrue(c.expiresAt > c.issuedAt)
        assertEquals(0L, LicenseToken.verify(lifeToken, key)!!.until)
    }

    @Test fun tamperedTokenRejected() {
        val i = 20
        val flipped = yearToken.substring(0, i) + (if (yearToken[i] == 'A') 'B' else 'A') + yearToken.substring(i + 1)
        assertNull(LicenseToken.verify(flipped, key))
        val sigFlip = yearToken.dropLast(2) + (if (yearToken[yearToken.length - 2] == 'A') "BA" else "AA")
        assertNull(LicenseToken.verify(sigFlip, key))
        assertNull(LicenseToken.verify(yearToken.substringBefore('.'), key))
        val otherKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(LicenseToken.verify(yearToken, otherKey))
    }

    @Test fun graceRules() {
        val c = LicenseToken.verify(yearToken, key)!!
        val t = c.issuedAt
        assertTrue(LicenseRules.signedActive(c, device, t, t + 1000))
        assertFalse("other device", LicenseRules.signedActive(c, "b".repeat(32), t, t + 1000))
        assertFalse("after signed exp", LicenseRules.signedActive(c, device, c.expiresAt, c.expiresAt + 1))
        assertFalse("7 days without check", LicenseRules.signedActive(c, device, t, t + LicenseRules.CHECK_GRACE_MS))
        assertTrue(LicenseRules.signedActive(c, device, t, t + LicenseRules.CHECK_GRACE_MS - 1))
        assertFalse("clock moved back", LicenseRules.signedActive(c, device, t, t - 3 * LicenseRules.DAY))
        val ended = c.copy(until = t + 5000)
        assertFalse("paid period ended", LicenseRules.signedActive(ended, device, t, t + 6000))
        assertFalse(LicenseRules.signedActive(c.copy(active = false), device, t, t + 1))
        assertFalse(LicenseRules.signedActive(null, device, t, t))
    }

    @Test fun provisionalAndDue() {
        val now = 1_000_000_000_000L
        assertTrue(LicenseRules.provisionalActive(now, now + LicenseRules.PROVISIONAL_MS - 1))
        assertFalse(LicenseRules.provisionalActive(now, now + LicenseRules.PROVISIONAL_MS))
        assertFalse(LicenseRules.provisionalActive(0, now))
        assertTrue(LicenseRules.checkDue(now, now + LicenseRules.CHECK_INTERVAL_MS))
        assertFalse(LicenseRules.checkDue(now, now + 1000))
        assertTrue("clock before last check", LicenseRules.checkDue(now, now - 1000))
    }

    @Test fun codeFormatRoundTrip() {
        val bytes = ByteArray(15) { (it * 37 + 5).toByte() }
        val code = CodeFormat.encode(bytes)
        assertEquals(29, code.length)
        assertArrayEquals(bytes, CodeFormat.decode(code))
        assertArrayEquals(bytes, CodeFormat.decode(code.lowercase().replace("-", " ")))
        assertEquals("011VV", CodeFormat.normalize("o-I l uv"))
        assertNull(CodeFormat.decode("ABC"))
        assertNull(CodeFormat.decode("!".repeat(24)))
    }

    @Test fun pythonGeneratedCodeDecodes() {
        // produced by the MODULE_acct Python generator (plan 2 = year)
        val b = CodeFormat.decode("098J-XTQ6-3DD4-SD24-WZP4-4G62")!!
        assertEquals(2, b[0].toInt())
    }
}
