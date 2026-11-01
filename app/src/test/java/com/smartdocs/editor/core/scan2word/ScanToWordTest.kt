package com.smartdocs.editor.core.scan2word

import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo → faithful Word, phase 1: page size, sizes, bold, figures, ruled tables. */
class ScanToWordTest {
    private val white = 0xFFFFFFFF.toInt(); private val black = 0xFF111111.toInt()

    @Test fun pageSizeSnapsToPaper() {
        assertEquals(595.28f to 841.89f, ScanToWord.pageSize(2480, 3508))   // A4 at 300 dpi
        assertEquals(841.89f to 595.28f, ScanToWord.pageSize(3508, 2480))   // landscape
        assertEquals(498.9f to 708.66f, ScanToWord.pageSize(1760, 2500))    // B5 shape
    }

    @Test fun pointSizeFromBoxHeight() {
        assertEquals(14f, ScanToWord.pointSize(18.5f, arabic = true))
        assertEquals(12f, ScanToWord.pointSize(13.4f, arabic = false))
    }

    @Test fun thickStrokesReadAsBold() {
        val w = 200; val h = 40
        val px = IntArray(w * h) { white }
        // thin strokes on the left, thick on the right
        for (x in 10 until 90 step 8) for (y in 5 until 35) { px[y * w + x] = black; px[y * w + x + 1] = black }
        for (x in 110 until 190 step 10) for (y in 5 until 35) for (k in 0 until 5) px[y * w + x + k] = black
        val thin = ScanToWord.strokeRatio(px, w, 5, 2, 95, 38); val thick = ScanToWord.strokeRatio(px, w, 105, 2, 195, 38)
        assertTrue("thin $thin thick $thick", thick > thin * 1.6f)
    }

    @Test fun figureIsFoundAndWordsAreNot() {
        val w = 400; val h = 500
        val px = IntArray(w * h) { white }
        val words = ArrayList<ScanToWord.OcrBox>()
        for (i in 0 until 6) { val t = 30 + i * 20; for (y in t until t + 10) for (x in 60 until 340) px[y * w + x] = black; words += ScanToWord.OcrBox("كلمة", 60, t, 340, t + 10, 90f, i) }
        // a coloured diagram block
        for (y in 250 until 400) for (x in 100 until 300) px[y * w + x] = if ((x + y) % 7 < 3) 0xFF3366CC.toInt() else 0xFFCC3344.toInt()
        val figs = ScanToWord.figures(px, w, h, words, emptyList())
        assertEquals(1, figs.size)
        val f = figs[0]
        assertTrue(f[0] <= 104 && f[2] >= 296 && f[1] <= 254 && f[3] >= 396)
    }

    @Test fun ruledGridBecomesATable() {
        val w = 600; val h = 800
        val px = IntArray(w * h) { white }
        val xs = listOf(100, 300, 500); val ys = listOf(200, 260, 320, 380)
        for (y in ys) for (x in 100..500) { px[y * w + x] = black; px[(y + 1) * w + x] = black }
        for (x in xs) for (y in 200..380) { px[y * w + x] = black; px[y * w + x + 1] = black }
        val words = ArrayList<ScanToWord.OcrBox>()
        var line = 0
        for (r in 0 until 3) for (c in 0 until 2) {
            val l = xs[c] + 40; val t = ys[r] + 20
            words += ScanToWord.OcrBox(if (c == 0) "ب$r" else "أ$r", l, t, l + 60, t + 20, 90f, line++)
        }
        val page = ScanToWord.page(0, px, w, h, words, "Simplified Arabic", "Times New Roman") { null }
        assertTrue(page.segments.size >= 7)
        val layout = LayoutAnalyzer.analyze(page, detectTables = true)
        val table = layout.blocks.filterIsInstance<TableBlock>().firstOrNull()
        assertTrue("blocks: ${layout.blocks.map { it::class.simpleName }}", table != null)
        assertEquals(6, table!!.cells.size)
    }
}
