package com.smartdocs.editor.core.share5

import android.content.Context
import com.smartdocs.editor.core.redact.SanitizeItem

/**
 * "Remove hidden information on share" (Foxit 2025.3 / old audit "auto sanitize on share"): the options remembered
 * for the share sheet, and the [SanitizeItem]s each one removes from the shared copy.
 */
object ShareStrip {
    data class Options(val metadata: Boolean = false, val scripts: Boolean = false, val attachments: Boolean = false)

    /** Metadata covers the document info / XMP and app-private data; scripts are every JavaScript action. */
    fun items(o: Options): Set<SanitizeItem> = buildSet {
        if (o.metadata) { add(SanitizeItem.METADATA); add(SanitizeItem.PRIVATE_DATA) }
        if (o.scripts) add(SanitizeItem.JAVASCRIPT)
        if (o.attachments) add(SanitizeItem.ATTACHMENTS)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share_strip", Context.MODE_PRIVATE)

    fun load(c: Context): Options = prefs(c).let { Options(it.getBoolean("meta", false), it.getBoolean("js", false), it.getBoolean("att", false)) }

    fun save(c: Context, o: Options) = prefs(c).edit().putBoolean("meta", o.metadata).putBoolean("js", o.scripts).putBoolean("att", o.attachments).apply()
}
