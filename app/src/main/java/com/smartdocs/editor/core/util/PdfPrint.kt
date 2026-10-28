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
