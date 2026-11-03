package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Audit DI-05: an annotation that fails to write is reported back, not silently dropped. */
class AnnotationCommitTest {
    private fun note(page: Int, text: String) = Annot(kind = AnnotKind.NOTE, page = page, text = text)

    @Test fun failedAnnotationIsReportedAndRolledBack() {
        PDDocument().use { d ->
            d.addPage(PDPage()); d.addPage(PDPage())
            val good = note(0, "ok"); val bad = note(0, "boom"); val other = note(1, "ok2"); val gone = note(7, "no page")
            val r = AnnotationWriter.writeAll(d, listOf(good, bad, other, gone)) { page, a ->
                page.annotations.add(PDAnnotationText().apply { contents = a.text })
                if (a.text == "boom") error("font problem")
            }
            assertEquals(2, r.written)
            assertEquals(setOf(bad, gone), r.failed.toSet())
            assertTrue(r.errors.any { it.contains("font problem") })
            // the half-written "boom" annotation was removed again
            assertEquals(listOf("ok"), d.getPage(0).annotations.filter { it.subtype != "Popup" }.map { it.contents })
            assertEquals(listOf("ok2"), d.getPage(1).annotations.filter { it.subtype != "Popup" }.map { it.contents })
            try { r.orThrow(); fail("partial failure must throw for one-shot callers") } catch (_: java.io.IOException) {}
        }
    }

    @Test fun allWrittenIsClean() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            val r = AnnotationWriter.writeAll(d, listOf(note(0, "a"), note(0, "b"))) { page, a -> page.annotations.add(PDAnnotationText().apply { contents = a.text }) }
            assertEquals(2, r.written); assertTrue(r.failed.isEmpty()); r.orThrow()
        }
    }

    /** Review: big files get annotation changes appended; the result must reopen with the new note and every page. */
    @Test fun appendedSaveKeepsPagesAndAddsNote() {
        val dir = kotlin.io.path.createTempDirectory("inc").toFile()
        val src = java.io.File(dir, "src.pdf"); val out = java.io.File(dir, "out.pdf")
        PDDocument().use { d -> repeat(3) { d.addPage(PDPage()) }; d.save(src) }
        PDDocument.load(src).use { d ->
            d.getPage(1).annotations.add(PDAnnotationText().apply { contents = "appended" })
            SignedSave.saveIncremental(d, listOf(d.getPage(1)), out)
        }
        assertTrue(out.length() > src.length())
        // the original bytes are untouched at the start of the new file (an append, not a rewrite)
        assertTrue(out.readBytes().copyOf(src.length().toInt()).contentEquals(src.readBytes()))
        PDDocument.load(out).use { d ->
            assertEquals(3, d.numberOfPages)
            assertEquals(listOf("appended"), d.getPage(1).annotations.map { it.contents })
        }
        dir.deleteRecursively()
    }
}
