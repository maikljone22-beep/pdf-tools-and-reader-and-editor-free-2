package android.print

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Converts a web page (URL or HTML string) into a PDF file by driving the WebView's own
 * [PrintDocumentAdapter] directly, without showing the system print dialog.
 *
 * Lives in package `android.print` because the constructors of
 * [PrintDocumentAdapter.LayoutResultCallback] / [PrintDocumentAdapter.WriteResultCallback]
 * are package-private in the SDK stubs; being in the same package lets us subclass them
 * without reflection. Every method must be called on the main thread.
 */
object WebPdfWriter {

    /**
     * Page layout of a conversion (Foxit web-page settings): paper, orientation, margins in mils, how long to wait for
     * the page to load before converting what is there ([timeoutMs]), the settle delay after loading and extra CSS
     * injected before printing (images, backgrounds, link underline — see `WebCss`).
     */
    data class Setup(
        val media: PrintAttributes.MediaSize = PrintAttributes.MediaSize.ISO_A4,
        val landscape: Boolean = false,
        val marginMils: Int = 500,
        val timeoutMs: Long = 30_000,
        val settleMs: Long = 1200,
        val css: String = "",
    )

    /** Suspends until the page has been written to [out]. Call from the main dispatcher. */
    suspend fun convertUrl(context: Context, url: String, out: File, setup: Setup = Setup()): File = suspendCoroutine { cont ->
        convert(context, url, null, out, setup) { err -> if (err == null) cont.resume(out) else cont.resumeWithException(err) }
    }

    suspend fun convertHtml(context: Context, html: String, out: File, setup: Setup = Setup()): File = suspendCoroutine { cont ->
        convert(context, null, html, out, setup) { err -> if (err == null) cont.resume(out) else cont.resumeWithException(err) }
    }

    fun convert(context: Context, url: String?, html: String?, out: File, setup: Setup = Setup(), onDone: (Throwable?) -> Unit) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.domStorageEnabled = true
        webView.settings.blockNetworkLoads = !com.smartdocs.editor.core.data.NpfNet.allowed()   // nav-prefs: offline mode
        webView.layout(0, 0, 1240, 1754)
        var finished = false
        var done = false
        val finish = { err: Throwable? -> if (!done) { done = true; runCatching { webView.destroy() }; onDone(err) } }
        fun print(view: WebView) {
            if (finished) return
            finished = true
            if (setup.css.isNotBlank()) runCatching {
                val js = "(function(){var s=document.createElement('style');s.textContent=" + org.json.JSONObject.quote(setup.css) + ";(document.head||document.documentElement).appendChild(s);})()"
                view.evaluateJavascript(js, null)
            }
            // let late layout / images settle before printing
            view.postDelayed({ try { write(view, out, setup, finish) } catch (e: Throwable) { finish(e) } }, setup.settleMs)   // runs on the main looper: an exception here would kill the app
        }
        // page-load timeout: a page that never finishes loading is converted as it is
        if (setup.timeoutMs > 0) webView.postDelayed({ if (!done) print(webView) }, setup.timeoutMs)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) = print(view)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !finished) { finished = true; finish(IOException(error.description?.toString() ?: "load failed")) }
            }
        }
        if (html != null) webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) else webView.loadUrl(url ?: "about:blank")
    }

    private fun write(view: WebView, out: File, setup: Setup, onDone: (Throwable?) -> Unit) {
        val adapter = view.createPrintDocumentAdapter(out.nameWithoutExtension)
        val m = setup.marginMils.coerceIn(0, 2000)
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (setup.landscape) setup.media.asLandscape() else setup.media.asPortrait())
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins(m, m, m, m))
            .build()
        val layoutCb = object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val pfd = try {
                    ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                } catch (e: Exception) { onDone(e); return }
                val writeCb = object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) { runCatching { pfd.close() }; onDone(null) }
                    override fun onWriteFailed(error: CharSequence?) { runCatching { pfd.close() }; onDone(IOException(error?.toString() ?: "write failed")) }
                    override fun onWriteCancelled() { runCatching { pfd.close() }; onDone(IOException("cancelled")) }
                }
                try { adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(), writeCb) } catch (e: Throwable) { runCatching { pfd.close() }; onDone(e) }
            }

            override fun onLayoutFailed(error: CharSequence?) { onDone(IOException(error?.toString() ?: "layout failed")) }
            override fun onLayoutCancelled() { onDone(IOException("cancelled")) }
        }
        adapter.onLayout(null, attrs, CancellationSignal(), layoutCb, null)
    }
}
