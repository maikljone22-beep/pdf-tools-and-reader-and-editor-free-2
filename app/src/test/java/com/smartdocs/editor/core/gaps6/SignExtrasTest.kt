package com.smartdocs.editor.core.gaps6

import com.smartdocs.editor.core.sign.CertFormat
import com.smartdocs.editor.core.sign.RevisionDiff
import com.smartdocs.editor.core.sign.RevisionDiff.AnnotSnap
import com.smartdocs.editor.core.sign.RevisionDiff.DocSnap
import com.smartdocs.editor.core.sign.RevisionDiff.FieldSnap
import com.smartdocs.editor.core.sign.RevisionDiff.Kind
import com.smartdocs.editor.core.sign.RevisionDiff.PageSnap
import com.smartdocs.editor.core.sign.SigAppearanceSpec
import com.smartdocs.editor.core.sign.TrustPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SignExtrasTest {

    @Test fun fingerprintAndSerial() {
        // SHA-256("abc")
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", CertFormat.sha256("abc".toByteArray()))
        assertEquals("00:FF", CertFormat.hex(byteArrayOf(0, -1)))
        assertEquals("80", CertFormat.serial(BigInteger.valueOf(128)))         // sign byte dropped
        assertEquals("01:00", CertFormat.serial(BigInteger.valueOf(256)))
        assertEquals("00", CertFormat.serial(BigInteger.ZERO))
    }

    @Test fun keyUsage() {
        val bits = BooleanArray(9).also { it[0] = true; it[1] = true; it[5] = true }
        assertEquals(listOf(CertFormat.KeyUsageBit.DIGITAL_SIGNATURE, CertFormat.KeyUsageBit.NON_REPUDIATION, CertFormat.KeyUsageBit.KEY_CERT_SIGN), CertFormat.keyUsage(bits))
        assertTrue(CertFormat.keyUsage(null).isEmpty())
        assertEquals(listOf(CertFormat.KeyUsageBit.DIGITAL_SIGNATURE), CertFormat.keyUsage(booleanArrayOf(true)))
        assertEquals(listOf("emailProtection", "1.2.3"), CertFormat.eku(listOf("1.3.6.1.5.5.7.3.4", "1.2.3")))
    }

    @Test fun dnParts() {
        val p = CertFormat.dnParts("CN=Ali\\, Co,O=Smart Docs,C=SA")
        assertEquals(listOf("CN" to "Ali, Co", "O" to "Smart Docs", "C" to "SA"), p)
        assertEquals("Smart Docs", CertFormat.dnValue("CN=A,O=Smart Docs", "o"))
        assertNull(CertFormat.dnValue("CN=A", "OU"))
        assertEquals(listOf("CN" to "x,y"), CertFormat.dnParts("CN=\"x,y\""))
    }

    private data class C(val sub: String, val iss: String, val fp: String, val signer: String)
    private val ops = TrustPath.Ops<C>({ it.sub }, { it.iss }, { it.fp }, { child, parent -> child.signer == parent.fp })

    @Test fun trustChain() {
        val root = C("R", "R", "r", "r"); val inter = C("I", "R", "i", "r"); val leaf = C("L", "I", "l", "i")
        val fakeRoot = C("R", "R", "x", "x")
        assertEquals(TrustPath.Status.TRUSTED, TrustPath.evaluate(leaf, listOf(inter), listOf(root), ops).status)
        assertEquals(listOf(leaf, inter, root), TrustPath.evaluate(leaf, listOf(inter), listOf(root), ops).chain)
        assertEquals(TrustPath.Status.TRUSTED, TrustPath.evaluate(leaf, listOf(inter, root), listOf(root), ops).status)
        assertEquals(TrustPath.Status.UNTRUSTED, TrustPath.evaluate(leaf, listOf(inter), emptyList(), ops).status)
        assertEquals(TrustPath.Status.UNTRUSTED, TrustPath.evaluate(leaf, listOf(inter), listOf(fakeRoot), ops).status)   // same name, other key
        assertEquals(TrustPath.Status.UNTRUSTED, TrustPath.evaluate(leaf, emptyList(), listOf(root), ops).status)         // missing intermediate
        assertEquals(TrustPath.Status.TRUSTED, TrustPath.evaluate(leaf, emptyList(), listOf(leaf), ops).status)            // leaf pinned
        assertEquals(TrustPath.Status.TRUSTED, TrustPath.evaluate(leaf, emptyList(), listOf(inter), ops).status)           // intermediate trusted
    }

    private fun page(rot: Int = 0, vararg a: AnnotSnap) = PageSnap(rot, "595x842", a.toList())

    @Test fun changeLog() {
        val link = AnnotSnap("Link", "1"); val hl = AnnotSnap("Highlight", "2"); val note = AnnotSnap("Text", "3")
        val old = DocSnap(listOf(page(0, link, hl), page(0)), mapOf("name" to FieldSnap("Tx", ""), "old" to FieldSnap("Btn", "Off")))
        val new = DocSnap(listOf(page(90, hl, note, AnnotSnap("Widget", "w")), page(0), page(0)),
            mapOf("name" to FieldSnap("Tx", "Ali"), "sig" to FieldSnap("Sig", "")))
        val ch = RevisionDiff.diff(old, new)
        val kinds = ch.map { it.kind }
        assertTrue(Kind.PAGE_ROTATED in kinds)
        assertTrue(ch.any { it.kind == Kind.LINK_REMOVED && it.page == 0 })
        assertTrue(ch.any { it.kind == Kind.ANNOT_ADDED && it.detail == "Text" })
        assertTrue(ch.none { it.detail == "Widget" })
        assertTrue(ch.any { it.kind == Kind.PAGE_ADDED && it.page == 2 })
        assertTrue(ch.any { it.kind == Kind.FIELD_CHANGED && it.detail == "name" })
        assertTrue(ch.any { it.kind == Kind.FIELD_ADDED && it.detail == "sig" })
        assertTrue(ch.any { it.kind == Kind.FIELD_REMOVED && it.detail == "old" })
        assertEquals(1, RevisionDiff.counts(ch)[Kind.PAGE_ADDED])
        assertTrue(RevisionDiff.diff(old, old).isEmpty())
        assertTrue(RevisionDiff.diff(DocSnap(listOf(page(270)), emptyMap()), DocSnap(listOf(page(-90)), emptyMap())).isEmpty())
        val removedPage = RevisionDiff.diff(new, old)
        assertTrue(removedPage.any { it.kind == Kind.PAGE_REMOVED && it.page == 2 })
    }

    @Test fun revisionEnds() {
        val b = "%PDF-1.7\n1 0 obj\n%%EOF\nmore\n%%EOF\r\n".toByteArray()
        val ends = RevisionDiff.revisionEnds(b)
        assertEquals(2, ends.size)
        assertEquals("%PDF-1.7\n1 0 obj\n%%EOF\n".length, ends[0])
        assertEquals(b.size, ends[1])
        assertTrue(RevisionDiff.revisionEnds("no marker".toByteArray()).isEmpty())
    }

    @Test fun appearanceLines() {
        val labels = SigAppearanceSpec.Labels("Signed by", "Reason", "Location", "Date", "DN")
        val spec = SigAppearanceSpec()
        assertEquals(listOf("Signed by Ali", "Reason: ok", "Date: 2026"), spec.lines("Ali", "ok", "", "2026", "CN=Ali", labels))
        val noLabels = spec.copy(showLabels = false, showDn = true, showReason = false)
        assertEquals(listOf("Ali", "Riyadh", "2026", "CN=Ali"), noLabels.lines("Ali", "ok", "Riyadh", "2026", "CN=Ali", labels))
        val enc = noLabels.copy(layout = SigAppearanceSpec.Layout.LOGO_TOP, logoPath = "/x/y.png").encode()
        assertEquals(noLabels.copy(layout = SigAppearanceSpec.Layout.LOGO_TOP, logoPath = "/x/y.png"), SigAppearanceSpec.decode(enc))
        assertEquals(SigAppearanceSpec(), SigAppearanceSpec.decode(null))
        assertEquals(SigAppearanceSpec(), SigAppearanceSpec.decode("garbage"))
    }
}
