package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * fix/links-bookmarks (Foxit §15 "View Web Links" and "Append All Web Links"): the web links of a page range, and
 * appending the converted web pages to the document with a bookmark per page (grouped by domain) and the links
 * re-pointed at the appended pages. The download / conversion itself is done by the caller (ToPdf.url / HTTP).
 */
object WebCapture {
    /** A web link target and the pages (zero-based) it appears on. */
    data class WebLink(val url: String, val pages: List<Int>)

    /** Comparison key of a URL: scheme and host in lower case, no fragment, no trailing slash. */
    fun key(url: String): String {
        var u = url.trim().substringBefore('#')
        val m = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*://)([^/?]*)(.*)$""").find(u)
        if (m != null) u = m.groupValues[1].lowercase() + m.groupValues[2].lowercase() + m.groupValues[3]
        return u.trimEnd('/')
    }

    /** Host of [url] without "www.", or the URL itself when it has none. */
    fun host(url: String): String =
        Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://([^/?#:]+)""").find(url.trim())?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: url.trim()

    /** A file name for the download of [url] (host + last path part, safe characters only). */
    fun fileName(url: String): String {
        val path = url.substringAfter("://", url).substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/', "").takeIf { path.contains('/') && it.isNotBlank() }
        return (host(url) + (last?.let { "_$it" } ?: "")).replace(Regex("""[^A-Za-z0-9._\-]"""), "_").take(80).ifBlank { "web" }
    }

    /** The http(s) links of [links] (mailto and other schemes left out), one entry per URL, in page order. */
    fun collect(links: List<PageLinkInfo>): List<WebLink> {
        val byKey = LinkedHashMap<String, Pair<String, MutableList<Int>>>()
        links.sortedWith(compareBy({ it.page }, { it.index })).forEach { l ->
            val u = webUrl(l.spec) ?: return@forEach
            val e = byKey.getOrPut(key(u)) { u to ArrayList() }
            if (l.page !in e.second) e.second += l.page
        }
        return byKey.values.map { (u, p) -> WebLink(u, p) }
    }

    private fun webUrl(s: LinkSpec): String? {
        val u = when (s.kind) {
            LinkKind.URI -> s.uri
            LinkKind.OTHER -> (s.actions.firstOrNull { it is NavAction.Uri } as? NavAction.Uri)?.uri
            else -> null
        } ?: return null
        return u.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * Bookmarks for appended web pages: (url, title, first page) → one item per page; with [byDomain] they are
     * grouped under a top-level item per host (Acrobat's web capture layout). Pure.
     */
    fun bookmarks(parts: List<Triple<String, String, Int>>, byDomain: Boolean): List<E5Bookmark> {
        if (!byDomain) return parts.map { (_, t, p) -> E5Bookmark(OutlineModel.newKey(), t, p, 0) }
        val out = ArrayList<E5Bookmark>()
        parts.groupBy { host(it.first) }.forEach { (h, l) ->
            out += E5Bookmark(OutlineModel.newKey(), h, l.first().third, 0, open = true)
            l.forEach { (_, t, p) -> out += E5Bookmark(OutlineModel.newKey(), t, p, 1) }
        }
        return out
    }

    /**
     * Append the PDFs of [parts] (url → converted file) at the end of [src]. [bookmarks] adds bookmarks (grouped by
     * domain when [byDomain]); [relink] turns the web links of the original pages that point at an appended URL into
     * links to its first appended page. Returns the number of pages added.
     */
    suspend fun append(src: File, out: File, password: String?, parts: List<Pair<String, File>>, bookmarks: Boolean, byDomain: Boolean, relink: Boolean): Int = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val opened = ArrayList<PDDocument>()
            try {
                val n0 = doc.numberOfPages
                val firsts = ArrayList<Triple<String, String, Int>>()
                for ((url, f) in parts) {
                    val od = runCatching { E5Io.load(f, null) }.getOrNull() ?: continue
                    opened += od
                    if (od.numberOfPages == 0) continue
                    val start = doc.numberOfPages
                    PageImport.importPages(doc, od, (0 until od.numberOfPages).toList()).forEach { doc.pages.add(it) }
                    val title = od.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: url
                    firsts += Triple(url, title, start)
                }
                if (relink && firsts.isNotEmpty()) {
                    val target = firsts.associate { key(it.first) to doc.getPage(it.third).cosObject }
                    for (i in 0 until n0) {
                        val annots = runCatching { doc.getPage(i).annotations }.getOrNull() ?: continue
                        for (a in annots) {
                            if (a !is PDAnnotationLink) continue
                            val act = a.cosObject.getDictionaryObject(COSName.A) as? COSDictionary ?: continue
                            if (act.getNameAsString(COSName.S) != "URI" || act.getDictionaryObject(COSName.NEXT) != null) continue
                            val pg = target[key(act.getString(COSName.URI) ?: continue)] ?: continue
                            a.cosObject.removeItem(COSName.A)
                            a.cosObject.setItem(COSName.DEST, COSArray().apply { add(pg); add(COSName.getPDFName("Fit")) })
                        }
                    }
                }
                if (bookmarks && firsts.isNotEmpty()) OutlineStore.write(doc, OutlineStore.read(doc) + bookmarks(firsts, byDomain))
                E5Io.save(doc, out)
                doc.numberOfPages - n0
            } finally { opened.forEach { runCatching { it.close() } } }
        }
    }

    private val http by lazy {
        okhttp3.OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
    }

    /** Extension for a downloaded file from its mime type ("" when the URL's last path part [segment] has one, or the type is unknown). */
    fun extFor(mime: String, segment: String): String {
        if (segment.contains('.')) return ""
        return when (mime.substringBefore(';').trim().lowercase()) {
            "text/html", "application/xhtml+xml" -> ".html"; "application/pdf" -> ".pdf"; "text/plain" -> ".txt"
            "image/png" -> ".png"; "image/jpeg" -> ".jpg"; "image/gif" -> ".gif"; "application/zip" -> ".zip"
            else -> ""
        }
    }

    /** [name] in [dir], with " (2)", " (3)" … before the extension when it is taken. */
    fun unique(dir: File, name: String): File {
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(dir, name); var n = 2
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /** Download [url] as is into [dir] (offline mode is honoured). Returns the file. */
    suspend fun download(url: String, dir: File): File = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.data.NpfNet.check()
        http.newCall(okhttp3.Request.Builder().url(url.trim()).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("empty")
            val base = fileName(url)
            val path = url.substringAfter("://", url).substringBefore('?').substringBefore('#').trimEnd('/')
            val ext = extFor(body.contentType()?.toString() ?: "", if (path.contains('/')) path.substringAfterLast('/') else "")
            val name = if (ext.isNotEmpty() && !base.endsWith(ext)) base + ext else base
            dir.mkdirs()
            val f = unique(dir, name)
            f.outputStream().use { o -> body.byteStream().use { it.copyTo(o) } }
            f
        }
    }
}
