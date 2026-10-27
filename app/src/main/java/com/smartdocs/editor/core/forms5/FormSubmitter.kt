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

    data class Result(val code: Int, val message: String)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Body bytes for [a] from the selected [values]; [pdf] is the filled document (used for SubmitPDF). */
    fun payload(a: ButtonAction.Submit, values: Map<String, String>, fileName: String, pdf: File?): ByteArray = when (a.format) {
        SubmitFormat.HTML -> FormSubmit.htmlBody(values).toByteArray(Charsets.UTF_8)
        SubmitFormat.XFDF -> FormSubmit.xfdf(values, fileName).toByteArray(Charsets.UTF_8)
        SubmitFormat.PDF -> pdf?.readBytes() ?: FormSubmit.fdf(values, fileName)
        SubmitFormat.FDF -> FormSubmit.fdf(values, fileName)
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
        http.newCall(req).execute().use { r -> Result(r.code, r.message) }
    }
}
