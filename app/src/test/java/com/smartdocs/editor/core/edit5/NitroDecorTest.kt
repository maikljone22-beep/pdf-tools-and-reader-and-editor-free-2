package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nitro gaps of section 12: tokens, every-N pages, Bates modes, units, wrap, profiles, page growth, click links. */
class NitroDecorTest {

    @Test fun newTokens() {
        val c = TokenContext(page = 2, pages = 5, file = "a.pdf", path = "/sdcard/a.pdf", subject = "Lease", keywords = "rent, flat",
            bates = "DOC-000007", batesTotal = "40", batchPage = 12, batchPages = 30)
        assertEquals("/sdcard/a.pdf | Lease | rent, flat", Tokens.expand("{path} | {subject} | {keywords}", c))
        assertEquals("DOC-000007 / 40", Tokens.expand("{bates} / {batestotal}", c))
        assertEquals("12 of 30", Tokens.expand("{batchpage} of {batchpages}", c))
        // outside a batch the batch tokens are the file's own numbers
        assertEquals("2 of 5", Tokens.expand("{batchpage} of {batchpages}", TokenContext(page = 2, pages = 5)))
        assertTrue(Tokens.usesBates("x {bates}")); assertFalse(Tokens.usesBates("{page}"))
    }

    @Test fun everyNPages() {
        val all = (0 until 10).toList()
        assertEquals(listOf(2, 5, 8), PageSet.every(all, start = 3, every = 3))
        assertEquals(all, PageSet.every(all, start = 3, every = 0))
        assertEquals(listOf(0, 2, 4, 6, 8), PageSet.every(all, 1, 2))
    }

    @Test fun batesModes() {
        val files = listOf(3 to listOf(0, 1, 2), 2 to listOf(0, 1))
        val cont = Bates.plan(BatesSpec(start = 1), files)
        assertEquals(mapOf(0 to 1L, 1 to 2L, 2 to 3L), cont[0]); assertEquals(mapOf(0 to 4L, 1 to 5L), cont[1])
        val perDoc = Bates.plan(BatesSpec(start = 10, mode = BatesMode.PER_DOCUMENT), files)
        assertEquals(setOf(10L), perDoc[0].values.toSet()); assertEquals(setOf(11L), perDoc[1].values.toSet())
        assertEquals(2L, Bates.total(perDoc))
        val restart = Bates.plan(BatesSpec(start = 1, mode = BatesMode.RESTART), files)
        assertEquals(mapOf(0 to 1L, 1 to 2L), restart[1])
        // only pages 2 and 3 numbered; skipped pages counted or not
        val some = listOf(4 to listOf(1, 2), 2 to listOf(0))
        assertEquals(listOf(mapOf(1 to 1L, 2 to 2L), mapOf(0 to 3L)), Bates.plan(BatesSpec(start = 1), some))
        assertEquals(listOf(mapOf(1 to 2L, 2 to 3L), mapOf(0 to 5L)), Bates.plan(BatesSpec(start = 1, countSkipped = true), some))
        assertEquals(5L, Bates.total(Bates.plan(BatesSpec(), files)))
    }

    @Test fun units() {
        assertEquals(72f, E5Unit.IN.toPoints(1f), 0.001f)
        assertEquals(10f, E5Unit.MM.fromPoints(E5Unit.MM.toPoints(10f)), 0.001f)
        assertEquals(28.35f, E5Unit.CM.toPoints(1f), 0.01f)
    }

    @Test fun wordWrap() {
        val w: (String) -> Float = { it.length.toFloat() }
        assertEquals(listOf("aa bb", "cc", "dddddddd", "e"), DecorExtras.wrap("aa bb cc dddddddd\ne", 5f, w))
        assertEquals(listOf("one line"), DecorExtras.wrap("one line", 0f, w))
    }

    @Test fun profileListEdits() {
        var l = E5ProfileList.put(emptyList(), " Draft ", """{"a":1}""")
        l = E5ProfileList.put(l, "Draft", """{"a":2}""")            // save over
        assertEquals(1, l.size); assertEquals("""{"a":2}""", l[0].json)
        l = E5ProfileList.duplicate(l, "Draft")
        assertEquals(listOf("Draft", "Draft (2)"), l.map { it.name })
        assertEquals(l, E5ProfileList.rename(l, "Draft (2)", "Draft"))   // taken
        l = E5ProfileList.rename(l, "Draft (2)", "Final")
        val text = E5ProfileList.export("wm", l)
        val back = E5ProfileList.import("wm", E5ProfileList.delete(l, "Final"), text)
        assertEquals(listOf("Draft", "Draft (2)", "Final"), back.map { it.name })
        assertTrue(runCatching { E5ProfileList.import("hf", emptyList(), text) }.isFailure)
    }

    @Test fun specsKeepTheNewOptions() {
        val d = DecorSpec(source = DecorSource.PDF, pdfPath = "/x.pdf", pdfPage = 2, lineSpacing = 1.5f, wrapWidth = 0.6f, border = 0x112233,
            borderWidth = 2f, borderDashed = true, click = ClickAction.URL, clickTarget = "example.com", everyStart = 2, everyN = 3, ignoreRotation = true,
            style = E5TextStyle(italic = true, underline = true, outline = 0x445566, hollow = true))
        assertEquals(d, DecorSpec.fromJson(d.toJson()))
        val h = HFSpec(marginRight = 50f, opacity = 0.5f, rotation = 15f, behind = true, everyStart = 1, everyN = 2, ignoreRotation = true,
            imagePath = "/logo.png", imageSlot = 4, imageHeight = 30f, bates = BatesSpec("P-", "-S", 4, 9), growPage = true, unit = E5Unit.MM,
            style = E5TextStyle(italic = true))
        assertEquals(h, HFSpec.fromJson(h.toJson()))
        assertEquals(50f, h.rightMargin); assertEquals(h.marginSide, HFSpec().rightMargin)
    }

    @Test fun pageGrowsAtTheVisibleEdge() {
        val p = PDPage(PDRectangle(0f, 0f, 600f, 800f))
        DecorExtras.grow(p, top = 0f, bottom = 30f)
        assertEquals(-30f, p.cropBox.lowerLeftY, 0.01f); assertEquals(830f, p.mediaBox.height, 0.01f)
        val r = PDPage(PDRectangle(0f, 0f, 600f, 800f)).apply { rotation = 90 }
        // turned clockwise: the user-space right edge shows at the bottom
        DecorExtras.grow(r, top = 0f, bottom = 20f)
        assertEquals(620f, r.cropBox.upperRightX, 0.01f); assertEquals(0f, r.cropBox.lowerLeftY, 0.01f)
    }

    @Test fun grownPagesAreRestored() {
        PDDocument().use { doc ->
            val p = PDPage(PDRectangle(0f, 0f, 600f, 800f)); doc.addPage(p)
            Grown.grow(p, 0f, 30f); Grown.grow(p, 0f, 30f)              // a second run does not grow again
            assertEquals(830f, p.cropBox.height, 0.01f)
            Grown.restore(doc)
            assertEquals(800f, p.cropBox.height, 0.01f); assertFalse(p.cosObject.containsKey(COSName.getPDFName("SDE5Grown")))
        }
    }

    @Test fun clickLinksAreAddedAndRemoved() {
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage(PDRectangle(0f, 0f, 600f, 800f))) }
            val page = doc.getPage(0)
            val frame = DecorExtras.frameOf(page, false)
            DecorExtras.addLink(doc, page, frame, "wm", 300f, 400f, 200f, 50f, 0f, ClickAction.PAGE, "", 3)
            DecorExtras.addLink(doc, page, frame, "wm", 300f, 100f, 100f, 20f, 0f, ClickAction.URL, "example.com", 1)
            DecorExtras.addLink(doc, page, frame, "wm", 0f, 0f, 1f, 1f, 0f, ClickAction.NONE, "", 1)
            val links = page.annotations.filterIsInstance<PDAnnotationLink>()
            assertEquals(2, links.size)
            assertEquals(200f, links[0].rectangle.width, 0.01f); assertEquals(375f, links[0].rectangle.lowerLeftY, 0.01f)
            assertTrue(links[0].action is PDActionGoTo)
            assertEquals("https://example.com", (links[1].action as PDActionURI).uri)
            assertEquals(2, DecorExtras.removeLinks(doc, "wm"))
            assertTrue(page.annotations.isEmpty())
        }
    }

    @Test fun ignoreRotationUsesTheUnrotatedBox() {
        val p = PDPage(PDRectangle(0f, 0f, 600f, 800f)).apply { rotation = 90 }
        assertEquals(800f, DecorExtras.frameOf(p, false).width, 0.01f)
        assertEquals(600f, DecorExtras.frameOf(p, true).width, 0.01f)
        assertNull(null)
    }
}
