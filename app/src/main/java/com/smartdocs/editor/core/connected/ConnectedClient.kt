package com.smartdocs.editor.core.connected

import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.cloud.HttpException
import com.smartdocs.editor.core.review.BuildCfg
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** `cloud.properties` connectedUrl / connectedToken -> BuildConfig CONNECTED_URL / CONNECTED_TOKEN. */
object ConnectedConfig {
    val baseUrl: String get() = BuildCfg.string("CONNECTED_URL").trimEnd('/')
    val appToken: String get() = BuildCfg.string("CONNECTED_TOKEN")
    /** fc-settings: also off when the user turned ConnectedPDF off (Settings → More preferences → General). */
    val enabled: Boolean get() = baseUrl.isNotEmpty() && !com.smartdocs.editor.core.data.FcsPrefs.connectedOff
}

data class ConnVersion(val n: Int, val sha: String, val size: Long, val note: String, val time: Long, val hasFile: Boolean)
data class ConnReader(val rid: String, val name: String, val opens: Int, val first: Long, val last: Long, val status: String, val message: String)
data class ConnDocInfo(
    val id: String, val title: String, val owner: String, val protected: Boolean, val requireApproval: Boolean, val offlineDays: Int,
    val revoked: Boolean, val opens: Int, val versions: List<ConnVersion>, val readers: List<ConnReader>, val created: Long,
)

/** Reader-side answer of `POST /open/:id`: ok | pending | blocked | revoked | gone. */
data class ConnAccess(
    val status: String, val latest: ConnVersion?, val isLatest: Boolean, val key: String?, val offlineDays: Int, val title: String, val owner: String,
) { val allowed get() = status == "ok" }

data class Registered(val id: String, val ownerKey: String, val key: String?)

object ConnectedClient {
    private fun url(path: String) = ConnectedConfig.baseUrl + path
    private fun Request.Builder.app() = apply { if (ConnectedConfig.appToken.isNotEmpty()) header("X-App-Token", ConnectedConfig.appToken) }
    private fun Request.Builder.owner(k: String) = app().header("X-Owner-Key", k)
    private fun obj(r: Request, client: OkHttpClient = CloudHttp.client) =
        client.newCall(r).execute().use { CloudHttp.check(it); JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
    private fun json(o: JSONObject) = o.toString().toRequestBody(CloudHttp.JSON)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Short timeouts for the check made while a document opens (6 s connect, 10 s read/call). */
    private val quick: OkHttpClient by lazy {
        CloudHttp.client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }

    fun register(title: String, owner: String, protected: Boolean, requireApproval: Boolean, offlineDays: Int): Registered {
        val o = obj(Request.Builder().url(url("/docs")).app().post(json(JSONObject().put("title", title).put("owner", owner)
            .put("protected", protected).put("requireApproval", requireApproval).put("offlineDays", offlineDays))).build())
        return Registered(o.getString("id"), o.getString("ownerKey"), o.optString("key").ifEmpty { null })
    }

    /** Publishes a version; [file] null = hash-only (readers are told a newer version exists but cannot download it). */
    fun publishVersion(id: String, ownerKey: String, sha: String, size: Long, note: String, file: File?): Int {
        val u = url("/docs/$id/versions?sha=$sha&size=$size&note=${enc(note)}")
        val body = file?.asRequestBody(CloudHttp.OCTET) ?: ByteArray(0).toRequestBody(null)
        return obj(Request.Builder().url(u).owner(ownerKey).put(body).build()).optInt("n")
    }

    fun info(id: String, ownerKey: String): ConnDocInfo {
        val o = obj(Request.Builder().url(url("/docs/$id")).owner(ownerKey).build())
        val v = o.optJSONArray("versions") ?: JSONArray(); val r = o.optJSONArray("readers") ?: JSONArray()
        return ConnDocInfo(o.getString("id"), o.optString("title"), o.optString("owner"), o.optBoolean("protected"), o.optBoolean("requireApproval"),
            o.optInt("offlineDays", 7), o.optBoolean("revoked"), o.optInt("opens"),
            (0 until v.length()).map { version(v.getJSONObject(it)) },
            (0 until r.length()).map { r.getJSONObject(it).let { x -> ConnReader(x.optString("rid"), x.optString("name"), x.optInt("opens"), x.optLong("first"), x.optLong("last"), x.optString("status"), x.optString("message")) } },
            o.optLong("created"))
    }

    private fun version(o: JSONObject) = ConnVersion(o.optInt("n"), o.optString("sha"), o.optLong("size"), o.optString("note"), o.optLong("time"), o.optBoolean("hasFile"))

    fun setReader(id: String, ownerKey: String, rid: String, status: String) {
        obj(Request.Builder().url(url("/docs/$id/readers/${enc(rid)}")).owner(ownerKey).post(json(JSONObject().put("status", status))).build())
    }

    fun settings(id: String, ownerKey: String, requireApproval: Boolean, offlineDays: Int, revoked: Boolean) {
        obj(Request.Builder().url(url("/docs/$id/settings")).owner(ownerKey).post(json(JSONObject().put("requireApproval", requireApproval)
            .put("offlineDays", offlineDays).put("revoked", revoked))).build())
    }

    fun revokeAll(id: String, ownerKey: String) { obj(Request.Builder().url(url("/docs/$id/revoke-all")).owner(ownerKey).post(json(JSONObject())).build()) }

    fun delete(id: String, ownerKey: String) { CloudHttp.string(Request.Builder().url(url("/docs/$id")).owner(ownerKey).delete().build()) }

    // ---------------------------------------------------------------- reader side

    fun open(id: String, rid: String, name: String, sha: String): ConnAccess {
        val o = obj(Request.Builder().url(url("/open/$id")).app().post(json(JSONObject().put("rid", rid).put("name", name).put("sha", sha))).build(), quick)
        return ConnAccess(o.optString("status", "ok"), o.optJSONObject("latest")?.let(::version), o.optBoolean("isLatest", true),
            o.optString("key").ifEmpty { null }, o.optInt("offlineDays", 7), o.optString("title"), o.optString("owner"))
    }

    fun requestAccess(id: String, rid: String, name: String, message: String) {
        obj(Request.Builder().url(url("/open/$id/request")).app().post(json(JSONObject().put("rid", rid).put("name", name).put("message", message))).build())
    }

    fun downloadLatest(id: String, rid: String, dest: File) =
        CloudHttp.download(Request.Builder().url(url("/open/$id/latest?rid=${enc(rid)}")).app().build(), dest)

    fun isGone(e: Throwable) = e is HttpException && (e.code == 404 || e.code == 410)
}
