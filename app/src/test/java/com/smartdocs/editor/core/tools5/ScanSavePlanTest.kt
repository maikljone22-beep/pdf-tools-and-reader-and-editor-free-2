package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.tools5.ScanSavePlan.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSavePlanTest {
    @Test fun formatFollowsKind() {
        assertEquals(Format.WORD, ScanSavePlan.format(asImages = true, asWord = true, ScanKind.DOCUMENT))
        assertEquals(Format.JPG, ScanSavePlan.format(asImages = true, asWord = false, ScanKind.BOOK))
        assertEquals(Format.PDF, ScanSavePlan.format(asImages = false, asWord = false, ScanKind.DOCUMENT))
        // a receipt / card / form is read from its PDF whatever was chosen
        for (k in listOf(ScanKind.RECEIPT, ScanKind.BUSINESS_CARD, ScanKind.FORM)) assertEquals(Format.PDF, ScanSavePlan.format(true, true, k))
    }

    @Test fun textOnlyWhenUsed() {
        assertTrue(ScanSavePlan.needsText(Format.WORD, autoOcr = false, ScanKind.DOCUMENT))
        assertFalse(ScanSavePlan.needsText(Format.JPG, autoOcr = true, ScanKind.DOCUMENT))
        assertFalse(ScanSavePlan.needsText(Format.PDF, autoOcr = false, ScanKind.DOCUMENT))
        assertTrue(ScanSavePlan.needsText(Format.PDF, autoOcr = false, ScanKind.RECEIPT))
    }

    @Test fun chunkCountsOutputPagesAndKeepsAppendWhole() {
        assertEquals(0, ScanSavePlan.chunk(0, ScanKind.DOCUMENT, appending = false, groups = 1))
        assertEquals(3, ScanSavePlan.chunk(3, ScanKind.DOCUMENT, false, 1))
        assertEquals(6, ScanSavePlan.chunk(3, ScanKind.ID_CARD, false, 1))
        assertEquals(2, ScanSavePlan.chunk(4, ScanKind.BOOK, false, 1))
        assertEquals(1, ScanSavePlan.chunk(1, ScanKind.BOOK, false, 1))
        // one document added to an existing PDF: never cut
        assertEquals(0, ScanSavePlan.chunk(3, ScanKind.DOCUMENT, appending = true, groups = 1))
        // several documents: no append happens, so pages per file still applies
        assertEquals(3, ScanSavePlan.chunk(3, ScanKind.DOCUMENT, appending = true, groups = 2))
    }

    @Test fun cancelKeepsOnlyUnsavedPages() {
        val groups = listOf(listOf("a", "b"), listOf("c"), listOf("d", "e"))
        assertEquals(groups, ScanSavePlan.remainingAfterCancel(groups, emptyList()))
        assertEquals(listOf(listOf("d", "e")), ScanSavePlan.remainingAfterCancel(groups, listOf(listOf("a", "b"), listOf("c"))))
        // a document cut from a group (pages per file) leaves the rest of that group
        assertEquals(listOf(listOf("b"), listOf("c"), listOf("d", "e")), ScanSavePlan.remainingAfterCancel(groups, listOf(listOf("a"))))
    }
}
