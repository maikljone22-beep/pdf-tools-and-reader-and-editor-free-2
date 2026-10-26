package com.smartdocs.editor.core.view5

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Audit UX-02: "save in the same file" keeps the previous version as a restorable trash entry. */
class TrashCopyTest {
    private val root = Files.createTempDirectory("tc").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun copyLeavesOriginalAndCanBeRestored() {
        val docs = File(root, "Documents").apply { mkdirs() }
        val doc = File(docs, "report.pdf").apply { writeText("v1") }
        val trash = Trash(File(root, "trash"))
        assertNotNull(trash.copyToTrash(doc))
        assertEquals("v1", doc.readText())
        doc.writeText("v2-compressed")                     // the tool result replaces the document
        assertEquals(1, trash.list().size)
        val back = trash.restore(trash.list().single())!!
        assertEquals("report (2).pdf", back.name); assertEquals("v1", back.readText())
        assertEquals("v2-compressed", doc.readText())
    }

    @Test fun folderOrMissingIsRefused() {
        val trash = Trash(File(root, "trash"))
        assertNull(trash.copyToTrash(File(root, "missing.pdf")))
        assertNull(trash.copyToTrash(root))
        assertTrue(trash.list().isEmpty())
    }
}
