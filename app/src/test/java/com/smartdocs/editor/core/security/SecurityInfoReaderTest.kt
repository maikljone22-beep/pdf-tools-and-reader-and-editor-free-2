package com.smartdocs.editor.core.security

import com.smartdocs.editor.core.redact.EncAlgo
import com.smartdocs.editor.core.redact.EncScope
import com.smartdocs.editor.core.redact.ProtectPrefills
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Rows 1-3: security properties read from real encrypted files, the restrictions they imply and the form pre-fill. */
class SecurityInfoReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun protectedFile(bits: Int, aes: Boolean, perms: AccessPermission): File {
        val f = tmp.newFile("p$bits$aes.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            d.protect(StandardProtectionPolicy("owner-pw", "user-pw", perms).apply { encryptionKeyLength = bits; setPreferAES(aes) })
            d.save(f)
        }
        return f
    }

    private fun restricted() = AccessPermission().apply {
        setCanPrint(true); setCanPrintFaithful(false); setCanModify(false); setCanExtractContent(false)
        setCanModifyAnnotations(false); setCanFillInForm(true); setCanAssembleDocument(false); setCanExtractForAccessibility(true)
    }

    @Test fun aes128OpenedWithTheUserPassword() {
        val f = protectedFile(128, true, restricted())
        val info = PDDocument.load(f, "user-pw").use { SecurityInfoReader.of(it) }
        assertEquals(SecMethod.PASSWORD, info.method)
        assertEquals("AES", info.algorithm); assertEquals(128, info.keyLength); assertEquals(4, info.version); assertEquals(4, info.revision)
        assertTrue(info.encryptMetadata)
        assertFalse(info.ownerAccess)
        val a = info.allowed
        assertTrue(a.getValue(Perm.PRINT)); assertFalse(a.getValue(Perm.PRINT_HIGH)); assertFalse(a.getValue(Perm.COPY))
        assertTrue(a.getValue(Perm.FILL_FORMS)); assertFalse(a.getValue(Perm.ANNOTATE)); assertFalse(a.getValue(Perm.ASSEMBLE))
        assertEquals(Block.AUTHOR, ReaderRestrictions.check(info, false, DocAction.COPY))
        assertNull(ReaderRestrictions.check(info, false, DocAction.FILL_FORMS))
        assertTrue(SecurityInfoReader.mentionsEncrypt(f))
        // Change Settings pre-fill: AES-128, the same permissions, restrictions on
        val pre = ProtectPrefills.from(info)!!
        assertEquals(EncAlgo.AES_128, pre.algo); assertTrue(pre.restrict); assertEquals(EncScope.ALL, pre.scope)
        assertFalse(pre.permissions.copy); assertTrue(pre.permissions.fillForms); assertTrue(pre.permissions.print); assertFalse(pre.permissions.printHigh)
    }

    @Test fun ownerPasswordGivesFullAccess() {
        val f = protectedFile(256, true, restricted())
        val info = PDDocument.load(f, "owner-pw").use { SecurityInfoReader.of(it) }
        assertTrue(info.ownerAccess)
        assertEquals(256, info.keyLength); assertEquals(5, info.version)
        DocAction.values().forEach { assertNull(ReaderRestrictions.check(info, false, it)) }
        // the author's settings are still shown
        assertFalse(info.allowed.getValue(Perm.COPY))
        assertEquals(EncAlgo.AES_256, ProtectPrefills.from(info)!!.algo)
    }

    @Test fun rc4Forty() {
        val f = protectedFile(40, false, AccessPermission())
        val info = PDDocument.load(f, "user-pw").use { SecurityInfoReader.of(it) }
        assertEquals("RC4", info.algorithm); assertEquals(40, info.keyLength); assertEquals(1, info.version)
        val pre = ProtectPrefills.from(info)!!
        assertEquals(EncAlgo.RC4_40, pre.algo); assertFalse(pre.restrict)
    }

    @Test fun plainFile() {
        val f = tmp.newFile("plain.pdf")
        PDDocument().use { d -> d.addPage(PDPage()); d.save(f) }
        val info = PDDocument.load(f).use { SecurityInfoReader.of(it) }
        assertEquals(SecMethod.NONE, info.method)
        assertFalse(SecurityInfoReader.mentionsEncrypt(f))
        assertNull(ProtectPrefills.from(info))
    }
}
