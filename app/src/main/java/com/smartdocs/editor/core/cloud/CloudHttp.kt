package com.smartdocs.editor.core.cloud

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Awaits a Play services [Task] without the kotlinx-coroutines-play-services dependency. */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (t.isSuccessful) cont.resume(t.result)
        else cont.resumeWithException(t.exception ?: IOException("task failed"))
    }
}

/** HTTP failure with the status code and (truncated) body for diagnostics. */
class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Thrown when a provider needs the user to sign in (again). */
class AuthRequiredException(message: String = "auth required") : IOException(message)

object CloudHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val JSON = "application/json; charset=utf-8".toMediaType()
    val OCTET = "application/octet-stream".toMediaType()

    /** Executes and returns the body string; throws [HttpException] / [AuthRequiredException] on failure. */
    fun string(req: Request): String = client.newCall(req).execute().use { r -> check(r); r.body?.string().orEmpty() }

    /** Executes and streams the body into [dest] (via a temp file so a failed download never leaves a partial file). */
    fun download(req: Request, dest: File, onProgress: ((Long, Long) -> Unit)? = null) {
        client.newCall(req).execute().use { r ->
            check(r)
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
    }

    fun check(r: Response) {
        if (r.isSuccessful) return
        val msg = runCatching { r.peekBody(2048).string() }.getOrDefault("")
        if (r.code == 401) throw AuthRequiredException(msg)
        throw HttpException(r.code, msg.take(300))
    }
}
