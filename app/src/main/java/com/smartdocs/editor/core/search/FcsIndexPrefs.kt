package com.smartdocs.editor.core.search

import android.content.Context
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * fc-settings (Foxit §37 "Index" / "Search" preferences), prefs "fcs_index": what the full-text index takes besides
 * page text (XMP metadata, custom document properties, structure-tag texts), the index log, the document title in
 * the results and a size cap for the index ("Fast Find" cache).
 */
object FcsIndexPrefs {
    private const val FILE = "fcs_index"
    private const val LOG = "index_log.txt"
    private const val LOG_MAX = 256 * 1024L

    data class Meta(val xmp: Boolean = false, val customProps: Boolean = false, val structTags: Boolean = false) {
        val any: Boolean get() = xmp || customProps || structTags
    }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun meta(ctx: Context) = p(ctx).let { Meta(it.getBoolean("xmp", false), it.getBoolean("custom", false), it.getBoolean("tags", false)) }
    fun setMeta(ctx: Context, m: Meta) { p(ctx).edit().putBoolean("xmp", m.xmp).putBoolean("custom", m.customProps).putBoolean("tags", m.structTags).apply() }
    fun logging(ctx: Context) = p(ctx).getBoolean("log", false)
    fun setLogging(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("log", on).apply() }
    /** Show the PDF /Title next to the file name in index search results. */
    fun showTitle(ctx: Context) = p(ctx).getBoolean("title", true)
    fun setShowTitle(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("title", on).apply() }
    /** Index size cap in MB of stored text; 0 = no limit. */
    fun cacheMb(ctx: Context) = p(ctx).getInt("cache_mb", 0)
    fun setCacheMb(ctx: Context, mb: Int) { p(ctx).edit().putInt("cache_mb", mb.coerceAtLeast(0)).apply() }

    // ---- index log ----
    fun logFile(ctx: Context) = File(ctx.applicationContext.filesDir, LOG)

    fun log(ctx: Context, line: String) {
        if (!logging(ctx)) return
        runCatching {
            val f = logFile(ctx)
            if (f.length() > LOG_MAX) f.writeText(f.readText().takeLast((LOG_MAX / 2).toInt()))
            f.appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line + "\n")
        }
    }

    fun clearLog(ctx: Context) { runCatching { logFile(ctx).delete() } }

    // ---- size cap ----
    /** One indexed document for [evict]: its id, when it was indexed and the bytes of text it stores. */
    data class Entry(val id: Long, val indexed: Long, val bytes: Long)

    /** Ids whose stored text is dropped, oldest indexed first, until the total is at most [limit] bytes (0 = no limit). */
    fun evict(entries: List<Entry>, limit: Long): List<Long> {
        if (limit <= 0) return emptyList()
        var total = entries.sumOf { it.bytes }
        if (total <= limit) return emptyList()
        val out = ArrayList<Long>()
        for (e in entries.filter { it.bytes > 0 }.sortedBy { it.indexed }) {
            if (total <= limit) break
            out += e.id; total -= e.bytes
        }
        return out
    }

    // ---- extra document parts ----
    private val STANDARD_INFO = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")

    /** XMP packet → its text values (tags and attributes removed, entities decoded, whitespace collapsed). */
    fun xmpText(xml: String): String = xml.replace(Regex("<\\?[^>]*\\?>"), " ").replace(Regex("<[^>]*>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    /** "Key: value" of the Info entries that are not standard (custom document properties). */
    fun customProps(info: Map<String, String>): String = info.filterKeys { it !in STANDARD_INFO }.filterValues { it.isNotBlank() }
        .entries.joinToString(" — ") { "${it.key}: ${it.value}" }

    /** The document /Title (Info, else blank). */
    fun title(doc: PDDocument): String = runCatching { doc.documentInformation?.title.orEmpty().trim() }.getOrDefault("")

    /** Metadata / tag parts of [doc] for the index ([PartKind.METADATA]). */
    fun parts(doc: PDDocument, m: Meta): List<DocPart> {
        if (!m.any) return emptyList()
        val out = ArrayList<DocPart>()
        if (m.xmp) runCatching {
            doc.documentCatalog.metadata?.let { md -> md.createInputStream().use { String(it.readBytes(), Charsets.UTF_8) } }?.let(::xmpText)?.takeIf { it.isNotBlank() }
                ?.let { out += DocPart(PartKind.METADATA, -1, it.take(100_000)) }
        }
        if (m.customProps) runCatching {
            val d = doc.documentInformation.cosObject
            val map = d.keySet().associate { k -> k.name to ((d.getDictionaryObject(k) as? COSString)?.string ?: "") }
            customProps(map).takeIf { it.isNotBlank() }?.let { out += DocPart(PartKind.METADATA, -1, it) }
        }
        if (m.structTags) runCatching {
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("StructTreeRoot")) as? COSDictionary ?: return@runCatching
            val pageOf = HashMap<COSDictionary, Int>()
            doc.pages.forEachIndexed { i, pg -> pageOf[pg.cosObject] = i }
            walkTags(root.getDictionaryObject(COSName.K), pageOf, -1, out, 0, intArrayOf(0))
        }
        return out
    }

    /** Alt / ActualText / T / E of structure elements, on the element's page (/Pg, inherited). */
    private fun walkTags(k: Any?, pageOf: Map<COSDictionary, Int>, page: Int, out: MutableList<DocPart>, depth: Int, count: IntArray) {
        if (depth > 60 || count[0] > 20_000) return
        when (k) {
            is COSArray -> for (i in 0 until k.size()) walkTags(k.getObject(i), pageOf, page, out, depth + 1, count)
            is COSDictionary -> {
                count[0]++
                val pg = (k.getDictionaryObject(COSName.getPDFName("Pg")) as? COSDictionary)?.let { pageOf[it] } ?: page
                val t = listOf("Alt", "ActualText", "T", "E").mapNotNull { (k.getDictionaryObject(COSName.getPDFName(it)) as? COSString)?.string?.trim() }.filter { it.isNotBlank() }
                if (t.isNotEmpty()) out += DocPart(PartKind.METADATA, pg, t.distinct().joinToString(" — "))
                walkTags(k.getDictionaryObject(COSName.K), pageOf, pg, out, depth + 1, count)
            }
        }
    }
}
