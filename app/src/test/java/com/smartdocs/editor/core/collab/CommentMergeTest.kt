package com.smartdocs.editor.core.collab

import com.smartdocs.editor.core.collab.CommentMerge.Incoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentMergeTest {
    private val me = "me-0000001"

    @Test fun takesOthersNewComments() {
        val p = CommentMerge.plan(listOf(Incoming("a", "bob-00001", 10, false), Incoming("b", "eve-00001", 20, false)), me, emptyMap(), emptySet())
        assertEquals(listOf("a", "b"), p.upserts)
        assertEquals(2, p.arrivals)
        assertEquals(mapOf("a" to 10L, "b" to 20L), p.applied)
    }

    @Test fun neverTakesMyOwnComments() {
        val p = CommentMerge.plan(listOf(Incoming("m1", me, 10, false), Incoming("m2", "bob-00001", 10, false)), me, emptyMap(), mine = setOf("m2"))
        assertTrue(p.isEmpty)
        assertEquals(0, p.arrivals)
    }

    @Test fun onlyNewerVersionsAreMergedAndEditsAreNotAnnounced() {
        val applied = mapOf("a" to 10L)
        assertTrue(CommentMerge.plan(listOf(Incoming("a", "bob-00001", 10, false)), me, applied, emptySet()).isEmpty)
        val p = CommentMerge.plan(listOf(Incoming("a", "bob-00001", 15, false)), me, applied, emptySet())
        assertEquals(listOf("a"), p.upserts)
        assertEquals(0, p.arrivals)
        assertEquals(15L, p.applied["a"])
    }

    @Test fun tombstones() {
        // known comment deleted remotely → delete
        val p = CommentMerge.plan(listOf(Incoming("a", "bob-00001", 30, true)), me, mapOf("a" to 10L), emptySet())
        assertEquals(setOf("a"), p.deletes)
        // unknown tombstone → nothing to delete, but remembered
        val q = CommentMerge.plan(listOf(Incoming("z", "bob-00001", 30, true)), me, emptyMap(), emptySet())
        assertTrue(q.deletes.isEmpty()); assertEquals(30L, q.applied["z"])
        // present in the file (e.g. came inside the shared PDF) though never applied → delete
        val r = CommentMerge.plan(listOf(Incoming("z", "bob-00001", 30, true)), me, emptyMap(), emptySet(), present = setOf("z"))
        assertEquals(setOf("z"), r.deletes)
    }

    @Test fun newestDuplicateWins() {
        val p = CommentMerge.plan(listOf(Incoming("a", "bob-00001", 30, true), Incoming("a", "bob-00002", 40, false)), me, mapOf("a" to 1L), emptySet())
        assertEquals(listOf("a"), p.upserts); assertTrue(p.deletes.isEmpty())
        assertEquals(40L, p.applied["a"])
    }

    @Test fun commentsAlreadyInFileAreNotCountedAsNew() {
        val p = CommentMerge.plan(listOf(Incoming("a", "bob-00001", 5, false)), me, emptyMap(), emptySet(), present = setOf("a"))
        assertEquals(listOf("a"), p.upserts)
        assertEquals(0, p.arrivals)
        assertEquals(1, CommentMerge.pendingCount(listOf(Incoming("a", "bob-00001", 5, false)), me, emptyMap(), emptySet()))
    }
}
