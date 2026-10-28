package com.smartdocs.editor.core.rpf

import android.content.Context

/**
 * reader-prefs (#23 / pdf39 §34 #296): reading options of one document, over the global preferences. An empty value
 * means "use the global preference". [layout] is a ViewMode name, [zoom] the reader's zoom factor (0 = default),
 * [fit] "width" / "page" / "", [direction] "ltr" / "rtl" / "" (the document's own), [order] "infer" / "raw" / "",
 * [mode] "visible" (read aloud only the page on screen) / "document" / "".
 */
data class DocReading(
    val order: String = "",
    val mode: String = "",
    val layout: String = "",
    val zoom: Float = 0f,
    val fit: String = "",
    val direction: String = "",
) {
    val isDefault: Boolean get() = this == DocReading()

    /** "k=v;k=v" (values never contain ';' or '='). */
    fun encode(): String = listOf("o" to order, "m" to mode, "l" to layout, "z" to (if (zoom > 0f) zoom.toString() else ""), "f" to fit, "d" to direction)
        .filter { it.second.isNotEmpty() }.joinToString(";") { "${it.first}=${it.second}" }

    companion object {
        private val ORDERS = setOf("infer", "raw"); private val MODES = setOf("visible", "document")
        private val FITS = setOf("width", "page"); private val DIRS = setOf("ltr", "rtl")

        fun decode(s: String?): DocReading {
            if (s.isNullOrBlank()) return DocReading()
            val m = s.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
            return DocReading(
                order = m["o"]?.takeIf { it in ORDERS } ?: "",
                mode = m["m"]?.takeIf { it in MODES } ?: "",
                layout = m["l"]?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "",
                zoom = m["z"]?.toFloatOrNull()?.takeIf { it in 0.25f..32f } ?: 0f,
                fit = m["f"]?.takeIf { it in FITS } ?: "",
                direction = m["d"]?.takeIf { it in DIRS } ?: "",
            )
        }

        /** Reading order to use: the document's own choice, else the global one. */
        fun orderOf(doc: DocReading, global: String): String = doc.order.ifEmpty { global }

        /** Direction override: true = right to left, false = left to right, null = keep the document's / app's. */
        fun rtlOf(doc: DocReading): Boolean? = when (doc.direction) { "rtl" -> true; "ltr" -> false; else -> null }

        /** Last page (exclusive) read aloud from [page]: only that page in "visible" mode, else the whole document. */
        fun readLimit(doc: DocReading, page: Int, pageCount: Int): Int = if (doc.mode == "visible") (page + 1).coerceAtMost(pageCount) else pageCount
    }
}

/** Per-file storage of [DocReading] (prefs "rpf_docs", key = absolute path). */
object DocReadingStore {
    private const val PREFS = "rpf_docs"
    private val cache = HashMap<String, DocReading>()

    fun get(ctx: Context, path: String): DocReading = synchronized(cache) {
        cache.getOrPut(path) { DocReading.decode(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(path, null)) }
    }

    fun put(ctx: Context, path: String, v: DocReading) {
        synchronized(cache) { cache[path] = v }
        val e = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (v.isDefault) e.remove(path) else e.putString(path, v.encode())
        e.apply()
    }
}
