package com.smartdocs.editor.core.util

import java.io.File

/**
 * Memory-saving mode (user request): on for documents over [BIG_FILE] bytes or on a device with a small heap. Pages
 * render a little less sharp at high zoom, fewer rendered pages are kept, and only work needed for reading runs at open.
 */
object MemoryMode {
    const val BIG_FILE = 200L * 1024 * 1024
    /** Set by the reader for the document it opened. */
    @Volatile var low = false

    /** Pure: the mode a document gets. [heapMax] = Runtime.maxMemory(). */
    fun lowFor(fileBytes: Long, heapMax: Long): Boolean = fileBytes > BIG_FILE || heapMax < 192L * 1024 * 1024

    fun lowFor(f: File): Boolean = lowFor(f.length(), Runtime.getRuntime().maxMemory())
}
