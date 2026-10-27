package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.edit5.DocPropsOps
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintOptions
import com.smartdocs.editor.core.view5.PrintPrep
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NVE-file: print layout / prep extras, properties (base URL, print preset), XMP import/export, PDF/UA, facts and fonts. */
class NveFileTest {

    private fun tmp(name: String) = File.createTempFile("nve_$name", ".pdf").also { it.deleteOnExit() }

    /**
     * [pages] A4 pages showing "Page n" with a Helvetica font dictionary written at COS level (JVM tests have no
     * Android assets, so PdfBox's standard-14 metrics cannot be loaded); a sticky note on each page in [notes].
     */
    private fun doc(pages: Int, notes: Set<Int> = emptySet(), build: (PDDocument) -> Unit = {}): File {
        val f = tmp("src")
        PDDocument().use { d ->
            val font = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, COSName.getPDFName("Type1"))
                setName(COSName.BASE_FONT, "Helvetica"); setName(COSName.ENCODING, "WinAnsiEncoding")
            }
            repeat(pages) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                val fonts = COSDictionary().apply { setItem(COSName.getPDFName("F1"), font) }
                p.resources = PDResources(COSDictionary().apply { setItem(COSName.FONT, fonts) })
                val st = PDStream(d)
                st.createOutputStream().use { it.write("BT /F1 12 Tf 72 700 Td (Page ${i + 1}) Tj ET 0 0 1 rg 50 50 100 100 re f".toByteArray()) }
                p.setContents(st)
                if (i in notes) p.annotations = listOf(PDAnnotationText().apply {
                    rectangle = PDRectangle(100f, 100f, 20f, 20f); contents = "Note on ${i + 1}"
                    cosObject.setString(COSName.T, "Ali"); cosObject.setString(COSName.M, "D:20240501102030")
                })
            }
            build(d)
            d.save(f)
        }
        return f
    }

    private fun pages(f: File) = PDDocument.load(f).use { it.numberOfPages }

    // ---------------------------------------------------------------- layout math

    @Test fun markupsOnlyKeepsAnnotatedPages() = runBlocking {
        val src = doc(4, notes = setOf(1, 3))
        PDDocument.load(src).use { assertEquals(setOf(1, 3), PrintExtras.markupPages(it)) }
        val out = tmp("mk")
        val n = PrintPrep.build(src, null, PrintOptions(markedOnly = true), out)
        assertEquals(2, n); assertEquals(2, pages(out))
    }

    @Test fun grayscaleCopiesAndSummary() = runBlocking {
        val src = doc(2, notes = setOf(0))
        val out = tmp("grey")
        // the comment summary collects type, author, date and text of the printed pages (its drawing needs real fonts: device only)
        PDDocument.load(src).use { d ->
            val c = PrintExtras.comments(d)
            assertEquals(1, c.size)
            assertEquals(PrintExtras.Comment(0, "Text", "Ali", "2024-05-01 10:20", "Note on 1"), c[0])
            assertTrue(PrintExtras.comments(d, listOf(1)).isEmpty())
        }
        assertFalse(PrintPrep.isPlain(PrintOptions(markedOnly = true), 2))
        assertTrue(PrintPrep.isPlain(PrintOptions(), 2))
    }

    @Test fun baseUrlAndDocPrintPresetRoundTrip() = runBlocking {
        val src = doc(6)
        val p = DocPropsOps.read(src, null)
        val out = tmp("props")
        DocPropsOps.write(src, out, null, p.copy(baseUrl = "https://example.com/a/",
            view = p.view.copy(printScaling = "None", duplex = "DuplexFlipLongEdge", pickTrayByPdfSize = true, printPageRange = "2-3, 5", numCopies = 3)))
        val r = DocPropsOps.read(out, null)
        assertEquals("https://example.com/a/", r.baseUrl)
        assertEquals("2-3, 5", r.view.printPageRange)
        assertEquals(3, r.view.numCopies)
        assertTrue(r.view.pickTrayByPdfSize)
        PDDocument.load(out).use { d ->
            assertEquals("https://example.com/a/", DocInspect.baseUrl(d))
        }
        // clearing the base URL removes the /URI dictionary
        val out2 = tmp("props2")
        DocPropsOps.write(out, out2, null, r.copy(baseUrl = ""))
        PDDocument.load(out2).use { assertEquals("", DocInspect.baseUrl(it)) }
    }

    @Test fun xmpExportImportReplaceAndMerge() {
        val first = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
            <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old title</rdf:li></rdf:Alt></dc:title><pdf:Keywords>keep</pdf:Keywords></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val second = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">New title</rdf:li></rdf:Alt></dc:title>
            <dc:creator><rdf:Seq><rdf:li>Mona</rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        val f = tmp("xmp")
        PDDocument().use { d ->
            d.addPage(PDPage())
            XmpOps.import(d, first.toByteArray(), XmpOps.Mode.REPLACE)
            assertEquals("Old title", d.documentInformation.title)
            XmpOps.import(d, second.toByteArray(), XmpOps.Mode.MERGE)
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val x = String(XmpOps.export(d)!!, Charsets.UTF_8)
            assertTrue(x.contains("New title")); assertFalse(x.contains("Old title")); assertTrue(x.contains("keep")); assertTrue(x.contains("Mona"))
            assertEquals("New title", d.documentInformation.title); assertEquals("Mona", d.documentInformation.author)
            XmpOps.import(d, second.toByteArray(), XmpOps.Mode.REPLACE)
            assertFalse(String(XmpOps.export(d)!!, Charsets.UTF_8).contains("keep"))
        }
        assertFalse(XmpOps.isXmp("<html/>".toByteArray()))
    }

    @Test fun markPdfUa() {
        val f = tmp("ua")
        PDDocument().use { d ->
            d.addPage(PDPage()); d.documentInformation.title = "Report"
            val w = XmpOps.markPdfUa(d, "ar")
            assertEquals(listOf(XmpOps.UaWarning.UNTAGGED), w)
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val facts = DocInspect.facts(f, d)
            assertEquals(1, facts.pdfuaClaim)
            assertEquals("ar", facts.lang)
            assertTrue(facts.tagged)
            assertTrue(d.documentCatalog.cosObject.let { c -> (c.getDictionaryObject(COSName.VIEWER_PREFERENCES) as com.tom_roush.pdfbox.cos.COSDictionary).getBoolean(COSName.getPDFName("DisplayDocTitle"), false) })
        }
    }

    // ---------------------------------------------------------------- facts, fonts, security

    @Test fun factsFontsAndSecurity() {
        val src = doc(2)
        PDDocument.load(src).use { d ->
            val facts = DocInspect.facts(src, d)
            assertEquals("A4", facts.firstPage?.name)
            assertEquals(210f, facts.firstPage!!.widthMm, 1f)
            assertFalse(facts.linearized); assertFalse(facts.security.encrypted); assertFalse(facts.locked)
            val fonts = DocInspect.fonts(d)
            assertEquals(1, fonts.size)
            val h = fonts[0]
            assertEquals("Helvetica", h.name); assertEquals("Type1", h.type); assertFalse(h.embedded); assertFalse(h.subset)
            assertEquals(setOf(0, 1), h.pages)
        }
        // encrypted with print forbidden, opened with the user password
        val enc = tmp("enc")
        PDDocument.load(src).use { d ->
            val ap = AccessPermission().apply { setCanPrint(false); setCanModify(false) }
            d.protect(StandardProtectionPolicy("owner", "user", ap).apply { encryptionKeyLength = 128; setPreferAES(true) })
            d.save(enc)
        }
        val facts = DocInspect.read(enc, "user")
        assertTrue(facts.security.encrypted)
        assertEquals("AES", facts.security.method); assertEquals(128, facts.security.keyBits)
        assertFalse(facts.security.permissions.print); assertFalse(facts.security.permissions.modify)
        assertTrue(facts.security.permissions.copy)
        assertTrue(facts.locked)
        val owner = DocInspect.read(enc, "owner")
        assertTrue(owner.security.owner); assertTrue(owner.security.permissions.print)
    }

    @Test fun permissionBitsAndNames() {
        val all = DocInspect.decode(-1)
        assertFalse(all.restricted)
        val noPrint = DocInspect.decode(-1 and (1 shl 2).inv())
        assertFalse(noPrint.print); assertFalse(noPrint.printHighQuality)
        assertEquals("Letter", DocInspect.paperName(792f, 612f))
        assertNull(DocInspect.paperName(500f, 500f))
        assertNotNull(PrintExtras.prettyDate("D:20240501102030+03'00'"))
        assertEquals("2024-05-01 10:20", PrintExtras.prettyDate("D:20240501102030+03'00'"))
    }
}
