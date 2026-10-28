package com.smartdocs.editor.core.share5

import android.content.Context

/**
 * Default e-mail subject and body (Foxit §37 e-mail preferences) used when a document is e-mailed from the share
 * sheet; {file} becomes the document name(s). Stored in SharedPreferences "share_prefs".
 */
object MailDefaults {
    const val TOKEN = "{file}"
    const val DEFAULT_SUBJECT = TOKEN

    /** [template] with {file} replaced by [files]; a blank subject template falls back to the names. */
    fun fill(template: String, files: String): String = template.replace(TOKEN, files)
    fun subjectOf(template: String, files: String): String = fill(template.ifBlank { DEFAULT_SUBJECT }, files).trim().ifBlank { files }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("share_prefs", Context.MODE_PRIVATE)
    fun subjectTemplate(ctx: Context): String = runCatching { prefs(ctx).getString("mail_subject", DEFAULT_SUBJECT) }.getOrNull() ?: DEFAULT_SUBJECT
    fun bodyTemplate(ctx: Context): String = runCatching { prefs(ctx).getString("mail_body", "") }.getOrNull() ?: ""
    fun set(ctx: Context, subject: String, body: String) { runCatching { prefs(ctx).edit().putString("mail_subject", subject).putString("mail_body", body).apply() } }

    fun subject(ctx: Context, files: String) = subjectOf(subjectTemplate(ctx), files)
    fun body(ctx: Context, files: String) = fill(bodyTemplate(ctx), files)
}
