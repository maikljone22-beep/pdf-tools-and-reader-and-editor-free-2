package com.smartdocs.editor.core.sign

import java.io.File
import java.io.RandomAccessFile

/**
 * "View signed version" (Nitro/Acrobat): the revision a signature covers is the first `signedLength` bytes of
 * the file (its byte range ends there), so copying that prefix gives the document exactly as it was signed.
 */
object SigRevisions {

    fun extract(src: File, signedLength: Long, out: File) {
        require(out.canonicalPath != src.canonicalPath) { "extract: out must differ from src" }
        require(signedLength in 1..src.length()) { "signed length $signedLength outside the file" }
        RandomAccessFile(src, "r").use { raf ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var left = signedLength
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n); left -= n
                }
            }
        }
    }
}
