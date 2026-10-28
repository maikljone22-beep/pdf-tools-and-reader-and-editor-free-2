package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.smartdocs.editor.core.security.SecurityInfo
import java.io.File
import java.io.RandomAccessFile

/**
 * The hub honours a PDF's restrictions (convert audit item 8 / C16): grayscale, compress, flatten and searchable keep
 * the protection on their output and need "modify"; PDF/A cannot be encrypted, so it needs owner access ("save
 * unprotected"); handouts / merges need "assemble"; every export of the content needs "copy". Pure.
 */
object Cv6Security {

    /** Conversions whose output is written with the source's protection when the source is encrypted. */
    val KEEPS_PROTECTION = setOf("pdf_gray", "pdf_compress", "pdf_flatten", "pdf_searchable")

    fun actionFor(convId: String): DocAction = when (convId) {
        "pdf_pdfa" -> DocAction.SAVE_UNPROTECTED
        in KEEPS_PROTECTION -> DocAction.MODIFY
        "pptx_handouts", "any_pdf", "word_pdf", "excel_pdf", "ppt_pdf", "images_pdf", "text_pdf", "md_pdf", "html_pdf", "epub_pdf", "eml_pdf" -> DocAction.ASSEMBLE
        else -> DocAction.COPY
    }

    fun allowed(convId: String, info: SecurityInfo?): Boolean = ReaderRestrictions.check(info, false, actionFor(convId)) == null

    /**
     * True when the PDF holds a digital signature (a /ByteRange entry, streamed search): grayscale / compress / flatten /
     * PDF/A outputs are new files whose signatures no longer validate, and the result sheet says so (convert audit C45).
     */
    fun hasSignature(f: File): Boolean = runCatching {
        val key = "/ByteRange".toByteArray(Charsets.ISO_8859_1)
        f.inputStream().buffered(1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16); var carry = ByteArray(0)
            while (true) {
                val n = s.read(buf); if (n <= 0) break
                val chunk = carry + buf.copyOf(n)
                if (String(chunk, Charsets.ISO_8859_1).contains("/ByteRange")) return@use true
                carry = chunk.copyOfRange(maxOf(0, chunk.size - key.size), chunk.size)
            }
            false
        }
    }.getOrDefault(false)

    /**
     * Cheap test whether a PDF may be encrypted (an /Encrypt entry near its end, or near its start for linearized
     * files), so unprotected files are not parsed twice.
     */
    fun mayBeEncrypted(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            fun has(off: Long, len: Int): Boolean {
                val n = minOf(len.toLong(), raf.length() - off).toInt().coerceAtLeast(0)
                val b = ByteArray(n); raf.seek(off); raf.readFully(b)
                return String(b, Charsets.ISO_8859_1).contains("/Encrypt")
            }
            val len = raf.length()
            if (has(maxOf(0L, len - 256 * 1024), 256 * 1024) || has(0L, 64 * 1024)) return@use true
            // the trailer / xref-stream dictionary that "startxref" points at (big xref streams end far from it)
            val tail = ByteArray(minOf(2048L, len).toInt()).also { raf.seek(len - it.size); raf.readFully(it) }
            val sx = Regex("""startxref\s+(\d+)""").findAll(String(tail, Charsets.ISO_8859_1)).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
            sx != null && sx in 0 until len && has(sx, 16 * 1024)
        }
    }.getOrDefault(true)
}
