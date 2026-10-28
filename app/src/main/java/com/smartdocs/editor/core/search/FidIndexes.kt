package com.smartdocs.editor.core.search

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * fix/files-index (catalog indexes): one search index definition. [id] names its database file, [title] is the
 * catalog title shown in the search screen, [folders] are user-picked include folders (SAF tree uris or plain paths)
 * and [appSources] adds the app's documents folder, recent files and the device PDF scan (the classic app index).
 */
data class IndexDef(val id: String, val title: String = "", val folders: List<String> = emptyList(), val appSources: Boolean = true)

/**
 * The named search indexes (prefs "fid_indexes"). The [DEFAULT] index is the app index of before (database
 * `search_index.db`); every other index has its own database `search_index_<id>.db`. [selected] are the indexes the
 * global search looks in. Encoding is pure (unit tested).
 */
object FidIndexes {
    const val DEFAULT = "default"
    private const val FILE = "fid_indexes"

    fun dbName(id: String) = if (id == DEFAULT) FullTextIndex.DB_NAME else "search_index_$id.db"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** One line per index: id, title, app sources flag, folders (each URL-encoded, tab / comma separated). */
    fun encode(list: List<IndexDef>): String = list.joinToString("\n") { d ->
        listOf(enc(d.id), enc(d.title), if (d.appSources) "1" else "0", d.folders.joinToString(",") { enc(it) }).joinToString("\t")
    }

    /** Inverse of [encode]; the default index is always present and first. */
    fun decode(s: String?): List<IndexDef> {
        val out = ArrayList<IndexDef>()
        s.orEmpty().split('\n').forEach { line ->
            val f = line.split('\t')
            val id = dec(f.getOrNull(0).orEmpty()).takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' } } ?: return@forEach
            if (out.any { it.id == id }) return@forEach
            out += IndexDef(id, dec(f.getOrNull(1).orEmpty()), f.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.map(::dec), f.getOrNull(2) != "0")
        }
        if (out.none { it.id == DEFAULT }) out.add(0, IndexDef(DEFAULT)) else out.sortBy { if (it.id == DEFAULT) 0 else 1 }
        return out
    }

    /** A new id that is not in [taken] (base-36 time stamp). */
    fun newId(taken: Set<String>, now: Long = System.currentTimeMillis()): String {
        var n = now
        while (true) { val id = "i" + java.lang.Long.toString(n, 36); if (id !in taken) return id; n++ }
    }

    /** Valid selection: unknown ids dropped, the default index when nothing is left. */
    fun cleanSelection(sel: Set<String>, defs: List<IndexDef>): Set<String> = sel.filter { id -> defs.any { it.id == id } }.toSet().ifEmpty { setOf(DEFAULT) }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<IndexDef> = decode(p(ctx).getString("defs", null))

    fun get(ctx: Context, id: String): IndexDef? = list(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, list: List<IndexDef>) { p(ctx).edit().putString("defs", encode(list)).apply() }

    fun put(ctx: Context, d: IndexDef) {
        val l = list(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == d.id }
        if (i >= 0) l[i] = d else l += d
        save(ctx, l)
    }

    fun add(ctx: Context, title: String): IndexDef {
        val l = list(ctx)
        val d = IndexDef(newId(l.map { it.id }.toSet()), title, emptyList(), appSources = false)
        save(ctx, l + d)
        return d
    }

    /** Removes a named index and its database (the default index cannot be removed). */
    fun delete(ctx: Context, id: String) {
        if (id == DEFAULT) return
        save(ctx, list(ctx).filter { it.id != id })
        setSelected(ctx, selected(ctx) - id)
        FullTextIndex.forget(id)
        runCatching { ctx.applicationContext.deleteDatabase(dbName(id)) }
    }

    fun selected(ctx: Context): Set<String> = cleanSelection(p(ctx).getStringSet("selected", null) ?: setOf(DEFAULT), list(ctx))

    fun setSelected(ctx: Context, s: Set<String>) { p(ctx).edit().putStringSet("selected", s.toSet()).apply() }

    /** Same file in several selected indexes: kept once, with the most hits. Most hits first. */
    fun merge(results: List<List<FileResult>>, maxFiles: Int = 200): List<FileResult> =
        results.flatten().groupBy { it.path }.values.map { g -> g.maxByOrNull { it.total }!! }.sortedByDescending { it.total }.take(maxFiles)
}
