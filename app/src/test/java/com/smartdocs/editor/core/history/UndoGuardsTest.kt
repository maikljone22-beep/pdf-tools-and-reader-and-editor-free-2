package com.smartdocs.editor.core.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Audit AN-08 (done-bar / AI undo must not silently undo newer changes) and AN-04 (no file undo during a conversion). */
class UndoGuardsTest {

    // memory steps run on the Main dispatcher (absent on the JVM), so the "later changes" here are file steps too
    private suspend fun change(h: DocHistory, work: File, label: String, newText: String): File {
        val snap = h.copyIn(work); work.writeText(newText)
        h.push(HistoryStep.FileSnapshot(label, snap, null)); return snap
    }

    @Test fun undoThroughRefusesWhenNewerStepsExist() = runBlocking {
        val tmp = Files.createTempDirectory("guard").toFile()
        try {
            val work = File(tmp, "doc.pdf").apply { writeText("v1") }
            val h = DocHistory.open(tmp, "doc")
            h.current = { work to null }
            h.restorer = { src, _ -> src.copyTo(work, overwrite = true) }
            val compress = change(h, work, "compress", "v2-compressed")
            assertEquals(0, h.newerStepsThan(compress))
            for (i in 1..3) change(h, work, "highlight $i", "v3-$i")
            assertEquals(3, h.newerStepsThan(compress))
            // the compress bar's "Undo" must not take the 3 later changes with it
            assertNull(h.undoThrough(compress))
            assertEquals("v3-3", work.readText())
            // the history sheet (explicit choice) still can
            assertEquals(listOf("highlight 3", "highlight 2", "highlight 1", "compress"), h.undoStepsTo(h.undoList().first().id).map { it.label })
            h.undoThrough(compress, allowNewer = true)
            assertEquals("v1", work.readText())
            assertEquals(-1, h.newerStepsThan(File(tmp, "unknown")))
            assertEquals(listOf("compress", "highlight 1"), h.redoStepsTo(h.redoList()[1].id).map { it.label })
            h.dispose()
        } finally { tmp.deleteRecursively() }
        Unit
    }

    @Test fun fileStepsBlockedOnlyDuringConversion() {
        val file = HistoryStep.FileSnapshot("rotate", File("x"), null)
        val mem = HistoryStep.Memory("note", undo = {}, redo = {})
        assertTrue(HistoryGuards.blocksFileSteps(listOf(mem, file), conversionPending = true))
        assertFalse(HistoryGuards.blocksFileSteps(listOf(mem), conversionPending = true))
        assertFalse(HistoryGuards.blocksFileSteps(listOf(file), conversionPending = false))
    }
}
