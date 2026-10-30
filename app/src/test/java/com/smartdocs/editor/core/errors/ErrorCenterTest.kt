package com.smartdocs.editor.core.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Error centre store: repeats are counted, sent state, scrubbing of personal data. */
class ErrorCenterTest {
    private fun setup(): File {
        val dir = Files.createTempDirectory("ec").toFile()
        val f = ErrorCenter::class.java.getDeclaredField("appDir"); f.isAccessible = true; f.set(ErrorCenter, dir)
        ErrorCenter.clear()
        return dir
    }

    private fun boom(): Throwable = runCatching { error("bad value 12345678 in /storage/emulated/0/Docs/secret.pdf for ali@example.com") }.exceptionOrNull()!!

    @Test fun repeatsAreCountedAndScrubbed() {
        setup()
        repeat(3) { ErrorCenter.record(boom(), "tool") }
        val all = ErrorCenter.all()
        assertEquals(1, all.size)
        assertEquals(3, all[0].count)
        val t = all[0].title
        assertFalse(t.contains("secret.pdf")); assertFalse(t.contains("ali@example.com")); assertFalse(t.contains("12345678"))
        assertTrue(t.contains("<path>") && t.contains("<email>") && t.contains("<number>"))
    }

    @Test fun sentStateAndRepeatAfterSend() {
        setup()
        ErrorCenter.record(boom(), "reader")
        val id = ErrorCenter.all().single().id
        assertEquals(1, ErrorCenter.unsentCount())
        ErrorCenter.markSent(listOf(id))
        assertEquals(0, ErrorCenter.unsentCount())
        ErrorCenter.record(boom(), "reader")
        assertEquals("a repeat after sending is sent again", 1, ErrorCenter.unsentCount())
    }

    @Test fun differentAreasAreDifferentErrors() {
        setup()
        ErrorCenter.record(boom(), "tool"); ErrorCenter.record(boom(), "reader"); ErrorCenter.recordMessage("Cannot open file", "reader")
        assertEquals(3, ErrorCenter.all().size)
        assertTrue(ErrorCenter.report(ErrorCenter.all()).contains("Cannot open file"))
    }
}
