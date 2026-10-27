package com.smartdocs.editor.core.openall

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** One entry of an archive. [path] is the normalized, safe path ([ArchiveReader.safePath]); [index] is its position. */
class ArcEntry(val index: Int, val path: String, val size: Long, val packed: Long, val isDir: Boolean, val modified: Long, val unsafe: Boolean = false) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** Refused by a guard (zip bomb, too many entries, too deep nesting, too big). [reason] is a stable code. */
class ArchiveLimitException(val reason: String) : IOException(reason)

/** Limits applied to every archive (zip-bomb guards). */
class ArcLimits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    /** Uncompressed / compressed above this (for entries over 1 MB) is treated as a bomb. */
    val maxRatio: Long = 200,
    val maxNesting: Int = 3,
)

/**
 * Lists ZIP / 7z / TAR / TAR.GZ / TAR.BZ2 / TAR.XZ / GZ / BZ2 / XZ archives and extracts single entries (open-all).
 * Entry names are normalized and never used as output paths (zip-slip safe); extraction counts real bytes against
 * the limits (zip-bomb safe). Pure JVM (commons-compress, Apache 2.0; xz-java, 0BSD).
 */
object ArchiveReader {

    enum class Format { ZIP, SEVENZ, TAR, TGZ, TBZ, TXZ, GZ, BZ2, XZ }

    /**
     * Normalizes an entry name: backslashes → '/', drops "." segments, leading '/' and drive letters. Returns null when
     * the name climbs out of the archive root ("..") — such entries are listed as unsafe and cannot be opened.
     */
    fun safePath(raw: String): String? {
        val s = raw.replace('\\', '/').replace(Regex("^[A-Za-z]:"), "")
        val parts = ArrayList<String>()
        for (p in s.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> return null
                else -> parts += p
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/") + if (s.endsWith("/")) "/" else ""
    }

    fun formatOf(f: File, type: FType): Format? {
        val n = f.name.lowercase()
        return when (type) {
            FType.ZIP, FType.CBZ, FType.EPUB, FType.IWORK -> Format.ZIP
            FType.SEVENZ, FType.CB7 -> Format.SEVENZ
            FType.TAR -> Format.TAR
            FType.GZIP -> if (n.endsWith(".tgz") || n.endsWith(".tar.gz") || innerIsTar(f, Format.GZ)) Format.TGZ else Format.GZ
            FType.BZIP2 -> if (n.endsWith(".tbz") || n.endsWith(".tbz2") || n.endsWith(".tar.bz2") || innerIsTar(f, Format.BZ2)) Format.TBZ else Format.BZ2
            FType.XZ -> if (n.endsWith(".txz") || n.endsWith(".tar.xz") || innerIsTar(f, Format.XZ)) Format.TXZ else Format.XZ
            else -> null
        }
    }

    private fun decompressor(f: File, fmt: Format): InputStream {
        val raw = f.inputStream().buffered()
        return try {
            when (fmt) {
                Format.GZ, Format.TGZ -> GZIPInputStream(raw, 65536)
                Format.BZ2, Format.TBZ -> BZip2CompressorInputStream(raw, true)
                Format.XZ, Format.TXZ -> XZCompressorInputStream(raw, true)
                else -> raw
            }
        } catch (e: Exception) { raw.close(); throw e }
    }

    private fun innerIsTar(f: File, fmt: Format): Boolean = runCatching {
        decompressor(f, fmt).use { s ->
            val b = ByteArray(512); var off = 0
            while (off < 512) { val r = s.read(b, off, 512 - off); if (r <= 0) break; off += r }
            off == 512 && String(b, 257, 5, Charsets.ISO_8859_1) == "ustar"
        }
    }.getOrDefault(false)

    private fun entryOf(i: Int, name: String, size: Long, packed: Long, dir: Boolean, time: Long): ArcEntry {
        val safe = safePath(name)
        return ArcEntry(i, safe ?: name, size, packed, dir || name.endsWith("/"), time, unsafe = safe == null)
    }

    /** All entries (directories included), up to [ArcLimits.maxEntries]. */
    fun list(f: File, fmt: Format, limits: ArcLimits = ArcLimits()): List<ArcEntry> {
        val out = ArrayList<ArcEntry>()
        fun add(e: ArcEntry) { if (out.size >= limits.maxEntries) throw ArchiveLimitException("entries"); out += e }
        when (fmt) {
            Format.ZIP -> ZipFile(f).use { z ->
                val it = z.entries(); var i = 0
                while (it.hasMoreElements()) { val e = it.nextElement(); add(entryOf(i++, e.name, e.size, e.compressedSize, e.isDirectory, e.time)) }
            }
            Format.SEVENZ -> SevenZFile.builder().setFile(f).get().use { z ->
                var i = 0
                for (e in z.entries) add(entryOf(i++, e.name ?: "entry$i", e.size, -1, e.isDirectory, runCatching { e.lastModifiedDate.time }.getOrDefault(0L)))
            }
            Format.TAR, Format.TGZ, Format.TBZ, Format.TXZ -> TarArchiveInputStream(decompressor(f, fmt)).use { t ->
                var i = 0
                while (true) {
                    val e = t.nextEntry ?: break
                    add(entryOf(i++, e.name, e.size, -1, e.isDirectory, runCatching { e.lastModifiedDate.time }.getOrDefault(0L)))
                }
            }
            Format.GZ, Format.BZ2, Format.XZ -> {
                val inner = f.name.substringBeforeLast('.').ifBlank { "content" }
                add(ArcEntry(0, safePath(inner) ?: "content", -1, f.length(), false, f.lastModified()))
            }
        }
        return out
    }

    /** Copies at most the limits from [src] to [out]; throws [ArchiveLimitException] beyond them. */
    private fun guardedCopy(src: InputStream, out: File, packed: Long, limits: ArcLimits): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        out.outputStream().buffered().use { o ->
            while (true) {
                val r = src.read(buf); if (r <= 0) break
                total += r
                if (total > limits.maxEntryBytes) throw ArchiveLimitException("size")
                if (packed > 0 && total > 1024 * 1024 && total / packed > limits.maxRatio) throw ArchiveLimitException("ratio")
                o.write(buf, 0, r)
            }
        }
        return total
    }

    /**
     * Extracts entry [e] into [dir] under a generated name (index + sanitized base name) — never under the entry's own
     * path. Returns the file. Unsafe entries (".." names) are refused.
     */
    fun extract(f: File, fmt: Format, e: ArcEntry, dir: File, limits: ArcLimits = ArcLimits()): File {
        if (e.unsafe) throw ArchiveLimitException("unsafe")
        if (e.isDir) throw IOException("directory")
        dir.mkdirs()
        val base = e.name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(120).ifBlank { "entry" }
        val out = File(dir, "${e.index}_$base")
        require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "outside" }
        if (out.exists() && out.length() > 0 && (e.size < 0 || out.length() == e.size)) return out
        val tmp = File(dir, ".${out.name}.part")
        try {
            when (fmt) {
                Format.ZIP -> ZipFile(f).use { z ->
                    // by index: names can repeat inside a ZIP
                    val it = z.entries(); var i = 0
                    var ze: java.util.zip.ZipEntry? = null
                    while (it.hasMoreElements()) { val x = it.nextElement(); if (i++ == e.index) { ze = x; break } }
                    val entry = ze ?: throw IOException("entry not found")
                    if (entry.size > limits.maxEntryBytes) throw ArchiveLimitException("size")
                    z.getInputStream(entry).use { s -> guardedCopy(s, tmp, entry.compressedSize, limits) }
                }
                Format.SEVENZ -> SevenZFile.builder().setFile(f).get().use { z ->
                    var i = 0
                    val entry = z.entries.firstOrNull { i++ == e.index } ?: throw IOException("entry not found")
                    if (entry.size > limits.maxEntryBytes) throw ArchiveLimitException("size")
                    z.getInputStream(entry).use { s -> guardedCopy(s, tmp, -1, limits) }
                }
                Format.TAR, Format.TGZ, Format.TBZ, Format.TXZ -> TarArchiveInputStream(decompressor(f, fmt)).use { t ->
                    var i = 0
                    while (true) {
                        val x = t.nextEntry ?: throw IOException("entry not found")
                        if (i++ == e.index) {
                            if (x.size > limits.maxEntryBytes) throw ArchiveLimitException("size")
                            guardedCopy(t, tmp, -1, limits); break
                        }
                    }
                }
                Format.GZ, Format.BZ2, Format.XZ -> decompressor(f, fmt).use { s -> guardedCopy(s, tmp, f.length(), limits) }
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return out
        } finally { tmp.delete() }
    }

    /** Direct children of folder [prefix] ("" = root), folders first; implicit folders are synthesized. */
    fun children(all: List<ArcEntry>, prefix: String): List<ArcEntry> {
        val p = if (prefix.isEmpty() || prefix.endsWith("/")) prefix else "$prefix/"
        val dirs = LinkedHashMap<String, ArcEntry>()
        val files = ArrayList<ArcEntry>()
        for (e in all) {
            if (!e.path.startsWith(p) || e.path == p) continue
            val rest = e.path.removePrefix(p)
            val slash = rest.indexOf('/')
            if (slash >= 0 && slash < rest.length - 1) {
                val d = p + rest.substring(0, slash + 1)
                dirs.getOrPut(d) { ArcEntry(-1, d, 0, 0, true, 0) }
            } else if (e.isDir) dirs[p + rest.trimEnd('/') + "/"] = e
            else files += e
        }
        return dirs.values.sortedBy { it.name.lowercase() } + files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }
}

/** "page2" before "page10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
