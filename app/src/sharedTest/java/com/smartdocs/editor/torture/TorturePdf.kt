package com.smartdocs.editor.torture

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Writes the heavy PDFs of the torture set: a very long text document and a scanned document of hundreds of MB.
 * Pure JVM (shared by the unit tests, which check the files parse, and the emulator tests, which open them in the app).
 * The files are generated, never stored in git, and deterministic.
 */
object TorturePdf {

    /** Minimal PDF writer: tracks byte offsets for the cross-reference table. Objects must be written in order from 1. */
    private class PdfOut(private val os: OutputStream) {
        private var pos = 0L
        private val offsets = ArrayList<Long>()

        fun raw(b: ByteArray) { os.write(b); pos += b.size }
        fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

        fun obj(body: String) { offsets.add(pos); raw("${offsets.size} 0 obj\n$body\nendobj\n") }

        fun streamObj(dict: String, data: ByteArray) {
            offsets.add(pos)
            raw("${offsets.size} 0 obj\n<< $dict /Length ${data.size} >>\nstream\n"); raw(data); raw("\nendstream\nendobj\n")
        }

        fun finish(root: Int) {
            val xref = pos
            raw("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            // Locale.US: under an Arabic locale String.format prints Arabic-Indic digits, which would corrupt the table
            offsets.forEach { raw(String.format(Locale.US, "%010d 00000 n \n", it)) }
            raw("trailer\n<< /Size ${offsets.size + 1} /Root $root 0 R >>\nstartxref\n$xref\n%%EOF\n")
            os.flush()
        }
    }

    /** A text PDF of [pages] A4 pages, 40 text lines and 30 rules each (a long report or book). */
    fun textPages(out: File, pages: Int) {
        BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
            val w = PdfOut(os)
            w.raw("%PDF-1.5\n")
            w.obj("<< /Type /Catalog /Pages 2 0 R >>")                                                                            // 1
            w.obj("<< /Type /Pages /Count $pages /Kids [" + (0 until pages).joinToString(" ") { "${4 + 2 * it} 0 R" } + "] >>")   // 2
            w.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")                                                      // 3
            for (p in 0 until pages) {
                w.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * p} 0 R >>")
                val sb = StringBuilder("BT /F1 22 Tf 56 790 Td (Page ${p + 1} of $pages) Tj ET\n")
                for (i in 0 until 40) sb.append("BT /F1 10 Tf 56 ${760 - i * 17} Td (Line ${i + 1}: the quick brown fox jumps over the lazy dog $p) Tj ET\n")
                for (i in 0 until 30) sb.append("0.4 w 56 ${775 - i * 24} m 540 ${775 - i * 24} l S\n")
                w.streamObj("", sb.toString().toByteArray(Charsets.ISO_8859_1))
            }
            w.finish(1)
        }
    }

    /**
     * A scanned-document PDF: every A4 page is one full-page JPEG ([jpeg], [width] x [height] px), and every page stores
     * its own copy, so the file really reaches [targetBytes]. Returns the page count.
     */
    fun scan(out: File, jpeg: ByteArray, width: Int, height: Int, targetBytes: Long): Int {
        val pages = (targetBytes / jpeg.size + 1).toInt().coerceIn(2, 5000)
        BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
            val w = PdfOut(os)
            w.raw("%PDF-1.5\n")
            w.obj("<< /Type /Catalog /Pages 2 0 R >>")                                                                            // 1
            w.obj("<< /Type /Pages /Count $pages /Kids [" + (0 until pages).joinToString(" ") { "${3 + 3 * it} 0 R" } + "] >>")   // 2
            for (p in 0 until pages) {
                w.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im0 ${5 + 3 * p} 0 R >> >> /Contents ${4 + 3 * p} 0 R >>")
                w.streamObj("", "q 595 0 0 842 0 0 cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1))
                w.streamObj("/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg)
            }
            w.finish(1)
        }
        return pages
    }
}
