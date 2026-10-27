package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar

/** nav-attach: one attachment deleted / described, several added, on plain and signed files; name-tree edits. */
class AttachmentOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = tmp.newFile(name).apply { writeText(text) }

    private fun plainPdf(pages: Int = 2): File = tmp.newFile("plain_${System.nanoTime()}.pdf").also { f -> PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) } }

    private fun signed(src: File): File {
        val id = runBlocking { DigitalSignature.generateSelfSigned(tmp.newFile("id_${System.nanoTime()}.p12"), "Test Signer", "", "", "pw".toCharArray()) }
        val signer = SignatureInterface { content ->
            val gen = CMSSignedDataGenerator()
            gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(JcaContentSignerBuilder("SHA256withRSA").build(id.privateKey), id.chain.first()))
            gen.addCertificates(JcaCertStore(id.chain))
            gen.generate(CMSProcessableByteArray(content.readBytes()), false).encoded
        }
        val out = tmp.newFile("signed_${System.nanoTime()}.pdf")
        PDDocument.load(src).use { d ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE); setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = "Test Signer"; signDate = Calendar.getInstance()
            }
            SignatureOptions().use { o -> d.addSignature(sig, signer, o); out.outputStream().use { d.saveIncremental(it) } }
        }
        return out
    }

    private fun out() = File(tmp.root, "out_${System.nanoTime()}.pdf")

    private fun assertStillSigned(signed: File, out: File) {
        val orig = signed.readBytes(); val now = out.readBytes()
        assertTrue(now.size > orig.size)
        assertArrayEquals("the signed revision is a byte-identical prefix", orig, now.copyOf(orig.size))
        val v = runBlocking { DigitalSignature.verify(out, online = false) }
        assertEquals(1, v.size); assertTrue("signature still verifies", v[0].signatureValid)
    }

    @Test fun addSeveralThenDeleteOneAndDescribe() = runBlocking {
        val src = plainPdf()
        val a = file("a.txt", "alpha"); val b = file("b.csv", "1,2")
        val o1 = out()
        val keys = AttachmentOps.addAll(src, listOf(a to "text/plain", b to "text/csv"), o1)
        assertEquals(listOf("a.txt", "b.csv"), keys)
        val list = AttachmentOps.list(o1)
        assertEquals(listOf("a.txt", "b.csv"), list.map { it.name })
        assertTrue(list.all { it.isDocumentLevel && it.created > 0 && it.modified > 0 && it.size > 0 })

        val o2 = out(); AttachmentOps.delete(o1, list[0], o2)
        assertEquals(listOf("b.csv"), AttachmentOps.list(o2).map { it.name })

        val o3 = out(); AttachmentOps.setDescription(o2, AttachmentOps.list(o2)[0], "numbers", o3)
        assertEquals("numbers", AttachmentOps.list(o3)[0].description)
        val x = AttachmentOps.extract(o3, AttachmentOps.list(o3)[0], tmp.newFolder())
        assertEquals("1,2", x!!.readText())
    }

    @Test fun sameNameGetsAUniqueKey() = runBlocking {
        val src = plainPdf()
        val a = file("a.txt", "one")
        val o1 = out(); AttachmentOps.add(src, a, o1, mime = "text/plain")
        val o2 = out(); AttachmentOps.add(o1, a, o2, mime = "text/plain")
        assertEquals(listOf("a.txt", "a_2.txt"), AttachmentOps.list(o2).map { it.key })
    }

    @Test fun pageAttachmentDeleteAndDescribe() = runBlocking {
        val src = plainPdf()
        val a = file("p.txt", "page file")
        val o1 = out(); AttachmentOps.add(src, a, o1, page = 1, mime = "text/plain")
        val att = AttachmentOps.list(o1).single()
        assertEquals(1, att.page); assertEquals(0, att.annotIndex)
        val o2 = out(); AttachmentOps.setDescription(o1, att, "about it", o2)
        assertEquals("about it", AttachmentOps.list(o2).single().description)
        val o3 = out(); AttachmentOps.delete(o2, AttachmentOps.list(o2).single(), o3)
        assertTrue(AttachmentOps.list(o3).isEmpty())
    }

    @Test fun signedFileChangesAreIncremental() = runBlocking {
        // a document-level file attached before signing, then deleted / described / added after signing
        val base = plainPdf()
        val pre = out(); AttachmentOps.add(base, file("old.txt", "old"), pre, mime = "text/plain")
        val s = signed(pre)

        val del = out(); AttachmentOps.delete(s, AttachmentOps.list(s).single(), del)
        assertStillSigned(s, del)
        assertTrue(AttachmentOps.list(del).isEmpty())

        val desc = out(); AttachmentOps.setDescription(s, AttachmentOps.list(s).single(), "edited", desc)
        assertStillSigned(s, desc)
        assertEquals("edited", AttachmentOps.list(desc).single().description)

        val add = out(); AttachmentOps.add(s, file("new.txt", "new"), add, mime = "text/plain")
        assertStillSigned(s, add)
        assertEquals(setOf("old.txt", "new.txt"), AttachmentOps.list(add).map { it.name }.toSet())

        val onPage = out(); AttachmentOps.add(s, file("pg.txt", "pg"), onPage, page = 0, mime = "text/plain")
        assertStillSigned(s, onPage)
        assertEquals(1, AttachmentOps.list(onPage).count { !it.isDocumentLevel })
    }

    @Test fun certifiedFileRefusesDocumentLevelChanges() = runBlocking {
        val f = plainPdf()
        val pre = out(); AttachmentOps.add(f, file("c.txt", "c"), pre, mime = "text/plain")
        val s = signed(pre)
        // make it "certified" (catalog /Perms /DocMDP P=2) in an incremental update the signature allows
        val cert = out()
        PDDocument.load(s).use { d ->
            val tp = COSDictionary().apply { setInt(COSName.P, 2) }
            val ref = COSDictionary().apply { setItem(COSName.TRANSFORM_METHOD, COSName.DOCMDP); setItem(COSName.TRANSFORM_PARAMS, tp) }
            d.documentCatalog.cosObject.setItem(COSName.PERMS, COSDictionary().apply { setItem(COSName.DOCMDP, COSDictionary().apply { setItem(COSName.REFERENCE, COSArray().apply { add(ref) }) }) })
            d.documentCatalog.cosObject.isNeedToBeUpdated = true
            cert.outputStream().use { d.saveIncremental(it) }
        }
        try { AttachmentOps.delete(cert, AttachmentOps.list(cert).single(), out()); fail("certified") }
        catch (e: SignedDocumentException) { assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, e.reason) }
    }

    @Test fun pairSources() {
        val a = File("a.docx"); val b = File("b.xlsx")
        val one = File("all.pdf"); val pa = File("a.pdf"); val pb = File("b (2).pdf")
        assertEquals(mapOf(one to listOf(a, b)), AttachmentOps.pairSources(listOf(one), listOf(a, b)))
        assertEquals(mapOf(pa to listOf(a), pb to listOf(b)), AttachmentOps.pairSources(listOf(pa, pb), listOf(a, b)))
        assertEquals(mapOf(pa to listOf(a)), AttachmentOps.pairSources(listOf(pa, File("x.pdf"), File("y.pdf")), listOf(a, b)))
        assertTrue(AttachmentOps.pairSources(emptyList(), listOf(a)).isEmpty())
    }

    @Test fun attachSourcesEmbedsTheOriginals() = runBlocking {
        val pdf = plainPdf(1)
        AttachmentOps.attachSources(pdf, listOf(file("src.md", "# hi")), "Source file")
        val att = AttachmentOps.list(pdf).single()
        assertEquals("src.md", att.name); assertEquals("Source file", att.description)
    }

    // ---- NameTreeCos ----

    private fun leaf(vararg keys: String) = COSDictionary().apply {
        setItem(COSName.NAMES, COSArray().apply { keys.forEach { add(COSString(it)); add(COSString("v_$it")) } })
        setItem(COSName.LIMITS, COSArray().apply { add(COSString(keys.first())); add(COSString(keys.last())) })
    }

    @Test fun nameTreeWithKids() {
        val l1 = leaf("a", "c"); val l2 = leaf("m", "p")
        val root = COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(l1); add(l2) }) }
        assertEquals(listOf("a", "c", "m", "p"), NameTreeCos.entries(root).map { it.key })

        val touched = NameTreeCos.put(root, "b", COSString("v_b"))
        assertTrue(touched.contains(l1))
        assertEquals(listOf("a", "b", "c", "m", "p"), NameTreeCos.entries(root).map { it.key })
        NameTreeCos.put(root, "z", COSString("v_z"))
        assertEquals("z", ((l2.getDictionaryObject(COSName.LIMITS) as COSArray).getObject(1) as COSString).string)
        assertNull("root has no /Names when it has kids", root.getDictionaryObject(COSName.NAMES))

        assertNotNull(NameTreeCos.remove(root, "m"))
        assertNotNull(NameTreeCos.remove(root, "p"))
        assertNotNull(NameTreeCos.remove(root, "z"))
        assertEquals("an emptied leaf is unlinked", 1, (root.getDictionaryObject(COSName.KIDS) as COSArray).size())
        assertNull(NameTreeCos.remove(root, "nope"))
        assertEquals("v_c", (NameTreeCos.get(root, "c") as COSString).string)
    }

    @Test fun uniqueKey() {
        assertEquals("a.pdf", NameTreeCos.uniqueKey(emptySet(), "a.pdf"))
        assertEquals("a_3.pdf", NameTreeCos.uniqueKey(setOf("a.pdf", "a_2.pdf"), "a.pdf"))
        assertEquals("readme_2", NameTreeCos.uniqueKey(setOf("readme"), "readme"))
        assertEquals("file", NameTreeCos.uniqueKey(emptySet(), ""))
    }
}
