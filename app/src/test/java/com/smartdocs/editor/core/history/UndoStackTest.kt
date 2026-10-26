package com.smartdocs.editor.core.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pure logic of the unified undo history: ordering, redo clearing, limits / pruning, jump-to-step, save consolidation. */
class UndoStackTest {

    /** In-memory "document": a list of ints changed by memory steps. */
    private class Doc { val items = ArrayList<Int>() }

    private fun memStep(doc: Doc, v: Int, owner: String? = "annots"): HistoryStep.Memory {
        doc.items += v
        return HistoryStep.Memory("add $v", undo = { doc.items.remove(v) }, redo = { doc.items += v }, owner = owner)
    }

    /** Undo the top step the way DocHistory does: perform, then commit. */
    private fun UndoStack.undoOne(): HistoryStep? {
        val s = peekUndo() ?: return null
        (s as? HistoryStep.Memory)?.undo?.invoke()
        commitUndo(s); return s
    }

    private fun UndoStack.redoOne(): HistoryStep? {
        val s = peekRedo() ?: return null
        (s as? HistoryStep.Memory)?.redo?.invoke()
        commitRedo(s); return s
    }

    private fun tempFile(bytes: Int): File = Files.createTempFile("hist", ".pdf").toFile().apply { writeBytes(ByteArray(bytes)); deleteOnExit() }

    @Test fun undoRedoKeepOrder() {
        val doc = Doc(); val st = UndoStack()
        st.push(memStep(doc, 1)); st.push(memStep(doc, 2)); st.push(memStep(doc, 3))
        assertEquals(listOf(1, 2, 3), doc.items)
        assertEquals("add 3", st.undoOne()!!.label)
        assertEquals("add 2", st.undoOne()!!.label)
        assertEquals(listOf(1), doc.items)
        assertEquals(listOf("add 2", "add 3"), st.redoList().map { it.label })   // next to redo first
        assertEquals("add 2", st.redoOne()!!.label)
        assertEquals(listOf(1, 2), doc.items)
        assertTrue(st.canUndo); assertTrue(st.canRedo)
        assertEquals("add 3", st.redoOne()!!.label)
        assertFalse(st.canRedo)
        assertEquals(listOf(1, 2, 3), doc.items)
    }

    @Test fun newChangeClearsRedo() {
        val doc = Doc(); val dropped = ArrayList<HistoryStep>()
        val st = UndoStack(onDrop = { dropped += it })
        st.push(memStep(doc, 1)); st.push(memStep(doc, 2))
        st.undoOne()
        assertTrue(st.canRedo)
        st.push(memStep(doc, 5))
        assertFalse(st.canRedo)
        assertEquals(listOf("add 2"), dropped.map { it.label })
        assertEquals(listOf("add 1", "add 5"), st.undoList().map { it.label })
    }

    @Test fun failedActionDoesNotMoveStep() {
        val st = UndoStack()
        val a = HistoryStep.Memory("a", {}, {})
        st.push(a)
        // the caller only commits when the undo succeeded; committing a different step is ignored
        st.commitUndo(HistoryStep.Memory("other", {}, {}))
        assertSame(a, st.peekUndo())
        assertFalse(st.canRedo)
    }

    @Test fun countLimitDropsOldest() {
        val dropped = ArrayList<String>()
        val st = UndoStack(maxSteps = 3, onDrop = { dropped += it.label })
        (1..5).forEach { st.push(HistoryStep.Memory("s$it", {}, {})) }
        assertEquals(listOf("s3", "s4", "s5"), st.undoList().map { it.label })
        assertEquals(listOf("s1", "s2"), dropped)
    }

    @Test fun sizeLimitDropsOldestFileSnapshotsAndDeletesThem() {
        val files = (1..4).map { tempFile(400) }
        val st = UndoStack(maxSteps = 50, maxBytes = 1000, onDrop = { s -> (s as? HistoryStep.FileSnapshot)?.files()?.forEach { it.delete() } })
        files.forEachIndexed { i, f -> st.push(HistoryStep.FileSnapshot("f$i", f, null)) }
        // 4 x 400 bytes > 1000: the two oldest go
        assertEquals(listOf("f2", "f3"), st.undoList().map { it.label })
        assertFalse(files[0].exists()); assertFalse(files[1].exists())
        assertTrue(files[2].exists()); assertTrue(files[3].exists())
        assertTrue(st.totalBytes() <= 1000)
    }

    @Test fun newestStepIsKeptEvenWhenTooLarge() {
        val big = tempFile(5000)
        val st = UndoStack(maxBytes = 1000)
        st.push(HistoryStep.FileSnapshot("big", big, "pw"))
        assertEquals(1, st.size)
        assertEquals("pw", (st.peekUndo() as HistoryStep.FileSnapshot).beforePassword)
    }

    @Test fun jumpCounts() {
        val st = UndoStack()
        val steps = (1..5).map { HistoryStep.Memory("s$it", {}, {}) }
        steps.forEach { st.push(it) }
        assertEquals(3, st.undoCountTo(steps[2].id))       // undo s5, s4, s3
        assertEquals(1, st.undoCountTo(steps[4].id))
        assertEquals(0, st.undoCountTo(-42))
        repeat(3) { st.undoOne() }
        assertEquals(listOf("s3", "s4", "s5"), st.redoList().map { it.label })
        assertEquals(2, st.redoCountTo(steps[3].id))        // redo s3, s4
        assertEquals(0, st.redoCountTo(steps[0].id))        // still in the undo list
    }

    @Test fun replaceMemoryConsolidatesAtSave() {
        val dropped = ArrayList<String>()
        val st = UndoStack(onDrop = { dropped += it.label })
        val f0 = tempFile(10)
        st.push(HistoryStep.FileSnapshot("rotate", f0, null))
        st.push(HistoryStep.Memory("note", {}, {}, owner = "annots"))
        st.push(HistoryStep.Memory("field", {}, {}, owner = "forms"))
        st.push(HistoryStep.Memory("ink", {}, {}, owner = "annots"))
        st.undoOne()                                         // ink is now redo-able
        val saved = HistoryStep.FileSnapshot("save", tempFile(10), null)
        val labels = st.replaceMemory(saved)
        assertEquals(listOf("note", "field"), labels)
        assertEquals(listOf("rotate", "save"), st.undoList().map { it.label })
        assertFalse(st.canRedo)                              // saving is a new change
        assertTrue(dropped.containsAll(listOf("note", "field", "ink")))
    }

    @Test fun removeIfAndClear() {
        val dropped = ArrayList<String>()
        val st = UndoStack(onDrop = { dropped += it.label })
        st.push(HistoryStep.Memory("a", {}, {}, owner = "forms"))
        st.push(HistoryStep.Memory("b", {}, {}, owner = "annots"))
        st.push(HistoryStep.Memory("c", {}, {}, owner = "forms"))
        st.undoOne()
        assertEquals(2, st.removeIf { it.owner == "forms" })
        assertEquals(listOf("b"), st.all().map { it.label })
        st.clear()
        assertEquals(0, st.size)
        assertEquals(listOf("a", "c", "b"), dropped)
    }

    @Test fun groupLabelAndFolderName() {
        assertEquals("fallback", HistoryNames.groupLabel(emptyList(), "fallback") { l, n -> "$l+$n" })
        assertEquals("one", HistoryNames.groupLabel(listOf("one"), "fallback") { l, n -> "$l+$n" })
        assertEquals("c+2", HistoryNames.groupLabel(listOf("a", "b", "c"), "fallback") { l, n -> "$l+$n" })
        val a = HistoryNames.folderName("content://x/doc.pdf", 1)
        val b = HistoryNames.folderName("content://x/doc.pdf", 2)
        assertTrue(a != b && a.startsWith("d") && !a.contains('/'))
    }

    @Test fun docHistoryFileUndoRedoRoundTrip() = runBlocking {
        val tmp = Files.createTempDirectory("hist_root").toFile()
        val work = File(tmp, "doc.pdf").apply { writeText("v1") }
        var pw: String? = "old"
        val h = DocHistory.open(tmp, "doc")
        h.current = { work to pw }
        h.restorer = { src, p -> src.copyTo(work, overwrite = true); pw = p }
        // a change: snapshot of v1 (password "old"), then the file becomes v2 with password "new"
        val snap = h.copyIn(work)
        work.writeText("v2"); pw = "new"
        h.push(HistoryStep.FileSnapshot("protect", snap, "old"))
        assertTrue(h.canUndo)
        assertEquals("protect", h.undo()!!.label)
        assertEquals("v1", work.readText()); assertEquals("old", pw)
        assertTrue(h.canRedo)
        assertEquals("protect", h.redo()!!.label)
        assertEquals("v2", work.readText()); assertEquals("new", pw)
        assertNull(h.redo())
        // stale folders of earlier processes are removed when a document opens; the live one is kept
        val stale = File(DocHistory.root(tmp), "dead_folder").apply { mkdirs() }
        val h2 = DocHistory.open(tmp, "other")
        assertFalse(stale.exists())
        assertTrue(h.dir.exists())
        h.dispose(); h2.dispose()
        assertFalse(h.dir.exists())
        tmp.deleteRecursively()
        Unit
    }
}
