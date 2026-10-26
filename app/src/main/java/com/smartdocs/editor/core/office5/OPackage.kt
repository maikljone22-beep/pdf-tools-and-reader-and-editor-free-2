package com.smartdocs.editor.core.office5

import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An editable OOXML (ZIP) package held in memory. Parts are kept as their original bytes; a part is
 * parsed into an [XDoc] only when asked, and re-serialised on save only when it was opened for editing
 * through [edit]. Every other entry is written back byte-for-byte (same content, same order), new parts
 * are appended, removed parts are dropped.
 *
 * Undo support: [snapshot] / [restore] capture the whole package cheaply (unchanged parts share their
 * byte arrays). After [restore] every DOM obtained earlier is stale and must be fetched again.
 */
class OPackage private constructor(private var entries: LinkedHashMap<String, ByteArray>) {

    private val doms = HashMap<String, XDoc>()
    private val dirty = LinkedHashSet<String>()

    /** Incremented on every [restore]; editors compare it to know their DOM references are stale. */
    var generation: Int = 0; private set

    val names: List<String> get() = entries.keys.toList()
    fun has(path: String) = entries.containsKey(path)

    /** Current bytes of [path] (serialising its DOM when it was edited). */
    fun bytes(path: String): ByteArray? = if (path in dirty) doms[path]!!.toBytes() else entries[path]

    fun text(path: String): String? = bytes(path)?.let { XDom.decode(it) }

    /** Parsed (shared, cached) DOM of [path] for reading. Do not mutate it: use [edit]. */
    fun dom(path: String): XDoc? {
        doms[path]?.let { return it }
        val b = entries[path] ?: return null
        return runCatching { XDom.parse(b) }.getOrNull()?.also { doms[path] = it }
    }

    /** DOM of [path] that will be written back on save. */
    fun edit(path: String): XDoc? = dom(path)?.also { dirty.add(path) }

    /** Adds or replaces a part with raw bytes. */
    fun put(path: String, bytes: ByteArray) { entries[path] = bytes; doms.remove(path); dirty.remove(path) }

    /** Adds or replaces a part with a DOM (kept editable). */
    fun put(path: String, doc: XDoc) { if (!entries.containsKey(path)) entries[path] = ByteArray(0); doms[path] = doc; dirty.add(path) }

    fun remove(path: String) { entries.remove(path); doms.remove(path); dirty.remove(path) }

    // ---------------- relationships ----------------

    /** Path of the .rels part of [part] ("" = package root). */
    fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
    }

    fun rels(part: String): List<ORel> {
        val d = dom(relsPath(part)) ?: return emptyList()
        val dir = part.substringBeforeLast('/', "")
        return d.root.children("Relationship").map { r ->
            val ext = r.attr("TargetMode") == "External"
            val t = r.attr("Target") ?: ""
            ORel(r.attr("Id") ?: "", r.attr("Type") ?: "", if (ext) t else resolve(dir, t), ext, t)
        }
    }

    fun rel(part: String, id: String): ORel? = rels(part).firstOrNull { it.id == id }

    /** The main document part (word/document.xml, xl/workbook.xml, ppt/presentation.xml). */
    fun mainPart(default: String): String = rels("").firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: default

    /** Adds a relationship from [part] to [targetPath] (a package path) and returns its new id. */
    fun addRel(part: String, type: String, targetPath: String): String {
        val rp = relsPath(part)
        val d = edit(rp) ?: XDoc(mutableListOf(XRaw(DECL)), XElem("Relationships").set("xmlns", REL_NS)).also { put(rp, it) }
        val used = d.root.children("Relationship").mapNotNull { it.attr("Id") }.toSet()
        var n = used.size + 1
        while ("rId$n" in used) n++
        val id = "rId$n"
        d.root.add(XElem("Relationship").set("Id", id).set("Type", type).set("Target", relative(part.substringBeforeLast('/', ""), targetPath)))
        return id
    }

    fun removeRel(part: String, id: String) {
        val d = edit(relsPath(part)) ?: return
        d.root.children("Relationship").filter { it.attr("Id") == id }.forEach { d.root.remove(it) }
    }

    // ---------------- content types ----------------

    fun addOverride(partPath: String, contentType: String) {
        val d = edit(CT) ?: return
        val pn = "/$partPath"
        if (d.root.children("Override").any { it.attr("PartName").equals(pn, true) }) return
        d.root.add(XElem("Override").set("PartName", pn).set("ContentType", contentType))
    }

    fun removeOverride(partPath: String) {
        val d = edit(CT) ?: return
        d.root.children("Override").filter { it.attr("PartName").equals("/$partPath", true) }.forEach { d.root.remove(it) }
    }

    fun ensureDefault(ext: String, contentType: String) {
        val d = edit(CT) ?: return
        if (d.root.children("Default").any { it.attr("Extension").equals(ext, true) }) return
        d.root.add(0, XElem("Default").set("Extension", ext).set("ContentType", contentType))
    }

    fun contentTypeOf(partPath: String): String? {
        val d = dom(CT) ?: return null
        d.root.children("Override").firstOrNull { it.attr("PartName").equals("/$partPath", true) }?.let { return it.attr("ContentType") }
        val ext = partPath.substringAfterLast('.', "")
        return d.root.children("Default").firstOrNull { it.attr("Extension").equals(ext, true) }?.attr("ContentType")
    }

    /** A free part name like "word/media/image3.png" (counting from 1). */
    fun freeName(dir: String, base: String, ext: String): String {
        var n = 1
        while (has("$dir/$base$n.$ext")) n++
        return "$dir/$base$n.$ext"
    }

    // ---------------- undo / save ----------------

    class Snapshot internal constructor(internal val entries: LinkedHashMap<String, ByteArray>) {
        val size: Long get() = entries.values.sumOf { it.size.toLong() }
    }

    fun snapshot(): Snapshot {
        val m = LinkedHashMap<String, ByteArray>(entries.size * 2)
        for ((k, v) in entries) m[k] = if (k in dirty) doms[k]!!.toBytes() else v
        return Snapshot(m)
    }

    /** Puts the package back to [s]; every DOM handed out before is stale afterwards. */
    fun restore(s: Snapshot) {
        entries = LinkedHashMap(s.entries)
        doms.clear(); dirty.clear(); generation++
    }

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zos ->
            for ((name, b) in entries) {
                val data = if (name in dirty) doms[name]!!.toBytes() else b
                zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
            }
        }
    }

    fun toBytes(): ByteArray = java.io.ByteArrayOutputStream().also { write(it) }.toByteArray()

    /** Saves to [target] through a temp file next to it. */
    fun saveTo(target: File) {
        val tmp = File(target.absoluteFile.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            tmp.outputStream().use { write(it) }
            if (tmp.length() == 0L) throw java.io.IOException("empty output")
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally { if (tmp.exists()) tmp.delete() }
    }

    companion object {
        const val CT = "[Content_Types].xml"
        const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

        fun open(file: File): OPackage = read(file.readBytes())

        fun read(bytes: ByteArray): OPackage {
            val m = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory && !m.containsKey(e.name)) m[e.name] = zin.readBytes()
                }
            }
            if (m.isEmpty()) throw XmlParseException("not a zip package")
            return OPackage(m)
        }

        /** A package from scratch (part path -> bytes, in order). */
        fun create(parts: Map<String, ByteArray>): OPackage = OPackage(LinkedHashMap(parts))

        fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
            for (seg in target.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }

        /** Relative target from directory [fromDir] to package path [to]. */
        fun relative(fromDir: String, to: String): String {
            if (fromDir.isEmpty()) return to
            val a = fromDir.split('/'); val b = to.split('/')
            var k = 0
            while (k < a.size && k < b.size - 1 && a[k] == b[k]) k++
            return "../".repeat(a.size - k) + b.drop(k).joinToString("/")
        }

        fun imageExt(bytes: ByteArray): String {
            if (bytes.size > 4) {
                val b0 = bytes[0].toInt() and 0xFF; val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 0x89 && b1 == 0x50) return "png"
                if (b0 == 0xFF && b1 == 0xD8) return "jpeg"
                if (b0 == 0x47 && b1 == 0x49) return "gif"
                if (b0 == 0x42 && b1 == 0x4D) return "bmp"
                if (b0 == 0x52 && b1 == 0x49) return "webp"
            }
            return "png"
        }

        fun imageMime(ext: String): String = when (ext) { "jpeg", "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"; else -> "image/png" }

        /** Pixel size (w, h) of a PNG/JPEG/GIF/BMP, or null. Pure JVM. */
        fun imageSize(b: ByteArray): Pair<Int, Int>? {
            fun u16be(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            fun u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
            fun u16le(i: Int) = ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
            return runCatching {
                when (imageExt(b)) {
                    "png" -> u32be(16) to u32be(20)
                    "gif" -> u16le(6) to u16le(8)
                    "bmp" -> (u16le(18) or (u16le(20) shl 16)) to kotlin.math.abs(u16le(22) or (u16le(24) shl 16))
                    "jpeg" -> {
                        var i = 2; var res: Pair<Int, Int>? = null
                        while (i + 9 < b.size) {
                            if (b[i].toInt() and 0xFF != 0xFF) { i++; continue }
                            val m = b[i + 1].toInt() and 0xFF
                            val len = u16be(i + 2)
                            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) { res = u16be(i + 7) to u16be(i + 5); break }
                            i += 2 + len
                        }
                        res
                    }
                    else -> null
                }
            }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }
        }
    }
}

/** One relationship; [target] is a resolved package path unless [external]. */
data class ORel(val id: String, val type: String, val target: String, val external: Boolean, val rawTarget: String)
