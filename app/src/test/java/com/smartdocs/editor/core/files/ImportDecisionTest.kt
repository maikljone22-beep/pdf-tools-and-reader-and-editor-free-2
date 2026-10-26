package com.smartdocs.editor.core.files

import com.smartdocs.editor.core.files.ImportDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Audit DI-02 / DI-20 / DI-01 (journal) / DI-13 / DI-29. */
class ImportDecisionTest {
    private val imported = FileFingerprint(1000, 50, 777)
    private val record = SyncRecord(imported, srcSize = 1000, srcMtime = 9000)
    private val edited = FileFingerprint(1400, 60, 888)

    @Test fun readOnlySourceUnchanged_editedCopyIsReused() {
        // Telegram case: the source still has its imported size, the working copy grew with annotations
        assertEquals(Action.REUSE, ImportDecision.decide(record, edited, srcSize = 1000, srcMtime = 9000, writeBackPending = false))
        assertEquals(Action.REUSE, ImportDecision.decide(record, edited, srcSize = 1000, srcMtime = -1, writeBackPending = false))
    }

    @Test fun sourceChanged_uneditedCopyIsRefreshed() {
        assertEquals(Action.REFRESH, ImportDecision.decide(record, imported.copy(mtime = 51), srcSize = 1200, srcMtime = 9500, writeBackPending = false))
    }

    @Test fun sameSizeExternalEdit_detectedByMtime() {   // DI-20
        assertEquals(Action.REFRESH, ImportDecision.decide(record, imported, srcSize = 1000, srcMtime = 9999, writeBackPending = false))
    }

    @Test fun bothChanged_editedCopyIsNeverOverwritten() {
        assertEquals(Action.IMPORT_AS_NEW, ImportDecision.decide(record, edited, srcSize = 1200, srcMtime = 9500, writeBackPending = false))
    }

    @Test fun interruptedWriteBack_keepsWorkingCopy() {   // DI-01: source truncated by our own crashed write
        assertEquals(Action.REUSE, ImportDecision.decide(record, edited, srcSize = 12, srcMtime = 9900, writeBackPending = true))
    }

    @Test fun legacyCopyWithoutRecord() {
        assertEquals(Action.REUSE, ImportDecision.decide(null, edited, srcSize = 1400, srcMtime = -1, writeBackPending = false))
        assertEquals(Action.REUSE, ImportDecision.decide(null, edited, srcSize = -1, srcMtime = -1, writeBackPending = false))
        // legacy + size differs: it may hold edits, so it is not overwritten (the old code re-imported here)
        assertEquals(Action.IMPORT_AS_NEW, ImportDecision.decide(null, edited, srcSize = 1000, srcMtime = -1, writeBackPending = false))
    }

    @Test fun recordsRoundTrip() {
        assertEquals(record, SyncRecord.decode(record.encode()))
        assertEquals(imported, FileFingerprint.decode(imported.encode()))
        assertNull(SyncRecord.decode("garbage")); assertNull(SyncRecord.decode(null)); assertNull(FileFingerprint.decode("1|x|3"))
    }

    @Test fun fingerprintSeesEditsAtHeadMiddleAndTail() {
        val dir = Files.createTempDirectory("fp").toFile()
        try {
            val f = File(dir, "a.pdf")
            val bytes = ByteArray(400_000) { (it % 251).toByte() }
            f.writeBytes(bytes)
            val fp = FileFingerprint.of(f)
            assertTrue(fp.sameContent(FileFingerprint.of(f)))
            for (pos in listOf(10, 200_000, 399_990)) {
                val b = bytes.copyOf(); b[pos] = (b[pos] + 1).toByte(); f.writeBytes(b)
                assertFalse("edit at $pos", fp.sameContent(FileFingerprint.of(f)))
            }
            f.writeBytes(ByteArray(10) { 1 })   // small file
            val small = FileFingerprint.of(f)
            assertEquals(10L, small.size); assertNotEquals(fp.crc, small.crc)
        } finally { dir.deleteRecursively() }
    }

    @Test fun originCheck() {   // DI-13
        assertTrue(OriginCheck.looksLikeOriginal("Report.pdf", 1000, "report.pdf", 1000))
        assertTrue(OriginCheck.looksLikeOriginal("Report.pdf", -1, "Report.pdf", 5))
        assertFalse(OriginCheck.looksLikeOriginal("Report.pdf", 1000, "Report (1).pdf", 1000))
        assertFalse(OriginCheck.looksLikeOriginal("Report.pdf", 1000, "Report.pdf", 2000))
        assertFalse(OriginCheck.looksLikeOriginal(null, 1000, "Report.pdf", 1000))
    }

    @Test fun shortCopiesAreErrors() {   // DI-29
        DocumentRepository.verifyCopied(100, 100, 100)
        DocumentRepository.verifyCopied(100, 100, -1)
        for ((c, r) in listOf(0L to -1L, 99L to 99L, 100L to 0L)) {
            try { DocumentRepository.verifyCopied(100, c, r); fail("copied=$c reported=$r accepted") } catch (_: java.io.IOException) {}
        }
    }
}
