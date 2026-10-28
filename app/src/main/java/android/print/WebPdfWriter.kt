package android.print

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Converts a web page (URL or HTML string) into a PDF file by driving the WebView's own
 * [PrintDocumentAdapter] directly, without showing the system print dialog.
 *
 * Lives in package `android.print` because the constructors of
 * [PrintDocumentAdapter.LayoutResultCallback] / [PrintDocumentAdapter.WriteResultCallback]
 * are package-private in the SDK stubs; being in the same package lets us subclass them
 * without reflection. Every method must be called on the main thread.
 *
 * Timeouts (convert audit A-H2 / A-H3):
 *  - a web address: many sites never report "finished" (ads, live content), so the page is printed once it has loaded
 *    and settled, or after [Setup.timeoutMs] if something rendered; nothing usable by [HARD_TIMEOUT_MS] fails with
 *    [TimeoutException];
 *  - local HTML (documents, books, e-mails): printing starts only when the page has really finished loading (no soft
 *    timeout that printed a half-laid-out document); [LOCAL_LOAD_LIMIT_MS] only guards against a page that never ends;
 *  - printing has its own watchdog that follows the progress of the output file: a long document may take minutes,
 *    only a print that stops growing for [PRINT_STALL_MS] fails.
 * A renderer process that dies (out of memory on a huge document) fails this conversion with
 * [com.smartdocs.editor.core.convert6.RendererGone] instead of killing the app (audit A-C1).
 * Cancelling the coroutine stops loading, cancels the print adapter and destroys the WebView at once.
 */
object WebPdfWriter {

    /** Give up loading a web address after this long (nothing usable arrived). */
    const val HARD_TIMEOUT_MS = 60_000L

    /** Local HTML that has not finished loading after this long is printed as it is (or fails when empty). */
    const val LOCAL_LOAD_LIMIT_MS = 10 * 60_000L

    /** Layout of the print (before the first byte) may take this long. */
    const val LAYOUT_LIMIT_MS = 6 * 60_000L

    /** A print whose output file does not grow for this long is considered hung. */
    const val PRINT_STALL_MS = 3 * 60_000L

    class TimeoutException(msg: String) : IOException(msg)

    /**
     * Page layout of a conversion (Foxit web-page settings): paper, orientation, margins in mils (or per side in
     * [marginsMils]: left, top, right, bottom), how long to wait for a web page to load before converting what is there
     * ([timeoutMs], web addresses only), the settle delay after loading and extra CSS injected before printing.
     * [javaScript] / [blockNetwork]: untrusted local content (HTML files, e-mails, books) is printed without scripts,
     * and e-mails / books without remote loads (read receipts, tracking pixels — audit A-M6).
     */
    data class Setup(
        val media: PrintAttributes.MediaSize = PrintAttributes.MediaSize.ISO_A4,
        val landscape: Boolean = false,
        val marginMils: Int = 500,
        val timeoutMs: Long = 25_000,
        val settleMs: Long = 1200,
        val css: String = "",
        val javaScript: Boolean = true,
        val blockNetwork: Boolean = false,
        val marginsMils: IntArray? = null,
    )

    /** Suspends until the page has been written to [out]. Call from the main dispatcher. Cancellable. */
    suspend fun convertUrl(context: Context, url: String, out: File, setup: Setup = Setup()): File = suspendCancellableCoroutine { cont ->
        val run = convert(context, url, null, out, setup) { err -> if (cont.isActive) { if (err == null) cont.resume(out) else cont.resumeWithException(err) } }
        cont.invokeOnCancellation { run.cancel() }
    }

    suspend fun convertHtml(context: Context, html: String, out: File, setup: Setup = Setup(javaScript = false)): File = suspendCancellableCoroutine { cont ->
        val run = convert(context, null, html, out, setup) { err -> if (cont.isActive) { if (err == null) cont.resume(out) else cont.resumeWithException(err) } }
        cont.invokeOnCancellation { run.cancel() }
    }

    /** A running conversion; [cancel] (any thread) stops it and frees the WebView. */
    class Run internal constructor(private val stop: () -> Unit) { fun cancel() = stop() }

    /** [html] with [css] added at the end of its head (scripts are off for local content, so CSS cannot be injected later). */
    internal fun withCss(html: String, css: String): String {
        if (css.isBlank()) return html
        val style = "<style>$css</style>"
        val i = html.indexOf("</head>", ignoreCase = true)
        return if (i >= 0) html.substring(0, i) + style + html.substring(i) else style + html
    }

    fun convert(context: Context, url: String?, html: String?, out: File, setup: Setup = Setup(), onDone: (Throwable?) -> Unit): Run {
        val main = Handler(Looper.getMainLooper())
        val isUrl = html == null
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = setup.javaScript && isUrl
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.domStorageEnabled = isUrl
        webView.settings.allowContentAccess = false
        webView.settings.blockNetworkLoads = setup.blockNetwork || !com.smartdocs.editor.core.data.NpfNet.allowed()   // nav-prefs: offline mode
        webView.layout(0, 0, 1240, 1754)
        val signals = mutableListOf<CancellationSignal>()
        var printing = false
        var done = false
        var progress = 0
        var mainFrameError: Throwable? = null
        val timers = mutableListOf<Runnable>()
        fun clearTimers() { timers.forEach { main.removeCallbacks(it) }; timers.clear() }
        fun schedule(delay: Long, r: () -> Unit) { val run = Runnable { if (!done) r() }; timers += run; main.postDelayed(run, delay) }
        val finish = { err: Throwable? ->
            if (!done) {
                done = true
                clearTimers()
                signals.forEach { runCatching { it.cancel() } }
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
                if (err != null) runCatching { out.delete() }
                onDone(err)
            }
        }
        fun watchPrint() {
            // progress-aware watchdog: layout may take minutes on a long document, then the file has to keep growing
            val started = System.currentTimeMillis()
            var lastLen = -1L
            var lastGrowth = started
            fun tick() {
                schedule(10_000) {
                    val now = System.currentTimeMillis()
                    val len = if (out.exists()) out.length() else 0L
                    if (len > lastLen) { if (lastLen >= 0 || len > 0) lastGrowth = now; lastLen = len }
                    when {
                        len == 0L && now - started > LAYOUT_LIMIT_MS -> finish(TimeoutException("timeout"))
                        len > 0L && now - lastGrowth > PRINT_STALL_MS -> finish(TimeoutException("timeout"))
                        else -> tick()
                    }
                }
            }
            tick()
        }
        fun startPrint() {
            if (printing || done) return
            printing = true
            clearTimers()
            if (isUrl && setup.css.isNotBlank() && webView.settings.javaScriptEnabled) runCatching {
                val js = "(function(){var s=document.createElement('style');s.textContent=" + org.json.JSONObject.quote(setup.css) + ";(document.head||document.documentElement).appendChild(s);})()"
                webView.evaluateJavascript(js, null)
            }
            // let late layout / images settle before printing
            schedule(setup.settleMs) {
                watchPrint()
                try { write(webView, out, setup, signals) { err -> main.post { finish(err) } } } catch (e: Throwable) { finish(e) }   // runs on the main looper: an exception here would kill the app
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { if (!printing) mainFrameError = null }

            override fun onPageFinished(view: WebView, u: String) {
                if (printing || done) return
                mainFrameError?.let { finish(it); return }
                startPrint()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !printing) finish(IOException(error.description?.toString()?.ifBlank { null } ?: "load failed"))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && !printing && errorResponse.statusCode >= 400)
                    mainFrameError = IOException("HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}".trim())
            }

            // local content never navigates (meta refresh, links, scripts); a web page may redirect until it has loaded
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isUrl || printing

            // the renderer died (usually out of memory): fail this conversion, never the app (convert audit A-C1)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val crashed = runCatching { detail.didCrash() }.getOrDefault(false)
                // finish() destroys this WebView; returning true keeps the app process alive
                main.post { finish(com.smartdocs.editor.core.convert6.RendererGone(crashed)) }
                return true
            }
        }
        if (isUrl) {
            // Soft timeout: the page still reports "loading" (ads, live content) — convert what is there if anything rendered.
            if (setup.timeoutMs > 0) schedule(setup.timeoutMs) {
                when {
                    mainFrameError != null -> finish(mainFrameError)
                    progress >= 10 || webView.contentHeight > 0 -> startPrint()
                }
            }
            // Hard timeout of the load: nothing usable arrived.
            schedule(maxOf(HARD_TIMEOUT_MS, setup.timeoutMs + 20_000)) { if (!printing) finish(TimeoutException("timeout")) }
        } else {
            // local HTML: wait for the real end of loading; only a page that never ends is printed as it is
            schedule(LOCAL_LOAD_LIMIT_MS) { if (!printing) { if (webView.contentHeight > 0) startPrint() else finish(TimeoutException("timeout")) } }
        }
        if (html != null) webView.loadDataWithBaseURL(null, withCss(html, setup.css), "text/html", "utf-8", null) else webView.loadUrl(url ?: "about:blank")
        return Run { main.post { finish(kotlinx.coroutines.CancellationException("cancelled")) } }
    }

    private fun write(view: WebView, out: File, setup: Setup, signals: MutableList<CancellationSignal>, onDone: (Throwable?) -> Unit) {
        val adapter = view.createPrintDocumentAdapter(out.nameWithoutExtension)
        val m = setup.marginMils.coerceIn(0, 2000)
        val ms = setup.marginsMils?.takeIf { it.size == 4 }?.map { it.coerceIn(0, 3000) }
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (setup.landscape) setup.media.asLandscape() else setup.media.asPortrait())
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(if (ms != null) PrintAttributes.Margins(ms[0], ms[1], ms[2], ms[3]) else PrintAttributes.Margins(m, m, m, m))
            .build()
        val layoutSignal = CancellationSignal().also { signals += it }
        val layoutCb = object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val pfd = try {
                    ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                } catch (e: Exception) { onDone(e); return }
                val writeCb = object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) { runCatching { pfd.close() }; onDone(null) }
                    override fun onWriteFailed(error: CharSequence?) { runCatching { pfd.close() }; onDone(IOException(error?.toString() ?: "write failed")) }
                    override fun onWriteCancelled() { runCatching { pfd.close() }; onDone(kotlinx.coroutines.CancellationException("cancelled")) }
                }
                val writeSignal = CancellationSignal().also { signals += it }
                try { adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, writeSignal, writeCb) } catch (e: Throwable) { runCatching { pfd.close() }; onDone(e) }
            }

            override fun onLayoutFailed(error: CharSequence?) { onDone(IOException(error?.toString() ?: "layout failed")) }
            override fun onLayoutCancelled() { onDone(kotlinx.coroutines.CancellationException("cancelled")) }
        }
        adapter.onLayout(null, attrs, layoutSignal, layoutCb, null)
    }
}
