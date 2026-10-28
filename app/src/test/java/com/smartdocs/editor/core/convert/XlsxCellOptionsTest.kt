package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** fix/pdf39-convert-2: PDF → Excel "split multi-line text into rows" and "reduce merged cells" (s6). */
class XlsxCellOptionsTest {
    private fun line(text: String, x: Float, baseline: Float): Line {
        val fs = 10f; val w = 5f
        val gs = text.mapIndexed { i, c -> Glyph(x + i * w, baseline - fs * 0.7f, w, fs * 0.7f, baseline, c.toString(), fs) }
        return LayoutAnalyzer.buildLines(gs).single()
    }

    private fun table(): TableBlock {
        val r = Rect(0f, 0f, 1f, 1f)
        return TableBlock(listOf(0f, 100f, 200f), listOf(0f, 30f, 60f), listOf(
            TableCell(0, 0, 1, 1, r, listOf(line("alpha", 5f, 10f), line("beta", 5f, 22f))),
            TableCell(0, 1, 1, 1, r, listOf(line("xray", 105f, 10f))),
            TableCell(1, 0, 1, 2, r, listOf(line("merged", 5f, 40f))),
        ), ruled = true)
    }

    private fun sheetXml(split: Boolean, noMerge: Boolean): String {
        val bos = ByteArrayOutputStream()
        XlsxBuilder(bos, "t", split, noMerge).use { b -> b.addSheet("S", listOf(PageLayout(0, 300f, 300f, listOf(table()), 10f))); b.finish() }
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "xl/worksheets/sheet1.xml") return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no sheet")
    }

    @Test fun defaultKeepsOneRowAndMerges() {
        val x = sheetXml(split = false, noMerge = false)
        assertTrue(x.contains("<mergeCell ref=\"A2:B2\"/>"))
        assertFalse(x.contains("r=\"3\""))
    }

    @Test fun splitAddsRowsAndShiftsTheRest() {
        val x = sheetXml(split = true, noMerge = false)
        assertTrue(x.contains("r=\"A2\""))                          // "beta" on its own row
        assertTrue(x.contains("<mergeCell ref=\"A3:B3\"/>"))        // the merged row moved down
    }

    @Test fun noMergeLeavesCellsSeparate() {
        assertFalse(sheetXml(split = false, noMerge = true).contains("mergeCell"))
    }
}
