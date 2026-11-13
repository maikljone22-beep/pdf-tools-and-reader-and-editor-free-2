package com.smartdocs.editor.core.util

import java.io.File

/**
 * Memory-saving mode (user request): on for documents over [BIG_FILE] bytes or on a device with a small heap. Pages
 * render a little less sharp at high zoom, fewer rendered pages are kept, and only work needed for reading runs at open.
 */
object MemoryMode {
    const val BIG_FILE = 200L * 1024 * 1024
    /** From this size a document already strains a tablet heap (PdfBox parses it into Java objects), so it opens in memory-saving mode. */
    const val LARGE_FILE = 80L * 1024 * 1024
    /** Set by the reader for the document it opened. */
    @Volatile var low = false

    /** Pure: the mode a document gets. [heapMax] = Runtime.maxMemory(). */
    fun lowFor(fileBytes: Long, heapMax: Long): Boolean = fileBytes > LARGE_FILE || heapMax < 192L * 1024 * 1024

    fun lowFor(f: File): Boolean = lowFor(f.length(), Runtime.getRuntime().maxMemory())
}
