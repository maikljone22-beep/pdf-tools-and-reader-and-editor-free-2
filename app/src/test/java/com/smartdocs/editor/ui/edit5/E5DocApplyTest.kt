package com.smartdocs.editor.ui.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class E5DocApplyTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun opened(): E5Doc {
        val src = tmp.newFile("source.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(src) }
        return E5Doc(src).also { it.open(tmp.root); assertNotNull(it.work) }
    }

    @Test fun exportSurvivesDisposalAfterOpeningTheWorkingFile() = runBlocking {
        val doc = opened()
        val out = doc.export(tmp.root, { _, _ ->
            doc.dispose()
            tmp.newFile("exported.pdf")
        }, "links")
        PDDocument.load(out).use { assertEquals(1, it.numberOfPages) }
    }

    @Test fun clearingTemporaryCacheDoesNotRemoveActiveEdits() = runBlocking {
        val src = tmp.newFile("durable-source.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(src) }
        val cache = tmp.newFolder("cache")
        val privateDir = tmp.newFolder("private-work")
        val doc = E5Doc(src, privateDir)
        try {
            doc.open(cache)
            doc.apply(cache) { input, out, _ ->
                PDDocument.load(input).use { it.addPage(PDPage()); it.save(out) }
            }
            cache.deleteRecursively()
            assertTrue(doc.work!!.isFile)
            val out = doc.export(tmp.root, { _, _ -> tmp.newFile("durable-result.pdf") }, "links")
            PDDocument.load(out).use { assertEquals(2, it.numberOfPages) }
        } finally { doc.dispose() }
        assertTrue(privateDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun completingAnEditAfterDisposalDoesNotReviveTheSession() = runBlocking {
        val doc = opened()
        var output: File? = null
        try {
            doc.apply(tmp.root) { _, out, _ ->
                output = out
                doc.dispose()
                PDDocument().use { it.addPage(PDPage()); it.save(out) }
            }
            fail("A disposed session cannot accept an edit")
        } catch (_: CancellationException) { }
        assertNull(doc.work)
        assertFalse(output!!.exists())
        assertFalse(doc.canUndo)
    }

    @Test fun validResultCommitsAndCanBeUndone() = runBlocking {
        val doc = opened()
        try {
            val previous = doc.work
            doc.apply(tmp.root) { src, out, _ ->
                PDDocument.load(src).use { it.addPage(PDPage()); it.save(out) }
            }
            assertNotEquals(previous, doc.work)
            assertEquals(2, doc.pageCount)
            assertTrue(doc.changed)
            assertTrue(doc.canUndo)
            doc.undo()
            assertEquals(previous, doc.work)
            assertEquals(1, doc.pageCount)
            assertFalse(doc.changed)
        } finally { doc.dispose() }
    }

    @Test fun unreadableOutputDoesNotReplaceWorkingCopyOrEnableSave() = runBlocking {
        val doc = opened()
        try {
            val previous = doc.work
            var output: File? = null
            try {
                doc.apply(tmp.root) { _, out, _ -> output = out; out.writeText("not a PDF") }
                fail("Unreadable output must fail")
            } catch (_: java.io.IOException) { }
            assertEquals(previous, doc.work)
            assertTrue(previous!!.exists())
            assertFalse(doc.changed)
            assertFalse(doc.canUndo)
            assertFalse(output!!.exists())
        } finally { doc.dispose() }
    }

    @Test fun cancellationCleansPartialOutputAndPropagates() = runBlocking {
        val doc = opened()
        try {
            val previous = doc.work
            var output: File? = null
            try {
                doc.apply(tmp.root) { _, out, _ ->
                    output = out; out.writeText("partial")
                    throw CancellationException("cancel test")
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(previous, doc.work)
            assertFalse(output!!.exists())
            assertFalse(doc.changed)
            assertFalse(doc.canUndo)
        } finally { doc.dispose() }
    }
}
