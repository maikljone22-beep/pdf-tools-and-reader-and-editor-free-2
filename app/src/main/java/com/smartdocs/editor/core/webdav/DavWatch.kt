package com.smartdocs.editor.core.webdav

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * fc-sync (WebDAV): notices that a server file behind a working copy changed, without downloading or listing it.
 *
 * While the app is open and WebDAV links exist, every [INTERVAL_MS] (back-off on errors up to [MAX_BACKOFF_MS]):
 *  1. one Depth-0 PROPFIND per folder that holds linked files asks only for its getctag / ETag ([DavClient.versions]);
 *     an unchanged folder tag means nothing inside changed, so the check stops there;
 *  2. when the folder tag moved (or the server has none), one Depth-1 PROPFIND reads the children's ETags.
 * A linked file whose server ETag differs from the one recorded at download / upload is listed in [changed]; the
 * WebDAV browser marks it "changed on the server" and opening it downloads the new version (its normal open rule:
 * [DavLinks.openAction]). Dirty copies are left alone: their upload uses If-Match and keeps both versions.
 */
object DavWatch {
    const val INTERVAL_MS = 5 * 60_000L
    const val MAX_BACKOFF_MS = 30 * 60_000L

    private val _changed = MutableStateFlow<Set<String>>(emptySet())
    /** Normalized URLs of linked files that changed on the server since they were downloaded / uploaded here. */
    val changed: StateFlow<Set<String>> = _changed
    private var job: Job? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("webdav_watch", Context.MODE_PRIVATE)

    /** Pure: linked files ([links]: url → ETag recorded here) whose server ETag ([server]) is known and different. */
    fun changedLinks(links: Map<String, String?>, server: Map<String, String>): Set<String> =
        links.filter { (url, mine) -> server[url]?.let { bare(it) != mine?.let(::bare) } == true }.keys

    /** ETags compared without the weak prefix and quotes (GET headers and PROPFIND bodies differ in quoting). */
    fun bare(etag: String): String = etag.trim().removePrefix("W/").trim('"')

    /** Pure: next delay after [errors] failures in a row. */
    fun nextDelay(errors: Int): Long = if (errors <= 0) INTERVAL_MS else (INTERVAL_MS shl (errors - 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)

    @Synchronized fun start(c: Context, scope: CoroutineScope, foreground: () -> Boolean) {
        val a = c.applicationContext
        if (job?.isActive == true) return
        if (DavLinks.all(a).isEmpty()) return   // nothing linked: no requests at all
        job = scope.launch {
            var errors = 0
            while (coroutineContext.isActive && foreground()) {
                if (DavLinks.all(a).isEmpty()) break
                errors = if (runCatching { checkOnce(a) }.isSuccess) 0 else errors + 1
                delay(nextDelay(errors))
            }
        }
    }

    @Synchronized fun stop() { job?.cancel(); job = null }

    /** One round over every place that has links. */
    fun checkOnce(c: Context) {
        if (!com.smartdocs.editor.core.data.NpfNet.allowed()) return
        val folderTags = runCatching { JSONObject(prefs(c).getString("tags", "{}")!!) }.getOrDefault(JSONObject())
        val found = HashSet<String>(_changed.value)
        var failure: Exception? = null
        val byPlace = DavLinks.all(c).values.filter { !it.dirty }.groupBy { it.placeId }
        for ((placeId, links) in byPlace) {
            val place = DavPlaces.get(c, placeId) ?: continue
            val client = DavClient(place)
            for ((folder, inFolder) in links.groupBy { DavXml.parentOf(it.url) }) {
                try {
                    val key = DavXml.normalize(folder)
                    val tag = client.versions(folder, "0")[key]
                    val mine = inFolder.associate { DavXml.normalize(it.url) to it.etag }
                    // folder unchanged: nothing inside changed (flags drop when a copy is downloaded / uploaded: [clear])
                    if (tag != null && tag == folderTags.optString(key).ifEmpty { null }) continue
                    val server = client.versions(folder, "1")
                    val diff = changedLinks(mine, server)
                    found.removeAll(mine.keys - diff)
                    found.addAll(diff)
                    if (tag != null) folderTags.put(key, tag)
                } catch (e: Exception) { failure = e }
            }
        }
        prefs(c).edit().putString("tags", folderTags.toString()).putString("seen", JSONObject().apply { found.forEach { put(it, true) } }.toString()).apply()
        _changed.value = found
        failure?.let { throw it }
    }

    /** Restores the last flags after a restart (no request). */
    fun load(c: Context) {
        val o = runCatching { JSONObject(prefs(c).getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        _changed.value = o.keys().asSequence().toSet()
    }

    /** True when [url] (a linked file) changed on the server and its working copy is not refreshed yet. */
    fun isChanged(url: String): Boolean = DavXml.normalize(url) in _changed.value

    /** The copy of [url] was just downloaded / uploaded: drop its flag. */
    fun clear(c: Context, url: String) {
        val key = DavXml.normalize(url)
        if (key !in _changed.value) return
        _changed.value = _changed.value - key
        prefs(c).edit().putString("seen", JSONObject().apply { _changed.value.forEach { put(it, true) } }.toString()).apply()
    }
}
