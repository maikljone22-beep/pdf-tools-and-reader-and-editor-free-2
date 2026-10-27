package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.SignedDocumentException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Nitro §11: sign into an existing field, place a new field anywhere, FieldMDP lock, clear, revisions. */
class SigFieldsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id by lazy { runBlocking { DigitalSignature.generateSelfSigned(tmp.newFile("id.p12"), "Field Signer", "", "", "pw".toCharArray()) } }

    /** Two pages; page 2 has an empty signature field "Approve" at (300, 100, 180, 60) and two text fields. */
    private fun formPdf(): File {
        val f = tmp.newFile("form.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage()); d.addPage(PDPage())
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val sf = PDSignatureField(form).apply { partialName = "Approve" }
            sf.widgets.first().apply { rectangle = PDRectangle(300f, 100f, 180f, 60f); page = d.getPage(1); isPrinted = true }
            d.getPage(1).annotations.add(sf.widgets.first())
            form.fields.add(sf)
            for ((i, n) in listOf("name", "amount").withIndex()) {
                val tf = PDTextField(form).apply { partialName = n }
                tf.widgets.first().apply { rectangle = PDRectangle(50f, 600f - i * 40f, 200f, 24f); page = d.getPage(0) }
                d.getPage(0).annotations.add(tf.widgets.first())
                form.fields.add(tf)
            }
            d.save(f)
        }
        return f
    }

    private fun sign(src: File, target: SigTarget, lock: SigFields.Lock? = null, name: String = "signed.pdf"): File {
        val out = File(tmp.root, name)
        DigitalSignature.signInto(src, out, id, DigitalSignature.SignOptions(name = "Field Signer", contactInfo = "a@b.c", lock = lock), target)
        return out
    }

    @Test fun listsEmptySignatureFields() {
        val fields = SigFields.list(formPdf())
        assertEquals(1, fields.size)
        val f = fields[0]
        assertEquals("Approve", f.name); assertEquals(1, f.page); assertFalse(f.signed)
        assertArrayEquals(floatArrayOf(300f, 100f, 180f, 60f), f.rect, 0.01f)
    }

    @Test fun signsIntoTheExistingFieldAndKeepsItsRectangle() {
        val src = formPdf()
        val out = sign(src, SigTarget(fieldName = "Approve"))
        val fields = SigFields.list(out)
        assertEquals("no extra field is created", 1, fields.size)
        assertTrue(fields[0].signed)
        assertArrayEquals(floatArrayOf(300f, 100f, 180f, 60f), fields[0].rect, 0.01f)
        val v = runBlocking { DigitalSignature.verify(out) }.single()
        assertTrue(v.signatureValid); assertTrue(v.coversWholeDocument)
        assertEquals("Approve", v.fieldName); assertEquals("a@b.c", v.contactInfo)
        assertEquals(2, v.revision); assertEquals(2, v.revisionCount)
        assertArrayEquals("the unsigned original is a prefix", src.readBytes(), out.readBytes().copyOf(src.length().toInt()))
        try { sign(out, SigTarget(fieldName = "Approve"), name = "again.pdf"); fail("a signed field cannot be signed again") } catch (_: IllegalStateException) {}
    }

    @Test fun placesANewVisibleFieldAnywhere() {
        val out = sign(formPdf(), SigTarget(page = 0, rect = floatArrayOf(20f, 30f, 150f, 50f)))
        val f = SigFields.list(out).first { it.signed }
        assertEquals(0, f.page)
        assertArrayEquals(floatArrayOf(20f, 30f, 150f, 50f), f.rect, 0.01f)
        assertTrue(runBlocking { DigitalSignature.verify(out) }.single().signatureValid)
    }

    @Test fun fieldMdpLockMakesFieldsReadOnlyAndIsReported() {
        val out = sign(formPdf(), SigTarget(fieldName = "Approve"), SigFields.Lock(SigFields.LockAction.INCLUDE, listOf("amount"), docPermission = 2))
        PDDocument.load(out).use { d ->
            val form = d.documentCatalog.acroForm
            assertTrue(form.getField("amount").isReadOnly)
            assertFalse(form.getField("name").isReadOnly)
        }
        val v = runBlocking { DigitalSignature.verify(out) }.single()
        assertTrue(v.signatureValid)
        assertEquals(SigFields.Lock(SigFields.LockAction.INCLUDE, listOf("amount"), 2), v.lock)
        assertEquals(SigFields.LockAction.INCLUDE, SigFields.list(out).single().lock?.action)
    }

    @Test fun lockActions() {
        assertTrue(SigFields.Lock(SigFields.LockAction.ALL).locks("x"))
        assertTrue(SigFields.Lock(SigFields.LockAction.INCLUDE, listOf("x")).locks("x"))
        assertFalse(SigFields.Lock(SigFields.LockAction.INCLUDE, listOf("x")).locks("y"))
        assertFalse(SigFields.Lock(SigFields.LockAction.EXCLUDE, listOf("x")).locks("x"))
        assertTrue(SigFields.Lock(SigFields.LockAction.EXCLUDE, listOf("x")).locks("y"))
    }

    @Test fun clearRemovesTheValueIncrementallyAndKeepsEarlierSignatures() {
        val first = sign(formPdf(), SigTarget(page = 0, rect = floatArrayOf(20f, 30f, 150f, 50f)), name = "first.pdf")
        val both = sign(first, SigTarget(fieldName = "Approve"), name = "both.pdf")
        val cleared = File(tmp.root, "cleared.pdf")
        SigFields.clear(both, cleared, "Approve")
        assertArrayEquals(both.readBytes(), cleared.readBytes().copyOf(both.length().toInt()))
        val approve = SigFields.list(cleared).first { it.name == "Approve" }
        assertFalse(approve.signed)
        assertArrayEquals(floatArrayOf(300f, 100f, 180f, 60f), approve.rect, 0.01f)
        val v = runBlocking { DigitalSignature.verify(cleared) }
        assertEquals(1, v.size); assertTrue(v[0].signatureValid)
        // the field can be signed again
        assertTrue(runBlocking { DigitalSignature.verify(sign(cleared, SigTarget(fieldName = "Approve"), name = "re.pdf")) }.all { it.signatureValid })
    }

    @Test fun noChangesLockRefusesLaterSigningAndClearingOthers() {
        val first = sign(formPdf(), SigTarget(page = 0, rect = floatArrayOf(20f, 30f, 150f, 50f)), name = "first.pdf")
        val locked = sign(first, SigTarget(fieldName = "Approve"), SigFields.Lock(SigFields.LockAction.ALL, docPermission = 1), name = "locked.pdf")
        try { SigFields.clear(locked, File(tmp.root, "x.pdf"), "Signature1"); fail("P=1 forbids changes") }
        catch (e: SignedDocumentException) { assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, e.reason) }
        try { sign(locked, SigTarget(page = 1), name = "third.pdf"); fail("P=1 forbids another signature") }
        catch (e: SignedDocumentException) { assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, e.reason) }
    }

    @Test fun revisionsAndLaterSignatures() {
        val first = sign(formPdf(), SigTarget(page = 0, rect = floatArrayOf(20f, 30f, 150f, 50f)), name = "first.pdf")
        val both = sign(first, SigTarget(fieldName = "Approve"), name = "both.pdf")
        val v = runBlocking { DigitalSignature.verify(both) }.sortedBy { it.signedLength }
        assertEquals(listOf(2, 3), v.map { it.revision }); assertEquals(listOf(3, 3), v.map { it.revisionCount })
        assertFalse(v[0].coversWholeDocument); assertTrue(v[1].coversWholeDocument)
        assertEquals(listOf("Field Signer"), v[0].signedLaterBy); assertTrue(v[1].signedLaterBy.isEmpty())
        // "view signed version": the first signed revision is exactly the first output
        assertTrue(v[0].signedLength <= first.length())
        val rev = File(tmp.root, "rev.pdf")
        SigRevisions.extract(both, v[0].signedLength, rev)
        val again = runBlocking { DigitalSignature.verify(rev) }.single()
        assertTrue(again.signatureValid); assertTrue(again.coversWholeDocument)
    }

    @Test fun displayedFractionsRoundTripForEveryRotation() {
        val box = PDRectangle(10f, 20f, 600f, 800f)
        for (rot in listOf(0, 90, 180, 270)) {
            val r = SigFields.toUserSpace(0.1f, 0.2f, 0.3f, 0.1f, box, rot)
            assertArrayEquals("rot $rot", floatArrayOf(0.1f, 0.2f, 0.3f, 0.1f), SigFields.toDisplayed(r, box, rot), 1e-4f)
        }
        // unrotated: top-left origin → bottom-left origin
        assertArrayEquals(floatArrayOf(70f, 580f, 180f, 80f), SigFields.toUserSpace(0.1f, 0.2f, 0.3f, 0.1f, box, 0), 1e-3f)
        // 90°: the displayed top-left corner is the unrotated bottom-left corner
        val r90 = SigFields.toUserSpace(0f, 0f, 0.1f, 0.1f, box, 90)
        assertEquals(10f, r90[0], 1e-3f); assertEquals(20f, r90[1], 1e-3f)
        assertNull(SigFields.matrixFor(0)); assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f), SigFields.matrixFor(90), 0f)
    }
}
