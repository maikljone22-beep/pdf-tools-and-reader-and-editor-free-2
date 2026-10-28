package com.smartdocs.editor.core.collab

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

/** Server settings from `cloud.properties` → BuildConfig COLLAB_URL / COLLAB_TOKEN. Empty URL = shared review disabled. */
object CollabConfig {
    val baseUrl: String get() = BuildCfg.string("COLLAB_URL").trimEnd('/')
    val appToken: String get() = BuildCfg.string("COLLAB_TOKEN")
    val enabled: Boolean get() = baseUrl.isNotEmpty()
}

data class Reviewer(val name: String, val lastSeen: Long, val count: Int)
data class ReviewMeta(val id: String, val title: String, val fileName: String, val author: String, val created: Long, val deadline: Long, val closed: Boolean, val size: Long, val reviewers: List<Reviewer>) {
    val expired: Boolean get() = closed || (deadline > 0 && System.currentTimeMillis() > deadline)
}
data class RemoteComment(val nm: String, val participant: String, val author: String, val modified: Long, val deleted: Boolean, val page: Int, val data: JSONObject?)
data class CreatedReview(val id: String, val code: String, val ownerKey: String, val shareUrl: String)

/** Thin client of `ai-proxy/collab/worker.js`. Blocking calls: use Dispatchers.IO. HTTP 410 = review closed / deadline passed. */
object CollabClient {
    private fun url(path: String) = CollabConfig.baseUrl + path
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun Request.Builder.app() = apply { if (CollabConfig.appToken.isNotEmpty()) header("X-App-Token", CollabConfig.appToken) }

    fun shareUrl(id: String, code: String) = url("/r/$id?c=${enc(code)}")
    fun deepLink(id: String, code: String) = "smartdocs://review/$id?c=${enc(code)}"

    fun create(file: File, title: String, author: String, deadline: Long): CreatedReview {
        val r = Request.Builder().url(url("/reviews")).app()
            .header("X-Review-Title", enc(title)).header("X-Review-Author", enc(author)).header("X-File-Name", enc(file.name))
            .header("X-Review-Deadline", deadline.toString())
            .post(file.asRequestBody(CloudHttp.OCTET)).build()
        val o = JSONObject(CloudHttp.string(r))
        return CreatedReview(o.getString("id"), o.getString("code"), o.getString("ownerKey"), o.optString("shareUrl").ifEmpty { shareUrl(o.getString("id"), o.getString("code")) })
    }

    fun meta(id: String, code: String): ReviewMeta {
        val o = JSONObject(CloudHttp.string(Request.Builder().url(url("/reviews/$id")).app().header("X-Review-Code", code).build()))
        val rv = o.optJSONArray("reviewers") ?: JSONArray()
        return ReviewMeta(
            id = o.getString("id"), title = o.optString("title"), fileName = o.optString("fileName", "review.pdf"), author = o.optString("author"),
            created = o.optLong("created"), deadline = o.optLong("deadline"), closed = o.optBoolean("closed"), size = o.optLong("size"),
            reviewers = (0 until rv.length()).map { i -> rv.getJSONObject(i).let { Reviewer(it.optString("name"), it.optLong("lastSeen"), it.optInt("count")) } },
        )
    }

    fun download(id: String, code: String, dest: File) =
        CloudHttp.download(Request.Builder().url(url("/reviews/$id/file")).app().header("X-Review-Code", code).build(), dest)

    fun comments(id: String, code: String): List<RemoteComment> {
        val o = JSONObject(CloudHttp.string(Request.Builder().url(url("/reviews/$id/comments")).app().header("X-Review-Code", code).build()))
        val arr = o.optJSONArray("comments") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            RemoteComment(c.getString("nm"), c.optString("participant"), c.optString("author"), c.optLong("modified"), c.optBoolean("deleted"), c.optInt("page"), c.optJSONObject("data"))
        }
    }

    /** Publishes the caller's own comments (upserts) and tombstones (deletes). */
    fun publish(id: String, code: String, participant: String, author: String, upserts: List<CollabAnnots.Comment>, deletes: Collection<String>) {
        val now = System.currentTimeMillis()
        val list = JSONArray()
        upserts.forEach { c -> list.put(JSONObject().put("nm", c.nm).put("page", c.page).put("modified", now).put("data", c.data)) }
        deletes.forEach { nm -> list.put(JSONObject().put("nm", nm).put("deleted", true).put("modified", now)) }
        val body = JSONObject().put("participant", participant).put("author", author).put("comments", list).toString()
        CloudHttp.string(Request.Builder().url(url("/reviews/$id/comments")).app().header("X-Review-Code", code).post(body.toRequestBody(CloudHttp.JSON)).build())
    }

    /**
     * fc-sync "Remove me": hides this participant from the reviewer list on the server (its comments stay). Returns
     * false when the server predates the route (HTTP 404 "not found" for the path): then leaving stays local.
     */
    fun leave(id: String, code: String, participant: String): Boolean = try {
        val body = JSONObject().put("participant", participant).toString()
        CloudHttp.string(Request.Builder().url(url("/reviews/$id/leave")).app().header("X-Review-Code", code).post(body.toRequestBody(CloudHttp.JSON)).build())
        true
    } catch (e: HttpException) { if (e.code == 404 || e.code == 410) false else throw e }

    fun close(id: String, ownerKey: String) {
        CloudHttp.string(Request.Builder().url(url("/reviews/$id/close")).app().header("X-Owner-Key", ownerKey).post(ByteArray(0).toRequestBody(null)).build())
    }

    fun delete(id: String, ownerKey: String) {
        CloudHttp.string(Request.Builder().url(url("/reviews/$id")).app().header("X-Owner-Key", ownerKey).delete().build())
    }

    fun isGone(e: Throwable) = e is HttpException && (e.code == 410 || e.code == 404)
}
