package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** FINAL_CHECK_2 Low: "reset to the original size" undoes one or more resizes. */
class ResizeResetTest {
    @Test fun resizeTwiceThenResetRestoresTheBoxes() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val src = File(dir, "a.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle(300f, 400f))); d.save(src) }
        val r1 = File(dir, "r1.pdf"); val r2 = File(dir, "r2.pdf"); val back = File(dir, "b.pdf")
        PageBoxOps.resize(src, r1, null, listOf(0), 595.28f, 841.89f)
        PageBoxOps.resize(r1, r2, null, listOf(0), 612f, 1008f, PageBoxOps.ResizeMode.STRETCH)
        assertEquals(listOf(0), PageBoxOps.resizedPages(r2, null))
        PageBoxOps.resetSize(r2, back, null, PageBoxOps.resizedPages(r2, null))
        PDDocument.load(back).use { d ->
            val b = d.getPage(0).mediaBox
            assertEquals(300f, b.width, 0.01f); assertEquals(400f, b.height, 0.01f)
        }
        assertEquals(emptyList<Int>(), PageBoxOps.resizedPages(back, null))
        dir.deleteRecursively(); Unit
    }
}
