package com.smartdocs.editor.ui.nve.search

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.core.edit.Bidi
import com.smartdocs.editor.core.nve.NveSearchCore
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.search.DocParts
import com.smartdocs.editor.core.search.FileResult
import com.smartdocs.editor.core.search.FullTextIndex
import com.smartdocs.editor.core.search.IndexHit
import com.smartdocs.editor.core.search.PartKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.TextMatcher
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Index options (prefs "nve_search"): stop words, numbers, excluded folders. */
object NveIndexPrefs {
    private const val FILE = "nve_search"
    @Volatile private var cache: Pair<Long, NveSearchCore.IndexOptions>? = null
    private var version = 0L

    fun load(ctx: Context): NveSearchCore.IndexOptions {
        cache?.takeIf { it.first == version }?.let { return it.second }
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val o = NveSearchCore.IndexOptions(
            stopWords = (p.getString("stop_words", "") ?: "").split(Regex("[\\s,،]+")).filter { it.isNotBlank() }.toSet(),
            includeNumbers = p.getBoolean("numbers", true),
            excluded = (p.getString("excluded", "") ?: "").split('\n').filter { it.isNotBlank() },
        )
        cache = version to o
        return o
    }

    fun save(ctx: Context, o: NveSearchCore.IndexOptions) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("stop_words", o.stopWords.joinToString(" "))
            .putBoolean("numbers", o.includeNumbers).putString("excluded", o.excluded.joinToString("\n")).apply()
        version++; cache = null
    }

    /** Best-effort file path of a SAF tree uri ("primary:Download/X" → /storage/emulated/0/Download/X). */
    fun treePath(uri: Uri): String? {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
        val root = if (vol.equals("primary", true)) "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) root else "$root/$rel"
    }
}

/** Highlight colour / opacity of search hits in the reader (prefs "nve_search"). */
object NveHitStyle {
    private const val FILE = "nve_search"
    /** 0xRRGGBB of the hit highlight (default amber). */
    fun color(ctx: Context): Int = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("hit_color", 0xFFC107)
    /** Colour of the current hit (default orange). */
    fun currentColor(ctx: Context): Int = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("hit_current", 0xFF5722)
    fun alpha(ctx: Context): Float = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("hit_alpha", 0.4f)
    fun save(ctx: Context, color: Int, current: Int, alpha: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("hit_color", color).putInt("hit_current", current).putFloat("hit_alpha", alpha).apply()
    }
}

/** Reader find bar: extra scopes (bookmarks, comments, form fields, attachments) of the open document. */
object NveFind {
    private const val FILE = "nve_search"
    fun scopes(ctx: Context): Set<PartKind> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("find_scopes", "") ?: ""
        return raw.split(',').mapNotNull { n -> PartKind.entries.firstOrNull { it.name == n } }.toSet()
    }
    fun setScopes(ctx: Context, s: Set<PartKind>) { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("find_scopes", s.joinToString(",") { it.name }).apply() }

    data class Hit(val kind: PartKind, val page: Int, val snippet: String, val range: IntRange)

    /** Matches of [query] in the non-page-text parts of [file] (plus [pending] comment texts with their page). */
    suspend fun extraHits(file: File, password: String?, query: String, options: SearchOptions, kinds: Set<PartKind>, pending: List<Pair<Int, String>> = emptyList()): List<Hit> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || kinds.isEmpty()) return@withContext emptyList()
            val parts = DocParts.load(file, password).filter { it.kind in kinds && it.kind != PartKind.TEXT } +
                (if (PartKind.COMMENTS in kinds) pending.map { (p, t) -> com.smartdocs.editor.core.search.DocPart(PartKind.COMMENTS, p, t) } else emptyList())
            val out = ArrayList<Hit>()
            for (p in parts) for (r in TextMatcher.find(p.text, query, options)) {
                val (snip, rr) = snippet(p.text, r)
                out += Hit(p.kind, p.page, snip, rr)
                if (out.size > 500) return@withContext out
            }
            out
        }

    internal fun snippet(raw: String, r: IntRange, pad: Int = 40): Pair<String, IntRange> {
        val s = (r.first - pad).coerceAtLeast(0); val e = (r.last + 1 + pad).coerceAtMost(raw.length)
        val pre = if (s > 0) "…" else ""
        val text = pre + raw.substring(s, e).replace('\r', ' ').replace('\n', ' ') + (if (e < raw.length) "…" else "")
        val off = pre.length - s
        return text to ((r.first + off)..(r.last + off))
    }
}

/** On-the-fly search outside the index: open tabs, a chosen folder, given files. */
object NveScopeSearch {

    /** Searches local PDF [files] (page text via PDFium + comments / bookmarks / fields / attachments). [label] = shown name. */
    suspend fun searchFiles(context: Context, files: List<Pair<String, File>>, query: String, options: SearchOptions, kinds: Set<PartKind>,
                            onProgress: (Int, Int) -> Unit = { _, _ -> }, perFile: Int = 20): List<FileResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileResult>()
        files.forEachIndexed { i, (path, f) ->
            ensureActive()
            onProgress(i, files.size)
            val hits = ArrayList<IndexHit>(); var total = 0; var pages = 0
            if (PartKind.TEXT in kinds) runCatching {
                val s = PdfSession.open(context, f, null)
                try {
                    pages = s.pageCount
                    for (p in 0 until s.pageCount) {
                        val t = runCatching { s.text(p) }.getOrDefault("")
                        for (r in TextMatcher.find(t, query, options)) {
                            total++
                            if (hits.size < perFile) { val (sn, rr) = NveFind.snippet(t, r); hits += IndexHit(p, PartKind.TEXT, sn, listOf(rr)) }
                        }
                    }
                } finally { s.closeAsync() }
            }
            val others = kinds - PartKind.TEXT
            if (others.isNotEmpty()) for (part in DocParts.load(f, null).filter { it.kind in others }) for (r in TextMatcher.find(part.text, query, options)) {
                total++
                if (hits.size < perFile) { val (sn, rr) = NveFind.snippet(part.text, r); hits += IndexHit(part.page, part.kind, sn, listOf(rr)) }
            }
            if (total > 0) out += FileResult(path, f.name.takeIf { !path.startsWith("content:") } ?: displayName(path, f), pages, hits, total)
        }
        onProgress(files.size, files.size)
        out
    }

    private fun displayName(path: String, f: File) = Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: f.name

    /** PDFs of a SAF folder tree (recursive, [maxDepth]); copied to the cache so PDFium can read them. Pairs: content uri → local copy. */
    suspend fun folderFiles(context: Context, tree: Uri, maxDepth: Int = 6, maxFiles: Int = 500): List<Pair<String, File>> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        val out = ArrayList<Pair<String, File>>()
        val dir = File(context.cacheDir, "nve_folder_search").apply { deleteRecursively(); mkdirs() }
        fun walk(d: DocumentFile, depth: Int) {
            if (depth > maxDepth || out.size >= maxFiles) return
            for (c in d.listFiles()) {
                if (out.size >= maxFiles) return
                if (c.isDirectory) walk(c, depth + 1)
                else if (c.name?.endsWith(".pdf", true) == true || c.type == "application/pdf") {
                    val local = File(dir, "${out.size}_" + (c.name ?: "doc.pdf").replace('/', '_'))
                    val ok = runCatching { context.contentResolver.openInputStream(c.uri)?.use { i -> local.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
                    if (ok) out += c.uri.toString() to local
                }
            }
        }
        walk(root, 0)
        out
    }

    /** Metadata of result files (for "additional criteria" and the date sort). Content uris use their local copy. */
    suspend fun metaOf(results: List<FileResult>, local: Map<String, File> = emptyMap()): Map<String, NveSearchCore.Meta> = withContext(Dispatchers.IO) {
        results.associate { r ->
            val f = local[r.path] ?: File(r.path)
            r.path to (runCatching { PdfOps.load(f, null).use { NveSearchCore.meta(it, f.lastModified()) } }.getOrNull() ?: NveSearchCore.Meta(fileTime = f.lastModified()))
        }
    }
}

/** "Save results to PDF": a simple report (query, then each file and its hits), Arabic shaped. */
object NveSearchReport {
    suspend fun write(context: Context, out: File, title: String, query: String, results: List<FileResult>, pageWord: String, kindName: (PartKind) -> String) =
        withContext(Dispatchers.IO) {
            PDDocument().use { doc ->
                val arabic: PDFont = PdfOps.arabicFont(context, doc)
                val latin: PDFont = PDType1Font.HELVETICA
                val bold: PDFont = PDType1Font.HELVETICA_BOLD
                val margin = 40f; val box = PDRectangle.A4
                var page = PDPage(box); doc.addPage(page)
                var cs = PDPageContentStream(doc, page)
                var y = box.height - margin
                fun canLatin(f: PDFont, c: Char) = runCatching { f.encode(c.toString()); true }.getOrDefault(false)
                fun line(text: String, size: Float, strong: Boolean = false) {
                    if (y < margin + size) {
                        cs.close(); page = PDPage(box); doc.addPage(page); cs = PDPageContentStream(doc, page); y = box.height - margin
                    }
                    val rtl = text.firstOrNull { it.isLetter() }?.let { it.code in 0x0590..0x08FF || it.code in 0xFB1D..0xFEFF } ?: false
                    val visual = Bidi.shapeVisual(text, rtl)
                    // runs: Arabic / digits in the Arabic font, everything else in Helvetica when it can encode it
                    val runs = ArrayList<Pair<PDFont, String>>()
                    val lat = if (strong) bold else latin
                    for (c in visual) {
                        val f = when {
                            c.code in 0x0600..0x06FF || c.code in 0xFB50..0xFEFF -> arabic
                            canLatin(lat, c) -> lat
                            canLatin(arabic, c) -> arabic
                            else -> null
                        } ?: continue
                        if (runs.isNotEmpty() && runs.last().first === f) runs[runs.size - 1] = f to (runs.last().second + c) else runs += f to c.toString()
                    }
                    val width = runs.sumOf { (f, s) -> runCatching { f.getStringWidth(s) / 1000.0 * size }.getOrDefault(0.0) }.toFloat()
                    var x = if (rtl) (box.width - margin - width).coerceAtLeast(margin) else margin
                    for ((f, s) in runs) {
                        cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(x, y); runCatching { cs.showText(s) }; cs.endText()
                        x += runCatching { f.getStringWidth(s) / 1000f * size }.getOrDefault(0f)
                    }
                    y -= size * 1.45f
                }
                fun wrap(text: String, maxChars: Int = 95) = text.chunked(maxChars)
                line(title, 16f, true)
                line("«$query»", 12f)
                y -= 6f
                for (r in results) {
                    line("${r.name}  (${r.total})", 12f, true)
                    line(r.path, 8f)
                    for (h in r.hits) {
                        val where = (if (h.page >= 0) "$pageWord ${h.page + 1} · " else "") + kindName(h.kind)
                        line(where, 9f, true)
                        wrap(h.snippet).forEach { line(it, 9f) }
                    }
                    y -= 6f
                }
                cs.close()
                doc.save(out)
            }
        }
}

/** Index maintenance: backup export / import of the FTS database file. */
object NveIndexBackup {
    suspend fun export(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        val idx = FullTextIndex.get(context)
        runCatching { idx.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)") }
        val db = context.getDatabasePath(FullTextIndex.DB_NAME)
        context.contentResolver.openOutputStream(target, "w")?.use { o -> db.inputStream().use { it.copyTo(o) } } ?: error("cannot write")
    }

    /** Replaces the index with a backup (validated as SQLite with the expected tables). */
    suspend fun import(context: Context, source: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "nve_index_import.db")
        context.contentResolver.openInputStream(source)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: error("cannot read")
        val ok = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT COUNT(*) FROM docs", null).use { it.moveToFirst() }
                d.rawQuery("SELECT COUNT(*) FROM parts", null).use { it.moveToFirst() }
            }
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); error("not a SmartDocs search index") }
        val idx = FullTextIndex.get(context)
        idx.close()
        val db = context.getDatabasePath(FullTextIndex.DB_NAME)
        listOf("-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        tmp.copyTo(db, overwrite = true); tmp.delete()
    }
}
