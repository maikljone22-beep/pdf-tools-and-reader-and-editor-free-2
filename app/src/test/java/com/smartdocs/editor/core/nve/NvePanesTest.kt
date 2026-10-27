package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.nve.panes.DocNotice
import com.smartdocs.editor.core.nve.panes.DocNotices
import com.smartdocs.editor.core.nve.panes.PaneReaders
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Readers behind the reader panes and the document notifications bar (nitro-view-edit, fork F). */
class NvePanesTest {

    private fun tmp() = File.createTempFile("nve_panes", ".pdf").also { it.deleteOnExit() }

    private fun reload(doc: PDDocument): PDDocument { val f = tmp(); doc.save(f); doc.close(); return PDDocument.load(f) }

    private fun spec(doc: PDDocument, name: String, data: String): PDComplexFileSpecification {
        val ef = PDEmbeddedFile(doc, ByteArrayInputStream(data.toByteArray()))
        ef.subtype = "text/plain"; ef.size = data.length
        return PDComplexFileSpecification().apply { file = name; fileUnicode = name; embeddedFile = ef; embeddedFileUnicode = ef; fileDescription = "d-$name" }
    }

    @Test fun attachmentsFromNameTreeAndAnnotations() {
        var doc = PDDocument()
        repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }
        val tree = PDEmbeddedFilesNameTreeNode()
        tree.names = mapOf("a.txt" to spec(doc, "a.txt", "hello"))
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
        val fa = PDAnnotationFileAttachment()
        fa.rectangle = PDRectangle(10f, 10f, 20f, 20f)
        fa.file = spec(doc, "b.csv", "x,y")
        doc.getPage(1).annotations.add(fa)
        doc = reload(doc)
        doc.use { d ->
            val list = PaneReaders.attachments(d)
            assertEquals(2, list.size)
            val a = list.first { it.name == "a.txt" }
            assertNull(a.page); assertEquals("d-a.txt", a.description); assertEquals(5L, a.size)
            assertEquals("hello", String(PaneReaders.attachmentBytes(d, a)!!))
            val b = list.first { it.name == "b.csv" }
            assertEquals(1, b.page)
            assertEquals("x,y", String(PaneReaders.attachmentBytes(d, b)!!))
            assertFalse(PaneReaders.isPortfolio(d))
        }
    }

    @Test fun structureTreeWalk() {
        var doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4)); doc.addPage(PDPage(PDRectangle.A4))
        val root = PDStructureTreeRoot()
        doc.documentCatalog.structureTreeRoot = root
        val top = PDStructureElement("Document", root)
        root.appendKid(top)
        val fig = PDStructureElement("Figure", top)
        fig.alternateDescription = "a chart"; fig.page = doc.getPage(1)
        top.appendKid(fig)
        doc = reload(doc)
        doc.use { d ->
            val tree = PaneReaders.structTree(d)!!
            assertEquals(1, tree.size)
            assertEquals("Document", tree[0].type)
            val f = tree[0].children.single()
            assertEquals("Figure", f.type); assertEquals("a chart", f.alt); assertEquals(1, f.page)
            assertEquals(1, tree[0].page)       // first child's page bubbles up
        }
        PDDocument().use { assertNull(PaneReaders.structTree(it)) }
    }

    @Test fun fieldsListed() {
        var doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4)); doc.addPage(PDPage(PDRectangle.A4))
        val form = PDAcroForm(doc); doc.documentCatalog.acroForm = form
        val tf = PDTextField(form); tf.partialName = "name"; tf.isRequired = true
        val w = tf.widgets[0]; w.rectangle = PDRectangle(50f, 50f, 100f, 20f); w.page = doc.getPage(1)
        doc.getPage(1).annotations.add(w)
        form.fields.add(tf)
        doc = reload(doc)
        doc.use { d ->
            val f = PaneReaders.fields(d).single()
            assertEquals("name", f.name); assertEquals("text", f.type); assertEquals(1, f.page); assertTrue(f.required); assertFalse(f.readOnly)
            assertTrue(PaneReaders.signatureFields(d).isEmpty())
            assertEquals(DocNotice.FORMS, DocNotices.detect(d).first().kind)
        }
    }

    @Test fun namedDestinationsRoundTrip() {
        var doc = PDDocument()
        repeat(3) { doc.addPage(PDPage(PDRectangle.A4)) }
        assertTrue(PaneReaders.addDest(doc, "intro", 0))
        assertTrue(PaneReaders.addDest(doc, "end", 2, top = 300, zoom = 1.5f))
        assertFalse(PaneReaders.addDest(doc, "end", 1))           // duplicate
        // PDF 1.1 /Dests dictionary entry too
        val dests = COSDictionary(); doc.documentCatalog.cosObject.setItem(COSName.DESTS, dests)
        val arr = com.tom_roush.pdfbox.cos.COSArray().apply { add(doc.getPage(1).cosObject); add(COSName.getPDFName("Fit")) }
        dests.setItem(COSName.getPDFName("old"), arr)
        doc = reload(doc)
        doc.use { d ->
            val list = PaneReaders.namedDests(d)
            assertEquals(setOf("intro", "end", "old"), list.map { it.name }.toSet())
            assertEquals(2, list.first { it.name == "end" }.page)
            assertEquals("XYZ", list.first { it.name == "end" }.kind)
            assertEquals(1, list.first { it.name == "old" }.page)
            assertTrue(PaneReaders.renameDest(d, "intro", "start"))
            assertTrue(PaneReaders.renameDest(d, "old", "older"))
            assertFalse(PaneReaders.renameDest(d, "start", "end"))   // target exists
            assertTrue(PaneReaders.deleteDest(d, "end"))
            assertFalse(PaneReaders.deleteDest(d, "missing"))
            val again = reload(d)
            again.use { r ->
                val names = PaneReaders.namedDests(r).associateBy { it.name }
                assertEquals(setOf("start", "older"), names.keys)
                assertEquals(0, names["start"]!!.page); assertEquals(1, names["older"]!!.page)
            }
        }
    }

    @Test fun noticesDetected() {
        var doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))
        doc.documentCatalog.openAction = PDActionJavaScript("app.alert(1)")
        val redact = COSDictionary().apply { setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Redact"); setItem(COSName.RECT, PDRectangle(1f, 1f, 5f, 5f).cosArray) }
        val d3 = COSDictionary().apply { setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "3D"); setItem(COSName.RECT, PDRectangle(1f, 1f, 5f, 5f).cosArray) }
        doc.getPage(0).annotations.add(PDAnnotation.createAnnotation(redact))
        doc.getPage(0).annotations.add(PDAnnotation.createAnnotation(d3))
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" pdfaid:part="2" pdfaid:conformance="B"/>
            <rdf:Description rdf:about="" xmlns:pdfuaid="http://www.aiim.org/pdfua/ns/id/"><pdfuaid:part>1</pdfuaid:part></rdf:Description>
            </rdf:RDF></x:xmpmeta>"""
        val md = PDMetadata(doc); md.importXMPMetadata(xmp.toByteArray()); doc.documentCatalog.metadata = md
        val tree = PDEmbeddedFilesNameTreeNode(); tree.names = mapOf("p1.pdf" to spec(doc, "p1.pdf", "%PDF"), "p2.pdf" to spec(doc, "p2.pdf", "%PDF"))
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("Collection"), COSDictionary())
        doc = reload(doc)
        doc.use { d ->
            val hits = DocNotices.detect(d).associateBy { it.kind }
            assertEquals(1, hits[DocNotice.JAVASCRIPT]!!.count)
            assertEquals(1, hits[DocNotice.PENDING_REDACTIONS]!!.count)
            assertEquals(1, hits[DocNotice.THREE_D]!!.count)
            assertEquals("PDF/A-2b", hits[DocNotice.PDFA]!!.detail)
            assertEquals("PDF/UA-1", hits[DocNotice.PDFUA]!!.detail)
            assertEquals(2, hits[DocNotice.PORTFOLIO]!!.count)
            assertNull(hits[DocNotice.FORMS])
            assertTrue(PaneReaders.isPortfolio(d))
        }
        PDDocument().use { it.addPage(PDPage()); assertTrue(DocNotices.detect(it).isEmpty()) }
    }

    @Test fun fontLicenceBits() {
        assertTrue(DocNotices.fsTypeRestricts(0x0002))
        assertTrue(DocNotices.fsTypeRestricts(0x0004))
        assertFalse(DocNotices.fsTypeRestricts(0x0000))
        assertFalse(DocNotices.fsTypeRestricts(0x0008))
        assertFalse(DocNotices.fsTypeRestricts(0x000C))
        assertNotNull(DocNotices.pdfaClaim("<pdfaid:part>1</pdfaid:part><pdfaid:conformance>A</pdfaid:conformance>"))
        assertEquals("PDF/A-1a", DocNotices.pdfaClaim("<pdfaid:part>1</pdfaid:part><pdfaid:conformance>A</pdfaid:conformance>"))
        assertNull(DocNotices.pdfuaClaim("nothing"))
    }
}
