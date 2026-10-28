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
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val API = "$GRAPH/me/drive"
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
    /** URL of an item: the personal drive, or (fc-sync) an item of a SharePoint library ([SharePoint.encode]). */
    private fun itemUrl(path: String?): String {
        if (path == null || path == rootId) return "$API/root"
        val sp = SharePoint.decode(path) ?: return "$API/items/$path"
        return if (sp.second == "root") "$GRAPH/drives/${sp.first}/root" else "$GRAPH/drives/${sp.first}/items/${sp.second}"
    }

    /** Drive of a library item (null = the personal drive): results are re-encoded with it. */
    private fun driveOf(id: String?): String? = SharePoint.decode(id)?.first

    private fun parse(o: JSONObject, drive: String? = null): CloudItem {
        val raw = o.getString("id")
        val parent = o.optJSONObject("parentReference")?.optString("id")?.ifEmpty { null }
        return CloudItem(
            id = if (drive == null) raw else SharePoint.encode(drive, raw), name = o.optString("name"), isFolder = o.has("folder"), size = o.optLong("size"),
            modified = o.optString("lastModifiedDateTime").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            mime = o.optJSONObject("file")?.optString("mimeType"), parentId = if (drive == null || parent == null) parent else SharePoint.encode(drive, parent),
        )
    }

    private fun pages(first: String, t: String, drive: String? = null): List<CloudItem> {
        val out = ArrayList<CloudItem>()
        var url: String? = first
        while (url != null && out.size < 2000) {
            val json = JSONObject(CloudHttp.string(req(url, t).build()))
            val arr = json.optJSONArray("value")
            if (arr != null) for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i), drive)
            url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Fetches the signed-in account name once after sign-in. */
    suspend fun loadAccount() = authed { t ->
        val me = JSONObject(CloudHttp.string(req("https://graph.microsoft.com/v1.0/me?\$select=userPrincipalName,displayName", t).build()))
        tokens.account = me.optString("userPrincipalName").ifEmpty { me.optString("displayName") }
    }

    override suspend fun list(folderId: String?) = authed { t ->
        if (folderId == SharePoint.LIBRARIES) libraries(t) else pages("${itemUrl(folderId)}/children?\$select=$SELECT&\$top=200", t, driveOf(folderId))
    }

    /** fc-sync: the document libraries of the sites the user follows, as folders (needs the library scopes). */
    private fun libraries(t: String): List<CloudItem> {
        val sites = SharePoint.sites(CloudHttp.string(req("$GRAPH/me/followedSites?\$select=id,displayName", t).build()))
        return sites.flatMap { (siteId, siteName) ->
            runCatching { SharePoint.libraries(siteName, CloudHttp.string(req("$GRAPH/sites/$siteId/drives?\$select=id,name,driveType", t).build())) }.getOrDefault(emptyList())
        }.map { (driveId, label) -> CloudItem(SharePoint.encode(driveId, "root"), label, isFolder = true, parentId = SharePoint.LIBRARIES) }
            .sortedBy { it.name.lowercase() }
    }

    /** fc-sync: the drive-wide delta feed covers only the personal drive; library folders rely on the periodic run. */
    override fun feedCovers(folderId: String): Boolean = !SharePoint.isLibraryId(folderId)

    override suspend fun search(query: String) = authed { t ->
        val q = query.replace("'", "''")
        pages("$API/root/search(q='${Uri.encode(q)}')?\$select=$SELECT&\$top=200", t)
    }

    override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) = authed { t ->
        CloudHttp.download(req("${itemUrl(item.id)}/content", t).build(), dest, onProgress)
    }

    override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = authed { t ->
        val target = "${itemUrl(parentId)}:/${Uri.encode(name)}:"
        val drive = driveOf(parentId)
        if (local.length() <= SIMPLE_LIMIT) {
            parse(JSONObject(CloudHttp.string(req("$target/content?@microsoft.graph.conflictBehavior=rename", t)
                .put(local.asRequestBody(mimeOf(name).toMediaType())).build())), drive)
        } else session("$target/createUploadSession", local, t, conflict = "rename", drive = drive)
    }

    override suspend fun update(item: CloudItem, local: File): CloudItem = authed { t ->
        if (local.length() <= SIMPLE_LIMIT) {
            parse(JSONObject(CloudHttp.string(req("${itemUrl(item.id)}/content", t).put(local.asRequestBody(mimeOf(local.name).toMediaType())).build())), driveOf(item.id))
        } else session("${itemUrl(item.id)}/createUploadSession", local, t, conflict = "replace", drive = driveOf(item.id))
    }

    /** Resumable upload in 10 MiB chunks for files above 4 MB. */
    private fun session(createUrl: String, local: File, t: String, conflict: String, drive: String? = null): CloudItem {
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
                if (offset >= total) return parse(JSONObject(text), drive)
            }
        }
        throw IllegalStateException("upload incomplete")
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudItem = authed { t ->
        val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "rename").toString()
        parse(JSONObject(CloudHttp.string(req("${itemUrl(parentId)}/children", t).post(body.toRequestBody(CloudHttp.JSON)).build())), driveOf(parentId))
    }

    /** Moves the item to the OneDrive recycle bin. */
    override suspend fun delete(item: CloudItem) = authed { t -> CloudHttp.string(req(itemUrl(item.id), t).delete().build()); Unit }

    override suspend fun metadataOf(item: CloudItem): CloudItem? = authed { t ->
        try { parse(JSONObject(CloudHttp.string(req("${itemUrl(item.id)}?\$select=$SELECT", t).build())), driveOf(item.id)) }
        catch (e: HttpException) { if (e.code == 404) null else throw e }
    }

    override val supportsLinks: Boolean get() = true

    /** Anonymous view link (`createLink`); where the tenant forbids it, or [anyone] is false, the item's web URL. */
    override suspend fun shareLink(item: CloudItem, anyone: Boolean): String = authed { t ->
        val anon = if (!anyone) null else runCatching {
            val body = JSONObject().put("type", "view").put("scope", "anonymous").toString()
            JSONObject(CloudHttp.string(req("${itemUrl(item.id)}/createLink", t).post(body.toRequestBody(CloudHttp.JSON)).build()))
                .optJSONObject("link")?.optString("webUrl")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        anon ?: JSONObject(CloudHttp.string(req("${itemUrl(item.id)}?\$select=webUrl", t).build())).optString("webUrl").ifEmpty { throw IllegalStateException("no link") }
    }

    override suspend fun changeCursor(): String? = authed { t ->
        ChangeFeeds.graph(CloudHttp.string(req("$API/root/delta?token=latest", t).build())).steady
    }

    override suspend fun changesSince(cursor: String): Pair<Boolean, String> = authed { t ->
        var url = cursor
        var changed = false
        try {
            for (round in 0 until 20) {
                val page = ChangeFeeds.graph(CloudHttp.string(req(url, t).build()))
                if (page.changed) changed = true
                if (page.steady != null) { url = page.steady; break }
                url = page.next ?: break
            }
            changed to url
        } catch (e: HttpException) {
            if (e.code == 410 || e.code == 404)
                true to (ChangeFeeds.graph(CloudHttp.string(req("$API/root/delta?token=latest", t).build())).steady ?: cursor)
            else throw e
        }
    }

    override suspend fun disconnect() { tokens.clear(); SharePoint.Scopes.reset(context) }
}
