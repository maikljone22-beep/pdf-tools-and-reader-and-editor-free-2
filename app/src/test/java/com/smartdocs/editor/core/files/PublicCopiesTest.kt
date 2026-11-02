package com.smartdocs.editor.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCopiesTest {
    @Test fun onlyRealLibraryDocumentsWithoutAnOriginArePublished() {
        assertTrue(PublicCopies.eligible("report.pdf", insideLibrary = true, hasOrigin = false))
        assertFalse(PublicCopies.eligible("report.pdf", insideLibrary = true, hasOrigin = true))     // imported: its source is the copy
        assertFalse(PublicCopies.eligible("report.pdf", insideLibrary = false, hasOrigin = false))    // temp / cache files
        assertFalse(PublicCopies.eligible(".report.pdf.import", insideLibrary = true, hasOrigin = false))
        assertFalse(PublicCopies.eligible("scan.part_1.pdf", insideLibrary = true, hasOrigin = false))
        assertFalse(PublicCopies.eligible("x.tmp", insideLibrary = true, hasOrigin = false))
    }
}
