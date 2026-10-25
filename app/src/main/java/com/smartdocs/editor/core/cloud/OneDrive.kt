package com.smartdocs.editor.core.cloud

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/** OneDrive (personal + work) through Microsoft Graph, signed in with [OAuthPkce]. */
class OneDrive private constructor(private val context: Context) : CloudProvider {
    override val id = ProviderId.ONEDRIVE
    override val rootId = "root"
    private val tokens = OAuthTokens(context, id.key)

    companion object {
        private const val API = "https://graph.microsoft.com/v1.0/me/drive"
        private const val SELECT = "id,name,size,lastModifiedDateTime,folder,file,parentReference"
        private const val SIMPLE_LIMIT = 4L * 1024 * 1024
        private const val CHUNK = 320 * 1024 * 32 // 10 MiB, multiple of 320 KiB as Graph requires

        @Volatile private var instance: OneDrive? = null
        fun get(context: Context): OneDrive = instance ?: synchronized(this) { instance ?: OneDrive(context.applicationContext).also { instance = it } }
    }

    override fun isConfigured() = CloudConfig.oneDriveClientId.isNotEmpty()
    override fun isConnected() = tokens.connected
    val account: String? get() = tokens.account

    private suspend fun <T> authed(block: (String) -> T): T = withContext(Dispatchers.IO) {
        try { block(OAuthPkce.accessToken(context, id)) } catch (e: AuthRequiredException) { block(OAuthPkce.accessToken(context, id, force = true)) }
    }

    private fun req(url: String, t: String) = Request.Builder().url(url).header("Authorization", "Bearer $t")
    private fun item(path: String?) = if (path == null || path == rootId) "$API/root" else "$API/items/$path"

    private fun parse(o: JSONObject) = CloudItem(
        id = o.getString("id"), name = o.optString("name"), isFolder = o.has("folder"), size = o.optLong("size"),
        modified = o.optString("lastModifiedDateTime").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
        mime = o.optJSONObject("file")?.optString("mimeType"), parentId = o.optJSONObject("parentReference")?.optString("id"),
    )

    private fun pages(first: String, t: String): List<CloudItem> {
        val out = ArrayList<CloudItem>()
        var url: String? = first
        while (url != null && out.size < 2000) {
            val json = JSONObject(CloudHttp.string(req(url, t).build()))
            val arr = json.optJSONArray("value")
            if (arr != null) for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i))
            url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Fetches the signed-in account name once after sign-in. */
    suspend fun loadAccount() = authed { t ->
        val me = JSONObject(CloudHttp.string(req("https://graph.microsoft.com/v1.0/me?\$select=userPrincipalName,displayName", t).build()))
        tokens.account = me.optString("userPrincipalName").ifEmpty { me.optString("displayName") }
    }

    override suspend fun list(folderId: String?) = authed { t -> pages("${item(folderId)}/children?\$select=$SELECT&\$top=200", t) }

    override suspend fun search(query: String) = authed { t ->
        val q = query.replace("'", "''")
        pages("$API/root/search(q='${Uri.encode(q)}')?\$select=$SELECT&\$top=200", t)
    }

    override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) = authed { t ->
        CloudHttp.download(req("$API/items/${item.id}/content", t).build(), dest, onProgress)
    }

    override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = authed { t ->
        val target = "${item(parentId)}:/${Uri.encode(name)}:"
        if (local.length() <= SIMPLE_LIMIT) {
            parse(JSONObject(CloudHttp.string(req("$target/content?@microsoft.graph.conflictBehavior=rename", t)
                .put(local.asRequestBody(mimeOf(name).toMediaType())).build())))
        } else session("$target/createUploadSession", local, t, conflict = "rename")
    }

    override suspend fun update(item: CloudItem, local: File): CloudItem = authed { t ->
        if (local.length() <= SIMPLE_LIMIT) {
            parse(JSONObject(CloudHttp.string(req("$API/items/${item.id}/content", t).put(local.asRequestBody(mimeOf(local.name).toMediaType())).build())))
        } else session("$API/items/${item.id}/createUploadSession", local, t, conflict = "replace")
    }

    /** Resumable upload in 10 MiB chunks for files above 4 MB. */
    private fun session(createUrl: String, local: File, t: String, conflict: String): CloudItem {
        val body = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", conflict)).toString()
        val uploadUrl = JSONObject(CloudHttp.string(req(createUrl, t).post(body.toRequestBody(CloudHttp.JSON)).build())).getString("uploadUrl")
        val total = local.length()
        RandomAccessFile(local, "r").use { raf ->
            var offset = 0L
            val buf = ByteArray(CHUNK)
            while (offset < total) {
                val n = minOf(CHUNK.toLong(), total - offset).toInt()
                raf.seek(offset); raf.readFully(buf, 0, n)
                val r = Request.Builder().url(uploadUrl) // the upload URL is pre-authenticated: no Authorization header
                    .header("Content-Range", "bytes $offset-${offset + n - 1}/$total")
                    .put(buf.toRequestBody(CloudHttp.OCTET, 0, n)).build()
                val text = CloudHttp.string(r)
                offset += n
                if (offset >= total) return parse(JSONObject(text))
            }
        }
        throw IllegalStateException("upload incomplete")
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudItem = authed { t ->
        val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "rename").toString()
        parse(JSONObject(CloudHttp.string(req("${item(parentId)}/children", t).post(body.toRequestBody(CloudHttp.JSON)).build())))
    }

    override suspend fun disconnect() { tokens.clear() }
}
