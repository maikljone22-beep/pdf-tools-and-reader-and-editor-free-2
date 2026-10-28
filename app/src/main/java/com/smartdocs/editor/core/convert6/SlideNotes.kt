package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Row 103 (PowerPoint add-in "convert speaker notes to notes"): the speaker notes of each slide become a note
 * comment in the top corner of its page (slide i → page i), so they travel with the PDF without changing the slide.
 */
object SlideNotes {
    /** (page, text) of the slides that have notes (blank notes are left out; [count] pages exist). */
    fun plan(notes: List<String?>, count: Int): List<Pair<Int, String>> =
        notes.take(count).mapIndexedNotNull { i, n -> n?.trim()?.takeIf { it.isNotEmpty() }?.let { i to it.take(20_000) } }

    /** Add the notes to [pdf] in place; [author] is the comment's author. Returns how many notes were added. */
    suspend fun apply(pdf: File, notes: List<String?>, author: String): Int = withContext(Dispatchers.IO) {
        val tmp = File(pdf.parentFile, ".${pdf.name}.notes.pdf")
        try {
            val n = PdfOps.loadRaw(pdf, null).use { d ->
                val plan = plan(notes, d.numberOfPages)
                plan.forEach { (pi, text) ->
                    val page = d.getPage(pi)
                    val box = page.cropBox
                    val a = PDAnnotationText().apply {
                        contents = text; titlePopup = author; subject = "Speaker notes"
                        rectangle = PDRectangle(box.lowerLeftX + 8f, box.upperRightY - 30f, 22f, 22f)
                        color = PDColor(floatArrayOf(1f, 0.92f, 0.23f), PDDeviceRGB.INSTANCE)
                        name = PDAnnotationText.NAME_NOTE
                    }
                    page.annotations.add(a)
                }
                if (plan.isNotEmpty()) d.save(tmp)
                plan.size
            }
            if (n > 0 && !tmp.renameTo(pdf)) tmp.copyTo(pdf, overwrite = true)
            n
        } finally { tmp.delete() }
    }
}
