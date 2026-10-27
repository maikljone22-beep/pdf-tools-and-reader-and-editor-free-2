package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Section 7 comment helpers: every write is saved and read back with PdfBox. */
class AnnotStyleTest {

    private fun annot(sub: String, l: Float = 100f, b: Float = 100f, r: Float = 200f, t: Float = 160f) = COSDictionary().apply {
        setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, sub)
        setItem(COSName.RECT, COSArray().apply { listOf(l, b, r, t).forEach { add(COSFloat(it)) } })
    }

    private fun roundTrip(d: PDDocument): PDDocument {
        val out = ByteArrayOutputStream(); d.save(out); d.close()
        return PDDocument.load(out.toByteArray())
    }

    private fun addTo(page: PDPage, cos: COSDictionary) { val l = page.annotations; l.add(PDAnnotation.createAnnotation(cos)); page.annotations = l }

    @Test fun borderFillCloudLockMetaRoundTrip() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val sq = annot("Square")
        AnnotStyle.applyBorder(sq, 2f, 1)
        AnnotStyle.setInterior(sq, 0x3366FF)
        AnnotStyle.setCloudy(sq, 3)
        AnnotStyle.setLocked(sq, true)
        AnnotStyle.setMeta(sq, author = "Ali", subject = "Check", name = "n-1")
        val ov = annot("Circle"); AnnotStyle.applyBorder(ov, 3f, 0, noBorder = true)
        addTo(p, sq); addTo(p, ov)
        roundTrip(d).use { r ->
            val a = r.getPage(0).annotations
            val c = a[0].cosObject
            assertEquals(1, AnnotStyle.dashIndexOf(c))
            assertEquals(0x3366FF, AnnotStyle.interiorOf(c))
            assertEquals(3, AnnotStyle.cloudyOf(c))
            assertTrue(AnnotStyle.isLocked(c)); assertTrue(AnnotStyle.isLockedContents(c)); assertTrue(a[0].isLocked)
            assertEquals("Ali", AnnotStyle.authorOf(c)); assertEquals("Check", AnnotStyle.subjectOf(c)); assertEquals("n-1", AnnotStyle.nameOf(c))
            assertTrue(AnnotStyle.isNoBorder(a[1].cosObject)); assertEquals(0, AnnotStyle.dashIndexOf(a[1].cosObject))
            AnnotStyle.setLocked(c, false)
            assertFalse(AnnotStyle.isLocked(c))
        }
    }

    @Test fun lineEndsAndLeaderRoundTrip() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val ln = annot("Line")
        AnnotStyle.setLineEnds(ln, "Circle", "ClosedArrow")
        AnnotStyle.setLeader(ln, 12f, 4f, 2f)
        addTo(p, ln)
        roundTrip(d).use { r ->
            val c = r.getPage(0).annotations[0].cosObject
            assertEquals("Circle" to "ClosedArrow", AnnotStyle.lineEndsOf(c))
            assertEquals(Triple(12f, 4f, 2f), AnnotStyle.leaderOf(c))
        }
        val g = AnnotStyle.leaderGeometry(0f, 0f, 100f, 0f, 10f, 3f, 2f)
        assertEquals(10f, g[1], 1e-3f); assertEquals(10f, g[3], 1e-3f)       // line moved up by LL
        assertEquals(2f, g[5], 1e-3f); assertEquals(13f, g[7], 1e-3f)       // leader from LLO to LL+LLE
    }

    @Test fun popupAddResetAndRemove() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val hl = annot("Highlight"); addTo(p, hl)
        val pop = AnnotStyle.addPopup(p, hl, open = false)
        assertEquals(pop, AnnotStyle.addPopup(p, hl))           // reused, not duplicated
        assertEquals(2, p.annotations.size)
        roundTrip(d).use { r ->
            val page = r.getPage(0)
            val parent = page.annotations[0].cosObject
            val popup = page.annotations[1]
            assertEquals("Popup", popup.subtype)
            assertEquals(false, AnnotStyle.popupOpen(parent))
            assertTrue(popup.cosObject.getDictionaryObject(COSName.PARENT) === parent)
            popup.cosObject.setBoolean(COSName.getPDFName("Open"), true)
            popup.cosObject.setItem(COSName.RECT, COSArray().apply { listOf(0f, 0f, 10f, 10f).forEach { add(COSFloat(it)) } })
            assertTrue(AnnotStyle.resetPopup(page, parent))
            assertEquals(false, AnnotStyle.popupOpen(parent))
            assertTrue(popup.rectangle.width > 100f)
            AnnotStyle.removeWithPopups(page, listOf(0))
            assertTrue(page.annotations.isEmpty())
        }
    }

    @Test fun richTextRoundTrip() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val ft = annot("FreeText")
        AnnotStyle.applyRich(ft, "a < b\nline 2", AnnotStyle.Rich(13f, 0xFF0000, bold = true, italic = true, underline = true), rtl = false)
        addTo(p, ft)
        roundTrip(d).use { r ->
            val c = r.getPage(0).annotations[0].cosObject
            val rich = AnnotStyle.readRich(c)!!
            assertEquals(13f, rich.fontSize, 0.01f); assertEquals(0xFF0000, rich.color)
            assertTrue(rich.bold); assertTrue(rich.italic); assertTrue(rich.underline)
            val rc = c.getString(COSName.getPDFName("RC"))!!
            assertTrue(rc.contains("&lt;")); assertEquals("a < b\nline 2", AnnotStyle.richPlain(rc))
        }
        assertNull(AnnotStyle.readRich(annot("FreeText")))
    }

    @Test fun shapeStreamWithFillAndDash() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val sq = annot("Square")
        val s = AnnotStyle.shapeStream(d, 100f, 100f, 100f, 60f, oval = false, stroke = 0xFF0000, fill = 0x00FF00, width = 2f, dash = 2, noBorder = false, opacity = 0.5f)
        sq.setItem(COSName.AP, PDAppearanceDictionary().apply { setNormalAppearance(s) }.cosObject)
        addTo(p, sq)
        roundTrip(d).use { r ->
            val ap = r.getPage(0).annotations[0].normalAppearanceStream
            assertNotNull(ap)
            val txt = String(ap.contentStream.toByteArray(), Charsets.ISO_8859_1)
            assertTrue(txt.contains(" d")); assertTrue(txt.contains("B"))     // dash pattern + fill and stroke
        }
    }

    @Test fun flattenChosenAnnotationsOnly() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val keep = annot("Square", 10f, 10f, 50f, 50f)
        val flat = annot("Square", 100f, 100f, 200f, 160f)
        val s = AnnotStyle.shapeStream(d, 100f, 100f, 100f, 60f, false, 0xFF0000, null, 2f, 0, false, 1f)
        flat.setItem(COSName.AP, PDAppearanceDictionary().apply { setNormalAppearance(s) }.cosObject)
        addTo(p, keep); addTo(p, flat)
        AnnotStyle.addPopup(p, flat)
        assertEquals(1, AnnotStyle.flatten(d, p, listOf(1)))
        roundTrip(d).use { r ->
            val page = r.getPage(0)
            assertEquals(1, page.annotations.size)                         // the flattened one and its pop-up are gone
            assertEquals(10f, page.annotations[0].rectangle.lowerLeftX, 0.01f)
            val res = page.resources
            assertTrue(res.xObjectNames.iterator().hasNext())              // drawn into the page as a form XObject
        }
        val m = AnnotStyle.flattenMatrix(floatArrayOf(0f, 0f, 100f, 60f), floatArrayOf(1f, 0f, 0f, 1f, 100f, 100f), floatArrayOf(100f, 100f, 200f, 160f))
        assertEquals(1f, m[0], 1e-4f); assertEquals(0f, m[4], 1e-3f); assertEquals(0f, m[5], 1e-3f)
    }

    @Test fun numberedTickmarks() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        assertEquals(1, AnnotStyle.nextTickmark(p))
        AnnotStyle.writeTickmark(d, p, 100f, 100f, 1, 0x1565C0, author = "Me")
        AnnotStyle.writeTickmark(d, p, 150f, 100f, AnnotStyle.nextTickmark(p), 0x1565C0)
        roundTrip(d).use { r ->
            val page = r.getPage(0)
            assertEquals(2, page.annotations.size)
            assertEquals("2", page.annotations[1].contents)
            assertEquals("Tickmark", AnnotStyle.subjectOf(page.annotations[0].cosObject))
            assertNotNull(page.annotations[0].normalAppearanceStream)
            assertEquals(3, AnnotStyle.nextTickmark(page))
        }
    }

    @Test fun noteIcon() {
        val n = annot("Text")
        assertEquals("Comment", AnnotStyle.noteIconOf(n))
        AnnotStyle.setNoteIcon(n, "Key")
        assertEquals("Key", AnnotStyle.noteIconOf(n))
    }
}
