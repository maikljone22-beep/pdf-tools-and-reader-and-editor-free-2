package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftoverTest {
    @Test fun hiddenTempsAreLeftovers() {
        listOf(".book.saving.pdf", ".book.pdf.import", ".a.pdf.123.tmp", ".x.sigflat.pdf", ".b.pdf.replace").forEach { assertTrue(it, Cv6Platform.isLeftover(it)) }
    }
    @Test fun documentsAndBackupsAreKept() {
        listOf("book.pdf", "book.saving.pdf", ".book.annotedit.pdf", "notes.tmp.docx").forEach { assertFalse(it, Cv6Platform.isLeftover(it)) }
    }
}
