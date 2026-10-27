package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nitro / Foxit compare gaps: page matching, moved pages, table compare, marker style, word count. */
class NitroCompareTest {

    private fun page(vararg w: String) = w.toSet()

    private val a = page("alpha", "beta", "gamma", "delta")
    private val b = page("one", "two", "three", "four")
    private val c = page("red", "green", "blue", "cyan")
    private val d = page("north", "south", "east", "west")

    @Test fun insertedAndDeletedPagesDoNotShiftTheRest() {
        // old: a b c   new: a X c   (b deleted, X inserted)
        val x = page("new", "page", "words", "here")
        val pairs = PageMatch.align(listOf(a, b, c), listOf(a, x, c))
        assertTrue(PagePair(0, 0, 1f) in pairs)
        assertTrue(PagePair(2, 2, 1f) in pairs)
        assertTrue(pairs.any { it.old == 1 && it.new == null })
        assertTrue(pairs.any { it.old == null && it.new == 1 })
        assertTrue(PageMatch.movedPairs(pairs).isEmpty())
    }

    @Test fun reorderedPagesAreMatchedAndReportedAsMoved() {
        // old: a b c d   new: a c d b
        val pairs = PageMatch.align(listOf(a, b, c, d), listOf(a, c, d, b))
        assertEquals(listOf(0 to 0, 2 to 1, 3 to 2, 1 to 3), pairs.map { it.old to it.new })
        val moved = PageMatch.movedPairs(pairs)
        assertEquals(setOf(1 to 3), moved.map { it.old to it.new }.toSet())
    }

    @Test fun pageRangesKeepTheirNumbers() {
        val pairs = PageMatch.align(listOf(c, d), listOf(c, d), oldIds = listOf(4, 5), newIds = listOf(9, 10))
        assertEquals(listOf(4 to 9, 5 to 10), pairs.map { it.old to it.new })
        assertEquals(listOf(PagePair(0, 3, 1f), PagePair(1, null, 1f)), PageMatch.inOrder(listOf(0, 1), listOf(3)))
    }

    @Test fun similarity() {
        assertEquals(1f, PageMatch.similarity(a, a), 0f)
        assertEquals(0f, PageMatch.similarity(a, b), 0f)
        assertEquals(1f / 3f, PageMatch.similarity(page("x", "y"), page("y", "z")), 0.001f)
    }

    private fun w(text: String, l: Float, y: Float, i: Int) = TableDetect.W(text, l, l + text.length * 5f, y, 10f, i)

    @Test fun tablesAreComparedCellByCell() {
        var i = 0
        val old = listOf(w("Item", 10f, 10f, i++), w("Price", 200f, 10f, i++), w("Apples", 10f, 30f, i++), w("5", 200f, 30f, i++),
            w("Pears", 10f, 50f, i++), w("7", 200f, 50f, i++), w("Just", 10f, 70f, i++), w("text", 32f, 70f, i++))
        val rowsOld = TableDetect.rows(old)
        assertEquals(3, rowsOld.size)   // the plain text line has one cell and is not a table row
        assertEquals(listOf("Apples", "5"), rowsOld[1].texts)
        i = 0
        val new = listOf(w("Item", 10f, 10f, i++), w("Price", 200f, 10f, i++), w("Apples", 10f, 30f, i++), w("6", 200f, 30f, i++),
            w("Pears", 10f, 50f, i++), w("7", 200f, 50f, i++), w("Plums", 10f, 70f, i++), w("9", 200f, 70f, i++))
        val ch = TableDetect.diff(rowsOld, TableDetect.rows(new), 0, 0)
        assertTrue(ch.any { it.row == 1 && it.col == 1 && it.old == "5" && it.new == "6" && it.type == ChangeType.REPLACE })
        assertTrue(ch.any { it.col == -1 && it.type == ChangeType.INSERT && it.new == "Plums | 9" })
        assertEquals(2, ch.size)
    }

    @Test fun styleRoundTrip() {
        val s = CompareStyle(insert = 0x43A047, delete = 0x8E24AA, replace = 0x212121, opacity = 0.7f, line = 1.5f)
        assertEquals(s, CompareStyle.fromJson(s.toJson()))
        assertEquals(CompareStyle(), CompareStyle.fromJson("not json"))
        assertEquals(0x8E24AA, s.colorOf(ChangeType.DELETE))
    }

    @Test fun wordCount() {
        val r = WordCount.count("Hello, world!\nمرحبا بالعالم 2026\n漢字かな")
        assertEquals(3 + 2 + 4, r.words)        // Hello world | مرحبا بالعالم 2026 | 4 Asian characters
        assertEquals(4, r.asian)
        assertEquals(3, r.lines)
        assertEquals("Hello, world!".length + "مرحبا بالعالم 2026".length + 4, r.chars)
        assertEquals(r.chars - 3, r.charsNoSpaces)
        assertEquals(0, WordCount.count("").words)
    }
}
