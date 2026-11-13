package com.smartdocs.editor.core.util

import java.io.File
import java.io.IOException

/** A file is over the size an in-memory editor or reader can take; the UI shows a message instead of running out of memory. */
class FileTooLargeException(val size: Long, val max: Long) : IOException("file too large: $size bytes (limit $max)")

/** Size limits (bytes) of the readers and editors that hold a whole file in memory. */
object FileLimits {
    /** Plain-text editor: the text is decoded to a String and held again by the text field. */
    const val TEXT_EDIT = 40L * 1024 * 1024
    /** CSV/TSV sheet: every cell becomes an object. */
    const val CSV_EDIT = 30L * 1024 * 1024
    /** Read-only viewers that decode a whole file (DXF, contacts, calendar). */
    const val VIEW_TEXT = 40L * 1024 * 1024

    fun require(f: File, max: Long) { val n = f.length(); if (n > max) throw FileTooLargeException(n, max) }
}
