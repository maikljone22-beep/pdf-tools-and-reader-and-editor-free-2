package com.smartdocs.editor.ui.reader

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.core.util.PdfPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small intent / platform helpers used by the reader screen. */
object ReaderIntents {

    fun openLink(context: Context, link: PageLink, jumpTo: (Int) -> Unit) {
        link.destPage?.let { if (it >= 0) { jumpTo(it); return } }
        val uri = link.uri?.trim().orEmpty()
        if (uri.isEmpty()) return
        openUri(context, uri)
    }

    fun openUri(context: Context, raw: String) {
        // NVE: safe reading - links to untrusted hosts ask first (NveTrust, Settings)
        com.smartdocs.editor.ui.nve.shell.NveTrust.guardLink(context, raw) { openUriNow(context, raw) }
    }

    private fun openUriNow(context: Context, raw: String) {
        val uri = runCatching { Uri.parse(if (raw.contains("://") || raw.startsWith("mailto:") || raw.startsWith("tel:")) raw else "https://$raw") }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val ok = runCatching {
                CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, uri)
            }.isSuccess
            if (ok) return
        }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, raw, Toast.LENGTH_SHORT).show() }
    }

    fun copy(context: Context, text: String, label: String = "text") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // a huge clip (whole-document highlights / text) throws TransactionTooLargeException
        runCatching { cm.setPrimaryClip(ClipData.newPlainText(label, text.take(200_000))) }
    }

    fun shareText(context: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text.take(100_000)) }   // binder limit
        runCatching { context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun webSearch(context: Context, query: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, query) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { openUri(context, "https://www.google.com/search?q=" + Uri.encode(query)) }
    }

    fun shareFile(context: Context, app: SmartDocsApp, file: File) {
        runCatching { context.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file)), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun print(context: Context, file: File, pageCount: Int) {
        runCatching { PdfPrint.print(context, file, file.nameWithoutExtension, pageCount) }
    }

    /** Decode an image file (signature / picked picture) at a size suitable for stamping. */
    suspend fun decode(file: File, maxDim: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            var sample = 1
            while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull()
    }
}
