package com.smartdocs.editor.core.pdf

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One whole-file PdfBox parse at a time, process-wide. Every background read after a document opens (initial view,
 * page frames, existing annotations, action scan, signature check) parses the whole file into the Java heap; run
 * together on a large file they exhausted it and the next allocation anywhere (a touch event) crashed the app.
 */
object ParseGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
