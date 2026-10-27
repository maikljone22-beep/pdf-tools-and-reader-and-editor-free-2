package com.smartdocs.editor.core.connected

import java.io.File
import java.io.RandomAccessFile

/**
 * Finds the connected-document id in a PDF without opening it (works on AES-256 protected files: the trailer
 * entry is a PDF *name*, and names are never encrypted).
 *
 * Markers written by [ConnectedStamp]:
 *   trailer   /SDConnId /<id>   and  /SDConnP /1 (protected)
 *   /Info     /SDConnId (<id>)
 *   XMP       <sdc:DocumentId><id></sdc:DocumentId>
 * Ids are 12 lower-case letters/digits.
 */
object ConnMarker {
    data class Mark(val docId: String, val protected: Boolean, val where: String)

    const val TRAILER_KEY = "SDConnId"
    const val TRAILER_PROT = "SDConnP"
    const val INFO_KEY = "SDConnId"
    const val XMP_NS = "http://ns.smartdocs.app/connected/1.0/"
    private val ID = Regex("^[a-z0-9]{12}$")
    private val TRAILER = Regex("""/SDConnId\s*/([a-z0-9]{12})""")
    private val TRAILER_P = Regex("""/SDConnP\s*/1""")
    private val INFO = Regex("""/SDConnId\s*\(([a-z0-9]{12})\)""")
    private val XMP = Regex("""<sdc:DocumentId>\s*([a-z0-9]{12})\s*</sdc:DocumentId>""")
    private val XMP_ATTR = Regex("""sdc:DocumentId\s*=\s*"([a-z0-9]{12})"""")

    fun isId(s: String) = ID.matches(s)

    /** Looks at text decoded as ISO-8859-1 (byte-preserving). Trailer marker wins, then /Info, then XMP. */
    fun find(text: String): Mark? {
        val encrypted = text.contains("/Encrypt")
        TRAILER.findAll(text).lastOrNull()?.let { m ->
            return Mark(m.groupValues[1], TRAILER_P.containsMatchIn(text) || encrypted, "trailer")
        }
        INFO.findAll(text).lastOrNull()?.let { return Mark(it.groupValues[1], encrypted, "info") }
        (XMP.findAll(text).lastOrNull() ?: XMP_ATTR.findAll(text).lastOrNull())?.let { return Mark(it.groupValues[1], encrypted, "xmp") }
        return null
    }

    fun find(bytes: ByteArray): Mark? = find(String(bytes, Charsets.ISO_8859_1))

    /** Cache key: the same path with the same size and modification time is the same file for the scan. */
    private data class Key(val path: String, val size: Long, val mtime: Long)
    private class Entry(val mark: Mark?)

    private const val CACHE_SIZE = 64
    private val cache = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > CACHE_SIZE
    }

    /** Number of real file scans (cache misses); for tests. */
    internal val scans = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Marker of [file], cached per (path, size, mtime) (audit RD-03: the slow path reads up to 64 MB, which used to
     * happen on every open, Recents included). Blocking I/O: call it off the main thread; safe from any thread.
     * A miss scans outside the lock, so two first opens of one file may both scan (same result).
     */
    fun find(file: File): Mark? {
        if (!file.isFile) return null
        val key = Key(file.absolutePath, file.length(), file.lastModified())
        synchronized(cache) { cache[key] }?.let { return it.mark }
        val mark = scan(file)
        // a file that changed while it was read is not cached under its old (size, mtime)
        if (file.length() == key.size && file.lastModified() == key.mtime) synchronized(cache) { cache[key] = Entry(mark) }
        return mark
    }

    /** Forget cached results for [file] (e.g. after writing a marker into it). */
    fun invalidate(file: File) { val p = file.absolutePath; synchronized(cache) { cache.keys.removeAll { it.path == p } } }

    /**
     * Fast path: the last 256 KB (trailers, incremental updates). Slow path for files up to 64 MB: a streaming scan
     * for the /Info and XMP markers (uncompressed metadata only).
     */
    private fun scan(file: File): Mark? {
        scans.incrementAndGet()
        val len = file.length()
        RandomAccessFile(file, "r").use { raf ->
            val tailLen = minOf(len, 256L * 1024).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen); raf.readFully(tail)
            find(tail)?.let { return it }
            if (len > 64L * 1024 * 1024) return null
            val enc = String(tail, Charsets.ISO_8859_1).contains("/Encrypt")
            val buf = ByteArray(1024 * 1024); var pos = 0L; var carry = ""
            raf.seek(0)
            while (pos < len) {
                val n = raf.read(buf); if (n <= 0) break
                val chunk = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                find(chunk)?.let { return it.copy(protected = it.protected || enc) }
                carry = chunk.takeLast(200); pos += n
            }
        }
        return null
    }
}
