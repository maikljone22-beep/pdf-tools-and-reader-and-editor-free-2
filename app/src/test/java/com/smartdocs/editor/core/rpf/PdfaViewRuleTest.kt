package com.smartdocs.editor.core.rpf

import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.smartdocs.editor.core.security.SecurityInfo
import com.smartdocs.editor.ui.reader.ReaderTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfaViewRuleTest {
    @Test fun onlyContentAndAnnotationEditsAreBlocked() {
        listOf(DocAction.ANNOTATE, DocAction.MODIFY, DocAction.ASSEMBLE, DocAction.CHANGE_SECURITY).forEach { assertTrue(it.name, PdfaViewRule.blocks(true, it)) }
        // reading, copying, printing, share / save a copy, form filling stay allowed
        listOf(DocAction.PRINT, DocAction.COPY, DocAction.SAVE_UNPROTECTED, DocAction.FILL_FORMS).forEach { assertFalse(it.name, PdfaViewRule.blocks(true, it)) }
    }

    @Test fun offBlocksNothing() {
        DocAction.entries.forEach { assertFalse(it.name, PdfaViewRule.blocks(false, it)) }
    }

    @Test fun fillAndSignToolsAreFormFilling() {
        ReaderTool.entries.filter { it.name.startsWith("FS_") }.forEach {
            assertEquals(it.name, DocAction.FILL_FORMS, PdfaViewRule.toolAction(it.name, DocAction.ANNOTATE))
            assertFalse(it.name, PdfaViewRule.blocks(true, PdfaViewRule.toolAction(it.name, DocAction.ANNOTATE)))
        }
        assertEquals(DocAction.FILL_FORMS, PdfaViewRule.toolAction(ReaderTool.SIGNATURE.name, DocAction.ANNOTATE))
        assertTrue(PdfaViewRule.blocks(true, PdfaViewRule.toolAction(ReaderTool.HIGHLIGHT.name, DocAction.ANNOTATE)))
        assertTrue(PdfaViewRule.blocks(true, PdfaViewRule.toolAction(ReaderTool.LINK.name, DocAction.MODIFY)))
    }

    @Test fun annotationChanges() {
        assertEquals(DocAction.FILL_FORMS, PdfaViewRule.annotAction(fillSignTab = true, toolName = null))
        assertEquals(DocAction.FILL_FORMS, PdfaViewRule.annotAction(false, "FS_TEXT"))
        assertEquals(DocAction.ANNOTATE, PdfaViewRule.annotAction(false, "INK"))
        assertEquals(DocAction.ANNOTATE, PdfaViewRule.annotAction(false, null))
    }

    /** The guard's decision: restrictions / Protected View first, then PDF/A view mode on what it weighs. */
    private fun guardBlocks(pdfaView: Boolean, protectedView: Boolean, a: DocAction, pdfaAs: DocAction = a): Boolean =
        ReaderRestrictions.check(SecurityInfo.NONE, protectedView, a) != null || PdfaViewRule.blocks(pdfaView, pdfaAs)

    @Test fun guardDecision() {
        assertFalse(guardBlocks(true, false, DocAction.FILL_FORMS))
        assertFalse(guardBlocks(true, false, DocAction.PRINT))
        assertFalse(guardBlocks(true, false, DocAction.SAVE_UNPROTECTED))
        assertFalse(guardBlocks(true, false, DocAction.ANNOTATE, DocAction.FILL_FORMS))     // Fill & Sign
        assertTrue(guardBlocks(true, false, DocAction.ANNOTATE))
        // Protected View still refuses form filling on its own
        assertTrue(guardBlocks(false, true, DocAction.FILL_FORMS))
        assertNull(ReaderRestrictions.check(SecurityInfo.NONE, false, DocAction.ANNOTATE))
    }
}
