package com.smartdocs.editor.core.files

import com.smartdocs.editor.core.files.SafeRename.Result
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Audit DI-04: Files → Rename must never replace another file. */
class SafeRenameTest {
    private val dir = Files.createTempDirectory("rn").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }

    @Test fun renameOntoExistingIsRefusedAndBothKeepContent() {
        val contract = file("contract.pdf", "CONTRACT"); val draft = file("draft.pdf", "DRAFT")
        val r = SafeRename.rename(draft, "contract")
        assertTrue(r is Result.Exists)
        assertEquals("CONTRACT", contract.readText()); assertEquals("DRAFT", draft.readText())
    }

    @Test fun clashIsCaseInsensitive() {
        file("Contract.pdf", "C"); val draft = file("draft.pdf", "D")
        assertTrue(SafeRename.rename(draft, "contract") is Result.Exists)
        assertEquals("D", draft.readText())
    }

    @Test fun invalidNames() {
        val f = file("a.pdf", "A")
        for (n in listOf("", "  ", "../x", "a/b", "a\\b", "..", "x:y")) {
            val r = SafeRename.rename(f, n)
            assertTrue("'$n' -> $r", r is Result.InvalidName || r is Result.Blank)
        }
        assertEquals("A", f.readText())
    }

    @Test fun normalAndCaseOnlyRename() {
        val f = file("a.pdf", "A")
        val r = SafeRename.rename(f, " b ") as Result.Ok
        assertEquals("b.pdf", r.file.name); assertEquals("A", r.file.readText())
        val r2 = SafeRename.rename(r.file, "B") as Result.Ok
        assertEquals("B.pdf", r2.file.name); assertEquals("A", r2.file.readText())
        assertEquals(listOf("B.pdf"), dir.list()!!.toList())
    }

    @Test fun folderKeepsNoExtension() {
        val d = File(dir, "Old").apply { mkdirs() }
        val r = SafeRename.rename(d, "New") as Result.Ok
        assertEquals("New", r.file.name); assertTrue(r.file.isDirectory)
    }
}
