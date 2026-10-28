package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** audit 5 E-5: after delete / reorder / duplicate / insert every remaining page keeps its page label. */
class PageLabelRemapTest {

    /** i, ii, iii, iv, then 1, 2, 3, 4 (8 pages). */
    private val front = listOf(LabelRange(0, LabelStyle.ROMAN_LOWER), LabelRange(4, LabelStyle.DECIMAL))

    private fun labels(ranges: List<LabelRange>, n: Int) = PageLabelFormat.labels(ranges, n)

    @Test fun deleteKeepsTheOldLabels() {
        val order = (0 until 8).filter { it != 1 }
        assertEquals(listOf("i", "iii", "iv", "1", "2", "3", "4"), labels(PageLabelRemap.remap(front, 8, order), 7))
    }

    @Test fun reorderMovesLabelsWithThePages() {
        val order = listOf(4, 0, 1, 2, 3, 5, 6, 7)
        assertEquals(listOf("1", "i", "ii", "iii", "iv", "2", "3", "4"), labels(PageLabelRemap.remap(front, 8, order), 8))
    }

    @Test fun newPagesContinueTheRunBeforeThem() {
        // a copy of page "ii" right after it, a new page at the end
        val order = listOf(0, 1, -1, 2, 3, 4, 5, 6, 7, -1)
        assertEquals(listOf("i", "ii", "iii", "iii", "iv", "1", "2", "3", "4", "5"), labels(PageLabelRemap.remap(front, 8, order), 10))
        // a new first page takes the style of the first kept page, from 1
        assertEquals(listOf("i", "i", "ii"), labels(PageLabelRemap.remap(front, 8, listOf(-1, 0, 1)), 3))
    }

    @Test fun prefixesAndStartNumbersSurvive() {
        val ranges = listOf(LabelRange(0, LabelStyle.DECIMAL, "A-", 5), LabelRange(2, LabelStyle.NONE, "Cover"))
        assertEquals(listOf("A-6", "Cover"), labels(PageLabelRemap.remap(ranges, 3, listOf(1, 2)), 2))
    }

    @Test fun plainNumberingStaysPhysical() {
        val plain = listOf(LabelRange(0))
        assertEquals(listOf(LabelRange(0)), PageLabelRemap.remap(plain, 3, listOf(2, 1, 0)))
    }

    private fun doc(n: Int, ranges: List<LabelRange>?): PDDocument {
        val d = PDDocument(); repeat(n) { d.addPage(PDPage()) }
        if (ranges != null) PageLabelRemap.write(d, ranges)
        return d
    }

    @Test fun deleteInTheOrganizerRemapsTheLabels() {
        doc(8, front).use { d ->
            PageOrganize.deleteIn(d, listOf(1), removeBookmarks = false)
            assertEquals(listOf("i", "iii", "iv", "1", "2", "3", "4"), labels(PageLabelRemap.read(d)!!, d.numberOfPages))
        }
    }

    @Test fun arrangeInPlaceAndDuplicateRemapTheLabels() {
        doc(8, front).use { d ->
            PdfOps.arrangeInPlace(d, listOf(7, 0, 1, 2, 3, 4, 5, 6))
            assertEquals(listOf("4", "i", "ii", "iii", "iv", "1", "2", "3"), labels(PageLabelRemap.read(d)!!, 8))
        }
        doc(8, front).use { d ->
            PageOps5.arrange(d, listOf(0, 0, 1, 2, 3, 4, 5, 6, 7))
            assertEquals(listOf("i", "ii", "ii", "iii", "iv", "1", "2", "3", "4"), labels(PageLabelRemap.read(d)!!, 9))
        }
    }

    @Test fun aDocumentWithoutLabelsGetsNone() {
        doc(3, null).use { d ->
            PageOrganize.deleteIn(d, listOf(0), removeBookmarks = false)
            assertNull(PageLabelRemap.read(d))
        }
    }
}
