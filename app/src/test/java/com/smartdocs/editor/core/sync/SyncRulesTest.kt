package com.smartdocs.editor.core.sync

import com.smartdocs.editor.core.sync.SyncRules.Action
import com.smartdocs.editor.core.sync.SyncRules.Side
import com.smartdocs.editor.core.sync.SyncRules.Snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    private val snap = Snap(localMod = 10_000, localSize = 100, remoteMod = 50_000, remoteSize = 100)

    @Test fun unchangedBothSidesDoesNothing() {
        assertEquals(Action.NONE, SyncRules.decide(Side(10_000, 100), Side(50_000, 100), snap))
    }

    @Test fun clockToleranceIgnoresRounding() {
        assertEquals(Action.NONE, SyncRules.decide(Side(11_500, 100), Side(49_000, 100), snap))
    }

    @Test fun localEditUploads() {
        assertEquals(Action.UPLOAD, SyncRules.decide(Side(90_000, 120), Side(50_000, 100), snap))
        assertEquals(Action.UPLOAD, SyncRules.decide(Side(10_000, 101), Side(50_000, 100), snap)) // same time, size differs
    }

    @Test fun remoteEditDownloads() {
        assertEquals(Action.DOWNLOAD, SyncRules.decide(Side(10_000, 100), Side(80_000, 140), snap))
    }

    @Test fun bothEditedIsConflictNeverLoss() {
        assertEquals(Action.CONFLICT, SyncRules.decide(Side(90_000, 120), Side(80_000, 140), snap))
        assertEquals(Action.CONFLICT, SyncRules.decide(Side(90_000, 100), Side(80_000, 100), snap)) // same size still conflict
    }

    @Test fun firstSyncSameSizeIsRecordedDifferentIsConflict() {
        assertEquals(Action.RECORD, SyncRules.decide(Side(1, 100), Side(2, 100), null))
        assertEquals(Action.CONFLICT, SyncRules.decide(Side(1, 100), Side(2, 101), null))
    }

    @Test fun deletionMirroredOnlyWhenOtherSideUnchanged() {
        // deleted in the cloud, unchanged locally → delete locally
        assertEquals(Action.DELETE_LOCAL, SyncRules.decide(Side(10_000, 100), null, snap))
        // deleted in the cloud, but edited locally → upload again (an edit wins over a deletion)
        assertEquals(Action.UPLOAD_NEW, SyncRules.decide(Side(70_000, 130), null, snap))
        // deleted locally, unchanged in the cloud → delete in the cloud
        assertEquals(Action.DELETE_REMOTE, SyncRules.decide(null, Side(50_000, 100), snap))
        // deleted locally, edited in the cloud → download it back
        assertEquals(Action.DOWNLOAD_NEW, SyncRules.decide(null, Side(90_000, 100), snap))
    }

    @Test fun newFilesAreCopied() {
        assertEquals(Action.UPLOAD_NEW, SyncRules.decide(Side(1, 1), null, null))
        assertEquals(Action.DOWNLOAD_NEW, SyncRules.decide(null, Side(1, 1), null))
        assertEquals(Action.NONE, SyncRules.decide(null, null, snap))
    }

    @Test fun conflictNamesAreUnique() {
        val taken = mutableSetOf<String>()
        val first = SyncRules.conflictName("report.pdf") { it in taken }
        assertEquals("report" + SyncRules.CONFLICT_SUFFIX + ".pdf", first)
        taken += first
        assertEquals("report" + SyncRules.CONFLICT_SUFFIX + " 2.pdf", SyncRules.conflictName("report.pdf") { it in taken })
        assertEquals("README" + SyncRules.CONFLICT_SUFFIX, SyncRules.conflictName("README") { false })
        assertEquals(".hidden" + SyncRules.CONFLICT_SUFFIX, SyncRules.conflictName(".hidden") { false })
        assertTrue(SyncRules.CONFLICT_SUFFIX.contains("نسخة"))
    }

    @Test fun treeContainment() {
        assertTrue(SyncRules.isBelow("primary:Docs", "primary:Docs/a.pdf"))
        assertTrue(SyncRules.isBelow("primary:Docs", "primary:Docs/sub/b.pdf"))
        assertTrue(SyncRules.isBelow("primary:Docs", "primary:Docs"))
        assertTrue(!SyncRules.isBelow("primary:Docs", "primary:Docs2/a.pdf"))
        assertTrue(SyncRules.isBelow("primary:", "primary:x.pdf"))
    }

    @Test fun ignoredNames() {
        assertTrue(SyncRules.ignored(".a.pdf.part"))
        assertTrue(SyncRules.ignored("x.tmp"))
        assertTrue(!SyncRules.ignored("x.pdf"))
    }
}
