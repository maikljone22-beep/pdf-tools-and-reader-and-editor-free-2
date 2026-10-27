package com.smartdocs.editor.core.openall

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text with the encoding it was read with. [bomLength] bytes at the start of the file are the byte-order mark. */
class Decoded(val text: String, val charset: Charset, val bomLength: Int) {
    val charsetName: String get() = charset.name()
}

/**
 * Encoding detection for text files (open-all): BOMs, UTF-16 without BOM (NUL pattern), strict UTF-8, and for
 * single-byte files Windows-1256 (Arabic) vs Windows-1252 (Western). Pure JVM.
 */
object TextDecode {

    private fun cs(name: String, fallback: Charset = Charsets.ISO_8859_1): Charset = runCatching { Charset.forName(name) }.getOrDefault(fallback)
    val WIN1256: Charset by lazy { cs("windows-1256") }
    val WIN1252: Charset by lazy { cs("windows-1252") }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** UTF-16 without BOM: NULs at one parity only (ASCII spaces / digits in UTF-16). Returns LE, BE or null. */
    fun utf16Guess(b: ByteArray): Charset? {
        val n = minOf(b.size, 8192) and 1.inv()
        if (n < 8) return null
        // ASCII in UTF-16 has NULs at one parity; Arabic / Cyrillic / Greek letters share one high byte (0x06, 0x04…),
        // so the high-byte parity is made of very few distinct values. Single-byte text never contains NUL.
        var even = 0; var odd = 0
        val evenHist = IntArray(256); val oddHist = IntArray(256)
        var i = 0
        while (i < n) {
            val e = b[i].toInt() and 0xFF; val o = b[i + 1].toInt() and 0xFF
            if (e == 0) even++; if (o == 0) odd++
            evenHist[e]++; oddHist[o]++
            i += 2
        }
        val pairs = n / 2
        fun top2(h: IntArray) = h.sortedArrayDescending().let { it[0] + it[1] }
        return when {
            odd > 0 && even * 20 <= odd && top2(oddHist) * 100 >= pairs * 85 -> Charsets.UTF_16LE
            even > 0 && odd * 20 <= even && top2(evenHist) * 100 >= pairs * 85 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** True when [b] (a file head) looks like text in some encoding. Empty = text. */
    fun looksText(b: ByteArray): Boolean {
        if (b.isEmpty()) return true
        if (bom(b).second > 0 || utf16Guess(b) != null) return true
        var ctrl = 0
        val n = minOf(b.size, 8192)
        for (i in 0 until n) {
            val c = u(b, i)
            if (c == 0) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) ctrl++
        }
        return ctrl * 50 <= n
    }

    private fun bom(b: ByteArray): Pair<Charset?, Int> = when {
        b.size >= 3 && u(b, 0) == 0xEF && u(b, 1) == 0xBB && u(b, 2) == 0xBF -> Charsets.UTF_8 to 3
        b.size >= 2 && u(b, 0) == 0xFF && u(b, 1) == 0xFE -> Charsets.UTF_16LE to 2
        b.size >= 2 && u(b, 0) == 0xFE && u(b, 1) == 0xFF -> Charsets.UTF_16BE to 2
        else -> null to 0
    }

    /** Strict UTF-8 check; a sequence cut at the end of a head buffer ([truncated]) is accepted. */
    fun isUtf8(b: ByteArray, off: Int = 0, truncated: Boolean = false): Boolean {
        fun ok(len: Int) = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, len)); true
        }.getOrDefault(false)
        val len = b.size - off
        if (ok(len)) return true
        if (truncated) for (cut in 1..3) if (len - cut > 0 && ok(len - cut)) return true
        return false
    }

    /**
     * Single-byte Arabic vs Western: Arabic words are runs of bytes ≥ 0x80 in Windows-1256, while Western accented
     * letters stand alone between ASCII letters in Windows-1252.
     */
    fun singleByteGuess(b: ByteArray, off: Int = 0): Charset {
        var high = 0; var inRun = 0
        val n = minOf(b.size, 65536)
        for (i in off until n) {
            if (u(b, i) < 0x80) continue
            high++
            val prev = i > off && u(b, i - 1) >= 0x80
            val next = i + 1 < n && u(b, i + 1) >= 0x80
            if (prev || next) inRun++
        }
        if (high == 0) return WIN1252
        return if (inRun * 2 >= high) WIN1256 else WIN1252
    }

    /** Detects the charset of [b] (whole file or its head when [truncated]). */
    fun detect(b: ByteArray, truncated: Boolean = false): Pair<Charset, Int> {
        val (c, len) = bom(b)
        if (c != null) return c to len
        utf16Guess(b)?.let { return it to 0 }
        if (isUtf8(b, 0, truncated)) return Charsets.UTF_8 to 0
        return singleByteGuess(b) to 0
    }

    fun decode(b: ByteArray, truncated: Boolean = true): Decoded {
        val (c, bomLen) = detect(b, truncated)
        return Decoded(String(b, bomLen, b.size - bomLen, c), c, bomLen)
    }

    /** Charset + BOM length of a file from its first 64 KB. */
    fun detectFile(f: File): Pair<Charset, Int> {
        val head = FileSniffer.readHead(f, 65536)
        return detect(head, truncated = f.length() > head.size)
    }

    /** Whole file decoded (for small files; callers cap the size). */
    fun readFile(f: File): Decoded = decode(f.readBytes(), truncated = false)
}
