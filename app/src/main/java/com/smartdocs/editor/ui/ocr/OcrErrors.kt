package com.smartdocs.editor.ui.ocr

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrException

/**
 * audit 5 OCR-8: the user-visible text of an OCR failure — always a string resource, never a raw exception message
 * ("HTTP 404 for ara", "ocr init", PdfBox English) in the Arabic UI.
 */
fun ocrErrorText(ctx: Context, e: Throwable): String = when (e) {
    is com.smartdocs.editor.ui.quick.QuickInfo -> e.message ?: ctx.getString(R.string.ttoc_err_failed)   // already a resource text
    is OcrException -> when (e.kind) {
        OcrException.Kind.DOWNLOAD -> ctx.getString(R.string.ttoc_err_download, OcrEngine.label(e.lang ?: ""))
        OcrException.Kind.VERIFY -> ctx.getString(R.string.ttoc_err_verify, OcrEngine.label(e.lang ?: ""))
        OcrException.Kind.NO_PACK -> ctx.getString(R.string.ttoc_err_no_pack, OcrEngine.label(e.lang ?: ""))
        OcrException.Kind.INIT -> ctx.getString(R.string.qk_ocr_init_failed)
        OcrException.Kind.IMAGE -> ctx.getString(R.string.ttoc_err_image)
    }
    is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException -> ctx.getString(R.string.ttoc_err_password)
    is OutOfMemoryError -> ctx.getString(R.string.ttoc_err_memory)
    is java.io.IOException -> ctx.getString(R.string.ttoc_err_io)
    else -> ctx.getString(R.string.ttoc_err_failed)
}
