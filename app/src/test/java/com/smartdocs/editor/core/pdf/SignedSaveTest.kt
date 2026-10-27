package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.runBlocking
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar

/** Audit AN-28 / DI-27: annotating a signed PDF appends an incremental update and keeps the signature valid. */
class SignedSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun signedPdf(): File {
        val plain = tmp.newFile("plain.pdf")
        PDDocument().use { d -> d.addPage(PDPage()); d.addPage(PDPage()); d.save(plain) }
        val id = runBlocking { DigitalSignature.generateSelfSigned(tmp.newFile("id.p12"), "Test Signer", "", "", "pw".toCharArray()) }
        val signer = SignatureInterface { content ->
            val gen = CMSSignedDataGenerator()
            gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(JcaContentSignerBuilder("SHA256withRSA").build(id.privateKey), id.chain.first()))
            gen.addCertificates(JcaCertStore(id.chain))
            gen.generate(CMSProcessableByteArray(content.readBytes()), false).encoded
        }
        val signed = tmp.newFile("signed.pdf")
        PDDocument.load(plain).use { d ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE); setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = "Test Signer"; signDate = Calendar.getInstance()
            }
            SignatureOptions().use { o -> d.addSignature(sig, signer, o); signed.outputStream().use { d.saveIncremental(it) } }
        }
        return signed
    }

    @Test fun annotationOnSignedPdfKeepsSignedBytesAndSignature() {
        val signed = signedPdf()
        val before = runBlocking { DigitalSignature.verify(signed) }
        assertEquals(1, before.size); assertTrue(before[0].signatureValid); assertTrue(before[0].coversWholeDocument)

        val out = File(tmp.root, "annotated.pdf")
        AnnotationWriter.saveChanged(signed, out, null) { d ->
            d.getPage(1).annotations.add(PDAnnotationText().apply { contents = "added after signing" })
            Unit to listOf(1)
        }

        val orig = signed.readBytes(); val now = out.readBytes()
        assertTrue("incremental update appends", now.size > orig.size)
        assertArrayEquals("the signed revision is a byte-identical prefix", orig, now.copyOf(orig.size))
        val after = runBlocking { DigitalSignature.verify(out) }
        assertEquals(1, after.size)
        assertTrue("signature still verifies", after[0].signatureValid)
        assertFalse("signature now covers an earlier revision", after[0].coversWholeDocument)
        PDDocument.load(out).use { d -> assertEquals(listOf("added after signing"), d.getPage(1).annotations.map { it.contents }) }
    }

    @Test fun unsignedPdfIsFullySaved() {
        val f = tmp.newFile("u.pdf"); PDDocument().use { it.addPage(PDPage()); it.save(f) }
        assertFalse(SignedSave.isSigned(f))
        val out = File(tmp.root, "u2.pdf")
        AnnotationWriter.saveChanged(f, out, null) { d -> d.getPage(0).annotations.add(PDAnnotationText()); Unit to listOf(0) }
        PDDocument.load(out).use { assertEquals(1, it.getPage(0).annotations.size) }
    }

    @Test fun certifiedWithoutAnnotationRightsIsATypedError() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            fun certify(p: Int) {
                val tp = COSDictionary().apply { setInt(COSName.P, p) }
                val ref = COSDictionary().apply { setItem(COSName.TRANSFORM_METHOD, COSName.DOCMDP); setItem(COSName.TRANSFORM_PARAMS, tp) }
                val sig = COSDictionary().apply { setItem(COSName.REFERENCE, COSArray().apply { add(ref) }) }
                d.documentCatalog.cosObject.setItem(COSName.PERMS, COSDictionary().apply { setItem(COSName.DOCMDP, sig) })
            }
            certify(3); assertEquals(3, SignedSave.certifyLevel(d)); SignedSave.requireAnnotationsAllowed(d)
            certify(1)
            try { SignedSave.requireAnnotationsAllowed(d); fail("P=1 forbids annotations") }
            catch (e: SignedDocumentException) { assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, e.reason) }
        }
    }
}
