package com.smartdocs.editor.core.pdf

import com.smartdocs.editor.torture.TorturePdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opening the reader started ten whole-file parses of the same document at once; [PdfRead] must make that one. */
class PdfReadTest {
    @get:Rule val tmp = TemporaryFolder()
    private var idle = 0L

    @Before fun keepIdle() { idle = PdfRead.idleMs }
    @After fun restoreIdle() { PdfRead.idleMs = idle; PdfRead.invalidate() }

    private fun pdf(name: String, pages: Int): File = tmp.newFile(name).also { TorturePdf.textPages(it, pages) }

    @Test fun manyReadersOfOneFileShareOneParse() = runBlocking<Unit> {
        val f = pdf("a.pdf", 5)
        val before = PdfRead.loads.get()
        val active = AtomicInteger(); val mostAtOnce = AtomicInteger()
        val results = (1..10).map { i ->
            async(Dispatchers.Default) {
                PdfRead.use(f, null, "reader$i") { d ->
                    mostAtOnce.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(5)
                    active.decrementAndGet()
                    d.numberOfPages
                }
            }
        }.awaitAll()
        assertEquals(List(10) { 5 }, results)
        assertEquals("ten readers must cost one parse", 1, PdfRead.loads.get() - before)
        assertEquals("readers share one document, so one at a time", 1, mostAtOnce.get())
    }

    @Test fun aChangedFileIsParsedAgain() = runBlocking<Unit> {
        val f = pdf("b.pdf", 3)
        assertEquals(3, PdfRead.use(f, null, "t") { it.numberOfPages })
        val before = PdfRead.loads.get()
        TorturePdf.textPages(f, 7)
        f.setLastModified(f.lastModified() + 5_000)
        assertEquals(7, PdfRead.use(f, null, "t") { it.numberOfPages })
        assertEquals(1, PdfRead.loads.get() - before)
    }

    @Test fun anotherFileTakesTheSlotAndTheFirstIsParsedAgainWhenAskedFor() = runBlocking<Unit> {
        val a = pdf("c.pdf", 2); val b = pdf("d.pdf", 4)
        val before = PdfRead.loads.get()
        assertEquals(2, PdfRead.use(a, null, "t") { it.numberOfPages })
        assertEquals(4, PdfRead.use(b, null, "t") { it.numberOfPages })
        assertEquals(2, PdfRead.use(a, null, "t") { it.numberOfPages })
        assertEquals(3, PdfRead.loads.get() - before)
    }

    @Test fun invalidateDropsTheParsedDocument() = runBlocking<Unit> {
        val f = pdf("e.pdf", 2)
        PdfRead.use(f, null, "t") { it.numberOfPages }
        val before = PdfRead.loads.get()
        PdfRead.invalidate()
        assertEquals(2, PdfRead.use(f, null, "t") { it.numberOfPages })
        assertEquals(1, PdfRead.loads.get() - before)
    }

    @Test fun theDocumentIsClosedSoonAfterItsLastReader() = runBlocking<Unit> {
        PdfRead.idleMs = 40
        val f = pdf("f.pdf", 2)
        PdfRead.use(f, null, "t") { it.numberOfPages }
        val before = PdfRead.loads.get()
        delay(400)
        PdfRead.use(f, null, "t") { it.numberOfPages }
        assertEquals("closed while idle, so this reader parses again", 1, PdfRead.loads.get() - before)
    }

    @Test fun aFailingReaderDoesNotBreakTheNextOne() = runBlocking<Unit> {
        val f = pdf("g.pdf", 2)
        assertTrue(runCatching { PdfRead.use(f, null, "t") { error("reader failed") } }.isFailure)
        assertEquals(2, PdfRead.use(f, null, "t") { it.numberOfPages })
    }

    @Test fun parsesAndReadersAreMeasuredSeparately() = runBlocking<Unit> {
        val f = pdf("h.pdf", 2)
        PdfRead.invalidate()
        PdfRead.use(f, null, "my-reader") { it.numberOfPages }
        val labels = ParseGate.stats().map { it.label }
        assertTrue(labels.contains("load")); assertTrue(labels.contains("my-reader"))
    }
}
