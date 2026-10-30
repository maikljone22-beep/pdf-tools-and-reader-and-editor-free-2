package com.smartdocs.editor.core.errors

import android.content.Context
import android.os.Build
import com.smartdocs.editor.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.MessageDigest

/**
 * Error centre: every crash and every failure the app reports is kept on the device (up to [MAX] distinct errors,
 * repeats counted), so the user can read, export or send them, and new ones can be sent automatically to the
 * developer's error server ([ErrorReporter]), which files them as GitHub issues.
 *
 * Privacy: only the error itself is kept (exception type, message, stack trace, the app area, app version, device
 * model, Android version). No document names, document text, file paths or account data. Messages are scrubbed
 * of paths, e-mail addresses and long numbers ([scrub]).
 *
 * Never throws; safe to call from the crash handler and from any thread.
 */
object ErrorCenter {

    data class Entry(
        val id: String,                 // fingerprint: same error in the same place = same id
        val kind: String,               // CRASH, ERROR
        val area: String,               // where in the app ("reader", "tool:merge", "crash" …)
        val title: String,              // exception type + short message
        val detail: String,             // scrubbed stack trace
        val firstAt: Long,
        val lastAt: Long,
        val count: Int,
        val version: String,
        val device: String,
        val sentAt: Long = 0L,          // 0 = not sent yet
        val sentCount: Int = 0,         // count at the last send (a later repeat is sent again)
    ) {
        val unsent: Boolean get() = sentAt == 0L || count > sentCount
    }

    const val MAX = 200
    private const val FILE = "errors/errors.json"
    @Volatile private var appDir: File? = null
    /** Called after a new error or a repeat was stored (the app schedules the automatic send). */
    @Volatile var onNew: (() -> Unit)? = null
    private val lock = Any()

    fun install(context: Context) { appDir = context.applicationContext.filesDir }

    private fun file(): File? = appDir?.let { File(it, FILE) }

    fun all(): List<Entry> = synchronized(lock) { read() }.sortedByDescending { it.lastAt }

    fun unsentCount(): Int = all().count { it.unsent }

    /** A crash or an exception caught somewhere in the app. */
    fun record(t: Throwable, area: String, crash: Boolean = false) = runCatching {
        val trace = scrub(t.stackTraceToString()).lines().take(60).joinToString("\n")
        val title = scrub("${t.javaClass.simpleName}: ${t.message.orEmpty()}").take(160)
        add(if (crash) "CRASH" else "ERROR", area, title, trace, fingerprint(t, area))
    }.let { }

    /** A failure known only by its message (no exception object at hand). */
    fun recordMessage(message: String, area: String) = runCatching {
        val m = scrub(message).take(300)
        if (m.isBlank()) return@runCatching
        add("ERROR", area, m.take(160), m, sha("$area|${m.replace(Regex("\\d+"), "#")}"))
    }.let { }

    fun markSent(ids: Collection<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        write(read().map { if (it.id in ids) it.copy(sentAt = now, sentCount = it.count) else it })
    }

    fun delete(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    fun clear() = synchronized(lock) { file()?.delete(); Unit }

    /** One text report of [entries] for sharing (e-mail, WhatsApp, a file). */
    fun report(entries: List<Entry>): String = buildString {
        append("SmartDocs error report — ${entries.size} error(s)\n")
        append("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${device()}\n\n")
        entries.forEach { e ->
            append("=== [${e.kind}] ${e.title}\n")
            append("id ${e.id} · area ${e.area} · ×${e.count} · app ${e.version}\n")
            append(e.detail).append("\n\n")
        }
    }

    fun exportJson(entries: List<Entry>): String = JsonArray(entries.map { toJson(it) }).toString()

    private fun toJson(e: Entry) = JsonObject(mapOf(
        "id" to JsonPrimitive(e.id), "kind" to JsonPrimitive(e.kind), "area" to JsonPrimitive(e.area), "title" to JsonPrimitive(e.title),
        "detail" to JsonPrimitive(e.detail), "firstAt" to JsonPrimitive(e.firstAt), "lastAt" to JsonPrimitive(e.lastAt),
        "count" to JsonPrimitive(e.count), "version" to JsonPrimitive(e.version), "device" to JsonPrimitive(e.device),
        "sentAt" to JsonPrimitive(e.sentAt), "sentCount" to JsonPrimitive(e.sentCount),
    ))

    private fun fromJson(o: JsonObject): Entry {
        fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
        return Entry(s("id"), s("kind"), s("area"), s("title"), s("detail"), o["firstAt"]?.jsonPrimitive?.long ?: 0L,
            o["lastAt"]?.jsonPrimitive?.long ?: 0L, o["count"]?.jsonPrimitive?.int ?: 1, s("version"), s("device"),
            o["sentAt"]?.jsonPrimitive?.long ?: 0L, o["sentCount"]?.jsonPrimitive?.int ?: 0)
    }

    // ------------------------------------------------------------------ internals

    private fun add(kind: String, area: String, title: String, detail: String, id: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = read().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            val e = list[i]
            list[i] = e.copy(lastAt = now, count = e.count + 1, version = BuildConfig.VERSION_NAME, kind = if (kind == "CRASH") kind else e.kind)
        } else {
            list += Entry(id, kind, area, title, detail, now, now, 1, BuildConfig.VERSION_NAME, device())
        }
        write(list.sortedByDescending { it.lastAt }.take(MAX))
        runCatching { onNew?.invoke() }
    }

    private fun read(): List<Entry> = runCatching {
        val f = file() ?: return emptyList()
        if (!f.exists()) emptyList() else Json.parseToJsonElement(f.readText()).jsonArray.map { fromJson(it.jsonObject) }
    }.getOrDefault(emptyList())

    private fun write(list: List<Entry>) {
        runCatching {
            val f = file() ?: return
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(exportJson(list))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Same exception type + same first app frame + same area = same error. */
    fun fingerprint(t: Throwable, area: String): String {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frame = (root.stackTrace.firstOrNull { it.className.startsWith("com.smartdocs") } ?: root.stackTrace.firstOrNull())
            ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return sha("$area|${root.javaClass.name}|$frame")
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** Removes personal data from a message or a trace: file paths, content URIs, e-mail addresses, long numbers. */
    fun scrub(s: String): String = s
        .replace(Regex("content://\\S+"), "content://…")
        .replace(Regex("file://\\S+"), "file://…")
        .replace(Regex("(/storage|/sdcard|/data/user|/data/data)/\\S+"), "<path>")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")
        .replace(Regex("\\b\\d{7,}\\b"), "<number>")
}
