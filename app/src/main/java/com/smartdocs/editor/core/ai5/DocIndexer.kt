package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Builds and caches the [DocIndex] of a PDF. Page texts come from PDFium (whole document, no page limit);
 * the chunk list is cached in `cacheDir/ai5_index/<contentHash>.json` (hash = size + first/last 64 KB), so an
 * edited file gets a fresh index and a renamed copy reuses the old one.
 */
object DocIndexer {
    private val mem = object : LinkedHashMap<String, DocIndex>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DocIndex>?) = size > 6
    }
    private val lock = Mutex()

    fun contentKey(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(f.length().toString().toByteArray())
        RandomAccessFile(f, "r").use { r ->
            val n = 64 * 1024
            val head = ByteArray(minOf(n.toLong(), r.length()).toInt()); r.readFully(head); md.update(head)
            if (r.length() > n) {
                r.seek((r.length() - n).coerceAtLeast(0)); val tail = ByteArray(n); r.readFully(tail); md.update(tail)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "ai5_index").apply { mkdirs() }

    /**
     * Index of [file]. [pageText] (optional) reuses an open reader session; otherwise a short-lived session is
     * opened. [progress] receives (pages read, total).
     */
    suspend fun get(
        ctx: Context, file: File, password: String? = null,
        pageText: (suspend (Int) -> String)? = null, pageCount: Int? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): DocIndex = withContext(Dispatchers.IO) {
        val key = contentKey(file)
        lock.withLock { mem[key] }?.let { return@withContext it.withFile(file) }
        val cache = File(dir(ctx), "$key.json")
        load(cache, key, file)?.let { idx -> lock.withLock { mem[key] = idx }; return@withContext idx }
        val pages: List<String> = if (pageText != null && pageCount != null) {
            (0 until pageCount).map { i -> progress(i, pageCount); runCatching { pageText(i) }.getOrDefault("") }
        } else {
            val s = PdfSession.open(ctx, file, password)
            try { (0 until s.pageCount).map { i -> progress(i, s.pageCount); runCatching { s.text(i) }.getOrDefault("") } } finally { s.closeAsync() }
        }
        val idx = DocIndex(key, file.name, file.absolutePath, pages.size, Chunker.chunk(pages))
        runCatching { save(cache, idx) }
        lock.withLock { mem[key] = idx }
        idx
    }

    /** Same chunks under another path (renamed / copied file with identical content). */
    private fun DocIndex.withFile(f: File): DocIndex = if (path == f.absolutePath) this else DocIndex(key, f.name, f.absolutePath, pageCount, chunks)

    private fun save(f: File, idx: DocIndex) {
        val o = buildJsonObject {
            put("v", 1); put("pages", idx.pageCount)
            put("c", buildJsonArray { idx.chunks.forEach { c -> add(buildJsonArray { add(JsonPrimitive(c.page)); add(JsonPrimitive(c.para)); add(JsonPrimitive(c.text)) }) } })
        }
        f.writeText(o.toString())
    }

    private fun load(f: File, key: String, file: File): DocIndex? = runCatching {
        if (!f.exists()) return null
        val o = AiJson.json.parseToJsonElement(f.readText()) as JsonObject
        val arr = o["c"] as JsonArray
        val chunks = arr.mapIndexed { i, e ->
            val a = e as JsonArray
            DocChunk(i, (a[0] as JsonPrimitive).contentOrNull!!.toInt(), (a[1] as JsonPrimitive).contentOrNull!!.toInt(), (a[2] as JsonPrimitive).contentOrNull.orEmpty())
        }
        DocIndex(key, file.name, file.absolutePath, AiJson.int(o, "pages") ?: 0, chunks)
    }.getOrNull()

    // ai6 (B9): text sources (web links / pasted text, `.sdsrc.txt`) and plain .txt files, split into pseudo pages
    suspend fun getText(ctx: Context, file: File): DocIndex = withContext(Dispatchers.IO) {
        val key = "t" + contentKey(file)
        lock.withLock { mem[key] }?.let { return@withContext it.withFile(file) }
        val src = com.smartdocs.editor.core.ai6.SpaceSources.read(file)
        val pages = com.smartdocs.editor.core.ai6.SpaceSources.pages(src.text)
        val idx = DocIndex(key, src.title.ifBlank { file.nameWithoutExtension }, file.absolutePath, pages.size, Chunker.chunk(pages))
        lock.withLock { mem[key] = idx }
        idx
    }

    /** True for files indexed as text instead of PDF. */
    fun isText(f: File): Boolean = f.name.endsWith(".txt", true) || f.name.endsWith(".md", true)

    /**
     * ai6 (§35 chat about attachments): indexes of the files embedded in [file] (PDF and text attachments). They are
     * extracted to `cacheDir/ai6_att/<key>/` so a citation can open the attachment itself. Failures are skipped.
     */
    suspend fun attachments(ctx: Context, file: File, password: String? = null): List<DocIndex> = withContext(Dispatchers.IO) {
        val list = runCatching { com.smartdocs.editor.core.pdf.AttachmentOps.list(file, password) }.getOrDefault(emptyList())
        if (list.isEmpty()) return@withContext emptyList()
        val dir = File(File(ctx.cacheDir, "ai6_att"), contentKey(file))
        list.take(20).mapNotNull { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            if (ext != "pdf" && ext != "txt" && ext != "md" && ext != "csv") return@mapNotNull null
            runCatching {
                val f = File(dir, a.name.replace(Regex("""[\\/:*?"<>|]"""), "_")).takeIf { it.exists() && it.length() > 0 }
                    ?: com.smartdocs.editor.core.pdf.AttachmentOps.extract(file, a, dir, password) ?: return@runCatching null
                val idx = if (ext == "pdf") get(ctx, f) else getText(ctx, f)
                DocIndex(idx.key, file.nameWithoutExtension + " › " + a.name, idx.path, idx.pageCount, idx.chunks)
            }.getOrNull()
        }
    }

    /** Indexes of several files (a Space), skipping files that fail to open. */
    suspend fun getAll(ctx: Context, files: List<File>, progress: (Int, Int) -> Unit = { _, _ -> }): List<DocIndex> =
        files.mapIndexedNotNull { i, f -> progress(i, files.size); runCatching { get(ctx, f) }.getOrNull() }

    fun clear(ctx: Context) { runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }; synchronized(mem) { mem.clear() } }
}
