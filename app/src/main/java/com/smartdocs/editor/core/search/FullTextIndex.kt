package com.smartdocs.editor.core.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Live state of the background indexer. */
data class IndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String? = null)

data class IndexStats(val docs: Int, val locked: Int, val parts: Int)

/** One match in an indexed file: [snippet] with [highlights] (ranges inside the snippet). */
data class IndexHit(val page: Int, val kind: PartKind, val snippet: String, val highlights: List<IntRange>)

data class FileResult(val path: String, val name: String, val pages: Int, val hits: List<IndexHit>, val total: Int)

/**
 * Full-text index across files, in its own SQLite file `search_index.db` (FTS4; the Room database is untouched).
 * Tables: `docs` (one row per file, with size/mtime to detect changes), `parts(doc, page, kind, raw)` and
 * `parts_fts(body)` whose rowid is the part id and whose body is [IndexText.body] (folded words + light stems).
 * [search] takes FTS stem-prefix candidates and verifies them with [TextMatcher] using the user's options.
 */
class FullTextIndex private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {
    private val appCtx: Context = context.applicationContext   // NVE-search

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE docs(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE, name TEXT, size INTEGER, mtime INTEGER, pages INTEGER, locked INTEGER NOT NULL DEFAULT 0, indexed INTEGER)")
        db.execSQL("CREATE TABLE parts(id INTEGER PRIMARY KEY AUTOINCREMENT, doc INTEGER NOT NULL, page INTEGER NOT NULL, kind INTEGER NOT NULL, raw TEXT NOT NULL)")
        db.execSQL("CREATE INDEX parts_doc ON parts(doc)")
        db.execSQL("CREATE VIRTUAL TABLE parts_fts USING fts4(body)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS parts_fts"); db.execSQL("DROP TABLE IF EXISTS parts"); db.execSQL("DROP TABLE IF EXISTS docs")
        onCreate(db)
    }

    /** True when [file] is not indexed yet or changed since (size / modification time). */
    fun needsIndex(file: File): Boolean = readableDatabase.rawQuery("SELECT size, mtime FROM docs WHERE path = ?", arrayOf(file.absolutePath)).use { c ->
        !c.moveToFirst() || c.getLong(0) != file.length() || c.getLong(1) != file.lastModified()
    }

    /** Replaces the index entry of [file]. [locked] files (password) are recorded without content. */
    fun put(file: File, pages: Int, parts: List<DocPart>, locked: Boolean) {
        val db = writableDatabase
        val opts = com.smartdocs.editor.ui.nve.search.NveIndexPrefs.load(appCtx)   // NVE-search: stop words / numbers
        db.beginTransaction()
        try {
            deleteLocked(db, file.absolutePath)
            val id = db.insert("docs", null, ContentValues().apply {
                put("path", file.absolutePath); put("name", file.name); put("size", file.length()); put("mtime", file.lastModified())
                put("pages", pages); put("locked", if (locked) 1 else 0); put("indexed", System.currentTimeMillis())
            })
            for (p in parts) {
                if (p.text.isBlank()) continue
                val pid = db.insert("parts", null, ContentValues().apply { put("doc", id); put("page", p.page); put("kind", p.kind.ordinal); put("raw", p.text) })
                db.insert("parts_fts", null, ContentValues().apply { put("rowid", pid); put("body", com.smartdocs.editor.core.nve.NveSearchCore.filterBody(IndexText.body(p.text), opts)) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(path: String) {
        val db = writableDatabase
        db.beginTransaction()
        try { deleteLocked(db, path); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    /** The indexed file at [from] is now [to] (renamed / moved, same content): keep its entry (audit DI-22). */
    fun movePath(from: String, to: File) {
        if (from == to.absolutePath) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteLocked(db, to.absolutePath)
            db.update("docs", ContentValues().apply { put("path", to.absolutePath); put("name", to.name); put("mtime", to.lastModified()) }, "path = ?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun deleteLocked(db: SQLiteDatabase, path: String) {
        val id = db.rawQuery("SELECT id FROM docs WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return
        db.execSQL("DELETE FROM parts_fts WHERE rowid IN (SELECT id FROM parts WHERE doc = ?)", arrayOf<Any>(id))
        db.execSQL("DELETE FROM parts WHERE doc = ?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM docs WHERE id = ?", arrayOf<Any>(id))
    }

    /** Drops entries whose file no longer exists. Returns how many were removed. */
    fun prune(): Int {
        val gone = readableDatabase.rawQuery("SELECT path FROM docs", null).use { c ->
            val l = ArrayList<String>(); while (c.moveToNext()) { val p = c.getString(0); if (!File(p).exists()) l += p }; l
        }
        gone.forEach { remove(it) }
        return gone.size
    }

    fun clear() { val db = writableDatabase; db.execSQL("DELETE FROM parts_fts"); db.execSQL("DELETE FROM parts"); db.execSQL("DELETE FROM docs") }

    fun stats(): IndexStats = readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM docs), (SELECT COUNT(*) FROM docs WHERE locked = 1), (SELECT COUNT(*) FROM parts)", null).use { c ->
        if (c.moveToFirst()) IndexStats(c.getInt(0), c.getInt(1), c.getInt(2)) else IndexStats(0, 0, 0)
    }

    /** Paths of files recorded as password protected (indexed without content). */
    fun lockedFiles(): List<String> = readableDatabase.rawQuery("SELECT path FROM docs WHERE locked = 1", null).use { c ->
        val l = ArrayList<String>(); while (c.moveToNext()) l += c.getString(0); l
    }

    /**
     * Searches the index. Results are grouped by file (most hits first), each with up to [perFile] snippets.
     * A regex query (or one without words) is verified on every stored part, up to [scanLimit] parts.
     */
    suspend fun search(query: String, options: SearchOptions, kinds: Set<PartKind> = PartKind.values().toSet(), perFile: Int = 20, maxFiles: Int = 200, scanLimit: Int = 30_000): List<FileResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || kinds.isEmpty()) return@withContext emptyList()
        // forward terms plus the reversed terms of visual-order Arabic, united (audit AN-15)
        val fts = IndexText.ftsQueries(com.smartdocs.editor.core.nve.NveSearchCore.stripQuery(query, com.smartdocs.editor.ui.nve.search.NveIndexPrefs.load(appCtx)), options.mode)   // NVE-search
        val kindList = kinds.joinToString(",") { it.ordinal.toString() }
        val sql = if (fts.isNotEmpty()) {
            val match = fts.joinToString(" UNION ") { "SELECT rowid FROM parts_fts WHERE parts_fts MATCH ?" }
            "SELECT p.doc, p.page, p.kind, p.raw, d.path, d.name, d.pages FROM parts p JOIN docs d ON d.id = p.doc WHERE p.id IN ($match) AND p.kind IN ($kindList) ORDER BY p.doc, p.page LIMIT $scanLimit"
        } else "SELECT p.doc, p.page, p.kind, p.raw, d.path, d.name, d.pages FROM parts p JOIN docs d ON d.id = p.doc WHERE p.kind IN ($kindList) ORDER BY p.doc, p.page LIMIT $scanLimit"
        val args = if (fts.isNotEmpty()) fts.toTypedArray() else null
        val groups = LinkedHashMap<Long, Triple<String, String, Int>>()
        val hits = HashMap<Long, MutableList<IndexHit>>()
        val totals = HashMap<Long, Int>()
        runCatching {
            readableDatabase.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val doc = c.getLong(0); val raw = c.getString(3)
                    val ranges = TextMatcher.find(raw, query, options)
                    if (ranges.isEmpty()) continue
                    groups.getOrPut(doc) { Triple(c.getString(4), c.getString(5) ?: "", c.getInt(6)) }
                    totals[doc] = (totals[doc] ?: 0) + ranges.size
                    val list = hits.getOrPut(doc) { ArrayList() }
                    for (r in ranges) {
                        if (list.size >= perFile) break
                        list += snippet(raw, r, c.getInt(1), PartKind.values().getOrElse(c.getInt(2)) { PartKind.TEXT })
                    }
                }
            }
        }
        groups.entries.map { (id, g) -> FileResult(g.first, g.second, g.third, hits[id].orEmpty(), totals[id] ?: 0) }
            .sortedByDescending { it.total }.take(maxFiles)
    }

    private fun snippet(raw: String, r: IntRange, page: Int, kind: PartKind, pad: Int = 40): IndexHit {
        val s = (r.first - pad).coerceAtLeast(0); val e = (r.last + 1 + pad).coerceAtMost(raw.length)
        val text = raw.substring(s, e).replace('\r', ' ').replace('\n', ' ')
        return IndexHit(page, kind, (if (s > 0) "…" else "") + text + (if (e < raw.length) "…" else ""), listOf((r.first - s + (if (s > 0) 1 else 0))..(r.last - s + (if (s > 0) 1 else 0))))
    }

    companion object {
        const val DB_NAME = "search_index.db"
        @Volatile private var instance: FullTextIndex? = null
        fun get(context: Context): FullTextIndex = instance ?: synchronized(this) { instance ?: FullTextIndex(context).also { instance = it } }

        internal val progressState = MutableStateFlow(IndexProgress())
        /** Live progress of the indexer (observed by the global search screen). */
        val progressFlow: StateFlow<IndexProgress> get() = progressState
    }
}
