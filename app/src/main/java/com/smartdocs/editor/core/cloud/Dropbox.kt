package com.smartdocs.editor.core.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Dropbox through API v2, signed in with [OAuthPkce].
 * Folder ids are lower-case paths ("" = root); file ids are Dropbox "id:…" values with [CloudItem.path] set.
 */
class Dropbox private constructor(private val context: Context) : CloudProvider {
    override val id = ProviderId.DROPBOX
    override val rootId = ""
    private val tokens = OAuthTokens(context, id.key)

    companion object {
        private const val API = "https://api.dropboxapi.com/2"
        private const val CONTENT = "https://content.dropboxapi.com/2"
        private const val MAX_SIMPLE_UPLOAD = 150L * 1024 * 1024

        @Volatile private var instance: Dropbox? = null
        fun get(context: Context): Dropbox = instance ?: synchronized(this) { instance ?: Dropbox(context.applicationContext).also { instance = it } }

        /** Dropbox-API-Arg must be ASCII: escape everything else as \uXXXX. */
        fun headerJson(o: JSONObject): String {
            val s = o.toString(); val sb = StringBuilder(s.length + 16)
            for (c in s) if (c.code < 0x7f) sb.append(c) else sb.append(String.format("\\u%04x", c.code))
            return sb.toString()
        }
    }

    override fun isConfigured() = CloudConfig.dropboxAppKey.isNotEmpty()
    override fun isConnected() = tokens.connected
    val account: String? get() = tokens.account

    private suspend fun <T> authed(block: (String) -> T): T = withContext(Dispatchers.IO) {
        try { block(OAuthPkce.accessToken(context, id)) } catch (e: AuthRequiredException) { block(OAuthPkce.accessToken(context, id, force = true)) }
    }

    private fun rpc(path: String, body: JSONObject?, t: String): JSONObject {
        val b = Request.Builder().url("$API$path").header("Authorization", "Bearer $t")
        // endpoints without arguments want an empty body with no content type
        if (body == null) b.post(ByteArray(0).toRequestBody(null)) else b.post(body.toString().toRequestBody(CloudHttp.JSON))
        return JSONObject(CloudHttp.string(b.build()).ifBlank { "{}" })
    }

    private fun parse(o: JSONObject): CloudItem {
        val folder = o.optString(".tag") == "folder"
        val pathDisplay = o.optString("path_display")
        val parentPath = pathDisplay.substringBeforeLast('/', "").lowercase()
        return CloudItem(
            id = if (folder) o.optString("path_lower") else o.optString("id"),
            name = o.optString("name"), isFolder = folder, size = o.optLong("size"),
            modified = o.optString("server_modified").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            mime = if (folder) null else mimeOf(o.optString("name")), parentId = parentPath, path = pathDisplay,
        )
    }

    suspend fun loadAccount() = authed { t ->
        val me = rpc("/users/get_current_account", null, t)
        tokens.account = me.optString("email").ifEmpty { me.optJSONObject("name")?.optString("display_name") }
    }

    override suspend fun list(folderId: String?): List<CloudItem> = authed { t ->
        val out = ArrayList<CloudItem>()
        var json = rpc("/files/list_folder", JSONObject().put("path", folderId ?: rootId).put("limit", 500), t)
        while (true) {
            val arr = json.optJSONArray("entries")
            if (arr != null) for (i in 0 until arr.length()) arr.getJSONObject(i).takeIf { it.optString(".tag") != "deleted" }?.let { out += parse(it) }
            if (!json.optBoolean("has_more") || out.size >= 2000) break
            json = rpc("/files/list_folder/continue", JSONObject().put("cursor", json.getString("cursor")), t)
        }
        out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    override suspend fun search(query: String): List<CloudItem> = authed { t ->
        val json = rpc("/files/search_v2", JSONObject().put("query", query).put("options", JSONObject().put("max_results", 200)), t)
        val arr = json.optJSONArray("matches")
        val out = ArrayList<CloudItem>()
        if (arr != null) for (i in 0 until arr.length()) {
            arr.getJSONObject(i).optJSONObject("metadata")?.optJSONObject("metadata")?.let { out += parse(it) }
        }
        out
    }

    override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) = authed { t ->
        val arg = headerJson(JSONObject().put("path", item.id))
        val r = Request.Builder().url("$CONTENT/files/download").header("Authorization", "Bearer $t").header("Dropbox-API-Arg", arg)
            .post(ByteArray(0).toRequestBody(null)).build()
        CloudHttp.download(r, dest, onProgress)
    }

    private fun put(local: File, path: String, overwrite: Boolean, t: String): CloudItem {
        if (local.length() > MAX_SIMPLE_UPLOAD) throw IllegalStateException("Dropbox: file larger than 150 MB")
        val arg = JSONObject().put("path", path).put("mode", if (overwrite) "overwrite" else "add").put("autorename", !overwrite).put("mute", true)
        val r = Request.Builder().url("$CONTENT/files/upload").header("Authorization", "Bearer $t").header("Dropbox-API-Arg", headerJson(arg))
            .post(local.asRequestBody(CloudHttp.OCTET)).build()
        val o = JSONObject(CloudHttp.string(r))
        if (!o.has(".tag")) o.put(".tag", "file")
        return parse(o)
    }

    override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = authed { t ->
        val parent = (parentId ?: rootId).trimEnd('/')
        put(local, "$parent/$name", overwrite = false, t = t)
    }

    override suspend fun update(item: CloudItem, local: File): CloudItem = authed { t ->
        put(local, item.path ?: throw IllegalStateException("no path"), overwrite = true, t = t)
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudItem = authed { t ->
        val parent = (parentId ?: rootId).trimEnd('/')
        val json = rpc("/files/create_folder_v2", JSONObject().put("path", "$parent/$name").put("autorename", true), t)
        val meta = json.getJSONObject("metadata"); meta.put(".tag", "folder")
        parse(meta)
    }

    override suspend fun delete(item: CloudItem) = authed { t -> rpc("/files/delete_v2", JSONObject().put("path", item.path ?: item.id), t); Unit }

    override suspend fun metadataOf(item: CloudItem): CloudItem? = authed { t ->
        try { parse(rpc("/files/get_metadata", JSONObject().put("path", item.id), t)) }
        catch (e: HttpException) { if (e.code == 409) null else throw e }
    }

    override val supportsLinks: Boolean get() = true

    /** Public shared link (needs the `sharing.write` + `sharing.read` scopes in the Dropbox App Console). */
    override suspend fun shareLink(item: CloudItem, anyone: Boolean): String = authed { t ->
        val path = item.path ?: item.id
        // owner-only web address (people who already have access)
        if (!anyone && item.path != null) return@authed "https://www.dropbox.com/preview" + item.path.split('/').joinToString("/") { android.net.Uri.encode(it) }
        val url = try {
            val settings = JSONObject().put("requested_visibility", "public")
            rpc("/sharing/create_shared_link_with_settings", JSONObject().put("path", path).put("settings", settings), t).optString("url")
        } catch (e: HttpException) {
            if (e.code != 409) throw e
            val links = rpc("/sharing/list_shared_links", JSONObject().put("path", path).put("direct_only", true), t).optJSONArray("links")
            links?.optJSONObject(0)?.optString("url") ?: throw e
        }
        url.ifEmpty { throw IllegalStateException("no link") }
    }

    override suspend fun changeCursor(): String? = authed { t ->
        rpc("/files/list_folder/get_latest_cursor", JSONObject().put("path", "").put("recursive", true), t).optString("cursor").ifEmpty { null }
    }

    override suspend fun changesSince(cursor: String): Pair<Boolean, String> = authed { t ->
        var c = cursor
        var changed = false
        try {
            for (round in 0 until 20) {
                val o = rpc("/files/list_folder/continue", JSONObject().put("cursor", c), t)
                if ((o.optJSONArray("entries")?.length() ?: 0) > 0) changed = true
                c = o.optString("cursor").ifEmpty { c }
                if (!o.optBoolean("has_more")) break
            }
            changed to c
        } catch (e: HttpException) {
            if (e.code == 409)
                true to rpc("/files/list_folder/get_latest_cursor", JSONObject().put("path", "").put("recursive", true), t).optString("cursor").ifEmpty { cursor }
            else throw e
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { authed { t -> rpc("/auth/token/revoke", null, t) } } }
        tokens.clear()
    }
}
