package com.smartdocs.editor.core.files

import com.smartdocs.editor.core.pdf.AnnotationWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteBackRepairTest {
    @Test fun onlyPendingMarkersAreRepaired() {
        val keys = listOf("l:/data/docs/a.pdf", "p:/data/docs/b.pdf", "s:/data/docs/b.pdf", "p:", "p:/data/docs/a.pdf")
        assertEquals(listOf("/data/docs/a.pdf", "/data/docs/b.pdf"), DocumentRepository.interruptedLocals(keys))
    }

    @Test fun bigPlainFilesAreAppended() {
        val big = AnnotationWriter.INCREMENTAL_MIN
        assertTrue(AnnotationWriter.useIncremental(false, false, big))
        assertFalse(AnnotationWriter.useIncremental(false, false, big - 1))   // small: full save (compact file)
        assertFalse(AnnotationWriter.useIncremental(true, false, big))        // a separate copy
        assertFalse(AnnotationWriter.useIncremental(false, true, big))        // encrypted: re-protected by a full save
    }
}
