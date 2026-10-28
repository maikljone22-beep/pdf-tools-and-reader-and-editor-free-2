package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** audit 5 R-1 (rasterised page still reachable), R-3 (whole-page wipe keeps form values / ActualText), R-4, R-9. */
class PageScrubTest {
    private val secret = "TOPSECRET-NAME-4711"
    private val n = COSName::getPDFName

    private fun content(doc: PDDocument, page: PDPage, ops: String) {
        val s = PDStream(doc)
        s.createOutputStream().use { it.write(ops.toByteArray(Charsets.ISO_8859_1)) }
        page.setContents(s)
    }

    private fun bytes(doc: PDDocument) = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    /** Raw bytes, decoded streams and every string of every object of the saved file. */
    private fun assertNowhere(saved: ByteArray, vararg words: String) {
        val raw = String(saved, Charsets.ISO_8859_1)
        words.forEach { assertFalse("raw file holds $it", raw.contains(it)) }
        PDDocument.load(saved).use { d ->
            for (o in d.document.objects) {
                val b = o.getObject()
                val texts = ArrayList<String>()
                if (b is COSStream) texts += String(b.createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1)
                if (b is COSDictionary) for (k in b.keySet()) (b.getDictionaryObject(k) as? COSString)?.let { texts += it.string }
                if (b is COSString) texts += b.string
                words.forEach { w -> texts.forEach { t -> assertFalse("object ${o.objectNumber} holds $w", t.contains(w)) } }
            }
            // (no PDFTextStripper here: the sample's non-embedded Helvetica needs the standard-14 fonts, which cannot
            // load on the JVM; every decoded stream and string of every object was checked above instead)
        }
    }

    /** Page 1: header text in a form XObject, a filled text field, a tagged paragraph; outline, named dest, OpenAction and a link on page 2 point at it. */
    private fun sample(): PDDocument {
        val doc = PDDocument()
        val p1 = PDPage(PDRectangle.A4); val p2 = PDPage(PDRectangle.A4)
        doc.addPage(p1); doc.addPage(p2)
        val font = COSDictionary().apply { setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, n("Type1")); setItem(n("BaseFont"), n("Helvetica")) }
        val form = doc.document.createCOSStream().apply {
            setItem(COSName.TYPE, COSName.XOBJECT); setItem(COSName.SUBTYPE, COSName.FORM)
            setItem(COSName.BBOX, PDRectangle.A4.cosArray)
            setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.FONT, COSDictionary().apply { setItem(n("F1"), font) }) })
            createRawOutputStream().use { it.write("BT /F1 12 Tf 72 800 Td ($secret) Tj ET".toByteArray()) }
        }
        p1.resources = PDResources(COSDictionary().apply { setItem(COSName.XOBJECT, COSDictionary().apply { setItem(n("Fm1"), form) }) })
        content(doc, p1, "/P <</MCID 0>> BDC q /Fm1 Do Q EMC")
        content(doc, p2, "q 0 0 10 10 re f Q")
        // a filled field whose widget is on page 1
        val ap = doc.document.createCOSStream().apply {
            setItem(COSName.TYPE, COSName.XOBJECT); setItem(COSName.SUBTYPE, COSName.FORM); setItem(COSName.BBOX, PDRectangle(0f, 0f, 100f, 20f).cosArray)
            createRawOutputStream().use { it.write("BT /F1 10 Tf 2 5 Td (FIELD-$secret) Tj ET".toByteArray()) }
        }
        val field = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, n("Widget")); setItem(COSName.FT, n("Tx"))
            setString(COSName.T, "name"); setString(COSName.V, "V-$secret"); setString(COSName.DV, "DV-$secret")
            setItem(COSName.RECT, PDRectangle(72f, 700f, 100f, 20f).cosArray); setItem(COSName.P, p1.cosObject)
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, ap) })
        }
        p1.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(field) })
        doc.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, COSDictionary().apply { setItem(COSName.FIELDS, COSArray().apply { add(field) }) })
        // structure: a /P with the logical text of page 1
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT) }
        val el = COSDictionary().apply {
            setItem(COSName.TYPE, n("StructElem")); setItem(COSName.S, n("P")); setItem(COSName.P, root)
            setItem(COSName.PG, p1.cosObject); setString(n("ActualText"), "AT-$secret"); setString(n("Alt"), "ALT-$secret"); setItem(COSName.K, COSInteger.get(0))
        }
        root.setItem(COSName.K, COSArray().apply { add(el) })
        doc.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, root)
        p1.cosObject.setInt(n("StructParents"), 0)
        // navigation to page 1
        val outline = PDDocumentOutline(); doc.documentCatalog.documentOutline = outline
        outline.addLast(PDOutlineItem().apply { title = "Intro"; destination = PDPageFitDestination().apply { setPage(p1) } })
        val fit = { COSArray().apply { add(p1.cosObject); add(n("Fit")) } }
        doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply {
            setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.NAMES, COSArray().apply { add(COSString("intro")); add(fit()) }) })
        })
        doc.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply { setItem(n("old"), fit()) })
        doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, fit())
        val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 50f, 20f); action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { setPage(p1) } } }
        p2.annotations = listOf(link)
        return doc
    }

    @Test fun maxSecurityReplacementLeavesNoReachableCopyOfThePage() {
        sample().use { assertTrue(String(bytes(it), Charsets.ISO_8859_1).contains(secret)) }   // the sample really holds it
        val saved = sample().use { doc ->
            val np = PDPage(PDRectangle.A4)   // the rasterised copy (blank here: an image in the app)
            assertTrue(PageScrub.replacePage(doc, doc.getPage(0), np) >= 5)
            RedactCleanup.pruneUnusedXObjects(doc)
            bytes(doc)
        }
        assertNowhere(saved, secret)
        PDDocument.load(saved).use { d ->
            assertEquals(2, d.numberOfPages)
            val first = d.getPage(0).cosObject
            fun isFirst(b: com.tom_roush.pdfbox.cos.COSBase?) = b === first
            // outline, link, OpenAction, named and catalog destinations go to the new page
            val item = d.documentCatalog.documentOutline.firstChild
            assertEquals(0, d.pages.indexOf((item.destination as PDPageDestination).page))
            val l = d.getPage(1).annotations.single() as PDAnnotationLink
            assertEquals(0, d.pages.indexOf(((l.action as PDActionGoTo).destination as PDPageDestination).page))
            assertTrue(isFirst((d.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as COSArray).getObject(0)))
            val names = (d.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as COSDictionary).getDictionaryObject(COSName.DESTS) as COSDictionary
            assertTrue(isFirst(((names.getDictionaryObject(COSName.NAMES) as COSArray).getObject(1) as COSArray).getObject(0)))
            val old = (d.documentCatalog.cosObject.getDictionaryObject(COSName.DESTS) as COSDictionary).getDictionaryObject(n("old")) as COSArray
            assertTrue(isFirst(old.getObject(0)))
            // structure: re-pointed, no text, no marked content of the old page
            val root = d.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as COSDictionary
            val el = (root.getDictionaryObject(COSName.K) as COSArray).getObject(0) as COSDictionary
            assertTrue(isFirst(el.getDictionaryObject(COSName.PG)))
            assertNull(el.getDictionaryObject(n("ActualText"))); assertNull(el.getDictionaryObject(COSName.K))
            // the field of the old page's widget is gone
            val fields = (d.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as COSDictionary).getDictionaryObject(COSName.FIELDS) as COSArray
            assertEquals(0, fields.size())
        }
    }

    @Test fun wholePageWipeRemovesFieldValuesAndStructureText() {
        val saved = sample().use { doc ->
            val p1 = doc.getPage(0); val p2 = doc.getPage(1)
            // a radio-like field with one widget on each page: only the page-1 widget goes, the field stays
            val parent = COSDictionary().apply { setItem(COSName.FT, n("Btn")); setString(COSName.T, "shared") }
            val w1 = COSDictionary().apply { setItem(COSName.SUBTYPE, n("Widget")); setItem(COSName.PARENT, parent); setItem(COSName.P, p1.cosObject); setItem(COSName.RECT, PDRectangle(0f, 0f, 10f, 10f).cosArray) }
            val w2 = COSDictionary().apply { setItem(COSName.SUBTYPE, n("Widget")); setItem(COSName.PARENT, parent); setItem(COSName.P, p2.cosObject); setItem(COSName.RECT, PDRectangle(0f, 0f, 10f, 10f).cosArray) }
            parent.setItem(COSName.KIDS, COSArray().apply { add(w1); add(w2) })
            (p1.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray).add(w1)
            p2.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(w2) })
            ((doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as COSDictionary).getDictionaryObject(COSName.FIELDS) as COSArray).add(parent)
            // an ancestor element summarising the page-1 line
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as COSDictionary
            val el = (root.getDictionaryObject(COSName.K) as COSArray).getObject(0) as COSDictionary
            val sect = COSDictionary().apply { setItem(COSName.S, n("Sect")); setString(n("ActualText"), "SECT-$secret"); setItem(COSName.K, COSArray().apply { add(el) }) }
            root.setItem(COSName.K, COSArray().apply { add(sect) })
            assertEquals(2, PageScrub.wipePage(doc, p1))   // the text field and the page-1 radio widget
            bytes(doc)
        }
        assertNowhere(saved, "V-$secret", "DV-$secret", "FIELD-$secret", "AT-$secret", "ALT-$secret", "SECT-$secret")
        PDDocument.load(saved).use { d ->
            assertEquals(2, d.numberOfPages)
            val fields = (d.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as COSDictionary).getDictionaryObject(COSName.FIELDS) as COSArray
            assertEquals(1, fields.size())
            val shared = fields.getObject(0) as COSDictionary
            assertEquals("shared", shared.getString(COSName.T))
            assertEquals(1, (shared.getDictionaryObject(COSName.KIDS) as COSArray).size())
            assertTrue(d.getPage(0).annotations.isEmpty())
            assertEquals(1, d.getPage(1).annotations.count { it.subtype == "Widget" })
        }
    }

    @Test fun signatureValuesAreRemovedFromARewrittenOutput() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val sig = COSDictionary().apply { setItem(COSName.TYPE, COSName.SIG); setItem(COSName.FILTER, n("Adobe.PPKLite")) }
            val field = COSDictionary().apply { setItem(COSName.FT, COSName.SIG); setString(COSName.T, "s1"); setItem(COSName.V, sig) }
            val form = COSDictionary().apply { setItem(COSName.FIELDS, COSArray().apply { add(field) }); setInt(n("SigFlags"), 3) }
            doc.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, form)
            doc.documentCatalog.cosObject.setItem(COSName.PERMS, COSDictionary().apply { setItem(COSName.DOCMDP, sig) })
            assertTrue(com.smartdocs.editor.core.pdf.SignedSave.isSigned(doc))
            assertEquals(1, PageScrub.stripSignatures(doc))
            assertFalse(com.smartdocs.editor.core.pdf.SignedSave.isSigned(doc))
            assertNull(field.getDictionaryObject(COSName.V))
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.PERMS))
            assertFalse(form.containsKey(n("SigFlags")))
        }
    }

    @Test fun sanitizeCommentsRemovesRedactMarksAndTheirReplies() {
        val saved = PDDocument().use { doc ->
            val p = PDPage(); doc.addPage(p)
            val redact = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, n("Redact")); setItem(COSName.RECT, PDRectangle(10f, 10f, 50f, 20f).cosArray)
                setString(COSName.CONTENTS, "C-$secret"); setString(n("OverlayText"), "O-$secret")
            }
            val reply = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, n("Text")); setItem(COSName.RECT, PDRectangle(10f, 10f, 50f, 20f).cosArray)
                setItem(n("IRT"), redact); setString(n("StateModel"), "Review"); setString(n("State"), "Accepted")
            }
            val wm = COSDictionary().apply { setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, n("Watermark")); setItem(COSName.RECT, PDRectangle(0f, 0f, 5f, 5f).cosArray); setString(COSName.CONTENTS, "W-$secret") }
            p.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(redact); add(reply); add(wm) })
            val r = Sanitizer.clean(doc, setOf(SanitizeItem.COMMENTS))
            assertEquals(3, r[SanitizeItem.COMMENTS])
            assertTrue(p.annotations.isEmpty())
            bytes(doc)
        }
        assertNowhere(saved, "C-$secret", "O-$secret", "W-$secret")
    }

    @Test fun redirectReachesOnlyTheGivenPage() {
        PDDocument().use { doc ->
            val a = PDPage(); val b = PDPage(); doc.addPage(a); doc.addPage(b)
            val outline = PDDocumentOutline(); doc.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "B"; destination = PDPageFitDestination().apply { setPage(b) } })
            val c = PDPage(); doc.pages.insertAfter(c, a); doc.removePage(a)
            assertEquals(0, PageScrub.redirect(doc, a.cosObject, c.cosObject, emptySet()))
            val dest = doc.documentCatalog.documentOutline.firstChild.destination as PDPageDestination
            assertNotNull(dest.page); assertEquals(1, doc.pages.indexOf(dest.page))
        }
    }
}
