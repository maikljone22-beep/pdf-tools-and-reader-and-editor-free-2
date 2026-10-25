package com.smartdocs.editor.core.cloud

import android.content.Context
import com.smartdocs.editor.core.collab.CollabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Remembers which cloud item a local working copy came from, so a save in the reader can be uploaded back
 * over the same cloud file. (`app.documents.setOrigin` covers content:// sources; cloud items are tracked here.)
 */
object CloudLinks {
    data class Link(val provider: ProviderId, val item: CloudItem)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("cloud_links", Context.MODE_PRIVATE)

    fun set(context: Context, local: File, provider: ProviderId, item: CloudItem) {
        val o = JSONObject().put("p", provider.key).put("id", item.id).put("name", item.name).put("parent", item.parentId ?: "")
            .put("path", item.path ?: "").put("mime", item.mime ?: "").put("mod", item.modified).put("size", item.size)
        prefs(context).edit().putString(local.absolutePath, o.toString()).apply()
    }

    fun get(context: Context, local: File): Link? {
        val s = prefs(context).getString(local.absolutePath, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Link(ProviderId.of(o.getString("p"))!!, CloudItem(
                id = o.getString("id"), name = o.optString("name"), isFolder = false, size = o.optLong("size"), modified = o.optLong("mod"),
                mime = o.optString("mime").ifEmpty { null }, parentId = o.optString("parent").ifEmpty { null }, path = o.optString("path").ifEmpty { null },
            ))
        }.getOrNull()
    }

    fun remove(context: Context, local: File) = prefs(context).edit().remove(local.absolutePath).apply()

    /** Downloads [item] into the provider's cache folder and records the link. Returns the local file to open in the reader. */
    suspend fun open(context: Context, provider: CloudProvider, item: CloudItem, onProgress: ((Long, Long) -> Unit)? = null): File {
        val dir = Clouds.cacheDir(context, provider.id)
        val name = if (item.mime?.startsWith("application/vnd.google-apps.") == true) "${item.name}.pdf" else item.name
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        // one working copy per cloud item: reuse the same local name for the same id
        val existing = prefs(context).all.entries.firstOrNull { (_, v) -> (v as? String)?.let { runCatching { JSONObject(it).getString("id") == item.id }.getOrDefault(false) } == true }
            ?.key?.let(::File)
        val dest = existing?.takeIf { it.parentFile == dir } ?: uniqueIn(dir, safe)
        provider.download(item, dest, onProgress)
        set(context, dest, provider.id, item)
        return dest
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name); var n = 2
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    /** Uploads the saved local copy over its cloud item. Returns null when the file has no cloud link. */
    suspend fun writeBack(context: Context, local: File): Result<CloudItem>? {
        val link = get(context, local) ?: return null
        val provider = Clouds.provider(context, link.provider)
        return runCatching { provider.update(link.item, local) }.onSuccess { set(context, local, link.provider, it.copy(parentId = it.parentId ?: link.item.parentId)) }
    }
}

/**
 * Hook the reader calls after every successful save (right after `app.documents.writeBack(f)` in ReaderState):
 *
 *     com.smartdocs.editor.core.cloud.SaveHooks.onSaved(context, f)
 *
 * It uploads cloud working copies back to their cloud item and publishes comments of shared-review documents.
 * Runs in the background; results are emitted on [events] (the Cloud screen shows them as messages).
 */
object SaveHooks {
    sealed interface Event {
        data class Uploaded(val file: File, val provider: ProviderId) : Event
        data class UploadFailed(val file: File, val provider: ProviderId, val error: Throwable) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    /** Extra listeners other modules may register (called on a background thread). */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(File) -> Unit>()

    fun onSaved(context: Context, file: File) {
        val app = context.applicationContext
        com.smartdocs.editor.core.review.ReviewPrompt.recordSuccess(app) // a save counts as a successful action
        scope.launch {
            CloudLinks.get(app, file)?.let { link ->
                CloudLinks.writeBack(app, file)?.fold(
                    onSuccess = { _events.tryEmit(Event.Uploaded(file, link.provider)) },
                    onFailure = { _events.tryEmit(Event.UploadFailed(file, link.provider, it)) },
                )
            }
            runCatching { CollabStore.onDocumentSaved(app, file) }
            listeners.forEach { l -> runCatching { l(file) } }
        }
    }
}
