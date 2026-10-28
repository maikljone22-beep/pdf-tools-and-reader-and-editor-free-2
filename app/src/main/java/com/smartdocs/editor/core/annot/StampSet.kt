package com.smartdocs.editor.core.annot

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 24.58 / §41 shared (enterprise) stamp sets: a zip with a "stamps.txt" manifest and one PNG per image stamp.
 * An administrator exports the custom stamps as a set, optionally locked; everyone who imports it gets the same
 * stamps, and locked ones cannot be renamed, restyled, replaced or deleted one by one (only the whole set).
 *
 * Manifest lines are tab-separated, with \\, \t and \n escaped:
 * `SDSTAMPSET 1`, then `set <name> <locked 0/1>`, then per stamp
 * `stamp <png file or empty> <name> <category> <text or empty> <colour long> <opacity> <white 0/1> <locked 0/1>`.
 */
object StampSet {
    const val MANIFEST = "stamps.txt"
    private const val MAGIC = "SDSTAMPSET 1"
    /** Largest image entry read from a set (protects against zip bombs). */
    private const val MAX_ENTRY = 8L * 1024 * 1024

    data class Entry(
        val file: String?, val name: String, val category: String, val text: String?,
        val color: Long, val opacity: Float = 1f, val whiteTransparent: Boolean = false, val locked: Boolean = false,
    )
    data class Manifest(val name: String, val locked: Boolean, val stamps: List<Entry>)

    fun esc(s: String): String = buildString { s.forEach { c -> append(when (c) { '\\' -> "\\\\"; '\t' -> "\\t"; '\n' -> "\\n"; '\r' -> ""; else -> c.toString() }) } }

    fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { append(c); i++ }
        }
    }

    fun write(m: Manifest): String = buildString {
        append(MAGIC).append('\n')
        append(listOf("set", esc(m.name), if (m.locked) "1" else "0").joinToString("\t")).append('\n')
        m.stamps.forEach { e ->
            append(listOf("stamp", esc(e.file ?: ""), esc(e.name), esc(e.category), esc(e.text ?: ""), e.color.toString(), e.opacity.toString(),
                if (e.whiteTransparent) "1" else "0", if (e.locked || m.locked) "1" else "0").joinToString("\t")).append('\n')
        }
    }

    /** Parse a manifest; null when it is not a stamp set. A locked set locks every stamp in it. */
    fun parse(text: String): Manifest? {
        val lines = text.replace("\r", "").split('\n')
        if (lines.firstOrNull()?.trim() != MAGIC) return null
        var name = ""; var locked = false
        val out = ArrayList<Entry>()
        lines.drop(1).forEach { line ->
            val f = line.split('\t')
            when (f.firstOrNull()) {
                "set" -> { name = unesc(f.getOrElse(1) { "" }); locked = f.getOrNull(2) == "1" }
                "stamp" -> {
                    if (f.size < 6) return@forEach
                    val file = unesc(f[1]).takeIf { it.isNotBlank() }?.substringAfterLast('/')   // never a path outside the set
                    val txt = unesc(f[4]).takeIf { it.isNotBlank() }
                    if (file == null && txt == null) return@forEach
                    out += Entry(file, unesc(f[2]), unesc(f[3]), txt, f[5].toLongOrNull() ?: 0xFF1565C0L,
                        f.getOrNull(6)?.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 1f, f.getOrNull(7) == "1", f.getOrNull(8) == "1")
                }
            }
        }
        return Manifest(name, locked, if (locked) out.map { it.copy(locked = true) } else out)
    }

    /** Write a set: [images] maps the manifest file names to the PNG files on disk. */
    fun zip(m: Manifest, images: Map<String, File>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST)); z.write(write(m).toByteArray(Charsets.UTF_8)); z.closeEntry()
            images.forEach { (n, f) -> if (f.isFile) { z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        }
    }

    /** Read a set: the manifest and its images copied into [dir] (renamed by [rename] to unique names). Null = not a set. */
    fun unzip(input: InputStream, dir: File, rename: (String) -> File): Pair<Manifest, Map<String, File>>? {
        var manifest: Manifest? = null
        val files = HashMap<String, File>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.substringAfterLast('/')
                if (n == MANIFEST) manifest = parse(z.readBytes().toString(Charsets.UTF_8))
                else if (n.endsWith(".png", true) || n.endsWith(".jpg", true) || n.endsWith(".webp", true)) {
                    val target = rename(n)
                    if (!target.canonicalPath.startsWith(dir.canonicalPath)) continue
                    var size = 0L
                    target.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) { val r = z.read(buf); if (r < 0) break; size += r; if (size > MAX_ENTRY) break; o.write(buf, 0, r) }
                    }
                    if (size > MAX_ENTRY) target.delete() else files[n] = target
                }
            }
        }
        val m = manifest ?: run { files.values.forEach { it.delete() }; return null }
        // images the manifest does not name are dropped
        files.filterKeys { k -> m.stamps.none { it.file == k } }.values.forEach { it.delete() }
        return m to files.filterKeys { k -> m.stamps.any { it.file == k } }
    }
}
