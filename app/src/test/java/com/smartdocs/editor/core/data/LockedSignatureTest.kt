package com.smartdocs.editor.core.data

import com.smartdocs.editor.core.share5.AppDataBundle
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** nitro-fillsign2 (pdf-ad): locked signatures leave the device only encrypted under their PIN, with their lock. */
class LockedSignatureTest {
    @get:Rule val tmp = TemporaryFolder()
    private val png = ByteArray(300) { (it * 7).toByte() }

    @Test fun encryptDecryptRoundTrip() {
        val blob = LockedSignatureCrypto.encrypt(png, "1234".toCharArray(), iterations = 1000)
        assertTrue(LockedSignatureCrypto.isBlob(blob))
        assertFalse(LockedSignatureCrypto.isBlob(png))
        assertArrayEquals(png, LockedSignatureCrypto.decrypt(blob, "1234".toCharArray()))
        assertNull(LockedSignatureCrypto.decrypt(blob, "4321".toCharArray()))
        // the image is not in the blob in the clear
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(png.copyOfRange(10, 40), Charsets.ISO_8859_1)))
    }

    @Test fun tamperedBlobIsRejected() {
        val blob = LockedSignatureCrypto.encrypt(png, "pass".toCharArray(), iterations = 1000)
        val bad = blob.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertNull(LockedSignatureCrypto.decrypt(bad, "pass".toCharArray()))
        assertNull(LockedSignatureCrypto.decrypt(png, "pass".toCharArray()))
    }

    @Test fun saltAndIvAreRandom() {
        val a = LockedSignatureCrypto.encrypt(png, "pass".toCharArray(), iterations = 1000)
        val b = LockedSignatureCrypto.encrypt(png, "pass".toCharArray(), iterations = 1000)
        assertFalse(a.contentEquals(b))
    }

    @Test fun filesSyncRestoresImageAndRefreshesBlob() {
        val image = tmp.newFile("sig.png").apply { writeBytes(png) }
        LockedSignatureFiles.write(image, "1234".toCharArray())
        assertTrue(LockedSignatureFiles.fresh(image))
        // "imported": only the blob is on the device; unlock brings the image back
        image.delete()
        assertTrue(LockedSignatureFiles.fresh(image))
        LockedSignatureFiles.sync(image, "1234".toCharArray())
        assertArrayEquals(png, image.readBytes())
        // a wrong secret never writes an image
        image.delete()
        LockedSignatureFiles.sync(image, "0000".toCharArray())
        assertFalse(image.exists())
    }

    @Test fun bundleEntryKeepsTheLock() {
        val (salt, hash, iter) = SignatureLock.create("abcd".toCharArray(), iterations = 1000)
        val meta = SignatureMeta(salt = salt, hash = hash, iterations = iter, name = "Mine")
        val o = JSONObject(AppDataBundle.lockedEntry("sig/locked_0.sdlock", true, "ff", meta).toString())
        assertTrue(o.getBoolean("locked")); assertFalse(o.has("used"))
        val back = AppDataBundle.lockOf(o)
        assertNotNull(back)
        assertEquals(meta.salt, back!!.salt); assertEquals(meta.hash, back.hash); assertEquals(iter, back.iterations); assertEquals("Mine", back.name)
        assertTrue(SignatureLock.matches(back, "abcd".toCharArray()))
        // incomplete lock records are rejected (never imported unlocked)
        assertNull(AppDataBundle.lockOf(JSONObject().put("locked", true).put("salt", "00")))
        assertNull(AppDataBundle.lockOf(JSONObject().put("file", "sig/0.png")))
    }
}
