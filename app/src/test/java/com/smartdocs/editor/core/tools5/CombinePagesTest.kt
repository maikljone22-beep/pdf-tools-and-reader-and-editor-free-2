package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CombinePagesTest {
    private val a = CombineEntry(File("/x/a.pdf"), "A")
    private val b = CombineEntry(File("/x/b.pdf"), "B", pages = "2-3")
    private val w = CombineEntry(File("/x/w.docx"), "W")
    private val att = CombineEntry(File("/x/z.xlsx"), "Z", attach = true)
    private val entries = listOf(a, w, b, att)
    private val counts = mapOf(0 to 3, 2 to 4)

    @Test fun expandFollowsRangesAndSkipsNonPdf() {
        val refs = CombinePages.expand(entries, counts)
        assertEquals(listOf(CombinePages.Ref(0, 0), CombinePages.Ref(0, 1), CombinePages.Ref(0, 2), CombinePages.Ref(2, 1), CombinePages.Ref(2, 2)), refs)
    }

    @Test fun foldUnchangedGivesTheSameEntries() {
        val refs = CombinePages.expand(entries, counts)
        val back = CombinePages.fold(refs, entries, counts)
        assertEquals(listOf(a, w, b, att), back)
    }

    @Test fun movingAPageOfBIntoAMakesThreeRuns() {
        val refs = CombinePages.expand(entries, counts)
        // B page 3 (index 4) moved up twice → between A p2 and A p3
        var r = CombinePages.move(refs, setOf(4), -1); r = CombinePages.move(r, setOf(3), -1)
        assertEquals(listOf(0, 0, 2, 0, 2).zip(listOf(0, 1, 2, 2, 1)).map { CombinePages.Ref(it.first, it.second) }, r)
        val back = CombinePages.fold(r, entries, counts)
        assertEquals(listOf(a.copy(pages = "1-2"), w, b.copy(pages = "3"), a.copy(pages = "3"), b.copy(pages = "2"), att), back)
    }

    @Test fun droppedPagesAndWholeFileDisappear() {
        val refs = CombinePages.expand(entries, counts).filter { it.entry != 2 && it.page != 1 }
        assertEquals(listOf(a.copy(pages = "1"), a.copy(pages = "3"), w, att), CombinePages.fold(refs, entries, counts))
    }

    @Test fun moveClampsAtTheEnds() {
        val refs = CombinePages.expand(entries, counts)
        assertEquals(refs, CombinePages.move(refs, setOf(0), -1))
        assertEquals(refs, CombinePages.move(refs, setOf(4), 1))
        assertEquals(setOf(1, 2), CombinePages.movedIndexes(setOf(0, 1), 1, 5))
    }
}
