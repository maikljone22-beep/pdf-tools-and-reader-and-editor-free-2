package com.smartdocs.editor.core.cloud

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Box through the Content API v2.0, signed in with [OAuthPkce] (authorization code; the client secret stays on the
 * collab worker, see [CloudConfig.boxTokenUrl]). Folder "0" is the root. Files above 50 MB use chunked upload sessions.
 */
class Box private constructor(private val context: Context) : CloudProvider {
    override val id = ProviderId.BOX
    override val rootId = "0"
    private val tokens = OAuthTokens(context, id.key)

    companion object {
        private const val API = "https://api.box.com/2.0"
        private const val UPLOAD = "https://upload.box.com/api/2.0"
        private const val FIELDS = "id,type,name,size,modified_at,parent"
        private const val SIMPLE_LIMIT = 50L * 1024 * 1024

        @Volatile private var instance: Box? = null
        fun get(context: Context): Box = instance ?: synchronized(this) { instance ?: Box(context.applicationContext).also { instance = it } }

        /** Box timestamps carry an offset ("2024-05-01T10:00:00-07:00"). */
        fun parseTime(s: String?): Long = s?.takeIf { it.isNotEmpty() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
    }

    override fun isConfigured() = CloudConfig.boxClientId.isNotEmpty() && CloudConfig.boxTokenUrl.isNotEmpty()
    override fun isConnected() = tokens.connected
    val account: String? get() = tokens.account
    override val supportsLinks: Boolean get() = true

    private suspend fun <T> authed(block: (String) -> T): T = withContext(Dispatchers.IO) {
        try { block(OAuthPkce.accessToken(context, id)) } catch (e: AuthRequiredException) { block(OAuthPkce.accessToken(context, id, force = true)) }
    }

    private fun req(url: String, t: String) = Request.Builder().url(url).header("Authorization", "Bearer $t")

    private fun parse(o: JSONObject): CloudItem {
        val folder = o.optString("type") == "folder"
        val name = o.optString("name")
        return CloudItem(
            id = o.getString("id"), name = name, isFolder = folder, size = o.optLong("size"), modified = parseTime(o.optString("modified_at")),
            mime = if (folder) null else mimeOf(name), parentId = o.optJSONObject("parent")?.optString("id"),
        )
    }

    private fun entries(json: JSONObject): List<CloudItem> {
        val arr = json.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "file" || it.optString("type") == "folder" }.map(::parse)
    }

    suspend fun loadAccount() = authed { t ->
        val me = JSONObject(CloudHttp.string(req("$API/users/me?fields=login,name", t).build()))
        tokens.account = me.optString("login").ifEmpty { me.optString("name") }
    }

    override suspend fun list(folderId: String?): List<CloudItem> = authed { t ->
        val out = ArrayList<CloudItem>()
        var offset = 0
        while (out.size < 2000) {
            val json = JSONObject(CloudHttp.string(req("$API/folders/${folderId ?: rootId}/items?fields=$FIELDS&limit=1000&offset=$offset", t).build()))
            val page = entries(json)
            out += page
            val total = json.optInt("total_count", out.size)
            offset += json.optInt("limit", 1000)
            if (page.isEmpty() || offset >= total) break
        }
        out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    override suspend fun search(query: String): List<CloudItem> = authed { t ->
        val url = "$API/search".toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("fields", FIELDS)
            .addQueryParameter("limit", "200").build().toString()
        entries(JSONObject(CloudHttp.string(req(url, t).build())))
    }

    override suspend fun metadataOf(item: CloudItem): CloudItem? = authed { t ->
        try { parse(JSONObject(CloudHttp.string(req("$API/${if (item.isFolder) "folders" else "files"}/${item.id}?fields=$FIELDS", t).build()))) }
        catch (e: HttpException) { if (e.code == 404) null else throw e }
    }

    // the content URL answers 302 to a pre-signed host; OkHttp drops the Authorization header on that cross-host redirect
    override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) = authed { t ->
        CloudHttp.download(req("$API/files/${item.id}/content", t).build(), dest, onProgress)
    }

    private fun multipart(attributes: JSONObject, local: File, name: String) = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("attributes", attributes.toString())
        .addFormDataPart("file", name, local.asRequestBody(mimeOf(name).toMediaType()))
        .build()

    override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = authed { t ->
        val parent = parentId ?: rootId
        val finalName = freeName(parent, name, t)
        if (local.length() > SIMPLE_LIMIT) {
            chunked("$UPLOAD/files/upload_sessions", JSONObject().put("folder_id", parent).put("file_size", local.length()).put("file_name", finalName), local, t)
        } else {
            val attrs = JSONObject().put("name", finalName).put("parent", JSONObject().put("id", parent))
            entries(JSONObject(CloudHttp.string(req("$UPLOAD/files/content?fields=$FIELDS", t).post(multipart(attrs, local, finalName)).build()))).first()
        }
    }

    /** Box refuses duplicate names (409): pick "name (2).ext" like the other providers' "rename" behaviour. */
    private fun freeName(parent: String, name: String, t: String): String {
        val taken = runCatching {
            JSONObject(CloudHttp.string(req("$API/folders/$parent/items?fields=name&limit=1000", t).build())).optJSONArray("entries")
                ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name").lowercase() }.toSet() }
        }.getOrNull().orEmpty()
        if (name.lowercase() !in taken) return name
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val c = if (ext.isEmpty() || base == name) "$name ($n)" else "$base ($n).$ext"
            if (c.lowercase() !in taken) return c
            n++
        }
    }

    override suspend fun update(item: CloudItem, local: File): CloudItem = authed { t ->
        if (local.length() > SIMPLE_LIMIT) chunked("$UPLOAD/files/${item.id}/upload_sessions", JSONObject().put("file_size", local.length()), local, t)
        else entries(JSONObject(CloudHttp.string(req("$UPLOAD/files/${item.id}/content?fields=$FIELDS", t)
            .post(multipart(JSONObject().put("name", item.name), local, item.name)).build()))).first()
    }

    /** Chunked upload session: parts of `part_size` bytes, each with its SHA-1 digest, then a commit with the whole-file digest. */
    private fun chunked(createUrl: String, body: JSONObject, local: File, t: String): CloudItem {
        val session = JSONObject(CloudHttp.string(req(createUrl, t).post(body.toString().toRequestBody(CloudHttp.JSON)).build()))
        val sid = session.getString("id")
        val partSize = session.getLong("part_size").toInt()
        val total = local.length()
        val whole = MessageDigest.getInstance("SHA-1")
        val parts = JSONArray()
        RandomAccessFile(local, "r").use { raf ->
            val buf = ByteArray(partSize)
            var offset = 0L
            while (offset < total) {
                val n = minOf(partSize.toLong(), total - offset).toInt()
                raf.seek(offset); raf.readFully(buf, 0, n)
                whole.update(buf, 0, n)
                val sha = Base64.encodeToString(MessageDigest.getInstance("SHA-1").apply { update(buf, 0, n) }.digest(), Base64.NO_WRAP)
                val r = req("$UPLOAD/files/upload_sessions/$sid", t).header("Digest", "sha=$sha")
                    .header("Content-Range", "bytes $offset-${offset + n - 1}/$total").put(buf.toRequestBody(CloudHttp.OCTET, 0, n)).build()
                parts.put(JSONObject(CloudHttp.string(r)).getJSONObject("part"))
                offset += n
            }
        }
        val digest = Base64.encodeToString(whole.digest(), Base64.NO_WRAP)
        val commit = req("$UPLOAD/files/upload_sessions/$sid/commit?fields=$FIELDS", t).header("Digest", "sha=$digest")
            .post(JSONObject().put("parts", parts).toString().toRequestBody(CloudHttp.JSON)).build()
        return entries(JSONObject(CloudHttp.string(commit))).first()
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudItem = authed { t ->
        val parent = parentId ?: rootId
        val body = JSONObject().put("name", freeName(parent, name, t)).put("parent", JSONObject().put("id", parent)).toString()
        parse(JSONObject(CloudHttp.string(req("$API/folders?fields=$FIELDS", t).post(body.toRequestBody(CloudHttp.JSON)).build())))
    }

    /** Moves the item to the Box trash. */
    override suspend fun delete(item: CloudItem) = authed { t ->
        val url = if (item.isFolder) "$API/folders/${item.id}?recursive=true" else "$API/files/${item.id}"
        CloudHttp.string(req(url, t).delete().build()); Unit
    }

    override suspend fun shareLink(item: CloudItem, anyone: Boolean): String = authed { t ->
        if (!anyone) return@authed "https://app.box.com/${if (item.isFolder) "folder" else "file"}/${item.id}"
        val kind = if (item.isFolder) "folders" else "files"
        val body = JSONObject().put("shared_link", JSONObject().put("access", "open")).toString()
        val o = JSONObject(CloudHttp.string(req("$API/$kind/${item.id}?fields=shared_link", t).put(body.toRequestBody(CloudHttp.JSON)).build()))
        o.optJSONObject("shared_link")?.optString("url")?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("no shared link")
    }

    override suspend fun changeCursor(): String? = authed { t ->
        JSONObject(CloudHttp.string(req("$API/events?stream_position=now&stream_type=changes", t).build())).opt("next_stream_position")?.toString()
    }

    override suspend fun changesSince(cursor: String): Pair<Boolean, String> = authed { t ->
        val o = JSONObject(CloudHttp.string(req("$API/events?stream_position=$cursor&stream_type=changes&limit=100", t).build()))
        (o.optInt("chunk_size") > 0) to (o.opt("next_stream_position")?.toString() ?: cursor)
    }

    override suspend fun disconnect() { tokens.clear() }
}
