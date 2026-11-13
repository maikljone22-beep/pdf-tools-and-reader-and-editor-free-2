package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream

/**
 * Every PdfBox load of the app goes through here (or through [PdfOps.loadRaw], which uses the same policy).
 *
 * PdfBox 2 parses the whole file on load: every object, and every stream is copied into its scratch storage. A plain
 * `PDDocument.load(file)` keeps that storage on the Java heap, so a 300 MB scan needs 300 MB of heap for one load,
 * and the app died with OutOfMemoryError. [memoryFor] keeps small files in memory (fast) and sends the streams of
 * bigger ones to a temp file in the cache folder.
 *
 * These documents are private to the caller (it may change and save them). Code that only reads should use
 * [PdfRead.use], which shares one parsed document between readers.
 */
object PdfLoad {
    /** Up to this size a document's streams stay on the heap. */
    const val IN_MEMORY = 16L * 1024 * 1024
    /** Up to this size the first [IN_MEMORY] bytes of streams stay on the heap and the rest go to a temp file; above it, all go to the file. */
    const val MIXED = 64L * 1024 * 1024

    /** Where PdfBox keeps the streams of a document of [bytes] bytes while it is open. */
    fun memoryFor(bytes: Long): MemoryUsageSetting = when {
        bytes <= IN_MEMORY -> MemoryUsageSetting.setupMainMemoryOnly()
        bytes <= MIXED -> MemoryUsageSetting.setupMixed(IN_MEMORY)
        else -> MemoryUsageSetting.setupTempFileOnly()
    }

    fun open(file: File): PDDocument = PDDocument.load(file, "", memoryFor(file.length()))
    fun open(file: File, password: String?): PDDocument = PDDocument.load(file, password ?: "", memoryFor(file.length()))

    fun open(bytes: ByteArray): PDDocument = open(bytes, null)
    fun open(bytes: ByteArray, password: String?): PDDocument = PDDocument.load(bytes, password ?: "", null, null, memoryFor(bytes.size.toLong()))

    /** A stream of unknown length: the heap share is capped, the rest goes to a temp file. */
    fun open(input: InputStream): PDDocument = open(input, null)
    fun open(input: InputStream, password: String?): PDDocument = PDDocument.load(input, password ?: "", MemoryUsageSetting.setupMixed(IN_MEMORY))

    /**
     * PdfBox's temp files ("PDFBox*.tmp" in the cache folder) are deleted when their document closes. A process that
     * was killed leaves them behind (hundreds of MB for a big document). Called once at start, off the main thread;
     * files younger than a minute are left alone. Returns the number deleted.
     */
    fun deleteStaleTempFiles(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith("PDFBox") && f.name.endsWith(".tmp") && f.lastModified() < now - 60_000 }
            ?.forEach { if (it.delete()) n++ }
        return n
    }
}
