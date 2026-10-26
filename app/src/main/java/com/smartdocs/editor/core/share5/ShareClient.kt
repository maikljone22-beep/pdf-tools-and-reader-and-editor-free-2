package com.smartdocs.editor.core.share5

import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.cloud.HttpException
import com.smartdocs.editor.core.review.BuildCfg
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** `ai-proxy/share` worker settings: BuildConfig SHARE_URL / SHARE_TOKEN (empty token → COLLAB_TOKEN). */
object S5Config {
    val baseUrl: String get() = BuildCfg.string("SHARE_URL").trimEnd('/')
    val token: String get() = BuildCfg.string("SHARE_TOKEN").ifEmpty { BuildCfg.string("COLLAB_TOKEN") }
    val enabled: Boolean get() = baseUrl.isNotEmpty()
}

data class InvitedReviewer(val name: String, val email: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("email", email)
    companion object { fun from(o: JSONObject) = InvitedReviewer(o.optString("name"), o.optString("email")) }
}

/** Owner-side settings of a managed review (stored on the share worker and mirrored locally in [ReviewExtras]). */
data class ReviewExtra(
    val subject: String = "", val message: String = "", val ownerName: String = "", val ownerEmail: String = "",
    val reviewers: List<InvitedReviewer> = emptyList(), val allowWeb: Boolean = false, val allowDownload: Boolean = true, val reminders: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("subject", subject).put("message", message).put("ownerName", ownerName).put("ownerEmail", ownerEmail)
        .put("reviewers", JSONArray().apply { reviewers.forEach { put(it.toJson()) } }).put("allowWeb", allowWeb).put("allowDownload", allowDownload).put("reminders", reminders)
    companion object {
        fun from(o: JSONObject?): ReviewExtra {
            if (o == null) return ReviewExtra()
            val a = o.optJSONArray("reviewers") ?: JSONArray()
            return ReviewExtra(o.optString("subject"), o.optString("message"), o.optString("ownerName"), o.optString("ownerEmail"),
                (0 until a.length()).map { InvitedReviewer.from(a.getJSONObject(it)) }, o.optBoolean("allowWeb"), o.optBoolean("allowDownload", true), o.optBoolean("reminders", true))
        }
    }
}

data class ReviewerStatus(val name: String, val email: String, val invited: Boolean, val status: String, val count: Int, val last: Long, val web: Boolean, val anonymous: Boolean)
data class ActivityItem(val kind: String, val name: String, val time: Long, val count: Int, val web: Boolean)
data class ReviewStatus(val extra: ReviewExtra, val code: String, val deadline: Long, val closed: Boolean, val reviewers: List<ReviewerStatus>,
                        val activity: List<ActivityItem>, val webUrl: String?, val mail: Boolean)

data class CreatedLink(val id: String, val ownerKey: String, val url: String)
data class LinkStats(val views: Int, val unique: Int, val downloads: Int, val days: Map<String, Int>, val last: Long)
data class LinkInfo(val id: String, val title: String, val fileName: String, val created: Long, val expires: Long, val allowDownload: Boolean,
                    val disabled: Boolean, val hasPassword: Boolean, val url: String, val stats: LinkStats)

/** Thin blocking client of the share worker (run on Dispatchers.IO). */
object S5Client {
    class NotConfigured : IllegalStateException("share server not configured")

    private fun url(path: String): String { if (!S5Config.enabled) throw NotConfigured(); return S5Config.baseUrl + path }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b(path: String) = Request.Builder().url(url(path)).apply { if (S5Config.token.isNotEmpty()) header("X-App-Token", S5Config.token) }
    private fun owner(path: String, key: String) = b(path).header("X-Owner-Key", key)
    private fun JSONObject.body() = toString().toRequestBody(CloudHttp.JSON)
    private fun call(r: Request): JSONObject = JSONObject(CloudHttp.string(r).ifBlank { "{}" })

    /** True when the HTTP error means "mail is not configured on the server". */
    fun isNoMail(e: Throwable) = e is HttpException && e.code == 501

    // ---- reviews
    fun putExtra(id: String, ownerKey: String, extra: ReviewExtra): String? =
        call(owner("/reviews/$id/extra", ownerKey).put(extra.toJson().body()).build()).optString("webUrl").takeIf { it.isNotEmpty() && it != "null" }

    fun status(id: String, ownerKey: String): ReviewStatus {
        val o = call(owner("/reviews/$id/extra", ownerKey).build())
        val meta = o.optJSONObject("meta") ?: JSONObject()
        val rv = o.optJSONArray("reviewers") ?: JSONArray()
        val act = o.optJSONArray("activity") ?: JSONArray()
        return ReviewStatus(
            ReviewExtra.from(o.optJSONObject("extra")), meta.optString("code"), meta.optLong("deadline"), meta.optBoolean("closed"),
            (0 until rv.length()).map { i -> rv.getJSONObject(i).let { r ->
                ReviewerStatus(r.optString("name"), r.optString("email"), r.optBoolean("invited"), r.optString("status"), r.optInt("count"), r.optLong("last"),
                    r.optBoolean("web"), r.optJSONObject("consent")?.optBoolean("anonymous") == true)
            } },
            (0 until act.length()).map { i -> act.getJSONObject(i).let { a -> ActivityItem(a.optString("kind"), a.optString("name"), a.optLong("time"), a.optInt("count"), a.optBoolean("web")) } },
            o.optString("webUrl").takeIf { it.isNotEmpty() && it != "null" }, o.optBoolean("mail"),
        )
    }

    fun setDeadline(id: String, ownerKey: String, deadline: Long) { call(owner("/reviews/$id/deadline", ownerKey).post(JSONObject().put("deadline", deadline).body()).build()) }
    fun setClosed(id: String, ownerKey: String, closed: Boolean) { call(owner("/reviews/$id/state", ownerKey).post(JSONObject().put("closed", closed).body()).build()) }
    /** Revokes the current link: returns the new access code. */
    fun rotate(id: String, ownerKey: String): String = call(owner("/reviews/$id/rotate", ownerKey).post(JSONObject().body()).build()).getString("code")
    /** Server e-mail to one reviewer ([to]) or all of them. Returns how many mails were sent; throws 501 when mail is off. */
    fun remind(id: String, ownerKey: String, to: String? = null, first: Boolean = false): Int =
        call(owner("/reviews/$id/${if (first) "invite" else "remind"}", ownerKey).post(JSONObject().apply { if (to != null) put("to", to) }.body()).build()).optInt("sent")

    fun seen(id: String, code: String, participant: String, name: String, consent: ReviewConsent.State?) {
        val o = JSONObject().put("participant", participant).put("name", name)
        if (consent != null) o.put("consent", JSONObject().put("time", consent.time).put("version", ReviewConsent.VERSION).put("anonymous", consent.anonymous))
        call(b("/reviews/$id/seen").header("X-Review-Code", code).post(o.body()).build())
    }

    // ---- view links
    fun createLink(file: File, title: String, expires: Long, password: String, allowDownload: Boolean): CreatedLink {
        val r = b("/links").header("X-Title", enc(title)).header("X-File-Name", enc(file.name)).header("X-Expires", expires.toString())
            .header("X-Allow-Download", if (allowDownload) "1" else "0").apply { if (password.isNotEmpty()) header("X-Password", enc(password)) }
            .post(file.asRequestBody(CloudHttp.OCTET)).build()
        val o = call(r)
        return CreatedLink(o.getString("id"), o.getString("ownerKey"), o.getString("url"))
    }

    fun link(id: String, ownerKey: String): LinkInfo {
        val o = call(owner("/links/$id", ownerKey).build())
        val s = o.optJSONObject("stats") ?: JSONObject()
        val days = s.optJSONObject("days") ?: JSONObject()
        return LinkInfo(o.getString("id"), o.optString("title"), o.optString("fileName"), o.optLong("created"), o.optLong("expires"), o.optBoolean("allowDownload"),
            o.optBoolean("disabled"), o.optBoolean("hasPassword"), o.optString("url"),
            LinkStats(s.optInt("views"), s.optInt("unique"), s.optInt("downloads"), days.keys().asSequence().associateWith { days.optInt(it) }, s.optLong("last")))
    }

    /** null fields are left unchanged; password "" removes it. */
    fun patchLink(id: String, ownerKey: String, title: String? = null, expires: Long? = null, password: String? = null, allowDownload: Boolean? = null, disabled: Boolean? = null) {
        val o = JSONObject()
        title?.let { o.put("title", it) }; expires?.let { o.put("expires", it) }; password?.let { o.put("password", it) }
        allowDownload?.let { o.put("allowDownload", it) }; disabled?.let { o.put("disabled", it) }
        call(owner("/links/$id", ownerKey).patch(o.body()).build())
    }

    fun deleteLink(id: String, ownerKey: String) { call(owner("/links/$id", ownerKey).delete().build()) }

    fun isGone(e: Throwable) = e is HttpException && (e.code == 404 || e.code == 410)
}
