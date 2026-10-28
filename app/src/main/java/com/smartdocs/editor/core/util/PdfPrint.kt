package com.smartdocs.editor.core.util

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Print a finished PDF through the system print dialog. The adapter streams the file as-is
 * (page selection is left to the print framework which re-renders the PDF).
 */
object PdfPrint {

    /**
     * [mono] preselects black and white, [media] the paper ("A4", "Letter", "A3", "A5", "Legal") and [duplex] a
     * [PrintAttributes] duplex mode (from the document's print preferences); the user can still change them.
     */
    fun print(context: Context, file: File, jobName: String = file.nameWithoutExtension, pageCount: Int = PrintDocumentInfo.PAGE_COUNT_UNKNOWN,
              mono: Boolean = false, media: String? = null, duplex: Int? = null) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val size = when (media?.lowercase()) {
            "letter" -> PrintAttributes.MediaSize.NA_LETTER; "a3" -> PrintAttributes.MediaSize.ISO_A3; "a5" -> PrintAttributes.MediaSize.ISO_A5
            "legal" -> PrintAttributes.MediaSize.NA_LEGAL; else -> PrintAttributes.MediaSize.ISO_A4
        }
        val b = PrintAttributes.Builder().setMediaSize(size).setColorMode(if (mono) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
        if (duplex != null) b.setDuplexMode(duplex)
        pm.print(jobName, StreamAdapter(file, jobName, pageCount), b.build())
    }

    /** audit 5 G-03 / G-09: the document's author forbids printing. */
    class PrintRestricted : Exception("print restricted")

    /**
     * audit 5 G-09: the ONE print path for a document outside the reader's print sheet (Organize "Print pages" and the
     * per-page Print): the V5 print pipeline with the same options as the print sheet (its last used settings and the
     * document's /ViewerPreferences). [range] is a page range like "1-3,5" (blank = all pages). Throws
     * [PrintRestricted] when the author's permissions forbid printing. Returns the number of pages sent.
     */
    suspend fun printDocument(context: Context, src: File, password: String?, range: String, jobName: String, tempDir: File): Int {
        val allowed = withContext(Dispatchers.IO) {
            if (!com.smartdocs.editor.core.security.SecurityInfoReader.mentionsEncrypt(src)) true
            else runCatching {
                com.smartdocs.editor.core.pdf.PdfOps.loadRaw(src, password).use { d ->
                    com.smartdocs.editor.core.security.ReaderRestrictions.check(
                        com.smartdocs.editor.core.security.SecurityInfoReader.of(d), false, com.smartdocs.editor.core.security.DocAction.PRINT) == null
                }
            }.getOrDefault(true)   // an unreadable file fails in the pipeline below
        }
        if (!allowed) throw PrintRestricted()
        val last = com.smartdocs.editor.core.view5.PrintPresets.last(context) ?: com.smartdocs.editor.core.view5.PrintSettings()
        val prefs = com.smartdocs.editor.core.view5.DocPrintPrefs.read(src, password)
        val settings = if (prefs.noScaling) last.copy(scale = com.smartdocs.editor.core.view5.PrintLayout.Scale.ACTUAL) else last
        val (file, n) = com.smartdocs.editor.core.view5.PrintPipeline.prepare(context, src, password, settings.toOptions(range), settings.extras(), tempDir)
        withContext(Dispatchers.Main) {
            print(context, file, jobName, n, mono = settings.grayscale, media = settings.paper,
                duplex = com.smartdocs.editor.core.view5.DocPrintPrefs.duplexMode(prefs.duplex))
        }
        return n
    }

    private class StreamAdapter(private val file: File, private val name: String, private val pageCount: Int) : PrintDocumentAdapter() {
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
            if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(if (name.endsWith(".pdf")) name else "$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback?.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: CancellationSignal?, callback: WriteResultCallback?) {
            if (destination == null) { callback?.onWriteFailed(null); return }
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) { callback?.onWriteCancelled(); return }
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.flush()
                    }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }
}
