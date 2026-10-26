package com.smartdocs.editor.core.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
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
import java.time.Instant

/**
 * Google Drive through the REST API v3 with a token from Play services' AuthorizationClient (no client id in code:
 * the app's package + SHA-1 must be registered as an Android OAuth client in Google Cloud Console, see MODULE_acct.md).
 *
 * Scopes: drive.file (files this app created or opened) + drive.readonly (browse everything). Writing back over a
 * file the app did not create needs the full `drive` scope: set [FULL_ACCESS] = true (requires Google verification).
 */
class GoogleDrive private constructor(private val context: Context) : CloudProvider {
    override val id = ProviderId.DRIVE
    override val rootId = "root"
    private val prefs = context.getSharedPreferences("cloud_drive", Context.MODE_PRIVATE)

    companion object {
        /** true → request https://www.googleapis.com/auth/drive (needed to overwrite files the app did not create). */
        const val FULL_ACCESS = false
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FIELDS = "id,name,mimeType,size,modifiedTime,parents"

        val scopes: List<Scope> get() = if (FULL_ACCESS) listOf(Scope("https://www.googleapis.com/auth/drive"))
            else listOf(Scope("https://www.googleapis.com/auth/drive.file"), Scope("https://www.googleapis.com/auth/drive.readonly"))

        @Volatile private var instance: GoogleDrive? = null
        fun get(context: Context): GoogleDrive = instance ?: synchronized(this) {
            instance ?: GoogleDrive(context.applicationContext).also { instance = it }
        }
    }

    override fun isConfigured() = true // needs no in-app key; failures show the Cloud Console hint
    override fun isConnected() = prefs.getBoolean("connected", false)
    val accountEmail: String? get() = prefs.getString("email", null)

    private fun request(): AuthorizationRequest = AuthorizationRequest.Builder().setRequestedScopes(scopes).build()

    /** Outcome of [beginAuthorization]: either connected, or the UI must launch [pendingIntent]. */
    sealed interface AuthStep {
        data object Connected : AuthStep
        class NeedsUi(val pendingIntent: PendingIntent) : AuthStep
    }

    /** Starts (or silently refreshes) authorization. Call from the UI; launch the PendingIntent when asked. */
    suspend fun beginAuthorization(): AuthStep {
        val result = Identity.getAuthorizationClient(context).authorize(request()).awaitTask()
        if (result.hasResolution()) return AuthStep.NeedsUi(result.pendingIntent ?: throw AuthRequiredException())
        store(result)
        return AuthStep.Connected
    }

    /** Call with the result Intent of the PendingIntent launched for [AuthStep.NeedsUi]. */
    fun finishAuthorization(data: Intent?) {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        store(result)
    }

    private fun store(result: AuthorizationResult) {
        val token = result.accessToken ?: throw AuthRequiredException("no token")
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
        prefs.edit().putBoolean("connected", true).putString("token", token).putLong("token_time", System.currentTimeMillis())
            .apply { if (email != null) putString("email", email) }.apply()
    }

    /** A valid access token, refreshed silently (tokens live ~1 hour). Throws [AuthRequiredException] if the user must consent again. */
    private suspend fun token(forceRefresh: Boolean = false): String {
        val cached = prefs.getString("token", null)
        val age = System.currentTimeMillis() - prefs.getLong("token_time", 0L)
        if (!forceRefresh && cached != null && age < 45 * 60_000L) return cached
        if (!isConnected()) throw AuthRequiredException()
        if (cached != null) runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(cached).build()).awaitTask() }
        val result = Identity.getAuthorizationClient(context).authorize(request()).awaitTask()
        if (result.hasResolution()) throw AuthRequiredException("consent needed")
        store(result)
        return result.accessToken ?: throw AuthRequiredException()
    }

    /** Runs [block] with a token; on 401 refreshes once and retries. */
    private suspend fun <T> authed(block: (String) -> T): T = withContext(Dispatchers.IO) {
        try { block(token()) } catch (e: AuthRequiredException) { block(token(forceRefresh = true)) }
    }

    private fun get(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    private fun parse(o: JSONObject): CloudItem {
        val mime = o.optString("mimeType")
        return CloudItem(
            id = o.getString("id"), name = o.optString("name"), isFolder = mime == FOLDER_MIME,
            size = o.optString("size").toLongOrNull() ?: 0L,
            modified = o.optString("modifiedTime").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            mime = mime, parentId = o.optJSONArray("parents")?.optString(0),
        )
    }

    private fun query(q: String, token: String): List<CloudItem> {
        val out = ArrayList<CloudItem>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($FIELDS)")
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("orderBy", "folder,name")
                .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                .build().toString()
            val json = JSONObject(CloudHttp.string(get(url, token)))
            val arr = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i))
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null && out.size < 2000)
        return out
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    override suspend fun list(folderId: String?): List<CloudItem> = authed { t ->
        query("'${esc(folderId ?: rootId)}' in parents and trashed = false", t)
    }

    override suspend fun search(query: String): List<CloudItem> = authed { t ->
        query("name contains '${esc(query)}' and trashed = false", t)
    }

    /** Metadata of one item (used by sync to read the current modified time). */
    suspend fun metadata(itemId: String): CloudItem = authed { t ->
        parse(JSONObject(CloudHttp.string(get("$API/files/$itemId?fields=$FIELDS", t))))
    }

    override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) = authed { t ->
        // Google Docs/Sheets/Slides have no binary content: export them as PDF
        val url = if (item.mime?.startsWith("application/vnd.google-apps.") == true) "$API/files/${item.id}/export?mimeType=application/pdf"
            else "$API/files/${item.id}?alt=media"
        CloudHttp.download(get(url, t), dest, onProgress)
    }

    override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = authed { t ->
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId ?: rootId))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(CloudHttp.JSON))
            .addPart(local.asRequestBody(mimeOf(local.name).toMediaType()))
            .build()
        val req = Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=$FIELDS").header("Authorization", "Bearer $t").post(body).build()
        parse(JSONObject(CloudHttp.string(req)))
    }

    override suspend fun update(item: CloudItem, local: File): CloudItem = authed { t ->
        val req = Request.Builder().url("$UPLOAD/files/${item.id}?uploadType=media&fields=$FIELDS").header("Authorization", "Bearer $t")
            .patch(local.asRequestBody(mimeOf(local.name).toMediaType())).build()
        parse(JSONObject(CloudHttp.string(req)))
    }

    override suspend fun createFolder(parentId: String?, name: String): CloudItem = authed { t ->
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parentId ?: rootId))
        val req = Request.Builder().url("$API/files?fields=$FIELDS").header("Authorization", "Bearer $t").post(meta.toString().toRequestBody(CloudHttp.JSON)).build()
        parse(JSONObject(CloudHttp.string(req)))
    }

    /** Moves an item to the Drive trash (recoverable for 30 days). Used by sync to mirror local deletions. */
    suspend fun trash(itemId: String) = authed { t ->
        val req = Request.Builder().url("$API/files/$itemId?fields=id").header("Authorization", "Bearer $t")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody(CloudHttp.JSON)).build()
        CloudHttp.string(req); Unit
    }

    override suspend fun delete(item: CloudItem) = trash(item.id)

    override suspend fun metadataOf(item: CloudItem): CloudItem? =
        try { metadata(item.id) } catch (e: HttpException) { if (e.code == 404) null else throw e }

    override val supportsLinks: Boolean get() = true

    /** "Anyone with the link" reader permission (works for files this app may write), then the web link. */
    override suspend fun shareLink(item: CloudItem, anyone: Boolean): String = authed { t ->
        if (anyone) runCatching {
            val body = JSONObject().put("role", "reader").put("type", "anyone").toString()
            CloudHttp.string(Request.Builder().url("$API/files/${item.id}/permissions?fields=id").header("Authorization", "Bearer $t")
                .post(body.toRequestBody(CloudHttp.JSON)).build())
        }
        JSONObject(CloudHttp.string(get("$API/files/${item.id}?fields=webViewLink", t))).optString("webViewLink").takeIf { it.isNotEmpty() }
            ?: "https://drive.google.com/file/d/${item.id}/view"
    }

    override suspend fun changeCursor(): String? = authed { t ->
        JSONObject(CloudHttp.string(get("$API/changes/startPageToken", t))).optString("startPageToken").ifEmpty { null }
    }

    override suspend fun changesSince(cursor: String): Pair<Boolean, String> = authed { t ->
        var token = cursor
        var changed = false
        try {
            for (round in 0 until 10) {
                val o = JSONObject(CloudHttp.string(get("$API/changes?pageToken=$token&pageSize=200&fields=newStartPageToken,nextPageToken,changes(fileId)", t)))
                if ((o.optJSONArray("changes")?.length() ?: 0) > 0) changed = true
                val fresh = o.optString("newStartPageToken")
                if (fresh.isNotEmpty()) { token = fresh; break }
                val next = o.optString("nextPageToken")
                if (next.isEmpty()) break
                token = next
            }
            changed to token
        } catch (e: HttpException) {
            if (e.code == 404 || e.code == 410 || e.code == 400)
                true to JSONObject(CloudHttp.string(get("$API/changes/startPageToken", t))).optString("startPageToken").ifEmpty { cursor }
            else throw e
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            prefs.getString("token", null)?.let { tok ->
                runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(tok).build()).awaitTask() }
            }
            runCatching { Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder().setScopes(scopes).build()).awaitTask() }
            prefs.edit().clear().apply()
        }
    }
}

internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "txt" -> "text/plain"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}
