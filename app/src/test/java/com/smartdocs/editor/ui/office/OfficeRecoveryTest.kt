package com.smartdocs.editor.ui.office

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeRecoveryTest {
    @Test fun offersOnlyANewerNonEmptyCopy() {
        assertTrue(OfficeRecovery.worthOffering(copyModified = 2000, copyLength = 10, fileModified = 1000))
        assertFalse(OfficeRecovery.worthOffering(copyModified = 1000, copyLength = 10, fileModified = 2000))   // saved after the copy
        assertFalse(OfficeRecovery.worthOffering(copyModified = 2000, copyLength = 0, fileModified = 1000))    // broken copy
    }
}
