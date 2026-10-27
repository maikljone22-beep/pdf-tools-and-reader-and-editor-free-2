package com.smartdocs.editor.core.ai6

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A fetched web page as readable text. */
data class WebPage(val url: String, val title: String, val text: String)

/**
 * ai6 (B9): web links as Space sources. The page is fetched directly (no proxy needed) and reduced to readable
 * text: scripts, styles, navigation, headers, footers and forms are dropped; block tags become line breaks;
 * entities are decoded. [htmlToText] is pure and unit tested.
 */
object WebText {
    const val MAX_BYTES = 3 * 1024 * 1024
    const val MAX_CHARS = 400_000

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private val DROP = Regex("(?is)<(script|style|noscript|template|svg|nav|header|footer|form|aside|iframe|head)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK = Regex("(?i)</?(p|div|section|article|main|h[1-6]|li|ul|ol|tr|table|blockquote|pre|br|hr|dd|dt|figcaption|caption)\\b[^>]*>")
    private val CELL = Regex("(?i)</t[dh]\\s*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val OG_TITLE = Regex("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']")
    private val NUM_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

    fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val u = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?')
        return if (host.contains('.') && host.length >= 4) u else null
    }

    fun title(html: String): String {
        val og = OG_TITLE.find(html)?.groupValues?.get(1)
        val t = og?.takeIf { it.isNotBlank() } ?: TITLE.find(html)?.groupValues?.get(1).orEmpty()
        return decode(t).replace(Regex("\\s+"), " ").trim()
    }

    /** Main text of an HTML page; prefers `<article>` / `<main>` when present and substantial. */
    fun htmlToText(html: String): String {
        var h = COMMENT.replace(html, " ")
        h = DROP.replace(h, " ")
        val main = Regex("(?is)<(article|main)\\b[^>]*>(.*?)</\\1\\s*>").findAll(h).map { it.groupValues[2] }.maxByOrNull { it.length }
        if (main != null && TAG.replace(main, "").trim().length > 400) h = main
        h = CELL.replace(h, "\t")
        h = BLOCK.replace(h, "\n")
        h = TAG.replace(h, " ")
        h = decode(h)
        return h.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .fold(ArrayList<String>()) { acc, l -> if (!(l.isEmpty() && (acc.isEmpty() || acc.last().isEmpty()))) acc += l; acc }
            .joinToString("\n").trim().take(MAX_CHARS)
    }

    fun decode(s: String): String {
        var r = NUM_ENTITY.replace(s) { m ->
            val n = runCatching { if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toInt(16) else m.groupValues[2].toInt() }.getOrNull()
            if (n == null || n <= 0 || n > 0x10FFFF) "" else String(Character.toChars(n))
        }
        r = r.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…").replace("&amp;", "&")
        return r
    }

    /** Fetch [url] and extract its text. Plain-text responses are kept as they are. */
    suspend fun fetch(url: String): WebPage = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) SmartDocs").header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("empty")
            val bytes = body.source().let { src -> src.request(MAX_BYTES.toLong()); src.buffer.snapshot().toByteArray().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it } }
            val ct = r.header("Content-Type").orEmpty().lowercase()
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val raw = String(bytes, charset)
            val finalUrl = r.request.url.toString()
            if (ct.startsWith("text/plain")) WebPage(finalUrl, finalUrl.substringAfterLast('/').ifBlank { finalUrl }, raw.take(MAX_CHARS))
            else WebPage(finalUrl, title(raw).ifBlank { r.request.url.host }, htmlToText(raw))
        }
    }
}
