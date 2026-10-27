package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reader view extras of the nitro-view-edit work: word count, table copy, URL finder, /Trans and spell-check texts round trips. */
class NveViewTest {

    private fun tmp() = File.createTempFile("nve_view", ".pdf").also { it.deleteOnExit() }

    @Test fun wordCountLatinArabicAsian() {
        val s = TextStats.of("Hello world, it's fine.\n\nمرحبا بالعالم\n你好", pages = 1)
        // hello, world, it's, fine, مرحبا, بالعالم + 2 CJK characters
        assertEquals(8, s.words)
        assertEquals(2, s.asian)
        assertEquals("مرحبابالعالم".length, s.arabic)
        assertEquals(3, s.lines)
        assertEquals(2, s.paragraphs)
        assertEquals(1, s.pages)
        assertTrue(s.charsNoSpaces < s.chars)
        assertEquals(TextStats.of("a b").words + TextStats.of("c").words, (TextStats.of("a b") + TextStats.of("c")).words)
    }

    private fun row(y: Float, vararg cells: Pair<Float, String>): List<CharBox> = cells.flatMap { (x0, t) ->
        t.mapIndexed { i, c -> CharBox(c, x0 + i * 6f, y + 10f, x0 + i * 6f + 5f, y) }
    }

    @Test fun tableCopyRowsAndColumns() {
        val chars = row(700f, 10f to "Name", 100f to "Qty") + row(680f, 10f to "Pen", 100f to "12") + row(660f, 10f to "Ink", 100f to "3")
        val cells = TableCopy.cells(chars)
        assertEquals(listOf(listOf("Name", "Qty"), listOf("Pen", "12"), listOf("Ink", "3")), cells)
        assertEquals("Name\tQty\nPen\t12\nInk\t3", TableCopy.toTsv(cells))
        assertTrue(TableCopy.toHtml(cells).contains("<td>Pen</td><td>12</td>"))
        assertEquals("a,\"b,c\"", TableCopy.toCsv(listOf(listOf("a", "b,c"))))
    }

    @Test fun styledHtml() {
        val h = StyledCopy.html(listOf(StyledRun("A<b", 12f, true, false, 0xFF0000, "Helvetica")), rtl = true)
        assertTrue(h.contains("dir=\"rtl\""))
        assertTrue(h.contains("font-weight:bold"))
        assertTrue(h.contains("#FF0000"))
        assertTrue(h.contains("A&lt;b"))
    }

    @Test fun urlsInPlainText() {
        val text = "See www.example.com/a, mail me at a.b@test.org. Or https://x.y/z?q=1."
        val found = UrlFinder.find(text)
        assertEquals(listOf("https://www.example.com/a", "mailto:a.b@test.org", "https://x.y/z?q=1"), found.map { it.first })
        // ranges point at the text without trailing punctuation
        assertEquals("www.example.com/a", text.substring(found[0].second.first, found[0].second.last + 1))
    }

    @Test fun transitionsRoundTrip() {
        val f = tmp()
        PDDocument().use { d -> repeat(3) { d.addPage(PDPage(PDRectangle.A4)) }
            PageTransitions.write(d, listOf(0, 2), PageTrans(TransStyle.SPLIT, 1.5f, horizontal = false, inward = false, autoAdvance = 4f))
            PageTransitions.write(d, listOf(1), PageTrans(TransStyle.WIPE, 0.5f, direction = 270))
            d.save(f) }
        PDDocument.load(f).use { d ->
            val t0 = PageTransitions.read(d, 0)!!
            assertEquals(TransStyle.SPLIT, t0.style); assertEquals(1.5f, t0.duration, 0.001f)
            assertEquals(false, t0.horizontal); assertEquals(false, t0.inward); assertEquals(4f, t0.autoAdvance!!, 0.001f)
            val t1 = PageTransitions.read(d, 1)!!
            assertEquals(TransStyle.WIPE, t1.style); assertEquals(270, t1.direction); assertNull(t1.autoAdvance)
            PageTransitions.write(d, null, null)
            assertNull(PageTransitions.read(d, 0))
        }
        assertEquals(listOf(0, 1, 2, 4), PageTransitions.parseRange("1-3, 5", 6))
        assertEquals(listOf(4, 5), PageTransitions.parseRange("5-", 6))
        assertNull(PageTransitions.parseRange("x", 6))
    }

    @Test fun spellTextsRoundTrip() {
        val f = tmp()
        PDDocument().use { d -> val p = PDPage(PDRectangle.A4); d.addPage(p)
            p.annotations.add(PDAnnotationText().apply { rectangle = PDRectangle(10f, 10f, 20f, 20f); contents = "Teh note" })
            d.save(f) }
        val out = tmp()
        PDDocument.load(f).use { d ->
            val list = DocTexts.collect(d)
            assertEquals(1, list.size); assertEquals("Teh note", list[0].text); assertEquals("Text", list[0].kind)
            assertEquals(1, DocTexts.apply(d, mapOf(list[0].id to "The note")))
            d.save(out)
        }
        PDDocument.load(out).use { d -> assertEquals("The note", d.getPage(0).annotations[0].contents) }
    }
}
