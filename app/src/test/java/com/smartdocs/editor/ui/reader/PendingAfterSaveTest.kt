package com.smartdocs.editor.ui.reader

import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Audit DI-05 / DI-36 (failed or newly added annotations survive a save) and the Stage 2 lifecycle wiring. */
class PendingAfterSaveTest {
    private fun a(id: Long) = Annot(id = id, kind = AnnotKind.NOTE, page = 0)

    @Test fun failedAndNewAnnotationsStayPending() {
        val pending = listOf(a(1), a(2), a(3))
        val current = pending + a(4)                    // a(4) was added while the save was running
        assertEquals(listOf(2L, 4L), stillPendingAfterSave(current, pending, failed = listOf(a(2))).map { it.id })
    }

    @Test fun cleanSaveLeavesOnlyNewOnes() {
        val pending = listOf(a(1), a(2))
        assertEquals(emptyList<Long>(), stillPendingAfterSave(pending, pending, emptyList()).map { it.id })
        assertEquals(listOf(9L), stillPendingAfterSave(pending + a(9), pending, emptyList()).map { it.id })
    }

    /** The reader must outlive its composition (AN-01/AN-03): no `remember` + `DisposableEffect { close() }` any more. */
    @Test fun readerIsHeldByTheBackStackEntry() {
        val src = File("src/main/java/com/smartdocs/editor/ui/reader/ReaderState.kt").readText()
        val fn = src.substringAfter("fun rememberReaderState(").substringBefore("\n}\n")
        assertTrue("rememberReaderState must use a ViewModel", fn.contains("viewModel("))
        assertFalse("closing on dispose drops edits and undo on every navigation", fn.contains("onDispose"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (c in listOf("keyboard", "navigation", "density", "fontScale", "layoutDirection")) assertTrue(c, Regex("""configChanges="[^"]*\b$c\b""").containsMatchIn(manifest))
    }
}
