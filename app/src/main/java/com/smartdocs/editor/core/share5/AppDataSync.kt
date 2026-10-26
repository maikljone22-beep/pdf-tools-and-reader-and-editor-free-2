package com.smartdocs.editor.core.share5

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.cloud.awaitTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Cross-device sync of [AppDataBundle] through Google Drive's hidden **appDataFolder** (scope `drive.appdata`, only
 * this app can see it; it does not count as access to the user's files). Each device keeps one file
 * `appdata-<device>.zip`; a sync first imports newer bundles of other devices, then uploads its own bundle only
 * when the content hash changed (no ping-pong).
 */
object AppDataSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val AUTO_INTERVAL_MS = 10 * 60_000L
    private val lock = Mutex()

    sealed interface Outcome {
        data class Done(val imported: Int, val uploaded: Boolean, val added: AppDataBundle.Summary) : Outcome
        class NeedsUi(val pendingIntent: PendingIntent) : Outcome
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share5_appdata", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()
    fun lastSync(c: Context) = prefs(c).getLong("last", 0L)
    fun lastError(c: Context): String? = prefs(c).getString("error", null)
    private fun device(c: Context): String = prefs(c).getString("device", null) ?: UUID.randomUUID().toString().take(12).also { prefs(c).edit().putString("device", it).apply() }

    private fun request() = AuthorizationRequest.Builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Silent token, or the consent screen to show. */
    private suspend fun token(c: Context): Pair<String?, PendingIntent?> {
        val r = Identity.getAuthorizationClient(c).authorize(request()).awaitTask()
        if (r.hasResolution()) return null to (r.pendingIntent ?: throw AuthRequiredException())
        return (r.accessToken ?: throw AuthRequiredException()) to null
    }

    /** Call with the result of the consent PendingIntent, then [sync] again. */
    fun finishAuthorization(c: Context, data: Intent?) {
        Identity.getAuthorizationClient(c).getAuthorizationResultFromIntent(data)
        setEnabled(c, true)
    }

    private fun get(url: String, t: String) = Request.Builder().url(url).header("Authorization", "Bearer $t")

    private data class Remote(val id: String, val name: String, val modified: Long, val hash: String?)

    private fun list(t: String): List<Remote> {
        val o = JSONObject(CloudHttp.string(get("$API/files?spaces=appDataFolder&pageSize=100&fields=files(id,name,modifiedTime,appProperties)", t).build()))
        val a = o.optJSONArray("files") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Remote(it.getString("id"), it.optString("name"), runCatching { Instant.parse(it.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0L),
                it.optJSONObject("appProperties")?.optString("hash"))
        }
    }

    private fun upload(t: String, existing: Remote?, name: String, file: File, hash: String, device: String) {
        val meta = JSONObject().put("name", name).put("appProperties", JSONObject().put("hash", hash).put("device", device))
        if (existing == null) meta.put("parents", JSONArray().put("appDataFolder"))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(CloudHttp.JSON))
            .addPart(file.asRequestBody("application/zip".toMediaType())).build()
        val r = if (existing == null) get("$UPLOAD/files?uploadType=multipart&fields=id", t).post(body)
            else get("$UPLOAD/files/${existing.id}?uploadType=multipart&fields=id", t).patch(body)
        CloudHttp.string(r.build())
    }

    /** One full sync. [Outcome.NeedsUi] when the user has not yet allowed the app-data scope. */
    suspend fun sync(c: Context): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val (t, ui) = token(c)
            if (ui != null) return@withContext Outcome.NeedsUi(ui)
            val tok = t!!
            setEnabled(c, true)
            val me = device(c)
            val myName = "appdata-$me.zip"
            val p = prefs(c)
            val imported = runCatching { JSONObject(p.getString("imported", "{}")!!) }.getOrDefault(JSONObject())
            val remotes = list(tok)
            var count = 0
            var added = AppDataBundle.Summary(0, 0, 0, 0, 0)
            val tmpDir = File(c.cacheDir, "appdata").apply { mkdirs() }
            try {
                for (r in remotes.filter { it.name.startsWith("appdata-") && it.name != myName }) {
                    if (imported.optLong(r.id, -1L) >= r.modified) continue
                    val f = File(tmpDir, "in_${r.id}.zip")
                    CloudHttp.download(get("$API/files/${r.id}?alt=media", tok).build(), f)
                    val s = AppDataBundle.import(c, f)
                    added = AppDataBundle.Summary(added.signatures + s.signatures, added.stamps + s.stamps, added.fields + s.fields, added.tags + s.tags, added.favorites + s.favorites)
                    imported.put(r.id, r.modified); count++
                }
                val out = File(tmpDir, myName)
                val built = AppDataBundle.export(c, out)
                val mine = remotes.firstOrNull { it.name == myName }
                val changed = built.hash != p.getString("uploadedHash", null) || mine == null
                if (changed) upload(tok, mine, myName, out, built.hash, me)
                p.edit().putString("imported", imported.toString()).putString("uploadedHash", built.hash)
                    .putLong("last", System.currentTimeMillis()).remove("error").apply()
                Outcome.Done(count, changed, added)
            } catch (e: Exception) {
                p.edit().putString("error", e.message ?: e.toString()).apply(); throw e
            } finally { tmpDir.deleteRecursively() }
        }
    }

    /** Background-friendly: runs only when enabled, consent exists and the last sync is older than 10 minutes. Never throws. */
    suspend fun autoSyncQuietly(c: Context) {
        if (!enabled(c) || System.currentTimeMillis() - lastSync(c) < AUTO_INTERVAL_MS) return
        runCatching { sync(c) }
    }

    /** Removes this device's bundle from Drive and turns sync off. */
    suspend fun disable(c: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val (t, _) = token(c)
            if (t != null) list(t).filter { it.name == "appdata-${device(c)}.zip" }.forEach {
                CloudHttp.string(get("$API/files/${it.id}", t).delete().build())
            }
        }
        setEnabled(c, false)
        prefs(c).edit().remove("uploadedHash").apply()
    }
}
