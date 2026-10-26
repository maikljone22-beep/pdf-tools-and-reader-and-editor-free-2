package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic-page tests of the conversion-fidelity logic (fonts, decorations, reading order, headers/footers). */
class FidLayoutTest {

    /** Glyphs of one word: [text] starting at [x] on [baseline], 0.5 em per character. */
    private fun glyphs(text: String, x: Float, baseline: Float, fs: Float = 10f, color: Int = 0): List<Glyph> {
        val w = fs * 0.5f
        return text.mapIndexed { i, c -> Glyph(x + i * w, baseline - fs * 0.7f, w, fs * 0.7f, baseline, c.toString(), fs, color = color) }
    }

    /** One line of words separated by one space (0.5 em). */
    private fun line(words: List<String>, x: Float, baseline: Float, fs: Float = 10f): List<Glyph> {
        val out = ArrayList<Glyph>(); var cx = x
        for (w in words) { out += glyphs(w, cx, baseline, fs); cx += (w.length + 1) * fs * 0.5f }
        return out
    }

    // ---------------------------------------------------------------- FontMap

    @Test fun fontMapNames() {
        assertEquals("Arial", FontMap.family("ABCDEF+Arial-BoldMT"))
        assertEquals("Times New Roman", FontMap.family("TimesNewRomanPSMT"))
        assertEquals("Times New Roman", FontMap.family("Times-Italic"))
        assertEquals("Courier New", FontMap.family("CourierNewPS-BoldMT"))
        assertEquals("Arial", FontMap.family("Helvetica"))
        assertEquals("Traditional Arabic", FontMap.family("XYZABC+TraditionalArabic,Bold"))
        assertEquals("Sakkal Majalla", FontMap.family("SakkalMajalla-Bold"))
        assertEquals("Noto Naskh Arabic", FontMap.family("NotoNaskhArabic-Regular"))
        assertEquals("Calibri", FontMap.family("ABCDEE+Calibri-Light"))
        // unknown: descriptor flags decide
        assertEquals("Times New Roman", FontMap.family("F12", serif = true))
        assertEquals("Courier New", FontMap.family("F13", monospace = true))
        assertEquals("Arial", FontMap.family(null))
        assertEquals("Arial", FontMap.baseName("ABCDEF+Arial-BoldMT"))
        assertEquals("TimesNewRoman", FontMap.baseName("TimesNewRomanPSMT"))
    }

    @Test fun complexScriptFallback() {
        assertTrue(FontMap.hasArabic("Amiri"))
        assertFalse(FontMap.hasArabic("Calibri"))
        assertEquals("Arial", FontMap.complexScript("Calibri"))
        assertEquals("Simplified Arabic", FontMap.complexScript("Simplified Arabic"))
    }

    // ---------------------------------------------------------------- decorations

    @Test fun underlineStrikeHighlight() {
        val gs = line(listOf("plain", "under", "struck", "marked"), 50f, 100f)
        val lines = LayoutAnalyzer.buildLines(gs)
        val ws = lines.single().visual
        val under = ws[1]; val struck = ws[2]; val marked = ws[3]
        val segs = listOf(
            Segment(under.x0, 101.5f, under.x1, 101.5f),                       // just below the baseline
            Segment(struck.x0, 96.5f, struck.x1, 96.5f),                       // through the middle
            Segment(20f, 300f, 580f, 300f),                                     // page-wide separator: ignored
        )
        val fills = listOf(Fill(Rect(marked.x0 - 1, 92f, marked.x1 + 1, 103f), 0xFFFF00), Fill(Rect(0f, 0f, 595f, 842f), 0xFFFFFF))
        Decorations.decorateWords(lines, segs, fills, 595f)
        assertFalse(ws[0].underline || ws[0].strike || ws[0].bg != null)
        assertTrue(under.underline); assertFalse(under.strike)
        assertTrue(struck.strike); assertFalse(struck.underline)
        assertEquals(0xFFFF00, marked.bg)
        val runs = TextRuns.of(lines.single())
        assertTrue(runs.any { it.underline && it.text.trim() == "under" })
        assertTrue(runs.any { it.bg == 0xFFFF00 && it.text.trim() == "marked" })
    }

    @Test fun gridRulesAreNotUnderlines() {
        val gs = line(listOf("cell"), 50f, 100f)
        val lines = LayoutAnalyzer.buildLines(gs)
        val w = lines.single().visual.single()
        // a cell bottom rule that touches vertical rules at both ends
        val segs = listOf(Segment(w.x0 - 2, 101.5f, w.x1 + 2, 101.5f), Segment(w.x0 - 2, 85f, w.x0 - 2, 101.5f), Segment(w.x1 + 2, 85f, w.x1 + 2, 101.5f))
        Decorations.decorateWords(lines, segs, emptyList(), 595f)
        assertFalse(w.underline)
    }

    @Test fun colorsAndCellFills() {
        val g = glyphs("red", 10f, 20f, color = 0xCC0000)
        val l = LayoutAnalyzer.buildLines(g).single()
        assertEquals(0xCC0000, l.words.single().color)
        assertEquals(0xCC0000, TextRuns.of(l).single().color)
        val cell = TableCell(0, 0, 1, 1, Rect(0f, 0f, 100f, 20f), listOf(l))
        val t = TableBlock(listOf(0f, 100f), listOf(0f, 20f, 40f), listOf(cell, TableCell(1, 0, 1, 1, Rect(0f, 20f, 100f, 40f), emptyList())), true)
        Decorations.fillCells(t, listOf(Fill(Rect(0f, 0f, 100f, 20f), 0x3366B3), Fill(Rect(0f, 0f, 595f, 842f), 0xFFFFFF)))
        assertEquals(0x3366B3, t.cells[0].fill)
        assertNull(t.cells[1].fill)
        assertEquals("3366B3", Decorations.hex(0x3366B3))
    }

    // ---------------------------------------------------------------- reading order

    private val nameOf = HashMap<Block, String>()

    /** A block with [rect] (an image block: the order logic only looks at rectangles). */
    private fun B(r: Rect, name: String): Block = ImageBlock(PageImage(r.x0, r.y0, r.w, r.h, ByteArray(0), "png")).also { nameOf[it] = name }

    private fun names(bs: List<Block>) = bs.joinToString(",") { nameOf[it]!! }

    @Test fun twoColumnsUnderTitle() {
        val blocks = listOf(
            B(Rect(50f, 40f, 545f, 60f), "title"),
            B(Rect(50f, 80f, 290f, 200f), "L1"), B(Rect(305f, 80f, 545f, 150f), "R1"),
            B(Rect(305f, 160f, 545f, 300f), "R2"), B(Rect(50f, 210f, 290f, 320f), "L2"),
            B(Rect(50f, 760f, 545f, 780f), "footer"),
        )
        assertEquals("title,L1,L2,R1,R2,footer", names(ReadingOrder.order(blocks, rtl = false)))
        assertEquals("title,R1,R2,L1,L2,footer", names(ReadingOrder.order(blocks, rtl = true)))
    }

    @Test fun sideColumnReadAfterMain() {
        val blocks = listOf(B(Rect(40f, 80f, 120f, 300f), "side"), B(Rect(140f, 80f, 560f, 200f), "main1"), B(Rect(140f, 210f, 560f, 400f), "main2"))
        assertEquals("main1,main2,side", names(ReadingOrder.order(blocks, rtl = false)))
    }

    @Test fun singleColumnStaysTopToBottom() {
        val blocks = listOf(B(Rect(50f, 300f, 545f, 400f), "c"), B(Rect(50f, 40f, 545f, 60f), "a"), B(Rect(50f, 100f, 545f, 250f), "b"))
        assertEquals("a,b,c", names(ReadingOrder.order(blocks, rtl = false)))
    }

    @Test fun riversSplitNarrowGutter() {
        // two columns with a 1.4 em (14 pt) gutter at x = 200..214, five lines
        val gs = ArrayList<Glyph>()
        for (i in 0 until 5) {
            val b = 100f + i * 13f
            gs += line(listOf("aaaa", "bbbbb", "cccc", "dddd"), 50f, b)   // ends at 50 + 21*5 - 5 = 150..
            gs += line(listOf("eeee", "ffff", "ggggg"), 214f, b)
        }
        val lines = LayoutAnalyzer.buildLines(gs)
        assertEquals(5, lines.size)
        val rivers = ReadingOrder.rivers(lines)
        assertTrue(rivers.isNotEmpty())
        val split = LayoutAnalyzer.splitColumns(lines, rivers)
        assertEquals(10, split.size)
    }

    // ---------------------------------------------------------------- headers / footers

    @Test fun furnitureDetectionAndPageField() {
        val pages = (1..4).map { p ->
            val gs = ArrayList<Glyph>()
            gs += line(listOf("Annual", "Report"), 56f, 30f, 9f)
            gs += line(listOf("Body", "text", "of", "page", "$p", "lorem"), 56f, 200f, 10f)
            gs += line(listOf("Body", "line", "two"), 56f, 213f, 10f)
            gs += line(listOf("Page", "$p", "of", "4"), 480f, 812f, 9f)
            LayoutAnalyzer.buildLines(gs)
        }
        val samples = pages.map { PageFurniture.Companion.Sample(it, 842f, LayoutAnalyzer.bodyFontSize(it)) }
        val f = PageFurniture.detect(samples)
        assertFalse(f.isEmpty)
        val first = pages[0]
        val body = LayoutAnalyzer.bodyFontSize(first)
        val header = first.filter { f.isHeader(it, 842f, body) }
        val footer = first.filter { f.isFooter(it, 842f, body) }
        assertEquals(listOf("Annual Report"), header.map { it.text })
        assertEquals(listOf("Page 1 of 4"), footer.map { it.text })
        assertTrue(first.none { it.text.startsWith("Body") && (f.isHeader(it, 842f, body) || f.isFooter(it, 842f, body)) })
        val parts = f.parts(footer.single(), 595f, 842f, body)
        assertEquals(PageFurniture.Pos.END, parts.single().pos)
        val pieces = parts.single().pieces
        assertEquals(1, pieces.count { it is PageFurniture.Piece.PageNumber })
        assertEquals("4", (pieces.last() as PageFurniture.Piece.Text).text.trim().substringAfterLast(' '))
        // the analysed page keeps furniture out of the blocks
        val content = PageContent(0, 595f, 842f, first.flatMap { l -> l.visual.flatMap { it.glyphs } })
        val layout = LayoutAnalyzer.analyze(content, true, f)
        assertEquals(1, layout.header.size); assertEquals(1, layout.footer.size)
        assertTrue(layout.blocks.filterIsInstance<ParagraphBlock>().none { b -> b.lines.any { it.text.startsWith("Page") || it.text.startsWith("Annual") } })
    }

    @Test fun noFurnitureOnSinglePage() {
        val lines = LayoutAnalyzer.buildLines(line(listOf("Page", "1"), 480f, 812f, 9f))
        assertTrue(PageFurniture.detect(listOf(PageFurniture.Companion.Sample(lines, 842f, 9f))).isEmpty)
        assertEquals(listOf("Page ", "12", " of ", "3"), PageFurniture.tokens("Page 12 of 3"))
    }

    // ---------------------------------------------------------------- borderless tables vs text columns

    @Test fun textColumnsAreNotATable() {
        val gs = ArrayList<Glyph>()
        for (i in 0 until 4) {
            val b = 100f + i * 13f
            gs += line(listOf("one", "two", "three", "four", "five"), 50f, b)
            gs += line(listOf("six", "seven", "eight", "nine", "ten"), 260f, b)
            gs += line(listOf("alpha", "beta", "gamma", "delta"), 460f, b)
        }
        val lines = LayoutAnalyzer.buildLines(gs)
        assertTrue(TableDetector.stream(lines).tables.isEmpty())
        // a real numeric table is still found
        val ts = ArrayList<Glyph>()
        for (i in 0 until 4) { val b = 300f + i * 13f; ts += glyphs("Item$i", 50f, b); ts += glyphs("${i * 10}", 200f, b); ts += glyphs("${i * 7}", 300f, b) }
        assertNotNull(TableDetector.stream(LayoutAnalyzer.buildLines(ts)).tables.firstOrNull())
    }
}
