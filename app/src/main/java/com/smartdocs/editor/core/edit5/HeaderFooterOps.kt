package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Header & footer: six cells (top/bottom × right/centre/left) with tokens (see [Tokens]).
 * Margins in points from the visible page edge. [mirrorEven] swaps left/right cells on even pages (book layout).
 * [shrink] (0..0.3) scales the existing page content down so the header/footer do not overlap it.
 */
data class HFSpec(
    val topLeft: String = "", val topCenter: String = "", val topRight: String = "",
    val bottomLeft: String = "", val bottomCenter: String = "{page}", val bottomRight: String = "",
    val style: E5TextStyle = E5TextStyle(),
    val marginTop: Float = 24f, val marginBottom: Float = 24f, val marginSide: Float = 36f,
    val startNumber: Int = 1,
    val arabicDigits: Boolean = false,
    val mirrorEven: Boolean = false,
    val shrink: Float = 0f,
    val range: String = "",
    val subset: PageSubset = PageSubset.ALL,
    val ummAlQura: Boolean = true,
    val arabicNames: Boolean = true,
    val user: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("cells", JSONArray(listOf(topLeft, topCenter, topRight, bottomLeft, bottomCenter, bottomRight)))
        put("font", style.font.name); put("size", style.size.toDouble()); put("color", style.color); put("bold", style.bold); put("underline", style.underline)
        put("mt", marginTop.toDouble()); put("mb", marginBottom.toDouble()); put("ms", marginSide.toDouble())
        put("start", startNumber); put("arabicDigits", arabicDigits); put("mirror", mirrorEven); put("shrink", shrink.toDouble())
        put("range", range); put("subset", subset.name); put("uaq", ummAlQura); put("arNames", arabicNames)
    }.toString()

    companion object {
        fun fromJson(s: String?): HFSpec? = s?.let {
            runCatching {
                val o = JSONObject(it); val c = o.getJSONArray("cells")
                HFSpec(c.optString(0), c.optString(1), c.optString(2), c.optString(3), c.optString(4), c.optString(5),
                    E5TextStyle(runCatching { E5Font.valueOf(o.optString("font")) }.getOrDefault(E5Font.NASKH), o.optDouble("size", 10.0).toFloat(), o.optInt("color", 0x333333), o.optBoolean("bold"), o.optBoolean("underline")),
                    o.optDouble("mt", 24.0).toFloat(), o.optDouble("mb", 24.0).toFloat(), o.optDouble("ms", 36.0).toFloat(),
                    o.optInt("start", 1), o.optBoolean("arabicDigits"), o.optBoolean("mirror"), o.optDouble("shrink", 0.0).toFloat(),
                    o.optString("range"), runCatching { PageSubset.valueOf(o.optString("subset")) }.getOrDefault(PageSubset.ALL), o.optBoolean("uaq", true), o.optBoolean("arNames", true))
            }.getOrNull()
        }
    }
}

object HeaderFooterOps {
    const val KIND = "hf"
    const val KIND_BATES = "bates"
    private const val SETTING = "HF"

    /** Settings stored by the last header/footer run (for "update"). */
    suspend fun stored(src: File, password: String?): HFSpec? = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { HFSpec.fromJson(Marked.getSetting(it, SETTING)) }
    }

    /** Whether pages carry our header/footer, someone else's pagination artifacts, or our Bates numbers. */
    suspend fun detect(src: File, password: String?): Set<String> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc -> doc.pages.take(30).flatMap { Marked.kinds(it) }.toSet() }
    }

    /** Add (or, when [replace], update) header & footer. */
    suspend fun apply(context: Context, src: File, out: File, password: String?, spec: HFSpec, replace: Boolean = true) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            // content shrunk by an earlier run is not scaled again on update (the scale itself is not tagged)
            val prev = HFSpec.fromJson(Marked.getSetting(doc, SETTING))
            if (replace) doc.pages.forEach { Marked.strip(doc, it) { k -> k == KIND } }
            draw(context, doc, if (replace && (prev?.shrink ?: 0f) > 0f) spec.copy(shrink = 0f) else spec, KIND, src.name, null)
            Marked.putSetting(doc, SETTING, spec.toJson())
        }
    }

    /** Remove our header/footer; [others] also removes other producers' header/footer artifacts (Acrobat). */
    suspend fun remove(src: File, out: File, password: String?, others: Boolean = false, bates: Boolean = false): Int = withContext(Dispatchers.IO) {
        var n = 0
        E5Io.edit(src, out, password) { doc ->
            doc.pages.forEach { p ->
                if (Marked.strip(doc, p) { k -> k == KIND || (bates && k == KIND_BATES) || (others && (k == "pdf:Header" || k == "pdf:Footer" || k == "pdf:BatesN")) }) n++
            }
            Marked.putSetting(doc, SETTING, null)
        }
        n
    }

    /**
     * Draw the cells of [spec] on its target pages of [doc]. [bates] (page index → Bates text) feeds `{bates}`.
     * Each cell row is tagged as /Artifact /Pagination /Header or /Footer with /SDE5 ([kind]).
     */
    fun draw(context: Context, doc: PDDocument, spec: HFSpec, kind: String, fileName: String, bates: ((Int) -> String)?) {
        val fonts = E5Fonts(context, doc)
        val n = doc.numberOfPages
        val targets = PageSet.filter(PageSet.parse(spec.range, n), spec.subset) { E5Io.isLandscape(doc.getPage(it)) }
        if (targets.isEmpty()) return
        val first = targets.first()
        val labels = runCatching { doc.documentCatalog.pageLabels?.labelsByPageIndices }.getOrNull()
        val info = doc.documentInformation
        val now = java.time.LocalDate.now(); val time = java.time.LocalTime.now()
        for (i in targets) {
            val page = doc.getPage(i)
            if (spec.shrink > 0f) shrinkContent(doc, page, spec.shrink)
            val ctx = TokenContext(page = spec.startNumber + (i - first), pages = n - first + spec.startNumber - 1, date = now, time = time,
                file = fileName, title = info.title ?: "", author = info.author ?: "", user = spec.user,
                bates = bates?.invoke(i) ?: "", label = labels?.getOrNull(i) ?: "", arabic = spec.arabicNames, ummAlQura = spec.ummAlQura)
            val even = (i + 1) % 2 == 0
            fun cell(s: String) = if (s.isBlank()) null else E5Line(fonts, Tokens.expand(s, ctx, spec.arabicDigits), spec.style).takeIf { !it.isEmpty }
            var tl = spec.topLeft; var tr = spec.topRight; var bl = spec.bottomLeft; var br = spec.bottomRight
            if (spec.mirrorEven && even) { tl = spec.topRight; tr = spec.topLeft; bl = spec.bottomRight; br = spec.bottomLeft }
            val top = listOf(cell(tl), cell(spec.topCenter), cell(tr))
            val bottom = listOf(cell(bl), cell(spec.bottomCenter), cell(br))
            if (top.all { it == null } && bottom.all { it == null }) continue
            val frame = PageFrame.of(page)
            val w = frame.width; val h = frame.height
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(frame.toUser)
                fun row(cells: List<E5Line?>, y: Float, sub: String) {
                    if (cells.all { it == null }) return
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.open(kind, sub))
                    cells[0]?.draw(cs, spec.marginSide, y)
                    cells[1]?.let { it.draw(cs, (w - it.width) / 2f, y) }
                    cells[2]?.let { it.draw(cs, w - spec.marginSide - it.width, y) }
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.CLOSE)
                }
                row(top, h - spec.marginTop - spec.style.size * 0.8f, "Header")
                row(bottom, spec.marginBottom, "Footer")
                cs.restoreGraphicsState()
            }
        }
    }

    /** Scale the existing content of [page] by (1 - [f]) around the page centre. */
    internal fun shrinkContent(doc: PDDocument, page: PDPage, f: Float) {
        val s = (1f - f.coerceIn(0f, 0.4f))
        val box = page.cropBox
        val cx = box.lowerLeftX + box.width / 2f; val cy = box.lowerLeftY + box.height / 2f
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(com.tom_roush.pdfbox.util.Matrix(s, 0f, 0f, s, cx - cx * s, cy - cy * s))
        }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs -> cs.restoreGraphicsState() }
    }

    // ---------------- Bates ----------------

    /** Where the Bates number goes. */
    enum class Slot { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    /**
     * Bates numbering across [files] (in order): numbers continue from file to file. Each result is written to
     * [outFor] (file → output). Returns the outputs with the range each got.
     */
    /**
     * Bates over [files] in order. [passwords] gives the password of each locked file (asked once per file by the
     * screen); outputs are protected like their source unless [protection] is REMOVE.
     */
    suspend fun bates(context: Context, files: List<File>, outFor: (File) -> File, bates: BatesSpec, slot: Slot, style: E5TextStyle, margin: Float = 24f,
                      passwords: (File) -> String? = { null }, protection: OutProtection = OutProtection.KEEP): List<Pair<File, LongRange>> = withContext(Dispatchers.IO) {
        val counts = files.map { f -> E5Io.load(f, passwords(f)).use { it.numberOfPages } }
        val ranges = Bates.ranges(bates, counts)
        files.mapIndexed { k, f ->
            val out = outFor(f)
            val first = ranges[k].first
            E5Io.edit(f, out, passwords(f)) { doc ->
                E5Locks.applyTo(doc, protection, passwords(f))
                doc.pages.forEach { p -> Marked.strip(doc, p) { it == KIND_BATES } }
                val cell = "{bates}"
                val spec = HFSpec(
                    topLeft = if (slot == Slot.TOP_LEFT) cell else "", topCenter = if (slot == Slot.TOP_CENTER) cell else "", topRight = if (slot == Slot.TOP_RIGHT) cell else "",
                    bottomLeft = if (slot == Slot.BOTTOM_LEFT) cell else "", bottomCenter = if (slot == Slot.BOTTOM_CENTER) cell else "", bottomRight = if (slot == Slot.BOTTOM_RIGHT) cell else "",
                    style = style, marginTop = margin, marginBottom = margin, marginSide = margin)
                draw(context, doc, spec, KIND_BATES, f.name) { i -> Bates.format(bates, first + i) }
                Marked.putSetting(doc, "BATES", org.json.JSONObject().put("prefix", bates.prefix).put("suffix", bates.suffix).put("digits", bates.digits)
                    .put("first", first).put("last", ranges[k].last).toString())
            }
            out to ranges[k]
        }
    }
}
