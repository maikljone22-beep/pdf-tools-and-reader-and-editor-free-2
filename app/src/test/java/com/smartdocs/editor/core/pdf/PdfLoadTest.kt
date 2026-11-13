package com.smartdocs.editor.core.pdf

import com.smartdocs.editor.torture.TorturePdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfLoadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun bigDocumentsKeepTheirStreamsOffTheHeap() {
        val mb = 1024L * 1024
        val small = PdfLoad.memoryFor(4 * mb)
        assertTrue(small.useMainMemory()); assertFalse(small.useTempFile())
        val medium = PdfLoad.memoryFor(40 * mb)
        assertTrue(medium.useMainMemory()); assertTrue(medium.useTempFile())
        assertEquals(PdfLoad.IN_MEMORY, medium.maxMainMemoryBytes)
        val big = PdfLoad.memoryFor(300 * mb)
        assertFalse("a 300 MB scan must not be copied onto the heap", big.useMainMemory()); assertTrue(big.useTempFile())
    }

    @Test fun everyWayOfOpeningGivesTheSameDocument() {
        val f = tmp.newFile("a.pdf").also { TorturePdf.textPages(it, 4) }
        PdfLoad.open(f).use { assertEquals(4, it.numberOfPages) }
        PdfLoad.open(f, null).use { assertEquals(4, it.numberOfPages) }
        PdfLoad.open(f.readBytes()).use { assertEquals(4, it.numberOfPages) }
        PdfLoad.open(f.readBytes(), "").use { assertEquals(4, it.numberOfPages) }
        f.inputStream().use { s -> PdfLoad.open(s).use { assertEquals(4, it.numberOfPages) } }
        PdfOps.loadRaw(f, null).use { assertEquals(4, it.numberOfPages) }
    }

    @Test fun aDocumentReadThroughATempFileIsComplete() {
        // the temp-file path is what a large document takes: prove it parses the same, without needing a large file
        val f = tmp.newFile("b.pdf").also { TorturePdf.textPages(it, 30) }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(f, "", com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { d ->
            assertEquals(30, d.numberOfPages)
            assertTrue(d.getPage(29).contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }.contains("(Page 30 of 30)"))
        }
    }

    @Test fun staleScratchFilesAreDeletedAndFreshOnesKept() {
        val dir = tmp.newFolder("cache")
        val old = File(dir, "PDFBox123.tmp").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val fresh = File(dir, "PDFBox456.tmp").apply { writeText("x") }
        val other = File(dir, "thumb.tmp").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        assertEquals(1, PdfLoad.deleteStaleTempFiles(dir))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(other.exists())
    }
}
