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
    private val space = context.getSharedPreferences("dropbox_space", Context.MODE_PRIVATE)

    companion object {
        private const val API = "https://api.dropboxapi.com/2"
        private const val CONTENT = "https://content.dropboxapi.com/2"
        private const val NOTIFY = "https://notify.dropboxapi.com/2"
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

    /** fc-sync (Foxit §32 Team Folders): the account belongs to a team space with a root of its own. */
    val teamSpaceAvailable: Boolean get() = space.getString("root", "").orEmpty().isNotEmpty()
    /** Team space shown as the root (team folders + the member folder) instead of the member folder alone. */
    val teamSpaceOn: Boolean get() = teamSpaceAvailable && space.getBoolean("on", false)
    val teamName: String? get() = space.getString("team", null)

    /** Switches the root. The caller resets the change cursor; sync pairs must not exist (their paths would move). */
    fun setTeamSpace(on: Boolean) { space.edit().putBoolean("on", on).putBoolean("toggled", true).apply() }

    /** `Dropbox-API-Path-Root` header value while the team space is on. */
    private fun pathRoot(): String? = if (teamSpaceOn) DropboxSpace.header(space.getString("root", "").orEmpty()) else null

    private fun Request.Builder.space(): Request.Builder = apply { pathRoot()?.let { header("Dropbox-API-Path-Root", it) } }

    override fun isConfigured() = CloudConfig.dropboxAppKey.isNotEmpty()
    override fun isConnected() = tokens.connected
    val account: String? get() = tokens.account

    private suspend fun <T> authed(block: (String) -> T): T = withContext(Dispatchers.IO) {
        try { block(OAuthPkce.accessToken(context, id)) } catch (e: AuthRequiredException) { block(OAuthPkce.accessToken(context, id, force = true)) }
    }

    private fun rpc(path: String, body: JSONObject?, t: String): JSONObject {
        val b = Request.Builder().url("$API$path").header("Authorization", "Bearer $t").space()
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
        val root = DropboxSpace.teamRoot(me)
        space.edit().putString("root", root ?: "").putString("team", me.optJSONObject("team")?.optString("name")).apply()
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
        val r = Request.Builder().url("$CONTENT/files/download").header("Authorization", "Bearer $t").header("Dropbox-API-Arg", arg).space()
            .post(ByteArray(0).toRequestBody(null)).build()
        CloudHttp.download(r, dest, onProgress)
    }

    private fun put(local: File, path: String, overwrite: Boolean, t: String): CloudItem {
        if (local.length() > MAX_SIMPLE_UPLOAD) throw IllegalStateException("Dropbox: file larger than 150 MB")
        val arg = JSONObject().put("path", path).put("mode", if (overwrite) "overwrite" else "add").put("autorename", !overwrite).put("mute", true)
        val r = Request.Builder().url("$CONTENT/files/upload").header("Authorization", "Bearer $t").header("Dropbox-API-Arg", headerJson(arg)).space()
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
        // fc-sync: after a root switch a stored path may be relative to the other root: ask where the file id is now
        val path = if (space.getBoolean("toggled", false) && item.id.startsWith("id:"))
            runCatching { rpc("/files/get_metadata", JSONObject().put("path", item.id), t).optString("path_display").ifEmpty { null } }.getOrNull() ?: item.path
        else item.path
        put(local, path ?: throw IllegalStateException("no path"), overwrite = true, t = t)
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
                val page = ChangeFeeds.dropbox(rpc("/files/list_folder/continue", JSONObject().put("cursor", c), t).toString(), c)
                if (page.changed) changed = true
                c = page.steady ?: page.next ?: c
                if (page.next == null) break
            }
            changed to c
        } catch (e: HttpException) {
            if (e.code == 409)
                true to rpc("/files/list_folder/get_latest_cursor", JSONObject().put("path", "").put("recursive", true), t).optString("cursor").ifEmpty { cursor }
            else throw e
        }
    }

    override val notifies: Boolean get() = true

    /**
     * fc-sync: `list_folder/longpoll` holds the request open until something under the cursor changes (or [maxSeconds]
     * pass). It needs no access token. A `backoff` answer is honoured before returning. A reset cursor (HTTP 400/409)
     * counts as a change, so the caller re-reads the feed and gets a fresh cursor.
     */
    override suspend fun waitForChange(cursor: String, maxSeconds: Int): Boolean? = withContext(Dispatchers.IO) {
        val timeout = maxSeconds.coerceIn(30, 480)
        val client = CloudHttp.client.newBuilder().readTimeout((timeout + 120).toLong(), java.util.concurrent.TimeUnit.SECONDS).build()
        val body = JSONObject().put("cursor", cursor).put("timeout", timeout).toString().toRequestBody(CloudHttp.JSON)
        val r = Request.Builder().url("$NOTIFY/files/list_folder/longpoll").post(body).build()
        val answer = try { ChangeFeeds.dropboxLongPoll(CloudHttp.stringCancellable(client, r)) }
        catch (e: HttpException) { if (e.code == 400 || e.code == 409) return@withContext true else throw e }
        if (answer.backoffSec > 0) kotlinx.coroutines.delay(answer.backoffSec * 1000L)
        answer.changes
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { authed { t -> rpc("/auth/token/revoke", null, t) } } }
        tokens.clear()
        space.edit().clear().apply()
    }
}

/**
 * fc-sync: Dropbox team spaces (pure, unit-tested). A member of a team space has a root namespace (team folders +
 * the member folder) that differs from the home namespace; `users/get_current_account` reports both in `root_info`.
 */
object DropboxSpace {
    /** Root namespace id when it differs from the home namespace (a team space), else null. */
    fun teamRoot(account: JSONObject): String? {
        val ri = account.optJSONObject("root_info") ?: return null
        val root = ri.optString("root_namespace_id").ifEmpty { return null }
        val home = ri.optString("home_namespace_id")
        return root.takeIf { ri.optString(".tag") == "team" && root != home }
    }

    /** Header value that makes paths relative to [rootNs]. */
    fun header(rootNs: String): String? = rootNs.takeIf { it.isNotEmpty() }?.let { JSONObject().put(".tag", "root").put("root", it).toString() }
}
