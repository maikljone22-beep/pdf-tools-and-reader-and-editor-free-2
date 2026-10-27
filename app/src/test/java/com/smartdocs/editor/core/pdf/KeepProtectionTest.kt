package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit DI-28 / AN-29: a full save keeps the key length, cipher and permissions, and never hands out the owner password. */
class KeepProtectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun restricted() = AccessPermission().apply { setCanPrint(false); setCanModify(false); setCanExtractContent(false) }

    private fun encrypted(bits: Int, aes: Boolean, user: String, owner: String = "owner-secret"): File {
        val f = tmp.newFile("enc$bits${if (aes) "aes" else "rc4"}${user.length}.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            d.protect(StandardProtectionPolicy(owner, user, restricted()).apply { encryptionKeyLength = bits; setPreferAES(aes) })
            d.save(f)
        }
        return f
    }

    private fun roundTrip(src: File, password: String?): File {
        val out = File(tmp.root, "out-${System.nanoTime()}.pdf")
        PdfOps.load(src, password).use { it.save(out) }
        return out
    }

    private fun assertSameProtection(src: File, out: File, openWith: String) {
        val a = PDDocument.load(src, openWith).use { Triple(it.encryption.length, it.encryption.permissions, it.encryption.stdCryptFilterDictionary?.cryptFilterMethod?.name) }
        val b = PDDocument.load(out, openWith).use { Triple(it.encryption.length, it.encryption.permissions, it.encryption.stdCryptFilterDictionary?.cryptFilterMethod?.name) }
        assertEquals("key length", a.first, b.first)
        assertEquals("cipher", a.third, b.third)
        val pa = AccessPermission(a.second); val pb = AccessPermission(b.second)
        assertEquals(pa.canPrint(), pb.canPrint()); assertEquals(pa.canModify(), pb.canModify())
        assertEquals(pa.canExtractContent(), pb.canExtractContent()); assertEquals(pa.canModifyAnnotations(), pb.canModifyAnnotations())
        assertEquals(pa.canFillInForm(), pb.canFillInForm()); assertEquals(pa.canAssembleDocument(), pb.canAssembleDocument())
    }

    @Test fun openedWithUserPassword_keepsKeyLengthAndPermissions_ownerStaysSecret() {
        for ((bits, aes) in listOf(256 to true, 128 to true, 128 to false, 40 to false)) {
            val src = encrypted(bits, aes, "user")
            val out = roundTrip(src, "user")
            assertSameProtection(src, out, "user")
            PDDocument.load(out, "user").use { assertFalse("$bits: user password must not grant owner rights", it.currentAccessPermission.isOwnerPermission) }
        }
    }

    @Test fun permissionsOnlyFile_doesNotGetAnEmptyOwnerPassword() {
        val src = encrypted(128, true, "")
        val out = roundTrip(src, null)
        assertSameProtection(src, out, "")
        PDDocument.load(out).use { assertFalse(it.currentAccessPermission.isOwnerPermission); assertFalse(it.currentAccessPermission.canPrint()) }
    }

    @Test fun openedWithOwnerPassword_keepsBothPasswords() {
        for (bits in listOf(128, 40)) {
            val src = encrypted(bits, bits == 128, "user")
            val out = roundTrip(src, "owner-secret")
            assertSameProtection(src, out, "owner-secret")
            PDDocument.load(out, "owner-secret").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
            PDDocument.load(out, "user").use { assertFalse(it.currentAccessPermission.isOwnerPermission); assertFalse(it.currentAccessPermission.canPrint()) }
        }
    }

    @Test fun aes256OpenedAsOwnerWithEmptyUserPassword_staysOpenable() {
        val src = encrypted(256, true, "")
        val out = roundTrip(src, "owner-secret")
        assertSameProtection(src, out, "owner-secret")
        PDDocument.load(out).use { assertFalse(it.currentAccessPermission.isOwnerPermission) }
        try { PDDocument.load(out, "wrong").close(); fail("wrong password must fail") } catch (_: Exception) {}
    }
}
