package com.smartdocs.editor.core.search

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * fc-sync (Foxit §37 search / index preferences), shown in the index sheet of the search screen:
 *  - what is indexed besides the page text: XMP, custom document properties, structure tags ([DocMeta]);
 *  - "ignore Asian character width" (full-width / half-width forms match normal ones; switching rebuilds the index);
 *  - the document title in the results;
 *  - an index size limit: the oldest-indexed documents are dropped when the database grows past it;
 *  - an indexing log (files indexed and errors) that can be shared.
 */
object IndexExtras {
    /** Size limit choices in MB (0 = no limit). */
    val LIMITS_MB = listOf(0, 50, 100, 250, 500)
    private const val LOG_MAX = 256 * 1024L

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("fcy_index", Context.MODE_PRIVATE)

    fun options(c: Context) = DocMeta.Options(xmp = p(c).getBoolean("xmp", false), custom = p(c).getBoolean("custom", true), tags = p(c).getBoolean("tags", false))
    fun setOptions(c: Context, o: DocMeta.Options) = p(c).edit().putBoolean("xmp", o.xmp).putBoolean("custom", o.custom).putBoolean("tags", o.tags).apply()

    fun widthFold(c: Context): Boolean = p(c).getBoolean("width", false)
    /** Changes the width folding; the caller rebuilds the index (stored words change). */
    fun setWidthFold(c: Context, on: Boolean) { p(c).edit().putBoolean("width", on).apply(); IndexText.foldWidth = on }

    fun showTitle(c: Context): Boolean = p(c).getBoolean("title", true)
    fun setShowTitle(c: Context, on: Boolean) = p(c).edit().putBoolean("title", on).apply()

    fun limitMb(c: Context): Int = p(c).getInt("limit", 0).let { if (it in LIMITS_MB) it else 0 }
    fun setLimitMb(c: Context, mb: Int) = p(c).edit().putInt("limit", mb).apply()
    fun limitBytes(c: Context): Long = limitMb(c) * 1024L * 1024L

    fun logging(c: Context): Boolean = p(c).getBoolean("log", false)
    fun setLogging(c: Context, on: Boolean) = p(c).edit().putBoolean("log", on).apply()

    /** Brings the process-wide switches in line with the stored ones (index and query must fold alike). */
    fun apply(c: Context) { IndexText.foldWidth = widthFold(c) }

    fun logFile(c: Context): File = File(c.applicationContext.filesDir, "search_index.log")

    /** One log line per indexed file when logging is on; the log keeps its newest part (≤ 256 KB). */
    fun log(c: Context, f: File, error: Throwable?) {
        if (!logging(c)) return
        runCatching {
            val log = logFile(c)
            if (log.length() > LOG_MAX) log.writeText(log.readText().takeLast((LOG_MAX / 2).toInt()))
            log.appendText(line(System.currentTimeMillis(), f.absolutePath, error?.let { it.message ?: it.javaClass.simpleName }) + "\n")
        }
    }

    // ---- pure ----

    fun line(time: Long, path: String, error: String?): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time)) + "  " + (if (error == null) "OK  " else "ERR ") + path + (error?.let { "  ($it)" } ?: "")

    /**
     * How many of [rows] documents (oldest first) to drop so a database of [size] bytes gets under [max]: the share
     * over the limit plus a 10 % margin, at least one, at most all.
     */
    fun toDrop(rows: Int, size: Long, max: Long): Int {
        if (rows <= 0 || max <= 0 || size <= max) return 0
        val dropBytes = size - max * 9 / 10   // bytes to free, aiming 10 % under the limit
        return ((rows.toLong() * dropBytes + size - 1) / size).toInt().coerceIn(1, rows)
    }
}
