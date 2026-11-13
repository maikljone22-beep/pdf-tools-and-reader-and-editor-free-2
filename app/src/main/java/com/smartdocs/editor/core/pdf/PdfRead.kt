package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only access to a document through PdfBox, shared by every reader.
 *
 * Why: PdfBox parses the whole file on each load, and opening the reader started about ten loads of the same file at
 * once (comments, page labels, signature fields, the action scan, the side panes, the PDF/A check, the portfolio
 * check…). On a 500 MB scan that filled the heap within seconds and the app died (found by the torture test, with
 * every one of those loads in the crash dump). Here one parsed document per file version serves all of them, one
 * reader at a time ([ParseGate]), and is closed a few seconds after the last one.
 *
 * Rules for the block of [use]:
 * - it must not change the document (the next reader gets the same one);
 * - it must not call [use] or [ParseGate.run] itself (the gate is not re-entrant);
 * - what it returns must be plain data, not objects of the document.
 * Code that changes and saves a document loads its own with [PdfOps.load] / [PdfLoad.open].
 */
object PdfRead {
    private data class Key(val path: String, val length: Long, val modified: Long, val password: String)
    private class Entry(val key: Key, val doc: PDDocument)

    @Volatile private var entry: Entry? = null     // replaced only inside the gate
    @Volatile private var stale = false
    private val generation = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long the parsed document stays open after its last reader (a variable for the tests). */
    @Volatile internal var idleMs = 4_000L

    /** Whole-file parses done so far (tests and the torture report). */
    val loads = AtomicInteger()

    suspend fun <T> use(file: File, password: String?, label: String, block: (PDDocument) -> T): T {
        val key = Key(file.absolutePath, file.length(), file.lastModified(), password.orEmpty())
        // the parse is measured as its own step, so the report tells the cost of loading from the cost of each reader
        if (!current(key)) ParseGate.run("load") { withContext(Dispatchers.IO) { ensure(key, file, password) } }
        return ParseGate.run(label) {
            withContext(Dispatchers.IO) {
                val doc = ensure(key, file, password)   // a hit, unless another file took the slot in between
                val gen = generation.incrementAndGet()
                try { block(doc) }
                catch (e: Error) { closeLocked(); throw e }   // out of memory, stack overflow: give the document back at once
                finally { closeWhenIdle(gen) }
            }
        }
    }

    /** [use] for code that is not a coroutine. Never call it on the main thread: it waits for the readers before it. */
    fun <T> blocking(file: File, password: String?, label: String, block: (PDDocument) -> T): T = runBlocking { use(file, password, label, block) }

    /** The file was written, or memory is short: the parsed document is dropped and the next reader parses again. */
    fun invalidate() {
        if (entry == null) return
        stale = true
        scope.launch { ParseGate.quiet { if (stale) closeLocked() } }
    }

    private fun current(key: Key): Boolean = !stale && entry?.key == key

    /** Inside the gate only. */
    private fun ensure(key: Key, file: File, password: String?): PDDocument {
        entry?.let { if (!stale && it.key == key) return it.doc }
        closeLocked()
        val doc = PdfOps.loadRaw(file, password)
        loads.incrementAndGet()
        entry = Entry(key, doc)
        return doc
    }

    /** Inside the gate only. */
    private fun closeLocked() {
        stale = false
        val e = entry ?: return
        entry = null
        runCatching { e.doc.close() }
    }

    private fun closeWhenIdle(gen: Long) {
        scope.launch {
            delay(idleMs)
            if (generation.get() == gen) ParseGate.quiet { if (generation.get() == gen) closeLocked() }
        }
    }
}
