package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAppendJournalTest {
    @Test fun restoresOnlyWhenTheWriteDidNotFinish() {
        assertFalse(ScanAppendJournal.needsRestore(1000L, 1000L))   // finished: keep the new file
        assertTrue(ScanAppendJournal.needsRestore(0L, 1000L))       // emptied by "wt", died before writing
        assertTrue(ScanAppendJournal.needsRestore(512L, 1000L))     // cut short
        assertTrue(ScanAppendJournal.needsRestore(null, 1000L))     // cannot be measured
    }
}
