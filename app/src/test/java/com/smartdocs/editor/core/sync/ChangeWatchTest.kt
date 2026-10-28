package com.smartdocs.editor.core.sync

import com.smartdocs.editor.core.cloud.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-sync: watcher timing, which providers are watched, and when a periodic run may skip a pair. */
class ChangeWatchTest {
    private val plan = ChangeWatch.Plan

    @Test fun pollBacksOffFrom30sTo5Min() {
        assertEquals(30_000L, plan.pollDelay(0))
        assertEquals(60_000L, plan.pollDelay(1))
        assertEquals(240_000L, plan.pollDelay(3))
        assertEquals(300_000L, plan.pollDelay(4))
        assertEquals(300_000L, plan.pollDelay(50))
        assertEquals(30_000L, plan.pollDelay(-3))
    }

    @Test fun errorsBackOffTo15Min() {
        assertEquals(30_000L, plan.errorDelay(1))
        assertEquals(60_000L, plan.errorDelay(2))
        assertEquals(15 * 60_000L, plan.errorDelay(20))
        assertEquals(30_000L, plan.errorDelay(0))
    }

    private fun pair(p: ProviderId, auto: Boolean = true) = SyncPair(treeUri = "t", localName = "l", remoteFolderId = "r", remoteName = "n", provider = p, auto = auto)

    @Test fun onlyProvidersWithAutoPairsAreWatched() {
        assertEquals(emptySet<ProviderId>(), plan.wanted(emptyList()))
        assertEquals(setOf(ProviderId.DROPBOX), plan.wanted(listOf(pair(ProviderId.DROPBOX), pair(ProviderId.DRIVE, auto = false))))
        assertEquals(setOf(ProviderId.BOX, ProviderId.ONEDRIVE), plan.wanted(listOf(pair(ProviderId.BOX), pair(ProviderId.ONEDRIVE), pair(ProviderId.BOX))))
    }

    @Test fun localListingMustMatchSnapshotExactly() {
        val snap = mapOf("a.pdf" to SyncStore.Entry(100, 10, "r1", 5, 10), "sub/b.pdf" to SyncStore.Entry(200, 20, "r2", 6, 20))
        val same = mapOf("a.pdf" to SyncSkip.Side(100, 10), "sub/b.pdf" to SyncSkip.Side(200, 20))
        assertTrue(SyncSkip.localUnchanged(same, snap))
        assertFalse(SyncSkip.localUnchanged(same + ("new.pdf" to SyncSkip.Side(1, 1)), snap))          // new local file
        assertFalse(SyncSkip.localUnchanged(mapOf("a.pdf" to SyncSkip.Side(100, 10)), snap))           // local deletion
        assertFalse(SyncSkip.localUnchanged(same + ("a.pdf" to SyncSkip.Side(101, 10)), snap))         // edited
        assertFalse(SyncSkip.localUnchanged(same + ("a.pdf" to SyncSkip.Side(100, 11)), snap))
        assertTrue(SyncSkip.localUnchanged(emptyMap(), emptyMap()))
    }

    @Test fun pairQuietNeedsCleanStatusEmptyQueueAndRecentFullRun() {
        val now = 10_000_000_000L
        val clean = listOf(SyncQueue.QItem("a", SyncQueue.Op.UPLOAD, SyncQueue.State.SYNCED))
        assertTrue(SyncSkip.pairQuiet(SyncStatus.SYNCED, now - 60_000, clean, now))
        assertFalse(SyncSkip.pairQuiet(SyncStatus.FAILED, now - 60_000, clean, now))
        assertFalse(SyncSkip.pairQuiet(SyncStatus.NEEDS_CONFIRMATION, now - 60_000, clean, now))
        assertFalse(SyncSkip.pairQuiet(SyncStatus.SYNCED, 0, clean, now))                              // never synced
        assertFalse(SyncSkip.pairQuiet(SyncStatus.SYNCED, now - SyncSkip.FULL_EVERY_MS, clean, now))  // full run due
        assertFalse(SyncSkip.pairQuiet(SyncStatus.SYNCED, now - 60_000, clean + SyncQueue.QItem("b", SyncQueue.Op.DOWNLOAD, SyncQueue.State.FAILED), now))
        assertFalse(SyncSkip.pairQuiet(SyncStatus.SYNCED, now - 60_000, listOf(SyncQueue.QItem("c", SyncQueue.Op.UPLOAD, SyncQueue.State.PENDING)), now))
    }
}
