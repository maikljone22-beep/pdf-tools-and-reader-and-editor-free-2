package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Row 4: certificate encryption with "don't encrypt metadata", and opening / changing / removing certificate
 * protection with a digital ID held as a private key (the KeyChain path), checked against PdfBox's own handler.
 */
class PubSecCryptoTest {
    @get:Rule val tmp = TemporaryFolder()
    private val xmp = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?><x:xmpmeta xmlns:x='adobe:ns:meta/'><marker>SMARTDOCS-XMP</marker></x:xmpmeta><?xpacket end='w'?>"

    private fun id(name: String): DigitalSignature.Identity {
        DigitalSignature.ensureProvider()
        val (key, cert) = DigitalIds.selfSigned(DigitalIds.Spec(name, usage = DigitalIds.Usage.BOTH))
        return DigitalSignature.Identity(key, listOf(cert))
    }

    private fun source(): File {
        val f = tmp.newFile("src.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            d.documentInformation.title = "Secret title"
            val m = PDMetadata(d); m.importXMPMetadata(xmp.toByteArray()); d.documentCatalog.metadata = m
            d.save(f)
        }
        return f
    }

    private fun contains(f: File, s: String) = String(f.readBytes(), Charsets.ISO_8859_1).contains(s)

    @Test fun metadataStaysInClearAndEverythingElseOpensWithTheId() = runBlocking {
        val alice = id("Alice")
        val out = File(tmp.root, "nometa.pdf")
        val perms = CertEncryption.Perms.READ_ONLY.copy(print = true)
        CertEncryption.encrypt(source(), out, listOf(CertEncryption.Recipient(alice.certificate, perms)), 256, encryptMetadata = false)
        assertTrue("XMP must be readable without the key", contains(out, "SMARTDOCS-XMP"))
        assertFalse("the title must be encrypted", contains(out, "Secret title"))
        PubSecCrypto.withDocument(out, alice) { d ->
            assertEquals("Secret title", d.documentInformation.title)
            assertTrue(String(d.documentCatalog.metadata.toByteArray(), Charsets.UTF_8).contains("SMARTDOCS-XMP"))
            assertFalse(d.encryption.isEncryptMetaData && d.encryption.defaultCryptFilterDictionary.isEncryptMetaData)
            assertTrue(d.currentAccessPermission.canPrint()); assertFalse(d.currentAccessPermission.canModify())
        }
    }

    @Test fun pdfBoxOwnHandlerReadsTheNoMetadataFileToo() = runBlocking {
        val bob = id("Bob")
        val out = File(tmp.root, "nometa128.pdf")
        CertEncryption.encrypt(source(), out, listOf(CertEncryption.Recipient(bob.certificate)), 128, encryptMetadata = false)
        val p12 = File(tmp.root, "bob.p12")
        DigitalIds.store(p12, bob.privateKey, bob.chain, "pw".toCharArray())
        CertEncryption.open(out, p12.readBytes(), "pw").use { d ->
            assertEquals("Secret title", d.documentInformation.title)
            assertTrue(String(d.documentCatalog.metadata.toByteArray(), Charsets.UTF_8).contains("SMARTDOCS-XMP"))
        }
    }

    @Test fun changeRecipientsAndRemoveProtectionWithTheId() = runBlocking {
        val alice = id("Alice"); val carol = id("Carol")
        val first = File(tmp.root, "first.pdf")
        CertEncryption.encrypt(source(), first, listOf(CertEncryption.Recipient(alice.certificate)), 256)        // PdfBox's handler
        assertFalse(contains(first, "SMARTDOCS-XMP"))
        // change settings: Alice opens it and re-encrypts it for Carol only
        val second = File(tmp.root, "second.pdf")
        CertEncryption.encrypt(first, second, listOf(CertEncryption.Recipient(carol.certificate)), 128, unlock = alice)
        PubSecCrypto.withDocument(second, carol) { assertEquals("Secret title", it.documentInformation.title) }
        try { PubSecCrypto.withDocument(second, alice) { }; fail("Alice is no longer a recipient") } catch (e: Exception) { assertTrue(true) }
        // remove: an unprotected copy
        val plain = File(tmp.root, "plain.pdf")
        CertEncryption.decryptToFile(second, plain, carol)
        PDDocument.load(plain).use { d -> assertFalse(d.isEncrypted); assertEquals("Secret title", d.documentInformation.title) }
        assertEquals(1, PubSecCrypto.patch(tmp.newFile("x.pdf").apply { writeBytes(ByteArray(65530) + "/Adobe.PubSec".toByteArray() + ByteArray(10)) }))
    }
}
