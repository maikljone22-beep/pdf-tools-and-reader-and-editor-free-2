package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * End-to-end checks of [ScopedSecurityHandler] with the real PdfBox-Android writer and parser: a PDF with page text,
 * an Info title, XMP metadata and two attachments (plain and Flate) is encrypted with each scope and AES-128 / AES-256,
 * then inspected as raw bytes and reopened. Set -Dsd.outDir=... to keep the files (for qpdf / pikepdf).
 */
class ProtectModesTest {
    private val pageText = "Hello Secret Page"
    private val title = "SecretTitleXYZ"
    private val xmpMarker = "SD-XMP-MARKER-42"
    private val pageXmpMarker = "SD-PAGE-XMP-77"
    private val payload = "ATTACHMENT-PAYLOAD-0123456789".repeat(20).toByteArray(Charsets.ISO_8859_1)
    private val payload2 = "FLATE-ATTACHMENT-abcdef".repeat(30).toByteArray(Charsets.ISO_8859_1)
    private val user = "user-pw"
    private val owner = "owner-pw"

    private fun source(): PDDocument {
        val doc = PDDocument()
        val page = PDPage()
        val font = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, COSName.TYPE1); setName(COSName.BASE_FONT, "Helvetica")
        }
        val fonts = COSDictionary().apply { setItem(COSName.getPDFName("F1"), font) }
        page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.FONT, fonts) })
        val content = PDStream(doc)
        content.createOutputStream().use { it.write("BT /F1 24 Tf 72 700 Td ($pageText) Tj ET".toByteArray(Charsets.ISO_8859_1)) }
        page.cosObject.setItem(COSName.CONTENTS, content.cosObject)
        doc.addPage(page)
        doc.documentInformation.title = title
        val xmp = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>$xmpMarker</dc:title></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
        val meta = PDMetadata(doc)
        meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
        doc.documentCatalog.metadata = meta
        // page-level XMP: not document-level, so /EncryptMetadata false still encrypts it
        val pageMeta = PDMetadata(doc)
        pageMeta.importXMPMetadata(xmp.replace(xmpMarker, pageXmpMarker).toByteArray(Charsets.UTF_8))
        page.metadata = pageMeta
        val ef1 = PDEmbeddedFile(doc, ByteArrayInputStream(payload)).apply { subtype = "text/plain"; size = payload.size }
        val s2: COSStream = doc.document.createCOSStream()
        s2.createOutputStream(COSName.FLATE_DECODE).use { it.write(payload2) }
        val ef2 = PDEmbeddedFile(s2)       // no /Type on purpose: found through the file specification
        val fs1 = PDComplexFileSpecification().apply { file = "a.txt"; embeddedFile = ef1 }
        val fs2 = PDComplexFileSpecification().apply { file = "b.txt"; embeddedFile = ef2 }
        val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("a.txt" to fs1, "b.txt" to fs2) }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
        return doc
    }

    private fun encrypt(scope: EncScope, bits: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        source().use { doc ->
            val policy = StandardProtectionPolicy(owner, user, AccessPermission()).apply { encryptionKeyLength = bits; setPreferAES(true) }
            if (scope == EncScope.ALL) doc.protect(policy) else { ScopedSecurityHandler.register(); doc.protect(ScopedProtectionPolicy(policy, scope)) }
            doc.save(bos)
        }
        val bytes = bos.toByteArray()
        System.getProperty("sd.outDir")?.let { File(it).apply { mkdirs() }.resolve("enc_${scope.name.lowercase()}_$bits.pdf").writeBytes(bytes) }
        return bytes
    }

    private fun ByteArray.has(s: String) = String(this, Charsets.ISO_8859_1).contains(s)
    private fun ByteArray.has(b: ByteArray) = String(this, Charsets.ISO_8859_1).contains(String(b, Charsets.ISO_8859_1))

    private fun pageContent(doc: PDDocument) = doc.getPage(0).contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun attachments(doc: PDDocument): Map<String, COSStream> =
        doc.documentCatalog.names.embeddedFiles.names.mapValues { it.value.embeddedFile.cosObject }

    private fun checkAllButMetadata(bits: Int) {
        val b = encrypt(EncScope.ALL_BUT_METADATA, bits)
        assertTrue("XMP must be stored in clear", b.has(xmpMarker))
        assertFalse("page-level XMP must be encrypted", b.has(pageXmpMarker))
        assertFalse("page content must be encrypted", b.has(pageText))
        assertFalse("strings must be encrypted", b.has(title))
        assertFalse("attachment must be encrypted", b.has(payload))
        for (pw in listOf(user, owner)) PDDocument.load(b, pw).use { d ->
            val e = d.encryption
            assertFalse(e.isEncryptMetaData)
            assertEquals(if (bits == 256) 5 else 4, e.version)
            assertEquals(if (bits == 256) 6 else 4, e.revision)
            assertTrue(pageContent(d).contains("($pageText) Tj"))
            assertEquals(title, d.documentInformation.title)
            assertTrue(String(d.documentCatalog.metadata.toByteArray(), Charsets.UTF_8).contains(xmpMarker))
            val a = attachments(d)
            assertArrayEquals(payload, a.getValue("a.txt").createInputStream().use { it.readBytes() })
            assertArrayEquals(payload2, a.getValue("b.txt").createInputStream().use { it.readBytes() })
            if (bits == 256) {
                val p = ScopedSecurityHandler.decryptPerms(e.securityHandler.getEncryptionKey(), e.perms)
                assertEquals('F'.code.toByte(), p[8])
                assertEquals("adb", String(p, 9, 3, Charsets.ISO_8859_1))
                val perm = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or ((p[2].toInt() and 0xFF) shl 16) or ((p[3].toInt() and 0xFF) shl 24)
                assertEquals(e.permissions, perm)
            }
        }
        try { PDDocument.load(b, "wrong").close(); fail("wrong password accepted") } catch (_: InvalidPasswordException) {}
    }

    private fun checkAttachmentsOnly(bits: Int) {
        val b = encrypt(EncScope.ATTACHMENTS_ONLY, bits)
        assertTrue("page content must stay in clear", b.has("($pageText) Tj"))
        assertTrue("strings must stay in clear", b.has(title))
        assertTrue("XMP must stay in clear", b.has(xmpMarker))
        assertFalse("attachment must be encrypted", b.has(payload))
        // the same bytes without the /Encrypt entry: any reader gets the pages, the attachment stays ciphertext
        val s = String(b, Charsets.ISO_8859_1)
        val at = s.lastIndexOf("/Encrypt")
        assertTrue(at > 0)
        val plain = (s.substring(0, at) + "/Encryx" + s.substring(at + 8)).toByteArray(Charsets.ISO_8859_1)
        PDDocument.load(plain).use { d ->
            assertFalse(d.isEncrypted)
            assertTrue(pageContent(d).contains("($pageText) Tj"))
            assertEquals(title, d.documentInformation.title)
            assertFalse(payload.contentEquals(attachments(d).getValue("a.txt").createRawInputStream().use { it.readBytes() }))
        }
        PDDocument.load(b, user).use { d ->
            val e = d.encryption
            assertEquals(COSName.IDENTITY, e.streamFilterName)
            assertEquals(COSName.IDENTITY, e.stringFilterName)
            assertEquals(COSName.STD_CF, e.cosObject.getCOSName(ScopedSecurityHandler.K_EFF))
            assertEquals(ScopedSecurityHandler.K_EF_OPEN, e.stdCryptFilterDictionary.cosObject.getCOSName(ScopedSecurityHandler.K_AUTH_EVENT))
            assertEquals(if (bits == 256) COSName.AESV3 else COSName.AESV2, e.stdCryptFilterDictionary.cryptFilterMethod)
            assertTrue(EmbeddedFileCrypt.needsManualDecrypt(d))
            assertTrue(pageContent(d).contains("($pageText) Tj"))
            assertEquals(title, d.documentInformation.title)
            val a = attachments(d)
            assertArrayEquals(payload, EmbeddedFileCrypt.bytes(d, a.getValue("a.txt")))
            assertArrayEquals(payload2, EmbeddedFileCrypt.bytes(d, a.getValue("b.txt")))
            assertNotNull(a.getValue("b.txt").getCOSName(COSName.TYPE))
        }
    }

    @Test fun allButMetadataAes256() = checkAllButMetadata(256)
    @Test fun allButMetadataAes128() = checkAllButMetadata(128)
    @Test fun attachmentsOnlyAes256() = checkAttachmentsOnly(256)
    @Test fun attachmentsOnlyAes128() = checkAttachmentsOnly(128)

    @Test fun permsLayout() {
        val key = ByteArray(32) { it.toByte() }
        val p = ScopedSecurityHandler.decryptPerms(key, ScopedSecurityHandler.perms(key, -3904, encryptMetadata = false))
        assertEquals('F'.code.toByte(), p[8])
        assertEquals("adb", String(p, 9, 3, Charsets.ISO_8859_1))
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), -1, -1, -1, -1), p.copyOf(8))
    }

    @Test fun normalEncryptionUnchanged() {
        val b = encrypt(EncScope.ALL, 256)
        assertFalse(b.has(xmpMarker)); assertFalse(b.has(pageText))
        PDDocument.load(b, user).use { d -> assertTrue(d.encryption.isEncryptMetaData); assertTrue(pageContent(d).contains(pageText)) }
    }

}
