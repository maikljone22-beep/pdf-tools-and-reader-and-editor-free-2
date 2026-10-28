package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** fix/pdf39-convert-2: batch page range per file and output folder (s6). */
class BatchOptionsTest {
    @Test fun perFileRangeFallsBackToTheCommonOne() {
        val a = File("/x/a.pdf"); val b = File("/x/b.pdf")
        val o = Cv6Opts(range = "1-2", fileRanges = mapOf(a.path to "5", b.path to " "))
        assertEquals("5", o.rangeFor(a))
        assertEquals("1-2", o.rangeFor(b))          // blank per-file range = the common one
        assertEquals("1-2", o.rangeFor(File("/x/c.pdf")))
        assertEquals("", Cv6Opts().outputTree)
    }
}
