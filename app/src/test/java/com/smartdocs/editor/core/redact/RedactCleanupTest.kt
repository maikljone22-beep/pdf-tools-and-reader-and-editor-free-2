package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.edit.ContentRewriter
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Audit AN-07 (removed images stay in the file) and AN-09 (/ActualText survives redaction). */
class RedactCleanupTest {

    private fun content(doc: PDDocument, page: PDPage, ops: String) {
        val s = PDStream(doc)
        s.createOutputStream().use { it.write(ops.toByteArray(Charsets.ISO_8859_1)) }
        page.setContents(s)
    }

    /** A 1×1 gray image whose (unfiltered) stream data is [marker], so the saved bytes can be searched for it. */
    private fun image(doc: PDDocument, marker: String) = doc.document.createCOSStream().apply {
        setItem(COSName.TYPE, COSName.XOBJECT); setItem(COSName.SUBTYPE, COSName.IMAGE)
        setInt(COSName.WIDTH, 1); setInt(COSName.HEIGHT, marker.length); setInt(COSName.BITS_PER_COMPONENT, 8)
        setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        createRawOutputStream().use { it.write(marker.toByteArray()) }
    }

    private fun saved(doc: PDDocument) = ByteArrayOutputStream().also { doc.save(it) }.toByteArray().toString(Charsets.ISO_8859_1)

    @Test fun removedImageIsGoneFromTheFile_sharedResourcesKeepWhatOtherPagesUse() {
        PDDocument().use { doc ->
            val p1 = PDPage(); val p2 = PDPage(); doc.addPage(p1); doc.addPage(p2)
            val xo = COSDictionary().apply {
                setItem(COSName.getPDFName("Im1"), image(doc, "PIXELS-ON-PAGE-TWO"))
                setItem(COSName.getPDFName("Im2"), image(doc, "REDACTED-ID-PHOTO"))     // its Do was removed by redaction
                setItem(COSName.getPDFName("Rd0"), image(doc, "BLANKED-COPY"))
            }
            val shared = PDResources(COSDictionary().apply { setItem(COSName.XOBJECT, xo) })
            p1.resources = shared; p2.resources = shared
            content(doc, p1, "q /Rd0 Do Q")
            content(doc, p2, "q /Im1 Do Q")
            assertEquals(1, RedactCleanup.pruneUnusedXObjects(doc))
            val bytes = saved(doc)
            assertFalse(bytes.contains("REDACTED-ID-PHOTO"))
            assertTrue(bytes.contains("PIXELS-ON-PAGE-TWO")); assertTrue(bytes.contains("BLANKED-COPY"))
        }
    }

    @Test fun inheritedResourcesNoPageUsesArePruned() {
        PDDocument().use { doc ->
            val p = PDPage(); doc.addPage(p)
            val xo = COSDictionary().apply { setItem(COSName.getPDFName("Im1"), image(doc, "OLD-PAGE-IMAGE")) }
            doc.pages.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.XOBJECT, xo) })
            p.resources = PDResources()     // e.g. the page was rasterised and got its own resources
            content(doc, p, "q Q")
            RedactCleanup.pruneUnusedXObjects(doc)
            assertFalse(saved(doc).contains("OLD-PAGE-IMAGE"))
        }
    }

    @Test fun paintedImagesAreKept() {
        PDDocument().use { doc ->
            val p = PDPage(); doc.addPage(p)
            p.resources = PDResources(COSDictionary().apply { setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Im1"), image(doc, "X")) }) })
            content(doc, p, "q /Im1 Do Q")
            assertEquals(0, RedactCleanup.pruneUnusedXObjects(doc))
        }
    }

    @Test fun spansTouchingFollowsNesting() {
        //            0      1     2     3      4     5      6     7     8
        val names = listOf("BDC", "BT", "Tj", "BMC", "Tj", "EMC", "EMC", "BDC", "Tj", "EMC")
        assertEquals(setOf(0), RedactCleanup.spansTouching(names, setOf(4)))
        assertEquals(setOf(7), RedactCleanup.spansTouching(names, setOf(8)))
        assertEquals(emptySet<Int>(), RedactCleanup.spansTouching(listOf("BT", "Tj", "ET"), setOf(1)))
    }

    @Test fun actualTextOfRedactedSpanAndItsStructureIsRemoved() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            content(doc, page,
                "/Span <</ActualText (SECRET NAME) /MCID 0>> BDC BT /F1 12 Tf 10 10 Td (abc) Tj ET EMC " +
                "/Span <</ActualText (KEEP THIS)>> BDC BT /F1 12 Tf 10 30 Td (def) Tj ET EMC")
            // structure: /P (ActualText "SECRET LINE") → /Span (Alt) → MCID 0 on this page
            val span = COSDictionary().apply { setName(COSName.S, "Span"); setItem(COSName.getPDFName("Alt"), COSString("SECRET ALT")); setItem(COSName.K, COSInteger.get(0)) }
            val para = COSDictionary().apply {
                setName(COSName.S, "P"); setItem(COSName.PG, page.cosObject)
                setItem(COSName.getPDFName("ActualText"), COSString("SECRET LINE")); setItem(COSName.K, COSArray().apply { add(span) })
            }
            val other = COSDictionary().apply { setName(COSName.S, "P"); setItem(COSName.PG, page.cosObject); setItem(COSName.getPDFName("ActualText"), COSString("OTHER LINE")); setItem(COSName.K, COSInteger.get(5)) }
            doc.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")); setItem(COSName.K, COSArray().apply { add(para); add(other) })
            })

            val rw = ContentRewriter(doc, page)
            val tj = (0 until rw.operatorCount).first { rw.operatorName(it) == "Tj" }   // the redacted "abc"
            val mcids = RedactCleanup.stripActualText(rw, page, setOf(tj))
            rw.commit()
            assertEquals(setOf(0), mcids)
            assertEquals(2, RedactCleanup.stripStructure(doc, page, mcids))

            val stream = page.contents.readBytes().toString(Charsets.ISO_8859_1)   // decoded (the rewrite is Flate)
            assertFalse(stream.contains("SECRET NAME")); assertTrue(stream.contains("KEEP THIS"))
            val bytes = saved(doc)
            for (secret in listOf("SECRET NAME", "SECRET LINE", "SECRET ALT")) assertFalse(secret, bytes.contains(secret))
            assertTrue(bytes.contains("OTHER LINE"))
        }
    }
}
