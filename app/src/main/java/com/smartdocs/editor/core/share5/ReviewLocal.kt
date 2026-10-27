package com.smartdocs.editor.core.share5

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local review preferences and bookkeeping (Foxit §31, nav-attach): the new-comments check interval, "work offline",
 * tracker folders, the in-app notifications inbox, and the archive-copy name. Nothing here talks to the server.
 */
object ReviewLocal {
    /** Choices of the automatic check, in minutes (0 = off). WorkManager allows 15 minutes at the shortest. */
    val INTERVALS = listOf(15, 30, 60, 180, 720, 0)
    const val DEFAULT_INTERVAL = 30

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("review_local", Context.MODE_PRIVATE)

    fun interval(c: Context): Int = prefs(c).getInt("interval", DEFAULT_INTERVAL).let { if (it in INTERVALS) it else DEFAULT_INTERVAL }
    fun setInterval(c: Context, minutes: Int) = prefs(c).edit().putInt("interval", minutes).apply()

    /** Work offline: no publishing, no checking, no live comments until the user goes back online. */
    fun offline(c: Context): Boolean = prefs(c).getBoolean("offline", false)
    fun setOffline(c: Context, on: Boolean) = prefs(c).edit().putBoolean("offline", on).apply()

    // ---- tracker folders: review id → folder name ----
    fun folders(c: Context): Map<String, String> = runCatching {
        val o = JSONObject(prefs(c).getString("folders", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun setFolder(c: Context, reviewId: String, folder: String?) {
        val m = folders(c).toMutableMap()
        if (folder.isNullOrBlank()) m.remove(reviewId) else m[reviewId] = folder.trim()
        prefs(c).edit().putString("folders", JSONObject(m as Map<*, *>).toString()).apply()
    }

    /** Folder names in use, sorted. */
    fun folderNames(map: Map<String, String>): List<String> = map.values.distinct().sortedBy { it.lowercase() }

    /** Removes a folder: its reviews go back to the top level. */
    fun removeFolder(c: Context, folder: String) {
        val m = folders(c).filterValues { it != folder }
        prefs(c).edit().putString("folders", JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ---- notifications inbox ----
    data class Note(val reviewId: String, val title: String, val count: Int, val time: Long, val read: Boolean)

    fun inbox(c: Context): List<Note> = runCatching {
        val a = JSONArray(prefs(c).getString("inbox", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Note(it.getString("id"), it.optString("t"), it.optInt("n"), it.optLong("at"), it.optBoolean("r")) } }
    }.getOrDefault(emptyList())

    private fun writeInbox(c: Context, list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.reviewId).put("t", it.title).put("n", it.count).put("at", it.time).put("r", it.read)) }
        prefs(c).edit().putString("inbox", a.toString()).apply()
    }

    /** Pure: a new note goes on top; the list keeps the newest [max]. */
    fun push(list: List<Note>, n: Note, max: Int = 100): List<Note> = (listOf(n) + list).take(max)

    fun addNote(c: Context, reviewId: String, title: String, count: Int) = synchronized(this) {
        writeInbox(c, push(inbox(c), Note(reviewId, title, count, System.currentTimeMillis(), false)))
    }

    fun unread(c: Context): Int = inbox(c).count { !it.read }

    fun markAllRead(c: Context) = synchronized(this) { writeInbox(c, inbox(c).map { it.copy(read = true) }) }

    fun markRead(c: Context, reviewId: String) = synchronized(this) { writeInbox(c, inbox(c).map { if (it.reviewId == reviewId) it.copy(read = true) else it }) }

    fun clearInbox(c: Context) = writeInbox(c, emptyList())

    /** "Title (archive 2026-10-27).pdf": the local copy kept after a review. */
    fun archiveName(title: String, now: Long = System.currentTimeMillis()): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), "_").removeSuffix(".pdf").ifBlank { "review" } + " (archive " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)) + ")"

    /** Plain-text tracker report (Foxit "Create PDF from tracker details"); [lines] one per review. */
    fun reportText(title: String, lines: List<String>, now: Long = System.currentTimeMillis()): String =
        title + "\n" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now)) + "\n\n" + lines.joinToString("\n\n")

}
