package com.smartdocs.editor.core.webdav

import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.cloud.HttpException
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

/** The resource is locked by someone else (HTTP 423). */
class DavLockedException(message: String = "locked") : IOException(message)

/** The file changed on the server since it was downloaded (HTTP 412 on If-Match). */
class DavConflictException(message: String = "changed on the server") : IOException(message)

/**
 * Minimal WebDAV client (RFC 4918) over the app's OkHttp client: PROPFIND, GET, PUT, MKCOL, DELETE, LOCK / UNLOCK.
 * Basic authentication (use HTTPS); all calls block, run them on Dispatchers.IO.
 */
class DavClient(private val user: String, private val password: String) {

    constructor(place: DavPlace) : this(place.user, DavPlaces.password(place))

    private val http = CloudHttp.client

    private val XML = "application/xml; charset=utf-8".toMediaType()

    data class Caps(val dav: Boolean, val locking: Boolean)

    private fun req(url: String): Request.Builder = Request.Builder().url(url).apply {
        if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
        header("User-Agent", "SmartDocs-WebDAV")
    }

    private fun check(r: Response) {
        if (r.isSuccessful || r.code == 207) return
        val msg = runCatching { r.peekBody(1024).string() }.getOrDefault("")
        when (r.code) {
            401 -> throw AuthRequiredException(msg)
            412 -> throw DavConflictException()
            423 -> throw DavLockedException()
            else -> throw HttpException(r.code, msg.take(200))
        }
    }

    /** OPTIONS: is it WebDAV, and does it support locks (class 2)? */
    fun options(url: String): Caps = http.newCall(req(url).method("OPTIONS", null).build()).execute().use { r ->
        check(r)
        val dav = r.header("DAV")
        Caps(dav != null, DavXml.supportsLocking(dav) || r.header("Allow")?.contains("LOCK", true) == true)
    }

    private fun propfind(url: String, depth: String): List<DavEntry> {
        val rq = req(url).header("Depth", depth).method("PROPFIND", DavXml.PROPFIND_BODY.toRequestBody(XML)).build()
        return http.newCall(rq).execute().use { r ->
            check(r)
            DavXml.parseMultistatus(r.body?.string().orEmpty(), r.request.url.toString())
        }
    }

    /** Folder listing (the folder itself removed). */
    fun list(folderUrl: String): List<DavEntry> = DavXml.children(propfind(folderUrl, "1"), folderUrl)

    /** Current properties of one resource, or null when it does not exist. */
    fun stat(url: String): DavEntry? = try { propfind(url, "0").firstOrNull() } catch (e: HttpException) { if (e.code == 404) null else throw e }

    /** Downloads [url] into [dest] (via a temp file). Returns the ETag. */
    fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)? = null): String? {
        var etag: String? = null
        http.newCall(req(url).get().build()).execute().use { r ->
            check(r)
            etag = r.header("ETag")
            val body = r.body ?: throw IOException("empty body")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, ".${dest.name}.part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
        return etag
    }

    /**
     * Uploads [local] to [url]. [lockToken] is sent as the If header of a held lock; [ifMatch] refuses to overwrite a
     * newer server version ([DavConflictException]); [mustNotExist] refuses to overwrite at all. Returns the new ETag.
     */
    fun put(url: String, local: File, mime: String = "application/octet-stream", lockToken: String? = null, ifMatch: String? = null, mustNotExist: Boolean = false): String? {
        val b = req(url).put(local.asRequestBody(mime.toMediaType()))
        lockToken?.let { b.header("If", "(<$it>)") }
        ifMatch?.let { b.header("If-Match", it) }
        if (mustNotExist) b.header("If-None-Match", "*")
        return http.newCall(b.build()).execute().use { r -> check(r); r.header("ETag") }
    }

    fun mkcol(url: String) = http.newCall(req(url).method("MKCOL", null).build()).execute().use { check(it) }

    fun delete(url: String, lockToken: String? = null) {
        val b = req(url).delete()
        lockToken?.let { b.header("If", "(<$it>)") }
        http.newCall(b.build()).execute().use { check(it) }
    }

    /** Takes an exclusive write lock for [seconds]. Returns the lock token. */
    fun lock(url: String, owner: String, seconds: Int): String {
        val rq = req(url).header("Timeout", "Second-$seconds").header("Depth", "0")
            .method("LOCK", DavXml.lockBody(owner).toRequestBody(XML)).build()
        return http.newCall(rq).execute().use { r ->
            check(r)
            DavXml.lockToken(r.header("Lock-Token"), r.body?.string()) ?: throw IOException("no lock token")
        }
    }

    /** Extends a held lock (LOCK with the If header and no body). */
    fun refreshLock(url: String, token: String, seconds: Int) {
        val rq = req(url).header("Timeout", "Second-$seconds").header("If", "(<$token>)").method("LOCK", ByteArray(0).toRequestBody(null)).build()
        http.newCall(rq).execute().use { check(it) }
    }

    fun unlock(url: String, token: String) {
        http.newCall(req(url).header("Lock-Token", "<$token>").method("UNLOCK", null).build()).execute().use { r ->
            // an expired lock is already gone
            if (r.code != 409 && r.code != 412) check(r)
        }
    }
}
