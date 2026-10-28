package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * audit 5 (reader Tools / Pages tabs): outputs keep the protection of their source (T-03 / T-05 / G-03 / E-3 / C-4),
 * new protections are AES-256 with Unicode passwords and a real owner password (T-12 / P-2 / P-5), blank pages follow
 * their neighbour (T-11), XMP follows the properties (T-16), placement on the displayed page (T-10), the result sheet's
 * replace target (E-1) and the reader password hand-over (T-09).
 */
class TabToolsProtectionTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 3 pages (page 2 /Rotate 90), AES [bits], owner "ownerpw", user [user], no print, no copy. */
    private fun restricted(bits: Int = 128, user: String = ""): File {
        val f = tmp.newFile("restricted_${bits}_${user.length}.pdf")
        PDDocument().use { d ->
            repeat(3) { i -> val p = PDPage(PDRectangle.A4); if (i == 1) p.rotation = 90; d.addPage(p) }
            val ap = AccessPermission().apply { setCanPrint(false); setCanExtractContent(false); setCanModify(true); setCanAssembleDocument(true) }
            d.protect(StandardProtectionPolicy("ownerpw", user, ap).apply { encryptionKeyLength = bits; setPreferAES(true) })
            d.save(f)
        }
        return f
    }

    private fun assertRestricted(f: File, open: String, bits: Int) {
        PDDocument.load(f, open).use { d ->
            assertTrue("encrypted", d.isEncrypted)
            assertFalse("print stays forbidden", d.currentAccessPermission.canPrint())
            assertFalse("copy stays forbidden", d.currentAccessPermission.canExtractContent())
            assertFalse("no owner rights", d.currentAccessPermission.isOwnerPermission)
            assertEquals("key length kept", bits, d.encryption.length)
        }
    }

    @Test fun extractKeepsProtection() = runBlocking {
        for (bits in listOf(128, 256)) {
            val src = restricted(bits)
            val out = tmp.newFile("ex$bits.pdf")
            PdfOps.keepPages(src, listOf(0, 1), out, null)       // opened without a password (permissions-only file)
            assertRestricted(out, "", bits)
            PDDocument.load(out).use { d -> assertEquals(2, d.numberOfPages); assertEquals(90, d.getPage(1).rotation) }
        }
    }

    @Test fun extractOpenedWithOwnerKeepsOwnerPassword() = runBlocking {
        val src = restricted(128)
        val out = tmp.newFile("exo.pdf")
        PdfOps.keepPages(src, listOf(2), out, "ownerpw")
        assertRestricted(out, "", 128)                                    // the user password (empty) still opens it, restricted
        PDDocument.load(out, "ownerpw").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
    }

    @Test fun extractWithUserPasswordNeedsIt() = runBlocking {
        val src = restricted(128, user = "u1")
        val out = tmp.newFile("exu.pdf")
        PdfOps.keepPages(src, listOf(0), out, "u1")
        try { PDDocument.load(out).close(); fail("opened without the user password") } catch (e: InvalidPasswordException) { }
        assertRestricted(out, "u1", 128)
    }

    @Test fun splitKeepsProtectionAndNeverOverwrites() = runBlocking {
        val src = restricted(128)
        val dir = tmp.newFolder("parts")
        val first = PdfOps.split(src, dir, "doc", 1, null)
        val second = PdfOps.split(src, dir, "doc", 2, null)
        assertEquals(3, first.size); assertEquals(2, second.size)
        assertTrue("no name reused", first.map { it.name }.intersect(second.map { it.name }.toSet()).isEmpty())
        (first + second).forEach { assertRestricted(it, "", 128) }
    }

    @Test fun extractKeepsFieldsAndOutline() = runBlocking {
        val src = tmp.newFile("form.pdf")
        PDDocument().use { d ->
            val p1 = PDPage(PDRectangle.A4); val p2 = PDPage(PDRectangle.A4); d.addPage(p1); d.addPage(p2)
            // raw COS: PDAcroForm / PDTextField pull in the standard 14 fonts, which PdfBox-Android cannot load on the JVM
            val field = com.tom_roush.pdfbox.cos.COSDictionary().apply {
                setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Widget"); setName(COSName.FT, "Tx")
                setString(COSName.T, "name"); setString(COSName.V, "Ali"); setItem(COSName.P, p2.cosObject)
                setItem(COSName.RECT, PDRectangle(50f, 700f, 200f, 20f).cosArray)
            }
            p2.cosObject.setItem(COSName.ANNOTS, com.tom_roush.pdfbox.cos.COSArray().apply { add(field) })
            d.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, com.tom_roush.pdfbox.cos.COSDictionary().apply {
                setItem(COSName.FIELDS, com.tom_roush.pdfbox.cos.COSArray().apply { add(field) })
            })
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "Two"; destination = PDPageFitDestination().apply { page = p2 } })
            d.save(src)
        }
        val out = tmp.newFile("form_ex.pdf")
        PdfOps.keepPages(src, listOf(1), out, null)
        PDDocument.load(out).use { d ->
            val acro = d.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? com.tom_roush.pdfbox.cos.COSDictionary
            assertNotNull("form kept", acro)
            val fields = acro!!.getDictionaryObject(COSName.FIELDS) as com.tom_roush.pdfbox.cos.COSArray
            val f = fields.getObject(0) as com.tom_roush.pdfbox.cos.COSDictionary
            assertEquals("name", f.getString(COSName.T)); assertEquals("Ali", f.getString(COSName.V))
            val annots = d.getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS) as com.tom_roush.pdfbox.cos.COSArray
            assertEquals(1, annots.size())
            assertEquals("the field is the page's widget", f, annots.getObject(0))
            assertEquals("widget points at the new page", d.getPage(0).cosObject, f.getDictionaryObject(COSName.P))
            val item = d.documentCatalog.documentOutline?.firstChild
            assertEquals("Two", item?.title)
        }
    }

    @Test fun arabicPasswordAes256AndRealOwner() = runBlocking {
        val src = tmp.newFile("plain.pdf"); PDDocument().use { d -> d.addPage(PDPage()); d.save(src) }
        val out = tmp.newFile("prot.pdf")
        val owner = PdfOps.protect(src, out, "سرّي", "", allowPrint = false)
        assertNotEquals("owner is not the open password", "سرّي", owner)
        assertNotEquals("owner is not a fixed word", "owner", owner)
        PDDocument.load(out, "سرّي").use { d ->
            assertEquals(256, d.encryption.length)
            assertFalse(d.currentAccessPermission.canPrint())
            assertFalse(d.currentAccessPermission.isOwnerPermission)
        }
        for (bad in listOf("????", "owner", "")) try { PDDocument.load(out, bad).close(); fail("opened with '$bad'") } catch (e: InvalidPasswordException) { }
        PDDocument.load(out, owner).use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
        // an owner password equal to the open password is replaced by a random one
        val out2 = tmp.newFile("prot2.pdf")
        val owner2 = PdfOps.protect(src, out2, "pw", "pw", allowCopy = false)
        assertNotEquals("pw", owner2)
        PDDocument.load(out2, "pw").use { assertFalse(it.currentAccessPermission.isOwnerPermission) }
        // empty open password + restrictions: "owner" does not lift them
        val out3 = tmp.newFile("prot3.pdf")
        PdfOps.protect(src, out3, "", "", allowCopy = false)
        try { PDDocument.load(out3, "owner").close(); fail("'owner' opened it") } catch (e: InvalidPasswordException) { }
        PDDocument.load(out3).use { assertFalse(it.currentAccessPermission.isOwnerPermission) }
    }

    @Test fun blankPageFollowsItsNeighbour() = runBlocking {
        val src = tmp.newFile("rot.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle.A4))
            d.addPage(PDPage(PDRectangle.A4).apply { rotation = 90; cropBox = PDRectangle(10f, 10f, 500f, 700f) })
            d.save(src)
        }
        val out = tmp.newFile("rot_out.pdf")
        PdfOps.insertBlankPage(src, 2, out)                   // after the rotated page
        PDDocument.load(out).use { d ->
            val p = d.getPage(2)
            assertEquals(90, p.rotation)
            assertEquals(500f, p.mediaBox.width, 0.5f); assertEquals(700f, p.mediaBox.height, 0.5f)
        }
    }

    @Test fun xmpUpdateKeepsOtherProperties() {
        val x = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" pdfaid:part="2" pdfaid:conformance="B"/>""" +
            """<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old</rdf:li></rdf:Alt></dc:title></rdf:Description>""" +
            """</rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        val u = XmpInfo.update(x, "عنوان جديد", "Ali", "", "", "", "")!!
        assertTrue(u.contains("pdfaid:part=\"2\""))
        assertFalse(u.contains(">Old<"))
        assertTrue(u.contains("عنوان جديد"))
        assertTrue(u.contains("<rdf:li>Ali</rdf:li>"))
        assertEquals(1, Regex("<dc:title>").findAll(u).count())
    }

    @Test fun displayPlaceMapsRotatedAndCroppedPages() {
        val p = DisplayPlace(10f, 20f, 600f, 800f, 90)
        assertEquals(800f, p.width, 0f); assertEquals(600f, p.height, 0f)
        // displayed bottom-left corner of a page turned clockwise = user-space bottom-right
        p.toUser(0f, 0f).let { assertEquals(610f, it[0], 0.01f); assertEquals(20f, it[1], 0.01f) }
        DisplayPlace(10f, 20f, 600f, 800f, 0).toUser(5f, 5f).let { assertEquals(15f, it[0], 0f); assertEquals(25f, it[1], 0f) }
        DisplayPlace(0f, 0f, 600f, 800f, 270).toUser(0f, 0f).let { assertEquals(0f, it[0], 0f); assertEquals(800f, it[1], 0f) }
        DisplayPlace(0f, 0f, 600f, 800f, 180).toUser(0f, 0f).let { assertEquals(600f, it[0], 0f); assertEquals(800f, it[1], 0f) }
    }

    @Test fun resultSheetReplacesOnlyTheProcessedFile() {
        val a = tmp.newFile("A.pdf"); val b = tmp.newFile("B.pdf")
        val fromB = tmp.newFile("B_cropped.pdf"); val fromA = tmp.newFile("A_cropped.pdf"); val report = tmp.newFile("A_compare.pdf")
        ToolOutputs.record(fromB, b); ToolOutputs.record(fromA, a)
        assertFalse("tool opened on A, B processed: A is never replaced", ToolOutputs.replaces(fromB, a))
        assertTrue(ToolOutputs.replaces(fromA, a))
        assertFalse("reports are never recorded", ToolOutputs.replaces(report, a))
        assertFalse(ToolOutputs.replaces(fromA, null))
    }

    @Test fun readerPasswordHandOver() {
        val f = tmp.newFile("locked.pdf")
        SessionPasswords.put(f, "pw", now = 1_000L)
        assertEquals("pw", SessionPasswords.get(File(f.parentFile, "./" + f.name), now = 2_000L))
        assertNull("expires", SessionPasswords.get(f, now = 2_000L + SessionPasswords.TTL_MS + 1))
        SessionPasswords.put(f, "pw"); SessionPasswords.forget(f); assertNull(SessionPasswords.get(f))
    }
}
