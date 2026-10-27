package com.smartdocs.editor.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** nitro-quicksign: signature sidecar metadata (lock + recently used). */
class SignatureMetaTest {
    @Test fun lockHashVerifies() {
        val (salt, hash, iter) = SignatureLock.create("1234".toCharArray(), iterations = 1000)
        val m = SignatureMeta(salt = salt, hash = hash, iterations = iter)
        assertTrue(m.locked)
        assertTrue(SignatureLock.matches(m, "1234".toCharArray()))
        assertFalse(SignatureLock.matches(m, "1235".toCharArray()))
        assertTrue(SignatureLock.matches(SignatureMeta(), "anything".toCharArray()))   // unlocked
    }

    @Test fun saltIsRandom() {
        assertNotEquals(SignatureLock.create("pw12".toCharArray(), 1000).second, SignatureLock.create("pw12".toCharArray(), 1000).second)
    }

    @Test fun jsonRoundTripAndRecentOrder() {
        val (salt, hash, iter) = SignatureLock.create("abcd".toCharArray(), iterations = 1000)
        val map = mapOf(
            "/s/a.png" to SignatureMeta(lastUsed = 10),
            "/s/b.png" to SignatureMeta(lastUsed = 30, salt = salt, hash = hash, iterations = iter),
            "/s/c.png" to SignatureMeta(),
        )
        val back = SignatureMetaStore.decode(SignatureMetaStore.encode(map))
        assertEquals(map - "/s/c.png", back)   // empty entries are not stored
        assertEquals(listOf("/s/b.png", "/s/a.png"), SignatureMetaStore.recent(back, listOf("/s/a.png", "/s/b.png", "/s/c.png")))
        assertEquals(listOf("/s/b.png"), SignatureMetaStore.recent(back, listOf("/s/a.png", "/s/b.png"), max = 1))
        assertEquals(emptyMap<String, SignatureMeta>(), SignatureMetaStore.decode("[broken"))
    }

    @Test fun partialLockIgnored() {
        val m = SignatureMetaStore.decode("""{"/x.png":{"salt":"00","iter":5}}""")
        assertFalse(m.getValue("/x.png").locked)
    }
}
