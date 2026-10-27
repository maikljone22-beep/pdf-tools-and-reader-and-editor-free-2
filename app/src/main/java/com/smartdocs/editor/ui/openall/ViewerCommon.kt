package com.smartdocs.editor.ui.openall

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import com.smartdocs.editor.core.openall.FType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** Icon of a file type (Files tab, info screens). */
fun iconFor(t: FType): ImageVector = when (t) {
    FType.PDF -> Icons.Default.PictureAsPdf
    FType.DOCX, FType.DOC, FType.RTF, FType.ODT -> Icons.Default.Description
    FType.TEXT, FType.MARKDOWN -> Icons.Default.TextSnippet
    FType.CODE -> Icons.Default.Code
    FType.HTML, FType.MHT -> Icons.Default.Language
    FType.XPS, FType.DJVU -> Icons.Default.InsertDriveFile
    FType.EPUB, FType.FB2, FType.MOBI, FType.CHM -> Icons.Default.AutoStories
    FType.XLSX, FType.XLS, FType.ODS, FType.CSV -> Icons.Default.TableChart
    FType.PPTX, FType.PPT, FType.ODP -> Icons.Default.Slideshow
    FType.IWORK -> Icons.Default.Description
    FType.IMAGE, FType.SVG, FType.TIFF, FType.HEIF, FType.AVIF, FType.RAW -> Icons.Default.Image
    FType.CBZ, FType.CB7, FType.CBR -> Icons.Default.Collections
    FType.ZIP, FType.SEVENZ, FType.TAR, FType.GZIP, FType.BZIP2, FType.XZ, FType.RAR -> Icons.Default.FolderZip
    FType.EML, FType.MSG -> Icons.Default.Email
    FType.VCF -> Icons.Default.Contacts
    FType.ICS -> Icons.Default.Event
    FType.AUDIO -> Icons.Default.AudioFile
    FType.VIDEO -> Icons.Default.VideoFile
    FType.FONT, FType.WOFF, FType.WOFF2 -> Icons.Default.FontDownload
    FType.DXF, FType.DWG -> Icons.Default.Architecture
    FType.FDF -> Icons.Default.Comment
    FType.UNKNOWN -> Icons.Default.HelpOutline
}

/** A resource the safe WebView serves: bytes + MIME type. */
class WebRes(val mime: String, val bytes: ByteArray, val charset: String? = null)

/**
 * WebView for untrusted documents (open-all): JavaScript off unless [allowJs], no file:// or content:// access, every
 * request answered by [resolver] (the document itself, its archive resources) and network requests refused unless
 * [allowNetwork] — [onBlocked] reports the first refused one. Link taps never navigate the view: [onLink] gets them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SafeWebView(
    url: String,
    resolver: (String) -> WebRes?,
    modifier: Modifier = Modifier,
    allowNetwork: Boolean = false,
    allowJs: Boolean = false,
    textZoom: Int = 100,
    onBlocked: () -> Unit = {},
    onLink: (String) -> Unit = {},
    /** Changing it reloads the page (new content behind the same URL). */
    reloadKey: Any? = null,
) {
    val res = rememberUpdatedState(resolver)
    val net = rememberUpdatedState(allowNetwork)
    val blocked = rememberUpdatedState(onBlocked)
    val link = rememberUpdatedState(onLink)
    val holder = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) { onDispose { holder[0]?.let { runCatching { it.stopLoading(); it.destroy() } } } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                holder[0] = this
                settings.apply {
                    javaScriptEnabled = allowJs
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    domStorageEnabled = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    this.textZoom = textZoom
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        val r = runCatching { res.value(u.substringBefore('#')) }.getOrNull()
                        if (r != null) return WebResourceResponse(r.mime, r.charset ?: if (r.mime.startsWith("text/")) "utf-8" else null, ByteArrayInputStream(r.bytes))
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme == "data" || scheme == "blob") return null
                        if ((scheme == "http" || scheme == "https") && net.value) return null
                        if (scheme == "http" || scheme == "https") view.post { blocked.value() }
                        return WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        // same-document anchors scroll inside the page
                        val cur = view.url
                        if (cur != null && u.substringBefore('#') == cur.substringBefore('#') && u.contains('#')) return false
                        link.value(u)
                        return true
                    }
                }
                loadUrl(url)
            }
        },
        update = { w ->
            if (w.settings.javaScriptEnabled != allowJs) w.settings.javaScriptEnabled = allowJs
            if (w.settings.textZoom != textZoom) w.settings.textZoom = textZoom
            val key = url + "\u0000" + reloadKey
            if (w.tag != key) {
                if (w.tag != null) w.loadUrl(url)   // the factory already loaded the first URL
                w.tag = key
            }
        },
    )
}

/** CSS for night reading injected into documents (no script). */
const val NIGHT_CSS = "<style>html,body{background:#121212!important;color:#e0e0e0!important}a{color:#8ab4f8!important}" +
    "*{background-color:transparent!important;color:inherit!important;border-color:#444!important}img,svg,video{filter:brightness(.85)}</style>"

/** Adds [css] before </head> (or at the start). */
fun injectHead(html: String, css: String): String {
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + css + html.substring(i) else css + html
}

/** Vector PDF from canvas drawing: one page per entry of [sizesPt] (points), drawn by [draw]. */
suspend fun canvasToPdf(out: File, sizesPt: List<Pair<Float, Float>>, draw: (Canvas, Int) -> Unit): File = withContext(Dispatchers.Default) {
    val doc = PdfDocument()
    try {
        sizesPt.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w.toInt().coerceIn(1, 14400), h.toInt().coerceIn(1, 14400), i + 1).create())
            runCatching { draw(page.canvas, i) }
            doc.finishPage(page)
        }
        withContext(Dispatchers.IO) { out.outputStream().buffered().use { doc.writeTo(it) } }
    } finally { doc.close() }
    out
}

/** Labels of e-mail header rows for the viewers and the PDF conversion. */
fun mailLabels(ctx: Context): Map<String, String> = mapOf(
    "from" to ctx.getString(com.smartdocs.editor.R.string.oa_mail_from), "to" to ctx.getString(com.smartdocs.editor.R.string.oa_mail_to),
    "cc" to ctx.getString(com.smartdocs.editor.R.string.oa_mail_cc), "date" to ctx.getString(com.smartdocs.editor.R.string.oa_mail_date),
    "subject" to ctx.getString(com.smartdocs.editor.R.string.oa_mail_subject), "attachments" to ctx.getString(com.smartdocs.editor.R.string.oa_attachments_plain),
)
