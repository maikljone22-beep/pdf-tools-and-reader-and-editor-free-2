package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.SigTarget
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

/** Foxit §30: signature on several pages, field add/move/delete, hash algorithms, EdDSA, trust levels, verification time. */
class SignStage2Test {
    @get:Rule val tmp = TemporaryFolder()

    private fun idOf(key: DigitalIds.KeyType = DigitalIds.KeyType.RSA_2048): DigitalSignature.Identity {
        val (k, c) = DigitalIds.selfSigned(DigitalIds.Spec("Stage Two", key = key))
        return DigitalSignature.Identity(k, listOf(c))
    }

    private fun pdf(pages: Int = 3): File = tmp.newFile().also { f -> PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) } }

    private fun sign(src: File, target: SigTarget, digest: DigitalSignature.Digest = DigitalSignature.Digest.SHA256, id: DigitalSignature.Identity = idOf()): File {
        val out = File(tmp.root, "s_${System.nanoTime()}.pdf")
        DigitalSignature.signInto(src, out, id, DigitalSignature.SignOptions(name = "Stage Two", digest = digest), target)
        return out
    }

    @Test fun oneSignatureShownOnSeveralPages() {
        val r = floatArrayOf(400f, 40f, 150f, 50f)
        val out = sign(pdf(), SigTarget(page = 0, rect = r, extra = listOf(1 to r, 2 to r)))
        PDDocument.load(out).use { d ->
            val f = d.documentCatalog.getAcroForm(null).fieldTree.filterIsInstance<PDSignatureField>().single()
            assertEquals(3, f.widgets.size)
            for (i in 0..2) assertTrue("widget on page $i", d.getPage(i).annotations.any { a -> f.widgets.any { it.cosObject == a.cosObject } })
            assertTrue("every widget has an appearance", f.widgets.all { it.appearance?.normalAppearance != null })
        }
        val v = runBlocking { DigitalSignature.verify(out) }.single()
        assertTrue(v.signatureValid); assertTrue(v.coversWholeDocument)
        assertEquals(0, SigFields.list(out).single().page)
    }

    @Test fun addMoveAndDeleteAnEmptyField() {
        val src = pdf(2)
        val added = File(tmp.root, "added.pdf")
        val name = SigFields.addEmpty(src, added, 1, floatArrayOf(50f, 60f, 120f, 40f), "Approver")
        assertEquals("Approver", name)
        assertArrayEquals(floatArrayOf(50f, 60f, 120f, 40f), SigFields.list(added).single().rect, 0.01f)
        val moved = File(tmp.root, "moved.pdf")
        SigFields.move(added, moved, "Approver", floatArrayOf(300f, 500f, 200f, 60f))
        val f = SigFields.list(moved).single()
        assertEquals(1, f.page); assertArrayEquals(floatArrayOf(300f, 500f, 200f, 60f), f.rect, 0.01f)
        val deleted = File(tmp.root, "deleted.pdf")
        SigFields.delete(moved, deleted, "Approver")
        assertTrue(SigFields.list(deleted).isEmpty())
        PDDocument.load(deleted).use { d -> assertTrue(d.getPage(1).annotations.isEmpty()) }
    }

    @Test fun fieldEditsOnASignedFileAreIncremental() {
        val signed = sign(pdf(2), SigTarget(page = 0, rect = floatArrayOf(20f, 20f, 100f, 40f)))
        val added = File(tmp.root, "added.pdf")
        SigFields.addEmpty(signed, added, 1, floatArrayOf(50f, 60f, 120f, 40f))
        assertArrayEquals(signed.readBytes(), added.readBytes().copyOf(signed.length().toInt()))
        assertTrue(runBlocking { DigitalSignature.verify(added) }.single().signatureValid)
        assertEquals(2, SigFields.list(added).size)
    }

    @Test fun otherHashAlgorithmsAndEdDsaVerify() {
        for (dg in listOf(DigitalSignature.Digest.SHA384, DigitalSignature.Digest.SHA512, DigitalSignature.Digest.SHA3_256)) {
            val out = sign(pdf(1), SigTarget(), dg)
            assertTrue("$dg", runBlocking { DigitalSignature.verify(out) }.single().signatureValid)
        }
        val ec = sign(pdf(1), SigTarget(), DigitalSignature.Digest.SHA384, idOf(DigitalIds.KeyType.EC_P384))
        assertTrue(runBlocking { DigitalSignature.verify(ec) }.single().signatureValid)
        val ed = sign(pdf(1), SigTarget(), id = idOf(DigitalIds.KeyType.ED25519))
        assertTrue("Ed25519", runBlocking { DigitalSignature.verify(ed) }.single().signatureValid)
    }

    @Test fun trustLevelsParse() {
        assertEquals(TrustedCerts.Level.entries.toSet(), TrustedCerts.parseLevels(null))
        assertEquals(setOf(TrustedCerts.Level.CERTIFIED), TrustedCerts.parseLevels("CERTIFIED"))
        assertTrue(TrustedCerts.parseLevels("").isEmpty())
        assertEquals(setOf(TrustedCerts.Level.SIGNATURES), TrustedCerts.parseLevels("SIGNATURES, bogus"))
    }

    @Test fun verificationTime() {
        val signed = Date(1_000_000L); val ts = Date(2_000_000L); val now = Date(9_000_000L)
        val r = DigitalSignature.VerifyResult("x", signed, "", true, true, "", "", Date(0), Date(5_000_000L), timestampDate = ts, timestampValid = true)
        assertEquals(ts, DigitalSignature.verificationTime(r, DigitalSignature.VerifyTime.SECURE_TIME, now))
        assertEquals(signed, DigitalSignature.verificationTime(r.copy(timestampValid = false), DigitalSignature.VerifyTime.SECURE_TIME, now))
        assertEquals(now, DigitalSignature.verificationTime(r, DigitalSignature.VerifyTime.CURRENT_TIME, now))
        assertEquals(true, DigitalSignature.certValidAt(r, ts))
        assertEquals(false, DigitalSignature.certValidAt(r, now))
        assertNull(DigitalSignature.certValidAt(r.copy(validFrom = null), now))
        assertFalse(DigitalSignature.certValidAt(r, Date(6_000_000L))!!)
    }

    @Test fun appearanceFontAndDirectionRoundTripAndOldSpecsDecode() {
        val spec = SigAppearanceSpec(maxFontSize = 24, font = SigAppearanceSpec.Font.SERIF, direction = SigAppearanceSpec.Direction.RTL)
        assertEquals(spec, SigAppearanceSpec.decode(spec.encode()))
        assertEquals(SigAppearanceSpec(font = SigAppearanceSpec.Font.MONO), SigAppearanceSpec.decode(SigAppearanceSpec(font = SigAppearanceSpec.Font.MONO).encode()))
        assertEquals(24, SigAppearanceSpec.decode("111100|LOGO_TOP:24|").maxFontSize)
        assertEquals(SigAppearanceSpec.Font.SANS, SigAppearanceSpec.decode("111100|LOGO_TOP|").font)
        val auto = SigAppearanceSpec()
        assertTrue(auto.rtl("موقّع من Ali")); assertFalse(auto.rtl("Signed by علي"))
        assertTrue(SigAppearanceSpec(direction = SigAppearanceSpec.Direction.RTL).rtl("abc"))
    }
}
