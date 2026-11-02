package com.smartdocs.editor.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AnnotRecoverTest {
    @Test fun aBackupLeftByADeadSessionIsPutBack() {
        val dir = Files.createTempDirectory("annrec").toFile()
        val f = java.io.File(dir, "doc.pdf").apply { writeText("without the annotation") }
        AnnotEditor.backupOf(f).writeText("with the annotation")
        assertTrue(AnnotEditor.recoverInterrupted(f))
        assertEquals("with the annotation", f.readText())
        assertFalse(AnnotEditor.backupOf(f).exists())
        assertFalse(AnnotEditor.recoverInterrupted(f))          // nothing left to recover
        dir.deleteRecursively()
    }

    @Test fun anEmptyBackupIsIgnored() {
        val dir = Files.createTempDirectory("annrec").toFile()
        val f = java.io.File(dir, "doc.pdf").apply { writeText("current") }
        AnnotEditor.backupOf(f).writeText("")
        assertFalse(AnnotEditor.recoverInterrupted(f))
        assertEquals("current", f.readText())
        dir.deleteRecursively()
    }
}
