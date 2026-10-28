package com.smartdocs.editor.ui.convert6

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Cv6Err
import com.smartdocs.editor.core.convert6.Cv6Errors

/**
 * Localised text of a conversion failure (convert audit A-H8 / B16 / C12): what happened and what to do, in the app
 * language — never "OutOfMemory", "not a zip package", PdfBox messages or class names. The technical text goes to the
 * log only.
 */
object Cv6ErrorText {
    fun of(ctx: Context, e: Throwable): String {
        val c = Cv6Errors.classify(e)
        return when (c.kind) {
            Cv6Err.USER -> c.detail ?: ctx.getString(R.string.cvf_e_generic)
            Cv6Err.OUT_OF_MEMORY -> ctx.getString(R.string.cvf_e_memory)
            Cv6Err.TOO_LARGE -> ctx.getString(R.string.cvf_e_too_large)
            Cv6Err.TIMEOUT -> ctx.getString(R.string.cvf_e_timeout)
            Cv6Err.NETWORK -> ctx.getString(R.string.cvf_e_network)
            Cv6Err.HTTP -> ctx.getString(R.string.cvf_e_http, c.code)
            Cv6Err.DAMAGED -> ctx.getString(R.string.cvf_e_damaged)
            Cv6Err.DAMAGED_PDF -> ctx.getString(R.string.cvf_e_damaged_pdf)
            Cv6Err.PASSWORD -> c.detail?.let { ctx.getString(R.string.cvf_pw_for, it) } ?: ctx.getString(R.string.cv_password_needed)
            Cv6Err.WRONG_PASSWORD -> ctx.getString(R.string.cvf_pw_wrong, c.detail.orEmpty())
            Cv6Err.ENCRYPTED -> ctx.getString(R.string.cv6_err_encrypted)
            Cv6Err.UNSUPPORTED -> ctx.getString(R.string.cv6_err_unsupported, c.detail ?: "?")
            Cv6Err.IMAGE -> imageReason(ctx, c.detail.orEmpty())
            Cv6Err.IMAGE_WRITE -> ctx.getString(R.string.cvf_e_image_write)
            Cv6Err.DISK_FULL -> ctx.getString(R.string.cvf_e_disk_full)
            Cv6Err.NAME_TOO_LONG -> ctx.getString(R.string.cvf_e_name_long)
            Cv6Err.OCR -> ctx.getString(R.string.cv6_err_ocr)
            Cv6Err.EMPTY -> ctx.getString(R.string.cvf_e_empty)
            Cv6Err.RESTRICTED -> ctx.getString(R.string.cvf_e_restricted, c.detail.orEmpty())
            Cv6Err.RANGE -> ctx.getString(R.string.cvf_e_range_file, c.detail.orEmpty())
            Cv6Err.UNKNOWN -> ctx.getString(R.string.cvf_e_generic)
        }
    }

    /** Picture problems in words (convert audit A-L6: "تعذّر فتح الصورة (tiff-ccitt)"). */
    fun imageReason(ctx: Context, reason: String): String = when {
        reason == "heic" -> ctx.getString(R.string.cv6_err_heic)
        reason == "tiff-ccitt" -> ctx.getString(R.string.cvf_e_img_fax)
        reason.startsWith("tiff") -> ctx.getString(R.string.cvf_e_img_tiff)
        reason == "memory" -> ctx.getString(R.string.cvf_e_memory)
        else -> ctx.getString(R.string.cvf_e_img_generic)
    }
}
