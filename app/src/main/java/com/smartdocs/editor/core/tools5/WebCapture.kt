package com.smartdocs.editor.core.tools5

import android.content.Context
import android.print.WebPdfWriter
import com.smartdocs.editor.core.cloud.CloudHttp
import com.smartdocs.editor.core.edit5.HFSpec
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FINAL_CHECK_2 Low (B #13 / #14 / #15 — Foxit web-capture options): follow the page's links for N levels (optionally
 * the whole site only), convert every page and bind them into one PDF with a bookmark per page; a header / footer
 * with the URL, title and date (on each page, or on a separate first page); the "screen" look instead of the print
 * style sheets; a zoom. The link crawl is pure Kotlin (tested); the conversion is the web writer.
 */
object WebCapture {

    data class Options(
        /** 0 = this page only; 1..3 = linked pages that many levels deep. */
        val levels: Int = 0,
        /** Follow links on the same host only ("whole site"). */
        val sameSite: Boolean = true,
        /** At most this many pages in all. */
        val maxPages: Int = 20,
        /** The page as on screen: the print style sheets are disabled before printing. */
        val screenLook: Boolean = false,
        /** Zoom in percent (100 = as is). */
        val zoom: Int = 100,
        /** Header / footer text with {url} {title} {date} {page} {pages} ("" = none). */
        val header: String = "",
        val footer: String = "",
        /** The header / footer information on a separate first page instead of every page. */
        val infoPage: Boolean = false,
    )

    private val HREF = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title[^>]*>([^<]{0,200})</title>""", RegexOption.IGNORE_CASE)

    /** The links of an HTML page resolved against [base] (http / https only, no fragments, no duplicates, document order). Pure. */
    fun links(html: String, base: String, sameSite: Boolean): List<String> {
        val b = runCatching { URI(base) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (m in HREF.findAll(html)) {
            val raw = m.groupValues[1].trim().replace("&amp;", "&").substringBefore('#')
            if (raw.isEmpty()) continue
            if (raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true)) continue
            val u = runCatching { b.resolve(raw).normalize() }.getOrNull() ?: continue
            if (u.scheme != "http" && u.scheme != "https" || u.host.isNullOrBlank()) continue
            if (sameSite && !u.host.equals(b.host, true)) continue
            val clean = URI(u.scheme, u.authority, u.path, u.query, null).toString()
            if (clean.endsWith(".pdf", true) || clean.endsWith(".zip", true) || clean.endsWith(".jpg", true) || clean.endsWith(".png", true)) continue
            out += clean
        }
        return out.toList()
    }

    /** Breadth-first plan of the pages to capture from [start]: (url, depth), [levels] deep, [maxPages] at most. [fetch] returns a page's HTML or null. */
    fun plan(start: String, levels: Int, sameSite: Boolean, maxPages: Int, fetch: (String) -> String?): List<Pair<String, Int>> {
        val startUrl = if (start.contains("://")) start else "https://$start"
        val seen = linkedSetOf(startUrl)
        val out = arrayListOf(startUrl to 0)
        var i = 0
        while (i < out.size && out.size < maxPages) {
            val (u, d) = out[i++]
            if (d >= levels) continue
            val html = fetch(u) ?: continue
            for (l in links(html, u, sameSite)) { if (out.size >= maxPages) break; if (seen.add(l)) out += l to d + 1 }
        }
        return out
    }

    private fun fetchHtml(url: String): String? = runCatching {
        CloudHttp.client.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { r ->
            if (!r.isSuccessful || r.header("Content-Type")?.contains("html", true) != true) null else r.body?.string()?.take(2_000_000)
        }
    }.getOrNull()

    /** The JavaScript that turns the print style sheets off and applies the zoom (run before printing a URL). */
    fun screenJs(screenLook: Boolean, zoom: Int): String? {
        val parts = ArrayList<String>()
        if (screenLook) parts += """try{document.querySelectorAll('link[media],style[media]').forEach(function(e){if(/print/i.test(e.media))e.disabled=true;});Array.prototype.forEach.call(document.styleSheets,function(s){try{var r=s.cssRules;for(var i=r.length-1;i>=0;i--){if(r[i].media&&/print/i.test(r[i].media.mediaText))s.deleteRule(i);}}catch(e){}});}catch(e){}"""
        if (zoom in 25..400 && zoom != 100) parts += "document.documentElement.style.zoom='${zoom}%';"
        return if (parts.isEmpty()) null else "(function(){" + parts.joinToString("") + "})()"
    }

    /**
     * Capture [start] with [o]: every planned page converted, bound into [out] with a bookmark per page, then the
     * header / footer. [convert] turns one URL into a PDF file (the web writer). Returns the titles in order.
     */
    suspend fun capture(ctx: Context, start: String, o: Options, out: File, tempDir: File, setup: WebPdfWriter.Setup, disableLinks: Boolean,
                        onProgress: (Int, Int) -> Unit = { _, _ -> }, convert: suspend (String, File, WebPdfWriter.Setup) -> File): List<String> {
        val pages = withContext(Dispatchers.IO) { if (o.levels <= 0) listOf(start to 0) else plan(start, o.levels, o.sameSite, o.maxPages.coerceIn(1, 200), ::fetchHtml) }
        val titles = ArrayList<String>()
        val files = ArrayList<File>()
        val js = screenJs(o.screenLook, o.zoom)
        val setupW = if (js == null) setup else setup.copy(preScript = js)
        try {
            pages.forEachIndexed { i, (u, _) ->
                onProgress(i, pages.size)
                val f = File(tempDir, "web_${System.nanoTime()}_$i.pdf")
                val ok = runCatching { convert(u, f, setupW) }.isSuccess && f.length() > 0
                if (!ok) { f.delete(); return@forEachIndexed }
                if (disableLinks) withContext(Dispatchers.IO) { WebCss.stripLinks(f) }
                files += f
                titles += withContext(Dispatchers.IO) { runCatching { PdfOps.load(f, null).use { it.documentInformation?.title } }.getOrNull()?.takeIf { it.isNotBlank() } ?: u }
            }
            if (files.isEmpty()) throw IllegalStateException("empty PDF")
            if (files.size == 1) files[0].copyTo(out, overwrite = true) else {
                PdfOps.merge(files, out)
                // a bookmark per captured page
                withContext(Dispatchers.IO) {
                    val counts = files.map { f -> runCatching { PdfOps.load(f, null).use { it.numberOfPages } }.getOrDefault(1) }
                    com.smartdocs.editor.core.edit5.E5Io.edit(out, out, null) { d ->
                        val root = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
                        var p = 0
                        titles.forEachIndexed { k, t ->
                            val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply { title = t.take(120); setDestination(d.getPage(p.coerceAtMost(d.numberOfPages - 1))) }
                            root.addLast(item); p += counts.getOrElse(k) { 1 }
                        }
                        d.documentCatalog.documentOutline = root
                    }
                }
            }
            if (o.header.isNotBlank() || o.footer.isNotBlank()) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                fun fill(t: String) = t.replace("{url}", start).replace("{title}", titles.firstOrNull() ?: start).replace("{date}", date)
                if (o.infoPage) {
                    // a first page carrying the information
                    val info = File(tempDir, "web_info_${System.nanoTime()}.pdf")
                    PdfOps.createBlank(info, 1)
                    HeaderFooterOps.apply(ctx, info, info, null, HFSpec(topCenter = fill(o.header), bottomCenter = fill(o.footer), marginTop = 72f, marginBottom = 72f))
                    val merged = File(tempDir, "web_bound_${System.nanoTime()}.pdf")
                    PdfOps.merge(listOf(info, out), merged); merged.copyTo(out, overwrite = true); merged.delete(); info.delete()
                } else HeaderFooterOps.apply(ctx, out, out, null, HFSpec(topCenter = fill(o.header), bottomCenter = fill(o.footer)))
            }
            onProgress(pages.size, pages.size)
        } finally { files.forEach { runCatching { it.delete() } } }
        return titles
    }
}
