package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** audit 5 P-2 / P-5 (advanced protect): Arabic passwords need AES-256; the owner password is never the open password. */
class ProtectUnicodeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun plain(name: String = "plain.pdf"): File = tmp.newFile(name).also { f -> PDDocument().use { it.addPage(PDPage()); it.save(f) } }

    @Test fun arabicPasswordWorksWithAes256() {
        val out = File(tmp.root, "ar.pdf")
        runBlocking { ProtectAdvanced.protect(plain(), out, null, ProtectOptions(openPassword = "سرّي", algo = EncAlgo.AES_256, permissions = Permissions(copy = false))) }
        PDDocument.load(out, "سرّي").use { assertTrue(it.isEncrypted); assertEquals(256, it.encryption.length) }
        try { PDDocument.load(out, "????").close(); fail("'????' opened an Arabic-password file") } catch (e: InvalidPasswordException) {}
    }

    @Test fun nonLatinPasswordIsRefusedForRevisionFourAndBelow() {
        for (algo in listOf(EncAlgo.AES_128, EncAlgo.RC4_128, EncAlgo.RC4_40)) {
            val o = ProtectOptions(openPassword = "سرّي", algo = algo)
            assertFalse(ProtectAdvanced.passwordsFit(o))
            try { runBlocking { ProtectAdvanced.protect(plain("p-$algo.pdf"), File(tmp.root, "o-$algo.pdf"), null, o) }; fail("$algo accepted an Arabic password") }
            catch (e: NonLatinPasswordException) {}
        }
        assertTrue(ProtectAdvanced.passwordsFit(ProtectOptions(openPassword = "café", algo = EncAlgo.AES_128)))
    }

    @Test fun ownerNeverEqualsOpen() {
        val o = ProtectOptions(openPassword = "same", ownerPassword = "same", permissions = Permissions(modify = false))
        assertNotEquals("same", ProtectAdvanced.ownerFor(o))
        assertNotEquals("", ProtectAdvanced.ownerFor(o.copy(ownerPassword = "")))
        assertEquals("other", ProtectAdvanced.ownerFor(o.copy(ownerPassword = "other")))
        val out = File(tmp.root, "same.pdf")
        val owner = runBlocking { ProtectAdvanced.protect(plain(), out, null, o) }
        assertNotEquals("same", owner)
        // the open password gives no owner rights, so the restriction holds
        PDDocument.load(out, "same").use { assertFalse(it.currentAccessPermission.isOwnerPermission); assertFalse(it.currentAccessPermission.canModify()) }
        PDDocument.load(out, owner).use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
    }
}
