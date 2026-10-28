package com.smartdocs.editor.core.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL_CHECK_2 #9: Excel → PDF fit to page (sheet setup, columns on one page, sheet on one page, column bands). */
class ExcelFitTest {
    private val cols = 0..9
    private val px = List(10) { 140 }   // 1400 px wide

    @Test fun readsSheetSetup() {
        val sp = SheetPrint.of(mapOf("orientation" to "landscape", "fitToWidth" to "1", "fitToHeight" to "0", "scale" to "55"), mapOf("fitToPage" to "1"))!!
        assertEquals(true, sp.landscape); assertTrue(sp.fitToPage); assertEquals(1, sp.fitToWidth); assertEquals(0, sp.fitToHeight); assertEquals(55, sp.scale)
        val plain = SheetPrint.of(mapOf("scale" to "5"), null)!!
        assertFalse(plain.fitToPage); assertNull(plain.landscape); assertEquals(10, plain.scale)   // Excel's 10 % minimum
        assertNull(SheetPrint.of(null, null))
    }

    @Test fun columnsOnOnePage() {
        val p = ExcelFit.plan(cols, px, 500, 700, 1000, ExcelFit.COLUMNS, null)
        assertEquals(0.5f, p.zoom, 1e-4f)
        assertEquals(listOf(cols), p.bands)
        assertFalse(p.onePart)
    }

    @Test fun actualSizeSplitsColumnsIntoBands() {
        val p = ExcelFit.plan(cols, px, 500, 700, 1000, ExcelFit.ACTUAL, null)
        assertEquals(1f, p.zoom, 1e-4f)
        assertEquals(listOf(0..4, 5..9), p.bands)   // 5 × 140 = 700 per page, "down, then over"
    }

    @Test fun wholeSheetOnOnePage() {
        val p = ExcelFit.plan(cols, px, 4000, 700, 1000, ExcelFit.ONE_PAGE, null)
        assertEquals(0.25f, p.zoom, 1e-4f)          // height decides: 1000 / 4000
        assertEquals(1, p.bands.size); assertTrue(p.onePart)
        assertEquals(0.1f, ExcelFit.plan(cols, px, 100_000, 700, 1000, ExcelFit.ONE_PAGE, null).zoom, 1e-4f)   // never under 10 %
    }

    @Test fun honoursSheetFitToPages() {
        // 2 pages wide, any number tall: 2 × 700 / 1400 = 1 → actual size in two bands
        val wide2 = SheetPrint(fitToPage = true, fitToWidth = 2, fitToHeight = 0)
        val p = ExcelFit.plan(cols, px, 5000, 700, 1000, ExcelFit.AUTO, wide2)
        assertEquals(1f, p.zoom, 1e-4f); assertEquals(2, p.bands.size); assertFalse(p.onePart)
        // 1 × 1: the smaller of width and height
        val one = ExcelFit.plan(cols, px, 2800, 700, 1000, ExcelFit.AUTO, SheetPrint(fitToPage = true))
        assertEquals(1000f / 2800, one.zoom, 1e-4f); assertTrue(one.onePart); assertEquals(1, one.bands.size)
        // a scale without fit-to-page; the columns that no longer fit go on further pages
        val scaled = ExcelFit.plan(cols, px, 500, 700, 1000, ExcelFit.AUTO, SheetPrint(scale = 70))
        assertEquals(0.7f, scaled.zoom, 1e-4f); assertEquals(listOf(0..6, 7..9), scaled.bands)
        // no setup: all columns on one page width (as before)
        assertEquals(0.5f, ExcelFit.plan(cols, px, 500, 700, 1000, ExcelFit.AUTO, null).zoom, 1e-4f)
        // a user mode wins over the sheet's own setup
        assertEquals(1f, ExcelFit.plan(cols, px, 500, 700, 1000, ExcelFit.ACTUAL, wide2.copy(fitToWidth = 1)).zoom, 1e-4f)
    }

    @Test fun bandsKeepAWideColumnAlone() {
        assertEquals(listOf(0..0, 1..1, 2..3), ExcelFit.bands(0..3, listOf(100, 900, 200, 200), 700f))
        assertEquals(emptyList<IntRange>(), ExcelFit.bands(IntRange.EMPTY, emptyList(), 700f))
    }

    @Test fun orientationFromTheSheetOnlyInAuto() {
        val setups = listOf(null, SheetPrint(landscape = true), SheetPrint(landscape = false))
        assertEquals(true, ExcelFit.landscape(setups, ExcelFit.AUTO))
        assertNull(ExcelFit.landscape(setups, ExcelFit.ONE_PAGE))
        assertNull(ExcelFit.landscape(listOf(SheetPrint()), ExcelFit.AUTO))
    }

    private fun cell(c: Int, v: String) = SheetCell(c, v, v, "s")

    @Test fun printsOneBandWithMergesCut() {
        val s = SheetModel("S", listOf(SheetRow(0, listOf(cell(0, "Title"))), SheetRow(1, listOf(cell(0, "a"), cell(1, "b"), cell(2, "c")))),
            listOf(MergeRange(0, 0, 0, 2)), emptyMap(), false)
        val book = WorkbookModel(listOf(s), emptyList())
        val html = OfficeSheetHtml.printSheet(book, 0, null, 700, null, null, band = 1..2, fixedZoom = 0.5f)
        assertEquals(2, Regex("<col style").findAll(html).count())
        assertTrue(html.contains("colspan=\"2\""))       // the merge cut to the band
        assertTrue(html.contains(">b<") && html.contains(">c<") && !html.contains(">a<"))
        assertTrue(html.contains("zoom:0.5"))
        // fitPlan uses the sheet's own setup and row heights
        val plan = OfficeSheetHtml.fitPlan(s.copy(print = SheetPrint(fitToPage = true)), 700, 1000, ExcelFit.AUTO)
        assertTrue(plan.onePart); assertEquals(1f, plan.zoom, 1e-4f)
    }
}
