package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** audit 5 C-12: large documents no longer come out as "everything changed". */
class WordDiffPagedTest {

    private fun words(prefix: String, n: Int) = (1..n).map { "$prefix$it" }

    @Test fun pagedDiffKeepsOffsetsAcrossPages() {
        val old = listOf(listOf("a", "b", "c"), listOf("d", "e"))
        val new = listOf(listOf("a", "x", "c"), listOf("d", "e", "f"))
        val c = WordDiff.diffPaged(old, new)
        val s = WordDiff.stats(c)
        assertEquals(1, s.replaced); assertEquals(1, s.inserted); assertEquals(4, s.equal)
        // the insert is at the end of the concatenated new list (index 5), after old index 4
        val ins = c.single { it.type == ChangeType.INSERT }
        assertEquals(5..5, ins.b)
        assertEquals(5, ins.a.first)
    }

    @Test fun pagedDiffHandlesUnmatchedPages() {
        val c = WordDiff.diffPaged(listOf(listOf("a", "b"), emptyList()), listOf(listOf("a", "b"), listOf("new", "page")))
        val s = WordDiff.stats(c)
        assertEquals(2, s.equal); assertEquals(2, s.inserted)
    }

    @Test fun beyondMaxDFallsBackToAnchorsInsteadOfReplacingEverything() {
        // 3 000 unique words, every second one changed: far more than maxD = 50 edits
        val old = words("w", 3000)
        val new = old.mapIndexed { i, w -> if (i % 2 == 0) "changed$i" else w }
        val c = WordDiff.diffKeys(old, new, maxD = 50)
        val s = WordDiff.stats(c)
        assertEquals(1500, s.equal)
        assertTrue(s.similarity() > 0.4f)
    }

    @Test fun noCommonTokensStillReplacesAll() {
        val c = WordDiff.diffKeys(words("a", 100), words("b", 100), maxD = 10)
        val s = WordDiff.stats(c)
        assertEquals(0, s.equal); assertEquals(100, s.replaced)
    }

    @Test fun longestIncreasingRun() {
        val pairs = listOf(intArrayOf(0, 3), intArrayOf(1, 1), intArrayOf(2, 2), intArrayOf(3, 0), intArrayOf(4, 4))
        val run = WordDiff.longestIncreasing(pairs).map { it[1] }
        assertEquals(listOf(1, 2, 4), run)
    }

    @Test fun interruptedThreadStopsTheDiff() {
        Thread.currentThread().interrupt()
        try {
            WordDiff.diffKeys(words("a", 500), words("b", 500).reversed(), maxD = 2000)
            org.junit.Assert.fail("expected InterruptedException")
        } catch (e: InterruptedException) {
            // expected
        } finally { Thread.interrupted() }
    }
}
