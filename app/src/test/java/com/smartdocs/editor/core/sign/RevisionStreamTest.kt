package com.smartdocs.editor.core.sign

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tabs audit D22: the streamed revision scan gives the same ends as the in-memory one, across block borders. */
class RevisionStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun same(bytes: ByteArray) {
        val f = File(tmp.root, "r_${System.nanoTime()}.pdf").apply { writeBytes(bytes) }
        assertEquals(RevisionDiff.revisionEnds(bytes), RevisionDiff.revisionEnds(f))
    }

    @Test fun matchesTheInMemoryScan() {
        same("a%%EOF\r\nb%%EOF\nc%%EOF\rd%%EOF".toByteArray())
        same("%%%EOF\n%EOF%%EO%%EOF".toByteArray())
        same(ByteArray(0))
        // a marker and its EOL split across the 64 KB read blocks
        for (shift in listOf(65536 - 6, 65536 - 5, 65536 - 3, 65536 - 1, 65536)) {
            val b = ByteArray(shift + 20) { 'x'.code.toByte() }
            "%%EOF\r\n".toByteArray().copyInto(b, shift)
            same(b)
        }
    }
}
