package com.smartdocs.editor.core.convert6

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix/pdf39-convert: PowerPoint speaker notes → note comments (row 103). */
class SlideNotesTest {
    @Test fun notesLandOnTheirSlidePages() {
        assertEquals(listOf(0 to "Say hello", 2 to "ملاحظة"), SlideNotes.plan(listOf("Say hello", "  ", "ملاحظة", "beyond"), 3))
        val f = File.createTempFile("notes", ".pdf")
        try {
            PDDocument().use { d -> repeat(3) { d.addPage(PDPage()) }; d.save(f) }
            assertEquals(2, runBlocking { SlideNotes.apply(f, listOf("Say hello", null, "ملاحظة"), "Speaker notes") })
            PDDocument.load(f).use { d ->
                val a0 = d.getPage(0).annotations.single() as PDAnnotationText
                assertEquals("Say hello", a0.contents); assertEquals("Speaker notes", a0.titlePopup)
                assertTrue(d.getPage(1).annotations.isEmpty())
                assertEquals("ملاحظة", d.getPage(2).annotations.single().contents)
            }
            assertEquals(0, runBlocking { SlideNotes.apply(f, listOf(null, null), "x") })
        } finally { f.delete() }
    }
}
