package com.smartdocs.editor.core.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage

class ProtectAdvancedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rc4With40BitKey() {
        val src = tmp.newFile("plain.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(src) }
        val out = java.io.File(tmp.root, "rc4-40.pdf")
        kotlinx.coroutines.runBlocking { ProtectAdvanced.protect(src, out, null, ProtectOptions(openPassword = "open", ownerPassword = "owner", algo = EncAlgo.RC4_40)) }
        PDDocument.load(out, "open").use { d ->
            assertTrue(d.isEncrypted)
            assertEquals(40, d.encryption.length)
        }
        try { PDDocument.load(out).close(); org.junit.Assert.fail("opened without password") } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {}
    }

    @Test fun rc4ScopesNeedAes() {
        assertFalse(EncAlgo.RC4_40.aes)
        assertEquals(40, EncAlgo.RC4_40.keyLength)
    }
    @Test fun printLevels() {
        val p = Permissions()
        assertEquals(PrintLevel.HIGH, p.printLevel)
        assertEquals(PrintLevel.LOW, p.withPrint(PrintLevel.LOW).printLevel)
        assertEquals(PrintLevel.NONE, p.withPrint(PrintLevel.NONE).printLevel)
        val low = ProtectAdvanced.accessPermission(p.withPrint(PrintLevel.LOW))
        assertTrue(low.canPrint()); assertFalse(low.canPrintFaithful())
    }

    @Test fun changePresetsRoundTrip() {
        for (l in ChangeLevel.values()) assertEquals(l, Permissions().withChanges(l).changeLevel)
        assertNull(Permissions().withChanges(ChangeLevel.NONE).copy(modify = true).changeLevel)
    }

    @Test fun bitsMapToAccessPermission() {
        val ap = ProtectAdvanced.accessPermission(Permissions(copy = false, accessibility = true).withChanges(ChangeLevel.FORMS))
        assertFalse(ap.canExtractContent()); assertTrue(ap.canExtractForAccessibility())
        assertTrue(ap.canFillInForm()); assertFalse(ap.canModify()); assertFalse(ap.canModifyAnnotations()); assertFalse(ap.canAssembleDocument())
    }

    @Test fun randomOwnerPassword() {
        val a = ProtectAdvanced.randomPassword(); val b = ProtectAdvanced.randomPassword()
        assertEquals(24, a.length); assertTrue(a != b)
    }
}
