package com.smartdocs.editor.core.redact

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * audit 5 R-L7: the unsaved marks of the redaction tool survive process death. A small JSON per source document
 * (keyed by its canonical path) in the app's private files dir. A draft is ignored (and deleted) when the
 * document changed since (size / modification time).
 */
object RedactDrafts {
    data class Draft(val marks: List<RedactMark>, val excluded: Set<String>)

    private fun dir(c: Context) = File(c.filesDir, "redact_drafts")

    private fun path(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private fun fileFor(c: Context, src: File): File {
        val h = MessageDigest.getInstance("SHA-256").digest(path(src).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir(c), h.take(32) + ".json")
    }

    fun clear(c: Context, src: File) { runCatching { fileFor(c, src).delete() } }

    /** Stores [marks] for [src] (an empty list removes the draft). Written via a temp file + rename. */
    fun save(c: Context, src: File, marks: List<RedactMark>, excluded: Set<String>) {
        if (marks.isEmpty()) { clear(c, src); return }
        val o = JSONObject()
            .put("path", path(src)).put("length", src.length()).put("modified", src.lastModified())
            .put("marks", JSONArray().apply { marks.forEach { put(markToJson(it)) } })
            .put("excluded", JSONArray(excluded.filter { id -> marks.any { it.id == id } }))
        val out = fileFor(c, src)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeText(o.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() } }
    }

    /** The draft of [src], or null (none, unreadable, or the document changed since). */
    fun load(c: Context, src: File): Draft? {
        val f = fileFor(c, src)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            if (o.optString("path") != path(src) || o.optLong("length") != src.length() || o.optLong("modified") != src.lastModified()) {
                f.delete(); return null
            }
            val a = o.getJSONArray("marks")
            val marks = (0 until a.length()).map { markFromJson(a.getJSONObject(it)) }
            val ex = o.optJSONArray("excluded")?.let { e -> (0 until e.length()).map { e.getString(it) }.toSet() }.orEmpty()
            Draft(marks, ex)
        }.getOrNull()
    }

    private fun markToJson(m: RedactMark) = JSONObject().apply {
        put("id", m.id); put("page", m.page); put("label", m.label); put("whole", m.wholePage)
        put("author", m.author); put("subject", m.subject); put("locked", m.locked)
        put("style", RedactPrefs.styleToJson(m.style))
        put("rects", JSONArray().apply { m.rects.forEach { r -> put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())) } })
        put("reviews", JSONArray().apply { m.reviews.forEach { r -> put(JSONObject().put("state", r.state).put("author", r.author).put("time", r.time)) } })
    }

    private fun markFromJson(o: JSONObject): RedactMark {
        val ra = o.optJSONArray("rects") ?: JSONArray()
        val rects = (0 until ra.length()).map { i ->
            val r = ra.getJSONArray(i)
            RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
        }
        val rv = o.optJSONArray("reviews") ?: JSONArray()
        val reviews = (0 until rv.length()).map { i -> rv.getJSONObject(i).let { RedactReview(it.optString("state"), it.optString("author"), it.optLong("time")) } }
        return RedactMark(
            id = o.getString("id"), page = o.getInt("page"), rects = rects, style = RedactPrefs.styleFromJson(o.optJSONObject("style")),
            label = o.optString("label", ""), wholePage = o.optBoolean("whole", false), author = o.optString("author", ""),
            subject = o.optString("subject", ""), locked = o.optBoolean("locked", false), reviews = reviews,
        )
    }
}
