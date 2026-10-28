package com.smartdocs.editor.core.convert6

import android.content.Context
import org.json.JSONObject

/**
 * fc-settings: per-conversion option defaults ("Save as default" in the convert hub's options sheet, Nitro §23 /
 * Foxit §37 conversion settings). One JSON object per conversion id in the SharedPreferences "fcs_conv_defaults";
 * the hub starts a conversion from it, or from the built-in defaults when nothing was saved. The page range,
 * the URL and the pasted text belong to one run and are never saved. Other converter screens (To PDF+) keep their
 * own object under a "screen:" key through [saveScreen] / [screen].
 */
object Cv6Defaults {
    private const val PREFS = "fcs_conv_defaults"
    private const val SCREEN = "screen:"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved defaults of [convId] over [builtIn], or [builtIn] itself. */
    fun load(ctx: Context, convId: String, builtIn: Cv6Opts): Cv6Opts =
        p(ctx).getString(convId, null)?.let { runCatching { decode(JSONObject(it), builtIn) }.getOrNull() } ?: builtIn

    fun save(ctx: Context, convId: String, o: Cv6Opts) { p(ctx).edit().putString(convId, encode(o).toString()).apply() }
    fun clear(ctx: Context, convId: String) { p(ctx).edit().remove(convId).apply() }
    fun has(ctx: Context, convId: String): Boolean = p(ctx).contains(convId)

    /** Conversion ids with saved defaults (screens excluded). */
    fun savedIds(ctx: Context): List<String> = p(ctx).all.keys.filterNot { it.startsWith(SCREEN) }.sorted()
    /** Screen names with saved defaults. */
    fun savedScreens(ctx: Context): List<String> = p(ctx).all.keys.filter { it.startsWith(SCREEN) }.map { it.removePrefix(SCREEN) }.sorted()
    fun clearAll(ctx: Context) { p(ctx).edit().clear().apply() }

    fun screen(ctx: Context, name: String): JSONObject? = p(ctx).getString(SCREEN + name, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun saveScreen(ctx: Context, name: String, o: JSONObject) { p(ctx).edit().putString(SCREEN + name, o.toString()).apply() }
    fun clearScreen(ctx: Context, name: String) { p(ctx).edit().remove(SCREEN + name).apply() }

    /** Everything but the per-run values (range, url, text). */
    fun encode(o: Cv6Opts): JSONObject = JSONObject().apply {
        put("autoOcr", o.autoOcr); put("tables", o.tables); put("includeImages", o.includeImages)
        put("singleSheet", o.singleSheet); put("pptEditable", o.pptEditable)
        put("keepLayout", o.keepLayout?.toString() ?: "auto")
        put("imgFormat", o.imgFormat?.name ?: "keep")
        put("dpi", o.dpi); put("quality", o.quality); put("zip", o.zip); put("gray", o.gray)
        put("size", o.setup.size.name); put("orient", o.setup.orient.name); put("fit", o.setup.fit.name); put("margin", o.setup.marginPt.toDouble()); put("customW", o.setup.customW.toDouble()); put("customH", o.setup.customH.toDouble())
        put("merge", o.merge); put("nUp", o.nUp); put("resizePercent", o.resizePercent); put("maxSide", o.maxSide)
        put("csvAllSheets", o.csvAllSheets); put("ocrLangs", o.ocrLangs ?: "auto"); put("pdfaStrict", o.pdfaStrict)
        put("attachSource", o.attachSource); put("bw", o.bw); put("perTable", o.perTable); put("bookmarks", o.bookmarks); put("recognizeFields", o.recognizeFields)
    }

    /** [j] over [base]: missing or unreadable keys keep the base value. */
    fun decode(j: JSONObject, base: Cv6Opts): Cv6Opts {
        fun b(k: String, d: Boolean) = if (j.has(k)) j.optBoolean(k, d) else d
        fun i(k: String, d: Int) = if (j.has(k)) j.optInt(k, d) else d
        fun <E : Enum<E>> e(k: String, values: Array<E>, d: E): E = j.optString(k, "").let { n -> values.firstOrNull { it.name == n } } ?: d
        val keep = if (!j.has("keepLayout")) base.keepLayout else when (j.optString("keepLayout")) { "true" -> true; "false" -> false; else -> null }
        val fmt = if (!j.has("imgFormat")) base.imgFormat else j.optString("imgFormat").let { n -> ImgFmt.entries.firstOrNull { it.name == n } }
        val langs = if (!j.has("ocrLangs")) base.ocrLangs else j.optString("ocrLangs").takeIf { it.isNotBlank() && it != "auto" }
        val s = base.setup
        val setup = s.copy(size = e("size", PageSize6.values(), s.size), orient = e("orient", Orient6.values(), s.orient), fit = e("fit", Fit6.values(), s.fit),
            marginPt = if (j.has("margin")) j.optDouble("margin", s.marginPt.toDouble()).toFloat() else s.marginPt,
            customW = if (j.has("customW")) j.optDouble("customW", s.customW.toDouble()).toFloat() else s.customW,
            customH = if (j.has("customH")) j.optDouble("customH", s.customH.toDouble()).toFloat() else s.customH)
        return base.copy(
            autoOcr = b("autoOcr", base.autoOcr), tables = b("tables", base.tables), includeImages = b("includeImages", base.includeImages),
            singleSheet = b("singleSheet", base.singleSheet), pptEditable = b("pptEditable", base.pptEditable), keepLayout = keep, imgFormat = fmt,
            dpi = i("dpi", base.dpi), quality = i("quality", base.quality).coerceIn(1, 100), zip = b("zip", base.zip), gray = b("gray", base.gray), setup = setup,
            merge = b("merge", base.merge), nUp = i("nUp", base.nUp), resizePercent = i("resizePercent", base.resizePercent), maxSide = i("maxSide", base.maxSide),
            csvAllSheets = b("csvAllSheets", base.csvAllSheets), ocrLangs = langs, pdfaStrict = b("pdfaStrict", base.pdfaStrict),
            attachSource = b("attachSource", base.attachSource), bw = b("bw", base.bw), perTable = b("perTable", base.perTable), bookmarks = b("bookmarks", base.bookmarks), recognizeFields = b("recognizeFields", base.recognizeFields),
        )
    }
}
