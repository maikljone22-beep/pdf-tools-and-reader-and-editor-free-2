package com.smartdocs.editor.core.esign

import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.cloud.HttpException
import com.smartdocs.editor.core.review.BuildCfg
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Server settings: `cloud.properties` esignUrl / esignToken -> BuildConfig ESIGN_URL / ESIGN_TOKEN. Empty URL = feature off. */
object EsignConfig {
    val baseUrl: String get() = BuildCfg.string("ESIGN_URL").trimEnd('/')
    val appToken: String get() = BuildCfg.string("ESIGN_TOKEN")
    val enabled: Boolean get() = baseUrl.isNotEmpty()
}

/** Result of creating a request: the owner key (kept on this device only) and one link per signer. */
data class CreatedRequest(val id: String, val ownerKey: String, val links: List<String>, val appLinks: List<String>, val mailed: Boolean)

/** What a signer sees (`GET /sign/:id?t=`). [me] = index of the signer the token belongs to. */
data class SignerView(val request: EsignRequest, val me: Int, val canSign: Boolean, val reason: String)

/** Values submitted by one signer + the server evidence (`GET /requests/:id/values`). */
data class SubmittedValues(val signer: Int, val values: Map<String, String>, val event: SignerEvent?)

/** Thin blocking client of `ai-proxy/esign/worker.js` (run on Dispatchers.IO). */
object EsignClient {
    private fun url(path: String) = EsignConfig.baseUrl + path
    private fun Request.Builder.app() = apply { if (EsignConfig.appToken.isNotEmpty()) header("X-App-Token", EsignConfig.appToken) }
    private fun Request.Builder.owner(key: String) = app().header("X-Owner-Key", key)
    private fun obj(r: Request) = JSONObject(CloudHttp.string(r).ifBlank { "{}" })
    private fun post(o: JSONObject) = o.toString().toRequestBody(CloudHttp.JSON)

    /** Step 1: metadata; step 2: the PDF (the server hashes it, then mails or returns the links). */
    fun create(file: File, title: String, sender: String, senderEmail: String, message: String, order: SignOrder,
               signers: List<Signer>, fields: List<EsignField>, pageCount: Int, expiresDays: Int, sendMail: Boolean): CreatedRequest {
        val meta = JSONObject().put("title", title).put("sender", sender).put("senderEmail", senderEmail).put("message", message)
            .put("order", order.name.lowercase()).put("pageCount", pageCount).put("expiresDays", expiresDays).put("sendMail", sendMail)
            .put("signers", JSONArray().apply { signers.forEach { put(JSONObject().put("name", it.name).put("email", it.email)) } })
            .put("fields", JSONArray().apply { fields.forEach { put(it.toJson()) } })
        val c = obj(Request.Builder().url(url("/requests")).app().post(post(meta)).build())
        val id = c.getString("id"); val key = c.getString("ownerKey")
        val up = obj(Request.Builder().url(url("/requests/$id/file")).owner(key).put(file.asRequestBody(CloudHttp.OCTET)).build())
        val links = up.optJSONArray("links") ?: JSONArray()
        val app = up.optJSONArray("appLinks") ?: JSONArray()
        return CreatedRequest(id, key, (0 until links.length()).map { links.getString(it) }, (0 until app.length()).map { app.getString(it) }, up.optBoolean("mailed"))
    }

    fun get(id: String, ownerKey: String): EsignRequest = EsignRequest.from(obj(Request.Builder().url(url("/requests/$id")).owner(ownerKey).build()))

    fun links(id: String, ownerKey: String): List<String> {
        val a = obj(Request.Builder().url(url("/requests/$id/links")).owner(ownerKey).build()).optJSONArray("links") ?: JSONArray()
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Reminds [signer] (or every pending signer when null). Returns true when the server sent an e-mail. */
    fun remind(id: String, ownerKey: String, signer: Int?): Boolean =
        obj(Request.Builder().url(url("/requests/$id/remind")).owner(ownerKey).post(post(JSONObject().put("signer", signer ?: -1))).build()).optBoolean("mailed")

    fun cancel(id: String, ownerKey: String) { obj(Request.Builder().url(url("/requests/$id/cancel")).owner(ownerKey).post(post(JSONObject())).build()) }

    fun delete(id: String, ownerKey: String) { CloudHttp.string(Request.Builder().url(url("/requests/$id")).owner(ownerKey).delete().build()) }

    fun values(id: String, ownerKey: String): List<SubmittedValues> {
        val a = obj(Request.Builder().url(url("/requests/$id/values")).owner(ownerKey).build()).optJSONArray("values") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val v = o.optJSONObject("values") ?: JSONObject()
            SubmittedValues(o.optInt("signer"), v.keys().asSequence().associateWith { v.optString(it) },
                o.optJSONObject("event")?.let { SignerEvent(it.optLong("time"), it.optString("ip"), it.optString("ua"), it.optString("device"), it.optString("channel")) })
        }
    }

    /** Uploads the completed PDF; the server records its SHA-256 for `/verify`. Returns the server's hash. */
    fun uploadFinal(id: String, ownerKey: String, file: File): String =
        obj(Request.Builder().url(url("/requests/$id/final")).owner(ownerKey).put(file.asRequestBody(CloudHttp.OCTET)).build()).optString("sha")

    // ---------------------------------------------------------------- signer side

    fun signerView(id: String, token: String): SignerView {
        val o = obj(Request.Builder().url(url("/sign/$id").toHttpUrl().newBuilder().addQueryParameter("t", token).build()).app().build())
        return SignerView(EsignRequest.from(o.getJSONObject("request")), o.optInt("me"), o.optBoolean("canSign"), o.optString("reason"))
    }

    fun downloadForSigner(id: String, token: String, dest: File) =
        CloudHttp.download(Request.Builder().url(url("/sign/$id/file").toHttpUrl().newBuilder().addQueryParameter("t", token).build()).app().build(), dest)

    fun submit(id: String, token: String, values: Map<String, String>, device: String) {
        val body = JSONObject().put("values", JSONObject(values as Map<*, *>)).put("consent", true).put("device", device).put("channel", "app")
        obj(Request.Builder().url(url("/sign/$id/submit").toHttpUrl().newBuilder().addQueryParameter("t", token).build()).app().post(post(body)).build())
    }

    fun decline(id: String, token: String, reason: String, device: String) {
        val body = JSONObject().put("reason", reason).put("device", device).put("channel", "app")
        obj(Request.Builder().url(url("/sign/$id/decline").toHttpUrl().newBuilder().addQueryParameter("t", token).build()).app().post(post(body)).build())
    }

    fun webLink(id: String, token: String) = url("/s/$id?t=$token")
    fun verifyPage() = url("/v")

    /** Looks up a completed document by its SHA-256 (public). Null when unknown. */
    fun verify(sha: String): JSONObject? = try { obj(Request.Builder().url(url("/verify/${sha.lowercase()}")).build()) }
        catch (e: HttpException) { if (e.code == 404) null else throw e }

    fun isGone(e: Throwable) = e is HttpException && (e.code == 404 || e.code == 410)
    fun isNotYourTurn(e: Throwable) = e is HttpException && e.code == 409
}
