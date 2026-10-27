package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit5.E5Bookmark
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage B (Nitro 5 / Foxit 4–5, old plan K3/K8/K12): combine helpers, Office properties, web CSS, scan options. */
class CreateScanTest {

    // ---------------- combine ----------------
    private data class F(val name: String, val time: Long, val size: Long)

    @Test fun sortsNaturallyAndByKeys() {
        val l = listOf(F("p10.pdf", 3, 30), F("p2.pdf", 1, 20), F("A.pdf", 2, 10))
        assertEquals(listOf("A.pdf", "p2.pdf", "p10.pdf"), CombinePlus.sorted(l, FileSort.NAME, false, { it.name }, { it.time }, { it.size }).map { it.name })
        assertEquals(listOf("p10.pdf", "A.pdf", "p2.pdf"), CombinePlus.sorted(l, FileSort.TIME, true, { it.name }, { it.time }, { it.size }).map { it.name })
        assertEquals(listOf("A.pdf", "p2.pdf", "p10.pdf"), CombinePlus.sorted(l, FileSort.SIZE, false, { it.name }, { it.time }, { it.size }).map { it.name })
        assertTrue(NaturalOrder.compare("file9", "file010") < 0)
    }

    @Test fun headingsNestUnderTheFileBookmark() {
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            val outline = PDDocumentOutline(); val holder = PDOutlineItem().apply { title = "Report" }; outline.addLast(holder)
            val heads = listOf(E5Bookmark(OutlineModel.newKey(), "Intro", 0, 0), E5Bookmark(OutlineModel.newKey(), "Scope", 0, 1),
                E5Bookmark(OutlineModel.newKey(), "Skipped", 7, 1), E5Bookmark(OutlineModel.newKey(), "Results", 2, 0))
            val n = CombinePlus.addHeadings(holder, heads) { d.pages.elementAtOrNull(it) }
            assertEquals(3, n)
            assertEquals(listOf("Intro", "Results"), holder.children().map { it.title })
            assertEquals(listOf("Scope"), holder.firstChild.children().map { it.title })
        }
    }

    @Test fun officeCoreProperties() {
        val xml = """<cp:coreProperties xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>تقرير &amp; خطة</dc:title>
            <dc:creator>Sara</dc:creator><cp:keywords></cp:keywords><dc:subject xml:lang="ar">ميزانية</dc:subject></cp:coreProperties>"""
        assertEquals(mapOf("title" to "تقرير & خطة", "author" to "Sara", "subject" to "ميزانية"), OfficeProps.parse(xml))
        assertTrue(OfficeProps.parse("<x/>").isEmpty())
    }

    @Test fun webCss() {
        assertEquals("", WebCss.build())
        val css = WebCss.build(images = false, backgrounds = true, underlineLinks = true)
        assertTrue(css.contains("img") && css.contains("print-color-adjust") && css.contains("underline"))
    }

    // ---------------- scan ----------------
    @Test fun scanPageSizesFollowThePicture() {
        val a4 = ScanExtras.pageRect(ScanPaper.A4, 1000, 1400)
        assertEquals(595.28f, a4.width, 0.01f)
        val wide = ScanExtras.pageRect(ScanPaper.LEGAL, 1400, 1000)
        assertEquals(1008f, wide.width, 0.01f); assertEquals(612f, wide.height, 0.01f)
        val card = ScanExtras.pageRect(ScanPaper.CARD, 1000, 600)
        assertTrue(card.width > card.height)
        assertEquals(72f, ScanExtras.pageRect(ScanPaper.FIT, 200, 400).width, 0.01f)
    }

    @Test fun chunksAndDuplex() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), ScanExtras.chunks(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1, 2, 3)), ScanExtras.chunks(listOf(1, 2, 3), 0))
        // fronts 1 3 5, backs scanned last first: 6 4 2
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ScanExtras.duplex(listOf(1, 3, 5), listOf(6, 4, 2)))
        assertEquals(listOf("a", "b", "c"), ScanExtras.duplex(listOf("a", "c"), listOf("b"), backsReversed = false))
    }

    @Test fun presetsRoundTrip() {
        val p = ScanPreset("Office; A4=good", ScanKind.RECEIPT, ScanPaper.A5, ocr = false, langs = "ara+eng", removeBlank = true, perFile = 3, author = "Ali")
        assertEquals(p, ScanPreset.decode(p.encode()))
        val two = listOf(p, ScanPreset("Fast"))
        assertEquals(two, ScanPreset.decodeAll(ScanPreset.encodeAll(two)))
        assertNull(ScanPreset.decode("kind=BOOK"))
    }

    @Test fun despeckleAndErase() {
        val w = 10; val h = 5; val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt()
        val px = IntArray(w * h) { white }
        px[1 * w + 1] = black; px[1 * w + 2] = black              // a two-pixel speck
        for (x in 2..8) px[3 * w + x] = black                     // a stroke (7 px) stays, ends included
        assertEquals(2, ScanClean.despeckle(px, w, h))
        assertEquals(white, px[1 * w + 1]); assertEquals(black, px[3 * w + 2]); assertEquals(black, px[3 * w + 8])
        ScanClean.erase(px, w, h, listOf(floatArrayOf(0.2f, 0.6f, 0.5f, 0.8f)))
        assertEquals(white, px[3 * w + 2]); assertEquals(white, px[3 * w + 4]); assertEquals(black, px[3 * w + 5])
    }

    @Test fun scanProjectManifestRoundTrip() {
        val e = PageEdit(ScanFilter.BW, 0.1f, 1.2f, 90, despeckle = true, erase = listOf(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)))
        val text = ScanProjects.encode("Lease", listOf(listOf("p0_0.jpg" to e, "p0_1.jpg" to PageEdit()), listOf("p1_0.jpg" to PageEdit(ScanFilter.GRAY))))
        val (name, groups) = ScanProjects.decode(text)
        assertEquals("Lease", name)
        assertEquals(listOf(2, 1), groups.map { it.size })
        val back = groups[0][0].second
        assertEquals(ScanFilter.BW, back.filter); assertEquals(90, back.rotation); assertTrue(back.despeckle)
        assertEquals(0.3f, back.erase.single()[2], 1e-4f)
        assertEquals(ScanFilter.GRAY, groups[1][0].second.filter)
    }
}
