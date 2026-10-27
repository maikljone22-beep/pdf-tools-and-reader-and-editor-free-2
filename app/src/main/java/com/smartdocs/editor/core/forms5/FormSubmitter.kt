package com.smartdocs.editor.core.forms5

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Sends a SubmitForm payload over HTTP(S). mailto: targets are handled by the UI (share sheet). */
object FormSubmitter {

    private const val MAX_BODY = 256L * 1024

    /** HTTP status plus the response body (first 256 KB as text) and its media type, for the response viewer. */
    data class Result(val code: Int, val message: String, val mime: String = "", val body: String = "")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Body bytes for [a] from the selected [values]; [pdf] is the filled document (used for SubmitPDF). */
    fun payload(a: ButtonAction.Submit, values: Map<String, String>, fileName: String, pdf: File?): ByteArray = when (a.format) {
        SubmitFormat.HTML -> FormSubmit.htmlBody(values).toByteArray(Charsets.UTF_8)
        SubmitFormat.XFDF -> FormSubmit.xfdf(values, fileName).toByteArray(Charsets.UTF_8)
        SubmitFormat.PDF -> pdf?.readBytes() ?: FormSubmit.fdf(values, fileName)
        SubmitFormat.FDF -> FormSubmit.fdf(values, fileName)
    }

    /** POST an already built FDF body (FDF with comments) to the action's URL. */
    suspend fun sendBytes(a: ButtonAction.Submit, body: ByteArray): Result = withContext(Dispatchers.IO) {
        val url = a.url.trim()
        require(FormSubmit.isHttp(url)) { url }
        val req = Request.Builder().url(url).post(body.toRequestBody(FormSubmit.mime(SubmitFormat.FDF).toMediaType())).build()
        http.newCall(req).execute().use { r -> read(r) }
    }

    private fun read(r: okhttp3.Response): Result {
        val b = r.body
        val text = runCatching { b?.source()?.let { src -> src.request(MAX_BODY); val buf = src.buffer; buf.readByteArray(minOf(buf.size, MAX_BODY)).toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        return Result(r.code, r.message, b?.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty(), text)
    }

    suspend fun send(a: ButtonAction.Submit, values: Map<String, String>, fileName: String, pdf: File?): Result = withContext(Dispatchers.IO) {
        val url = a.url.trim()
        require(FormSubmit.isHttp(url)) { url }
        val req = if (a.get) {
            val sep = if (url.contains('?')) "&" else "?"
            Request.Builder().url(url + sep + FormSubmit.htmlBody(values)).get().build()
        } else {
            val mime = FormSubmit.mime(if (a.format == SubmitFormat.PDF && pdf == null) SubmitFormat.FDF else a.format)
            Request.Builder().url(url).post(payload(a, values, fileName, pdf).toRequestBody(mime.toMediaType())).build()
        }
        http.newCall(req).execute().use { r -> read(r) }
    }
}
