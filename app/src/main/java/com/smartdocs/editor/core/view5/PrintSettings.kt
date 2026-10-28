package com.smartdocs.editor.core.view5

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the print sheet / batch print offer except the page range, as one value that can be saved as a named
 * print preset (Foxit "print preferences"). [toOptions] and [extras] give what [PrintPipeline] needs.
 */
data class PrintSettings(
    val subset: PrintLayout.Subset = PrintLayout.Subset.ALL,
    val reverse: Boolean = false,
    /** NVE-file: only pages that carry comments / markups. */
    val markedOnly: Boolean = false,
    val content: PrintContent = PrintContent.DOC_AND_MARKUPS,
    val notes: Boolean = false,
    val summary: Boolean = false,
    val perSheet: Int = 1,
    val cols: Int = 0, val rows: Int = 0,
    val vertical: Boolean = false,
    val rtlOrder: Boolean = true,
    val booklet: Boolean = false,
    val rightBinding: Boolean = true,
    val sides: PrintLayout.Sides = PrintLayout.Sides.BOTH,
    val sheets: String = "",
    val paper: String = "A4",
    val landscape: Boolean = false,
    val borders: Boolean = false,
    val scale: PrintLayout.Scale = PrintLayout.Scale.AUTO,
    val scalePct: Int = 100,
    val autoRotate: Boolean = true,
    val margin: Float = 18f,
    val poster: Boolean = false,
    val posterZoom: Int = 200,
    val overlap: Float = 18f,
    val posterLabels: Boolean = true,
    val cutMarks: Boolean = false,
    // fc-settings: printer marks for normal printing and the hairline fix (see PrinterMarks)
    val regMarks: Boolean = false,
    val bleedMarks: Boolean = false,
    val bleed: Float = 9f,
    val hairline: Float = 0f,
    val grayscale: Boolean = false,
    val asImage: Boolean = false,
    val dpi: Int = 150,
) {
    fun toOptions(range: String): PrintOptions = PrintOptions(
        range = range, subset = subset, reverse = reverse, markedOnly = markedOnly, perSheet = perSheet, booklet = booklet, rightBinding = rightBinding,
        paper = paperRect(paper), landscape = landscape, borders = borders, annotations = true, rtlOrder = rtlOrder,
        scale = scale, scalePct = scalePct, autoRotate = autoRotate, margin = margin, cols = cols, rows = rows, vertical = vertical,
        sides = sides, sheets = sheets, content = content, poster = poster, posterZoom = posterZoom, overlap = overlap,
        posterLabels = posterLabels, cutMarks = cutMarks, notes = notes,
        regMarks = regMarks, bleedMarks = bleedMarks, bleed = bleed, hairline = hairline,
    )

    fun extras(): PrintExtras = PrintExtras(grayscale = grayscale, asImage = asImage, dpi = dpi, summary = summary)

    fun toJson(): String = JSONObject().apply {
        put("subset", subset.name); put("reverse", reverse); put("markedOnly", markedOnly); put("content", content.name); put("notes", notes); put("summary", summary)
        put("perSheet", perSheet); put("cols", cols); put("rows", rows); put("vertical", vertical); put("rtl", rtlOrder)
        put("booklet", booklet); put("rightBinding", rightBinding); put("sides", sides.name); put("sheets", sheets)
        put("paper", paper); put("landscape", landscape); put("borders", borders); put("scale", scale.name); put("pct", scalePct)
        put("autoRotate", autoRotate); put("margin", margin.toDouble()); put("poster", poster); put("zoom", posterZoom); put("overlap", overlap.toDouble())
        put("labels", posterLabels); put("cutMarks", cutMarks); put("gray", grayscale); put("image", asImage); put("dpi", dpi)
        put("reg", regMarks); put("bleedMarks", bleedMarks); put("bleed", bleed.toDouble()); put("hairline", hairline.toDouble())
    }.toString()

    companion object {
        val papers = listOf("A4", "Letter", "A3", "A5", "Legal")

        fun paperRect(name: String): PDRectangle = when (name.lowercase()) {
            "letter" -> PDRectangle.LETTER; "a3" -> PDRectangle.A3; "a5" -> PDRectangle.A5; "legal" -> PDRectangle.LEGAL; else -> PDRectangle.A4
        }

        private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E = enumValues<E>().firstOrNull { it.name == s } ?: def

        fun fromJson(s: String?): PrintSettings? = s?.let {
            runCatching {
                val o = JSONObject(it); val d = PrintSettings()
                PrintSettings(
                    subset = enumOf(o.optString("subset"), d.subset), reverse = o.optBoolean("reverse"), markedOnly = o.optBoolean("markedOnly"), content = enumOf(o.optString("content"), d.content),
                    notes = o.optBoolean("notes"), summary = o.optBoolean("summary"), perSheet = o.optInt("perSheet", 1), cols = o.optInt("cols"), rows = o.optInt("rows"),
                    vertical = o.optBoolean("vertical"), rtlOrder = o.optBoolean("rtl", true), booklet = o.optBoolean("booklet"), rightBinding = o.optBoolean("rightBinding", true),
                    sides = enumOf(o.optString("sides"), d.sides), sheets = o.optString("sheets"), paper = o.optString("paper", "A4"), landscape = o.optBoolean("landscape"),
                    borders = o.optBoolean("borders"), scale = enumOf(o.optString("scale"), d.scale), scalePct = o.optInt("pct", 100), autoRotate = o.optBoolean("autoRotate", true),
                    margin = o.optDouble("margin", 18.0).toFloat(), poster = o.optBoolean("poster"), posterZoom = o.optInt("zoom", 200), overlap = o.optDouble("overlap", 18.0).toFloat(),
                    posterLabels = o.optBoolean("labels", true), cutMarks = o.optBoolean("cutMarks"), grayscale = o.optBoolean("gray"), asImage = o.optBoolean("image"), dpi = o.optInt("dpi", 150),
                    regMarks = o.optBoolean("reg"), bleedMarks = o.optBoolean("bleedMarks"), bleed = o.optDouble("bleed", 9.0).toFloat(), hairline = o.optDouble("hairline", 0.0).toFloat(),
                )
            }.getOrNull()
        }
    }
}

/** Named print presets (SharedPreferences "nbt_print_presets": a JSON list of {name, json}). */
object PrintPresets {
    private const val PREFS = "nbt_print_presets"

    fun list(ctx: Context): List<Pair<String, PrintSettings>> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { o -> PrintSettings.fromJson(o.optString("json"))?.let { o.optString("name") to it } } }
    }.getOrDefault(emptyList())

    private fun store(ctx: Context, list: List<Pair<String, PrintSettings>>) {
        val a = JSONArray().apply { list.forEach { (n, s) -> put(JSONObject().put("name", n).put("json", s.toJson())) } }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    /** Save (or replace by name) a preset. */
    fun save(ctx: Context, name: String, s: PrintSettings) = store(ctx, list(ctx).filter { !it.first.equals(name, true) } + (name to s))

    fun delete(ctx: Context, name: String) = store(ctx, list(ctx).filter { it.first != name })

    /** The last settings used (restored the next time the print sheet opens). */
    fun last(ctx: Context): PrintSettings? = PrintSettings.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null))
    fun remember(ctx: Context, s: PrintSettings) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", s.toJson()).apply() }
}

/**
 * The print preferences a PDF carries in /ViewerPreferences (PrintScaling, Duplex, PrintPageRange, NumCopies):
 * read so the print sheet starts with them.
 */
data class DocPrintPrefs(val noScaling: Boolean = false, val duplex: String? = null, val range: String = "", val copies: Int = 1) {
    companion object {
        /** PrintPageRange pairs (zero-based first/last) → "1-3,6". Odd or invalid entries are ignored. */
        fun rangeSpec(nums: List<Int>): String = nums.chunked(2).filter { it.size == 2 && it[0] >= 0 && it[1] >= it[0] }
            .joinToString(",") { (a, b) -> if (a == b) "${a + 1}" else "${a + 1}-${b + 1}" }

        /** /Duplex name → android.print.PrintAttributes duplex mode (values are the framework constants). */
        fun duplexMode(name: String?): Int? = when (name) {
            "Simplex" -> 1                       // DUPLEX_MODE_NONE
            "DuplexFlipLongEdge" -> 2            // DUPLEX_MODE_LONG_EDGE
            "DuplexFlipShortEdge" -> 4           // DUPLEX_MODE_SHORT_EDGE
            else -> null
        }

        suspend fun read(file: File, pw: String?): DocPrintPrefs = withContext(Dispatchers.IO) {
            runCatching {
                PdfOps.load(file, pw).use { d ->
                    val vp = d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("ViewerPreferences")) as? COSDictionary ?: return@use DocPrintPrefs()
                    val ranges = (vp.getDictionaryObject(COSName.getPDFName("PrintPageRange")) as? COSArray)?.mapNotNull { (it as? COSNumber)?.intValue() }.orEmpty()
                    DocPrintPrefs(
                        noScaling = vp.getNameAsString(COSName.getPDFName("PrintScaling")) == "None",
                        duplex = vp.getNameAsString(COSName.getPDFName("Duplex")),
                        range = rangeSpec(ranges),
                        copies = (vp.getDictionaryObject(COSName.getPDFName("NumCopies")) as? COSInteger)?.intValue() ?: 1,
                    )
                }
            }.getOrDefault(DocPrintPrefs())
        }
    }
}
