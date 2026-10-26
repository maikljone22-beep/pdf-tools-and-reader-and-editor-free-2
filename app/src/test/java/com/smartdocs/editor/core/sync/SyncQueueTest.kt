package com.smartdocs.editor.core.sync

import com.smartdocs.editor.core.sync.SyncQueue.Op
import com.smartdocs.editor.core.sync.SyncQueue.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQueueTest {
    @Test fun happyPath() {
        val q = SyncQueue()
        q.enqueue("a.pdf", Op.UPLOAD, 0)
        assertEquals(State.PENDING, q["a.pdf"]!!.state)
        assertTrue(q.start("a.pdf", 1))
        assertEquals(State.RUNNING, q["a.pdf"]!!.state)
        q.succeed("a.pdf", 2)
        assertEquals(State.SYNCED, q["a.pdf"]!!.state)
        assertEquals(0, q["a.pdf"]!!.attempts)
        assertTrue(q.due(3).isEmpty())
    }

    @Test fun failureBacksOffAndBecomesDue() {
        val q = SyncQueue()
        q.enqueue("a.pdf", Op.DOWNLOAD, 0); q.start("a.pdf", 0)
        q.fail("a.pdf", "timeout", 1_000)
        val it = q["a.pdf"]!!
        assertEquals(State.FAILED, it.state); assertEquals(1, it.attempts); assertEquals(1_000 + 30_000, it.nextAt)
        assertFalse(q.mayRun("a.pdf", 10_000))
        assertTrue(q.mayRun("a.pdf", 10_000, force = true))
        assertTrue(q.due(10_000).isEmpty())
        assertEquals(listOf("a.pdf"), q.due(31_000).map { it.path })
        // second failure doubles the wait
        q.start("a.pdf", 31_000); q.fail("a.pdf", "timeout", 31_000)
        assertEquals(31_000 + 60_000, q["a.pdf"]!!.nextAt)
    }

    @Test fun backoffIsCapped() {
        assertEquals(0, SyncQueue.backoff(0))
        assertEquals(30_000, SyncQueue.backoff(1))
        assertEquals(240_000, SyncQueue.backoff(4))
        assertEquals(SyncQueue.MAX_BACKOFF_MS, SyncQueue.backoff(40))
    }

    @Test fun givesUpAfterMaxAttemptsUntilManualRetry() {
        val q = SyncQueue(maxAttempts = 2)
        q.enqueue("a", Op.UPLOAD, 0)
        repeat(2) { q.start("a", 0); q.fail("a", "x", 0) }
        assertTrue(q.gaveUp(q["a"]!!))
        assertTrue(q.due(Long.MAX_VALUE / 2).isEmpty())
        assertTrue(q.retry("a", 5))
        assertEquals(State.PENDING, q["a"]!!.state)
        assertEquals(1, q["a"]!!.attempts)
        assertEquals(1, q.due(5).size)
    }

    @Test fun newChangeResetsAttemptsButSameOpKeepsBackoff() {
        val q = SyncQueue()
        q.enqueue("a", Op.UPLOAD, 0); q.start("a", 0); q.fail("a", "x", 0)
        q.enqueue("a", Op.UPLOAD, 1)
        assertEquals(1, q["a"]!!.attempts)
        q.enqueue("a", Op.DOWNLOAD, 2)
        assertEquals(0, q["a"]!!.attempts)
        assertNull(q["a"]!!.error)
    }

    @Test fun runningItemIsNotReplacedAndRecoversAfterCrash() {
        val q = SyncQueue()
        q.enqueue("a", Op.UPLOAD, 0); q.start("a", 0)
        q.enqueue("a", Op.DOWNLOAD, 1)
        assertEquals(State.RUNNING, q["a"]!!.state)
        assertFalse(q.start("a", 2))
        q.recoverInterrupted(3)
        assertEquals(State.PENDING, q["a"]!!.state)
    }

    @Test fun pruneAndJsonRoundTrip() {
        val q = SyncQueue()
        q.enqueue("old", Op.UPLOAD, 0); q.start("old", 0); q.succeed("old", 0)
        q.enqueue("gone", Op.UPLOAD, 0)
        q.enqueue("keep", Op.DELETE_REMOTE, 0); q.start("keep", 0); q.fail("keep", "denied", 0)
        val copy = SyncQueue.fromJson(org.json.JSONArray(q.toJson().toString()))
        assertEquals(q.all, copy.all)
        copy.prune(now = 10L * 24 * 3600_000, exists = { it != "gone" })
        assertEquals(listOf("keep"), copy.all.map { it.path })
        assertEquals(1, copy.counts()[State.FAILED])
    }

    @Test fun opMapping() {
        assertEquals(Op.UPLOAD, SyncQueue.opFor(SyncRules.Action.UPLOAD_NEW))
        assertEquals(Op.CONFLICT, SyncQueue.opFor(SyncRules.Action.CONFLICT))
        assertNull(SyncQueue.opFor(SyncRules.Action.RECORD))
    }
}
