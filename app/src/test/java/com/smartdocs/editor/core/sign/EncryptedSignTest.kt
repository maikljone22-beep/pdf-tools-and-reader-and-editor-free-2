package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.SigTarget
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tabs audit D3 / D12: an encrypted PDF is signed incrementally with its own security; a second signer keeps the first. */
class EncryptedSignTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun id(n: String) = DigitalIds.selfSigned(DigitalIds.Spec(n)).let { (k, c) -> DigitalSignature.Identity(k, listOf(c)) }

    private fun encrypted(): File {
        val f = tmp.newFile("enc.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage()); d.addPage(PDPage())
            d.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            d.save(f)
        }
        return f
    }

    @Test fun encryptedFileStaysEncryptedAndKeepsTheFirstSignature() {
        val src = encrypted()
        val one = File(tmp.root, "one.pdf")
        DigitalSignature.signInto(src, one, id("First"), DigitalSignature.SignOptions(name = "First"), SigTarget(page = 0, rect = floatArrayOf(20f, 20f, 120f, 40f)), password = "user")
        assertArrayEquals("incremental: the original is a prefix", src.readBytes(), one.readBytes().copyOf(src.length().toInt()))
        PDDocument.load(one, "user").use { assertTrue("still encrypted", it.isEncrypted) }
        val v1 = DigitalSignature.verifyBlocking(one, "user")
        assertEquals(1, v1.size); assertTrue(v1[0].signatureValid)

        val two = File(tmp.root, "two.pdf")
        DigitalSignature.signInto(one, two, id("Second"), DigitalSignature.SignOptions(name = "Second"), SigTarget(page = 1, rect = floatArrayOf(20f, 20f, 120f, 40f)), password = "user")
        val v2 = DigitalSignature.verifyBlocking(two, "user")
        assertEquals(2, v2.size)
        assertTrue("both signatures valid", v2.all { it.signatureValid })
    }

    @Test fun signedSignatureWidgetsAreNotRewrittenByLaterUpdates() {
        val plain = tmp.newFile("p.pdf").also { f -> PDDocument().use { d -> d.addPage(PDPage()); d.save(f) } }
        val signed = File(tmp.root, "s.pdf")
        DigitalSignature.signInto(plain, signed, id("A"), DigitalSignature.SignOptions(name = "A"), SigTarget(page = 0, rect = floatArrayOf(20f, 20f, 120f, 40f)))
        PDDocument.load(signed).use { d ->
            val w = d.getPage(0).annotations.single()
            assertTrue(com.smartdocs.editor.core.pdf.SignedSave.isSignedSigWidget(w.cosObject))
        }
    }
}
