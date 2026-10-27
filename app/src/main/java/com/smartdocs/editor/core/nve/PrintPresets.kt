package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintOptions
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.json.JSONArray
import org.json.JSONObject

/**
 * NVE-file (FILE-5): print presets. Two sources feed the print sheet:
 * - the document's own print preset in /ViewerPreferences (PrintScaling, Duplex, PickTrayByPDFSize, PrintPageRange,
 *   NumCopies), applied as defaults when the sheet opens;
 * - the user's named presets (JSON in prefs), chosen in the sheet or saved from the current options.
 */
object PrintPresets {

    /** Papers offered by the print sheet (index stored in presets). */
    val PAPERS = listOf("A4" to PDRectangle.A4, "Letter" to PDRectangle.LETTER, "A3" to PDRectangle.A3, "A5" to PDRectangle.A5, "Legal" to PDRectangle.LEGAL)

    fun paperIndex(p: PDRectangle): Int = PAPERS.indexOfFirst { kotlin.math.abs(it.second.width - p.width) < 1f && kotlin.math.abs(it.second.height - p.height) < 1f }.coerceAtLeast(0)

    data class Preset(val name: String, val options: PrintOptions, val builtIn: Boolean = false)

    /** Built-in presets (labels are the caller's localised names, in this order). */
    fun builtIns(names: List<String>): List<Preset> {
        val n = { i: Int -> names.getOrElse(i) { "#$i" } }
        return listOf(
            Preset(n(0), PrintOptions(), true),
            Preset(n(1), PrintOptions(perSheet = 4, borders = true, grayscale = true), true),
            Preset(n(2), PrintOptions(booklet = true), true),
            Preset(n(3), PrintOptions(grayscale = true, annotations = false, scaling = PrintLayout.Scaling.SHRINK), true),
            Preset(n(4), PrintOptions(markupsOnly = true, commentSummary = true), true),
        )
    }

    fun toJson(o: PrintOptions): JSONObject = JSONObject().apply {
        put("subset", o.subset.name); put("reverse", o.reverse); put("perSheet", o.perSheet); put("cols", o.cols); put("rows", o.rows)
        put("booklet", o.booklet); put("rightBinding", o.rightBinding); put("sides", o.bookletSides.name); put("paper", paperIndex(o.paper))
        put("landscape", o.landscape); put("borders", o.borders); put("annotations", o.annotations); put("scaling", o.scaling.name)
        put("customScale", o.customScale); put("autoRotate", o.autoRotate); put("center", o.center); put("markupsOnly", o.markupsOnly)
        put("grayscale", o.grayscale); put("summary", o.commentSummary); put("duplex", o.duplex)
    }

    fun fromJson(j: JSONObject, base: PrintOptions = PrintOptions()): PrintOptions = base.copy(
        subset = runCatching { PrintLayout.Subset.valueOf(j.optString("subset")) }.getOrDefault(base.subset),
        reverse = j.optBoolean("reverse", base.reverse), perSheet = j.optInt("perSheet", base.perSheet), cols = j.optInt("cols", base.cols), rows = j.optInt("rows", base.rows),
        booklet = j.optBoolean("booklet", base.booklet), rightBinding = j.optBoolean("rightBinding", base.rightBinding),
        bookletSides = runCatching { PrintLayout.BookletSides.valueOf(j.optString("sides")) }.getOrDefault(base.bookletSides),
        paper = PAPERS.getOrNull(j.optInt("paper", -1))?.second ?: base.paper, landscape = j.optBoolean("landscape", base.landscape),
        borders = j.optBoolean("borders", base.borders), annotations = j.optBoolean("annotations", base.annotations),
        scaling = runCatching { PrintLayout.Scaling.valueOf(j.optString("scaling")) }.getOrDefault(base.scaling),
        customScale = j.optInt("customScale", base.customScale), autoRotate = j.optBoolean("autoRotate", base.autoRotate), center = j.optBoolean("center", base.center),
        markupsOnly = j.optBoolean("markupsOnly", base.markupsOnly), grayscale = j.optBoolean("grayscale", base.grayscale),
        commentSummary = j.optBoolean("summary", base.commentSummary), duplex = j.optString("duplex", base.duplex),
    )

    fun encode(list: List<Preset>): String = JSONArray().apply { list.filter { !it.builtIn }.forEach { put(JSONObject().put("name", it.name).put("o", toJson(it.options))) } }.toString()

    fun decode(s: String?): List<Preset> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { val o = a.getJSONObject(it); Preset(o.getString("name"), fromJson(o.getJSONObject("o"))) }
    }.getOrDefault(emptyList())

    private fun d(b: COSBase?): COSBase? = if (b is COSObject) b.`object` else b

    /** The document's /ViewerPreferences print preset (null fields when not set). */
    data class DocPreset(val printScalingNone: Boolean?, val duplex: String?, val pickTray: Boolean?, val range: String?, val copies: Int?) {
        val isEmpty get() = printScalingNone == null && duplex == null && pickTray == null && range == null && copies == null
    }

    fun docPreset(doc: PDDocument): DocPreset {
        val vp = d(doc.documentCatalog.cosObject.getDictionaryObject(COSName.VIEWER_PREFERENCES)) as? COSDictionary
            ?: return DocPreset(null, null, null, null, null)
        val n = { s: String -> COSName.getPDFName(s) }
        val scaling = vp.getNameAsString(n("PrintScaling"))
        val range = (d(vp.getDictionaryObject(n("PrintPageRange"))) as? COSArray)?.let { com.smartdocs.editor.core.edit5.DocPropsOps.rangeText(it) }?.takeIf { it.isNotBlank() }
        return DocPreset(
            printScalingNone = scaling?.let { it == "None" }, duplex = vp.getNameAsString(n("Duplex")),
            pickTray = if (vp.containsKey(n("PickTrayByPDFSize"))) vp.getBoolean(n("PickTrayByPDFSize"), false) else null,
            range = range, copies = (d(vp.getDictionaryObject(n("NumCopies"))) as? COSNumber)?.intValue(),
        )
    }

    /** [base] with the document's preset applied: PrintScaling None -> actual size, Duplex, tray by size, range, copies. */
    fun applyDocPreset(base: PrintOptions, p: DocPreset): PrintOptions = base.copy(
        scaling = if (p.printScalingNone == true) PrintLayout.Scaling.ACTUAL else base.scaling,
        duplex = p.duplex ?: base.duplex, paperFromPage = p.pickTray ?: base.paperFromPage,
        range = p.range ?: base.range, copies = p.copies?.coerceIn(1, 99) ?: base.copies,
    )
}
