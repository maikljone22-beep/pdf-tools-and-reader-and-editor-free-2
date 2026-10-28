package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage G (Nitro 20 / Foxit 8): PDF/A levels, claims, print checks, version compatibility, Factur-X, reports. */
class StandardsTest {
    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" xmlns:pdfuaid="http://www.aiim.org/pdfua/ns/id/" pdfaid:part="3">
        <pdfaid:conformance>B</pdfaid:conformance><pdfuaid:part>1</pdfuaid:part><dc:format>application/pdf</dc:format></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun withXmp(d: PDDocument, x: String) { val md = PDMetadata(d); md.importXMPMetadata(x.toByteArray()); d.documentCatalog.metadata = md }

    @Test fun levels() {
        assertEquals("PDF/A-2u", PdfALevel.A2U.label)
        assertTrue(PdfALevel.A1A.needsTags && PdfALevel.A1A.strict1 && PdfALevel.A1A.needsUnicode)
        assertFalse(PdfALevel.A2B.needsUnicode); assertTrue(PdfALevel.A3U.anyAttachments)
    }

    @Test fun xmpClaimsAndRemoval() {
        assertEquals("3", Standards.xmpValue(xmp, "pdfaid:part"))
        assertEquals("B", Standards.xmpValue(xmp, "pdfaid:conformance"))
        assertNull(Standards.xmpValue(xmp, "pdfxid:GTS_PDFXVersion"))
        val stripped = Standards.stripXmp(xmp, setOf("pdfaid"))
        assertFalse(stripped.contains("pdfaid")); assertTrue(stripped.contains("<pdfuaid:part>1</pdfuaid:part>"))
        PDDocument().use { d ->
            d.addPage(PDPage()); withXmp(d, xmp)
            d.documentInformation.cosObject.setString(COSName.getPDFName("GTS_PDFXVersion"), "PDF/X-4")
            val c = Standards.claims(d)
            assertEquals("PDF/A-3b", c.pdfa); assertEquals("PDF/UA-1", c.pdfua); assertEquals("PDF/X-4", c.pdfx)
            Standards.removeClaims(d, setOf("pdfuaid", "pdfxid"))
            val c2 = Standards.claims(d)
            assertEquals("PDF/A-3b", c2.pdfa); assertNull(c2.pdfua); assertNull(c2.pdfx)
        }
    }

    @Test fun versionAndPpi() {
        assertEquals(1.3f, Standards.minVersion(false, false, false, false, 0, false))
        assertEquals(1.4f, Standards.minVersion(true, false, false, false, 0, true))
        assertEquals(1.5f, Standards.minVersion(true, true, false, false, 0, false))
        assertEquals(1.7f, Standards.minVersion(false, false, false, true, 256, false))
        // 300 × 300 px drawn at 72 × 72 pt = 300 ppi; at 144 pt = 150 ppi
        assertEquals(300f, Standards.ppi(300, 300, 72f, 0f, 0f, 72f), 0.01f)
        assertEquals(150f, Standards.ppi(300, 300, 144f, 0f, 0f, 144f), 0.01f)
    }

    @Test fun printChecksFindHairlinesAndOverprint() {
        PDDocument().use { d ->
            val p = PDPage(); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingOverprintControl = true })
                cs.setLineWidth(0.1f); cs.moveTo(10f, 10f); cs.lineTo(100f, 10f); cs.stroke()
                cs.setLineWidth(2f); cs.moveTo(10f, 20f); cs.lineTo(100f, 20f); cs.stroke()
            }
            val f = Standards.printFacts(d, listOf(0))
            assertEquals(1, f.hairlines); assertEquals(setOf(0), f.hairlinePages); assertEquals(1, f.overprint)
            val r = Standards.preflight(d, PreflightProfile.PRINT, listOf(0))
            assertTrue(r.findings.any { it.code == "hairline" && it.count == 1 })
            assertTrue(r.findings.any { it.code == "overprint" })
        }
    }

    @Test fun levelRules() {
        val facts = PdfFacts(1.6f, false, 2, listOf(FontInfo("Foo", true, false, "TrueType")), setOf(1), false, true, "PDF/A-2b", 1, 0, 0, 0, 0, 0, false, emptySet(), 0, false)
        val pf = PrintFacts(layers = true)
        val r1 = Standards.levelReport(facts, pf, PdfALevel.A1B)
        assertTrue(r1.findings.any { it.code == "transparency" && it.severity == Severity.ERROR })
        assertTrue(r1.findings.any { it.code == "a_layers" }); assertTrue(r1.findings.any { it.code == "a_version14" })
        assertTrue(r1.findings.any { it.code == "a_claim_other" })
        val r2u = Standards.levelReport(facts, pf, PdfALevel.A2U)
        assertTrue(r2u.findings.any { it.code == "unicode" && it.severity == Severity.ERROR })
        assertFalse(r2u.findings.any { it.code == "a_layers" })
        assertTrue(Standards.levelReport(facts, pf, PdfALevel.A2A).findings.any { it.code == "a_tags" })
    }

    @Test fun facturX() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            assertTrue(Standards.zugferd(d, Standards.claims(d)).any { it.code == "zf_xml" })
            Embed.addFiles(d, listOf(Attachment("factur-x.xml", "text/xml", "<x/>".toByteArray())))
            Standards.associateAttachments(d)
            Embed.all(d).values.first().cosObject.setItem(COSName.getPDFName("AFRelationship"), COSName.getPDFName("Alternative"))
            withXmp(d, xmp.replace("<dc:format>", "<fx:ConformanceLevel>EN 16931</fx:ConformanceLevel><dc:format>"))
            val f = Standards.zugferd(d, Standards.claims(d))
            assertEquals(listOf("zf_ok"), f.map { it.code })
        }
    }

    @Test fun reportFormats() {
        val r = PreflightReport(PdfFacts(1.7f, false, 1, emptyList(), emptySet(), false, true, null, 0, 0, 0, 0, 0, 0, false, emptySet(), 0, false),
            listOf(Finding("fonts", Severity.ERROR, 2, listOf(0, 3)), Finding("xmp", Severity.ERROR, fixed = true)))
        val label = { c: String -> "L-$c" }
        assertTrue(Standards.reportText("a.pdf", "P", r, label, "xml").contains("<finding code=\"fonts\" severity=\"ERROR\" count=\"2\" pages=\"1,4\">L-fonts</finding>"))
        assertTrue(Standards.reportText("a.pdf", "P", r, label, "txt").contains("[OK] L-xmp"))
        assertTrue(Standards.reportText("a.pdf", "P", r, label, "html").contains("class=\"ERROR\""))
    }
}
