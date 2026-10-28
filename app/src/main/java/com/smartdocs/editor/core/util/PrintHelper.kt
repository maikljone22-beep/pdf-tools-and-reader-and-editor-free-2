package com.smartdocs.editor.core.util

import android.content.Context
import java.io.File

/**
 * Sends a PDF file to the Android print framework (system print dialog).
 * audit 5 G-09: kept only as an alias of [PdfPrint.print] (one adapter, one set of print attributes); documents
 * printed from the app go through [PdfPrint.printDocument], which also honours the author's PRINT permission.
 */
object PrintHelper {

    @Deprecated("audit 5 G-09: use PdfPrint.printDocument (or PdfPrint.print for a prepared file)")
    fun printPdf(context: Context, file: File, jobName: String = file.nameWithoutExtension) = PdfPrint.print(context, file, jobName)
}
