package com.smartdocs.editor.core.voice

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Joins audio chunks from the same speech provider into one file.
 * - MP3: frames are self-contained, so same-bitrate chunks join by plain byte concatenation; ID3v2 headers of the
 *   following chunks and ID3v1 trailers are dropped so players do not stop at a tag.
 * - WAV (e.g. Groq Orpheus): the PCM `data` of every chunk is appended under one new RIFF header (all chunks of a
 *   provider share sample rate / channels / bits).
 */
object AudioConcat {

    fun isWav(b: ByteArray): Boolean = b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() && b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()

    fun join(files: List<File>, out: File) {
        require(files.isNotEmpty())
        val first = files[0].readBytes()
        if (isWav(first)) joinWav(files, out) else joinMp3(files, out)
    }

    private fun joinMp3(files: List<File>, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.outputStream().buffered().use { o ->
            files.forEachIndexed { i, f ->
                val b = f.readBytes()
                var start = 0
                var end = b.size
                if (i > 0) start = id3v2Size(b)
                if (end - start > 128 && b[end - 128] == 'T'.code.toByte() && b[end - 127] == 'A'.code.toByte() && b[end - 126] == 'G'.code.toByte()) end -= 128
                if (end > start) o.write(b, start, end - start)
            }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    /** Size of a leading ID3v2 tag (0 when absent). */
    private fun id3v2Size(b: ByteArray): Int {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val footer = if ((b[5].toInt() and 0x10) != 0) 10 else 0
        return (10 + size + footer).coerceAtMost(b.size)
    }

    /** Format info + PCM data range of a WAV byte array. */
    class WavInfo(val fmt: ByteArray, val dataStart: Int, val dataLen: Int) {
        val channels: Int get() = ByteBuffer.wrap(fmt, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val sampleRate: Int get() = ByteBuffer.wrap(fmt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val byteRate: Int get() = ByteBuffer.wrap(fmt, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parseWav(b: ByteArray): WavInfo? {
        if (!isWav(b)) return null
        var pos = 12
        var fmt: ByteArray? = null
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var len = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = pos + 8
            if (id == "fmt " && len >= 16 && body + len <= b.size) fmt = b.copyOfRange(body, body + len)
            if (id == "data") {
                // streamed WAVs may carry 0 / 0xFFFFFFFF as size: take the rest of the file
                if (len <= 0 || body + len > b.size) len = b.size - body
                return fmt?.let { WavInfo(it, body, len) }
            }
            if (len < 0) return null
            pos = body + len + (len and 1)
        }
        return null
    }

    private fun joinWav(files: List<File>, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        var fmt: ByteArray? = null
        var total = 0L
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44)) // header placeholder
            files.forEach { f ->
                val b = f.readBytes()
                val info = parseWav(b) ?: return@forEach
                if (fmt == null) fmt = info.fmt
                raf.write(b, info.dataStart, info.dataLen)
                total += info.dataLen
            }
            val f = fmt ?: throw IllegalStateException("no wav data")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt((36 + total).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII)); header.put("fmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16)
            header.put(f, 0, 16)
            header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(total.toInt())
            raf.seek(0); raf.write(header.array())
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    /** Duration of an audio file in ms (WAV from its header, otherwise MediaMetadataRetriever). */
    fun durationMs(f: File): Long {
        if (f.extension.equals("wav", true)) {
            val head = f.inputStream().use { s -> val buf = ByteArray(minOf(f.length(), 4096L).toInt()); s.read(buf); buf }
            val info = parseWav(head)
            if (info != null && info.byteRate > 0) {
                val dataLen = f.length() - info.dataStart
                return dataLen * 1000L / info.byteRate
            }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
    }
}
