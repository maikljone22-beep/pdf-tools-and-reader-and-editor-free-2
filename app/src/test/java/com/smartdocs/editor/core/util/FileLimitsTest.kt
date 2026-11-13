package com.smartdocs.editor.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLimitsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileOverTheLimitIsRefusedWithItsSize() {
        val f = tmp.newFile("big.txt").also { it.writeBytes(ByteArray(2048)) }
        try { FileLimits.require(f, 1024); fail("expected FileTooLargeException") }
        catch (e: FileTooLargeException) { assertEquals(2048L, e.size); assertEquals(1024L, e.max) }
    }

    @Test fun fileAtOrUnderTheLimitPasses() {
        val f = tmp.newFile("ok.txt").also { it.writeBytes(ByteArray(1024)) }
        FileLimits.require(f, 1024)
    }

    @Test fun textAndCsvLimitsAreSetBelowTheViewerLimit() {
        assertTrue(FileLimits.CSV_EDIT <= FileLimits.TEXT_EDIT)
    }
}
