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

    /** Suspends until the page has been written to [out]. Call from the main dispatcher. */
    suspend fun convertUrl(context: Context, url: String, out: File): File = suspendCoroutine { cont ->
        convert(context, url, null, out) { err -> if (err == null) cont.resume(out) else cont.resumeWithException(err) }
    }

    suspend fun convertHtml(context: Context, html: String, out: File): File = suspendCoroutine { cont ->
        convert(context, null, html, out) { err -> if (err == null) cont.resume(out) else cont.resumeWithException(err) }
    }

    fun convert(context: Context, url: String?, html: String?, out: File, onDone: (Throwable?) -> Unit) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.domStorageEnabled = true
        webView.layout(0, 0, 1240, 1754)
        var finished = false
        var done = false
        val finish = { err: Throwable? -> if (!done) { done = true; runCatching { webView.destroy() }; onDone(err) } }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) {
                if (finished) return
                finished = true
                // let late layout / images settle before printing
                view.postDelayed({ write(view, out, finish) }, 1200)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !finished) { finished = true; finish(IOException(error.description?.toString() ?: "load failed")) }
            }
        }
        if (html != null) webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) else webView.loadUrl(url ?: "about:blank")
    }

    private fun write(view: WebView, out: File, onDone: (Throwable?) -> Unit) {
        val adapter = view.createPrintDocumentAdapter(out.nameWithoutExtension)
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins(500, 500, 500, 500))
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
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(), writeCb)
            }

            override fun onLayoutFailed(error: CharSequence?) { onDone(IOException(error?.toString() ?: "layout failed")) }
            override fun onLayoutCancelled() { onDone(IOException("cancelled")) }
        }
        adapter.onLayout(null, attrs, CancellationSignal(), layoutCb, null)
    }
}
