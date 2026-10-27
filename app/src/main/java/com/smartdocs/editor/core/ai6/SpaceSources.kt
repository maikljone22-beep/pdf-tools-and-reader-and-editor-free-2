package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai5.Space
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ai6 (B9): non-PDF sources of a Space — a web link or pasted text. Each is a small `.sdsrc.txt` file
 * (header + body) whose path sits in [Space.files] next to the PDFs, so chats, citations and study tools
 * treat it like any other document.
 */
data class TextSource(val kind: String, val title: String, val url: String, val text: String) {
    companion object { const val URL = "url"; const val TEXT = "text" }
}

object SpaceSources {
    const val EXT = ".sdsrc.txt"
    private const val MAGIC = "SDSRC1"

    fun isSource(path: String): Boolean = path.endsWith(EXT, ignoreCase = true)

    fun encode(s: TextSource): String = buildString {
        append(MAGIC).append('\n')
        append("kind: ").append(s.kind).append('\n')
        append("title: ").append(s.title.replace('\n', ' ')).append('\n')
        append("url: ").append(s.url.replace('\n', ' ')).append('\n')
        append('\n').append(s.text)
    }

    /** Parse a source file; a plain text file without the header becomes a TEXT source titled by its first line. */
    fun decode(raw: String, fallbackTitle: String = ""): TextSource {
        if (!raw.startsWith(MAGIC)) return TextSource(TextSource.TEXT, fallbackTitle.ifBlank { raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(60).orEmpty() }, "", raw)
        val head = raw.substringBefore("\n\n")
        val body = raw.substringAfter("\n\n", "")
        fun field(k: String) = head.lines().firstOrNull { it.startsWith("$k: ") }?.removePrefix("$k: ")?.trim().orEmpty()
        return TextSource(field("kind").ifBlank { TextSource.TEXT }, field("title"), field("url"), body)
    }

    fun write(dir: File, s: TextSource): File {
        dir.mkdirs()
        val base = Skills.slug(s.title.ifBlank { s.kind }).take(30)
        var f = File(dir, base + EXT); var i = 2
        while (f.exists()) { f = File(dir, "${base}_$i$EXT"); i++ }
        f.writeText(encode(s))
        return f
    }

    fun read(f: File): TextSource = decode(f.readText(), f.name.removeSuffix(EXT))

    /**
     * Split a long text into pseudo pages of about [size] characters at paragraph (or line) ends, so
     * citations say "page 3" of the source.
     */
    fun pages(text: String, size: Int = 3000): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        text.split("\n").forEach { line ->
            if (cur.length + line.length + 1 > size && cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            var l = line
            while (l.length > size) { out += l.take(size); l = l.drop(size) }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }
}

/**
 * ai6 (B9): share a Space as one `.sdspace` file (zip): `space.json` (name, instructions, file names) and every
 * source file under `files/`. Import unpacks it into the app's storage and returns the new Space.
 */
object SpaceBundle {
    const val EXT = "sdspace"
    const val MIME = "application/zip"

    fun manifest(s: Space, names: List<String>): String = buildJsonObject {
        put("format", "smartdocs-space"); put("v", 1); put("name", s.name); put("persona", s.persona)
        put("files", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    data class Manifest(val name: String, val persona: String, val files: List<String>)

    fun parseManifest(text: String): Manifest? = runCatching {
        val o = com.smartdocs.editor.core.ai.AiClient.json.parseToJsonElement(text) as JsonObject
        if ((o["format"] as? JsonPrimitive)?.contentOrNull != "smartdocs-space") return null
        Manifest((o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["persona"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            (o["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
    }.getOrNull()

    /** Safe entry name: no folders, no "..". */
    fun safeName(n: String): String = n.substringAfterLast('/').substringAfterLast('\\').replace("..", "_").replace(Regex("[:*?\"<>|]"), "_").ifBlank { "file" }

    fun export(s: Space, out: File) {
        val files = s.files.map(::File).filter { it.exists() }
        val used = HashSet<String>()
        val names = files.map { f -> var n = safeName(f.name); var i = 2; while (!used.add(n)) { n = "${i}_" + safeName(f.name); i++ }; n }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("space.json")); z.write(manifest(s, names).toByteArray(Charsets.UTF_8)); z.closeEntry()
            files.forEachIndexed { i, f -> z.putNextEntry(ZipEntry("files/" + names[i])); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }

    /** Unpack [zip] into [dir] (a new folder per import). Returns null when it is not a Space bundle. */
    fun import(zip: File, dir: File, newId: String): Space? {
        dir.mkdirs()
        var manifest: Manifest? = null
        val extracted = LinkedHashMap<String, File>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            var total = 0L
            while (e != null) {
                if (!e.isDirectory) {
                    if (e.name == "space.json") manifest = parseManifest(z.readBytes().toString(Charsets.UTF_8))
                    else if (e.name.startsWith("files/")) {
                        val name = safeName(e.name.removePrefix("files/"))
                        val f = File(dir, name)
                        f.outputStream().use { o -> total += z.copyTo(o) }
                        if (total > 500L * 1024 * 1024) throw java.io.IOException("bundle too large")
                        extracted[name] = f
                    }
                }
                e = z.nextEntry
            }
        }
        val m = manifest ?: return null
        val files = m.files.mapNotNull { extracted[safeName(it)]?.absolutePath }.ifEmpty { extracted.values.map { it.absolutePath } }
        return Space(newId, m.name.ifBlank { zip.nameWithoutExtension }, files, m.persona)
    }
}
