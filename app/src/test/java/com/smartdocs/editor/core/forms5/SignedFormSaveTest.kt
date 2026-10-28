package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.SigTarget
import com.smartdocs.editor.core.pdf.SignedDocumentException
import com.smartdocs.editor.core.sign.DigitalIds
import com.smartdocs.editor.core.sign.SigFields
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit V-01 / tabs D1: filling a signed form appends an incremental update and keeps the signature valid. */
class SignedFormSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id by lazy { DigitalIds.selfSigned(DigitalIds.Spec("Party A")).let { (k, c) -> DigitalSignature.Identity(k, listOf(c)) } }

    /** A form with two text fields ("buyer", "amount"), signed by party A (optionally with a lock / certification). */
    private fun signedForm(lock: SigFields.Lock? = null, certify: Int = 0): File {
        val plain = tmp.newFile("form.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            for ((i, n) in listOf("buyer", "amount").withIndex()) {
                val tf = PDTextField(form).apply { partialName = n }
                tf.widgets.first().apply { rectangle = PDRectangle(50f, 600f - i * 40f, 200f, 24f); page = d.getPage(0) }
                d.getPage(0).annotations.add(tf.widgets.first())
                form.fields.add(tf)
            }
            d.save(plain)
        }
        val out = File(tmp.root, "signed_${System.nanoTime()}.pdf")
        DigitalSignature.signInto(plain, out, id, DigitalSignature.SignOptions(name = "Party A", lock = lock, certifyLevel = certify),
            SigTarget(page = 0, rect = floatArrayOf(300f, 100f, 150f, 50f)))
        return out
    }

    private fun value(f: File, name: String): String? = PDDocument.load(f).use { d ->
        d.documentCatalog.getAcroForm(null).getField(name).cosObject.getString(COSName.V)
    }

    private fun fill(src: File, values: Map<String, String>): File {
        val out = File(tmp.root, "filled_${System.nanoTime()}.pdf")
        runBlocking { FormValueWriter.writeSigned(null, src, values, emptyMap(), emptyMap(), emptyMap(), out, null) }
        return out
    }

    @Test fun fillingASignedFormKeepsTheSignature() {
        val signed = signedForm()
        val out = fill(signed, mapOf("buyer" to "Party B"))
        assertArrayEquals("the signed revision is a byte-identical prefix", signed.readBytes(), out.readBytes().copyOf(signed.length().toInt()))
        assertEquals("Party B", value(out, "buyer"))
        val v = runBlocking { DigitalSignature.verify(out) }.single()
        assertTrue("signature still valid", v.signatureValid)
        assertEquals(3, v.revisionCount)
    }

    @Test fun certifiedFormFillingAllowedOnlyFromP2() {
        assertEquals("Party B", value(fill(signedForm(certify = 2), mapOf("buyer" to "Party B")), "buyer"))
        try { fill(signedForm(certify = 1), mapOf("buyer" to "x")); fail("P=1 allows no changes") }
        catch (e: SignedDocumentException) { assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, e.reason) }
    }

    @Test fun fieldsLockedByTheSignatureKeepTheirValue() {
        val signed = signedForm(lock = SigFields.Lock(SigFields.LockAction.INCLUDE, listOf("amount")))
        val out = fill(signed, mapOf("buyer" to "B", "amount" to "999"))
        assertEquals("B", value(out, "buyer"))
        assertEquals(null, value(out, "amount"))
        assertTrue(runBlocking { DigitalSignature.verify(out) }.single().signatureValid)
    }
}
