package com.smartdocs.editor.ui.tools5

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedInputsStateTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun restoredShareKeepsEveryFileAndImportFailureCount() {
        val a = tmp.newFile("a.pdf"); val b = tmp.newFile("b.pdf")
        val restored = SharePayloadSaver.restore(listOf(null, "note", null, "subject", "failed:2", a.absolutePath, b.absolutePath))!!.value!!
        assertEquals(listOf(a, b), restored.files)
        assertEquals(2, restored.failedFiles)
        assertEquals("note", restored.text)
    }
    @Test fun oldSavedSharesRemainReadable() {
        val a = tmp.newFile("old.pdf")
        val restored = SharePayloadSaver.restore(listOf(null, null, null, "subject", a.absolutePath))!!.value!!
        assertEquals(listOf(a), restored.files)
        assertEquals(0, restored.failedFiles)
    }
    @Test fun savedMultiSelectionRestoresOnlyExistingFiles() {
        val a = tmp.newFile("present.pdf")
        val removed = tmp.newFile("removed.pdf"); removed.delete()
        assertEquals(listOf(a), SharedFileListSaver.restore(listOf(a.absolutePath, removed.absolutePath)))
    }
}
