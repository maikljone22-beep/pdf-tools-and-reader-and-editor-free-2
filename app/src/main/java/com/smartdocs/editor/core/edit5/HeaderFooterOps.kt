package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
 * Nitro additions: [marginRight] (< 0 = same as [marginSide], which is then the left margin), [opacity],
 * [rotation] of each cell around its centre, [behind] the page content, "from page [everyStart] every [everyN]
 * pages", [ignoreRotation], an image or PDF page ([imagePath], [imagePage]) [imageHeight] points high in cell
 * [imageSlot] (0..5 = top left, top centre, top right, bottom left, bottom centre, bottom right), the number format
 * of `{bates}` outside the Bates tool ([bates]), [growPage] (enlarge the page so the rows sit outside the content)
 * and the [unit] the margins are shown in.
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
    val marginRight: Float = -1f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val behind: Boolean = false,
    val everyStart: Int = 1,
    val everyN: Int = 0,
    val ignoreRotation: Boolean = com.smartdocs.editor.core.data.NpfPrefs.hfIgnoreRotation,   // nav-prefs: default from Settings
    val imagePath: String = "",
    val imagePage: Int = 0,
    val imageSlot: Int = 1,
    val imageHeight: Float = 28f,
    val bates: BatesSpec = BatesSpec(),
    val growPage: Boolean = false,
    val unit: E5Unit = E5Unit.PT,
) {
    val rightMargin: Float get() = if (marginRight < 0f) marginSide else marginRight

    fun toJson(): String = JSONObject().apply {
        put("cells", JSONArray(listOf(topLeft, topCenter, topRight, bottomLeft, bottomCenter, bottomRight)))
        put("font", style.font.name); put("size", style.size.toDouble()); put("color", style.color); put("bold", style.bold); put("underline", style.underline)
        put("mt", marginTop.toDouble()); put("mb", marginBottom.toDouble()); put("ms", marginSide.toDouble())
        put("start", startNumber); put("arabicDigits", arabicDigits); put("mirror", mirrorEven); put("shrink", shrink.toDouble())
        put("range", range); put("subset", subset.name); put("uaq", ummAlQura); put("arNames", arabicNames)
        style.putExtras(this)
        put("mr", marginRight.toDouble()); put("opacity", opacity.toDouble()); put("rotation", rotation.toDouble()); put("behind", behind)
        put("everyStart", everyStart); put("everyN", everyN); put("noRot", ignoreRotation)
        put("image", imagePath); put("imagePage", imagePage); put("imageSlot", imageSlot); put("imageH", imageHeight.toDouble())
        put("bPrefix", bates.prefix); put("bSuffix", bates.suffix); put("bDigits", bates.digits); put("bStart", bates.start)
        put("grow", growPage); put("unit", unit.name)
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
                    .let { h -> h.copy(style = h.style.withExtras(o), marginRight = o.optDouble("mr", -1.0).toFloat(), opacity = o.optDouble("opacity", 1.0).toFloat(),
                        rotation = o.optDouble("rotation", 0.0).toFloat(), behind = o.optBoolean("behind"), everyStart = o.optInt("everyStart", 1), everyN = o.optInt("everyN"),
                        ignoreRotation = o.optBoolean("noRot"), imagePath = o.optString("image"), imagePage = o.optInt("imagePage"), imageSlot = o.optInt("imageSlot", 1).coerceIn(0, 5),
                        imageHeight = o.optDouble("imageH", 28.0).toFloat(),
                        bates = BatesSpec(o.optString("bPrefix"), o.optString("bSuffix"), o.optInt("bDigits", 6), o.optLong("bStart", 1)),
                        growPage = o.optBoolean("grow"), unit = runCatching { E5Unit.valueOf(o.optString("unit")) }.getOrDefault(E5Unit.PT)) }
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

    /**
     * Add (or, when [replace], update) header & footer. [fileName]/[path] feed `{file}`/`{path}` (the screen works on a
     * temp copy); [batch] = (pages before this file, pages of the whole batch) feeds `{batchpage}`/`{batchpages}`.
     */
    suspend fun apply(context: Context, src: File, out: File, password: String?, spec: HFSpec, replace: Boolean = true,
                      fileName: String = src.name, path: String = "", batch: Pair<Int, Int>? = null) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            // content shrunk by an earlier run is not scaled again on update (the scale itself is not tagged)
            val prev = HFSpec.fromJson(Marked.getSetting(doc, SETTING))
            if (replace) doc.pages.forEach { Marked.strip(doc, it) { k -> k == KIND } }
            if (replace && prev?.growPage == true) Grown.restore(doc)
            draw(context, doc, if (replace && (prev?.shrink ?: 0f) > 0f) spec.copy(shrink = 0f) else spec, KIND, fileName, null, path = path, batch = batch)
            Marked.putSetting(doc, SETTING, spec.toJson())
        }
    }

    /**
     * The same header & footer on several files (Nitro batch): `{batchpage}` counts across them in order and
     * `{batchpages}` is their total. Returns the outputs.
     */
    suspend fun applyBatch(context: Context, files: List<File>, outFor: (File) -> File, spec: HFSpec, passwords: (File) -> String? = { null },
                           sourceOf: (File) -> File = { it }): List<File> = withContext(Dispatchers.IO) {
        val counts = files.map { f -> E5Io.load(f, passwords(f)).use { it.numberOfPages } }
        val total = counts.sum()
        var before = 0
        files.mapIndexed { k, f ->
            val out = outFor(f)
            apply(context, f, out, passwords(f), spec, replace = true, fileName = sourceOf(f).name, path = sourceOf(f).absolutePath, batch = before to total)
            before += counts[k]
            out
        }
    }

    /**
     * Remove our header/footer; [others] also removes other producers' header/footer artifacts (Acrobat). Bates numbers
     * (ours or Acrobat's /BatesN) stay unless [bates]: the Bates tool removes them ([removeBates]).
     */
    suspend fun remove(src: File, out: File, password: String?, others: Boolean = false, bates: Boolean = false): Int = withContext(Dispatchers.IO) {
        var n = 0
        E5Io.edit(src, out, password) { doc ->
            doc.pages.forEach { p ->
                if (Marked.strip(doc, p) { k -> k == KIND || (bates && k == KIND_BATES) || (others && (k == "pdf:Header" || k == "pdf:Footer")) }) n++
            }
            if (HFSpec.fromJson(Marked.getSetting(doc, SETTING))?.growPage == true || bates) Grown.restore(doc)
            Marked.putSetting(doc, SETTING, null)
        }
        n
    }

    /**
     * Draw the cells of [spec] on its target pages of [doc]. [bates] (page index → Bates text) feeds `{bates}`; without
     * it `{bates}` numbers the target pages with [HFSpec.bates]. [batesTotal] feeds `{batestotal}`, [batch] the batch
     * tokens. Each cell row is tagged as /Artifact /Pagination /Header or /Footer with /SDE5 ([kind]).
     */
    fun draw(context: Context, doc: PDDocument, spec: HFSpec, kind: String, fileName: String, bates: ((Int) -> String)?,
             path: String = "", batch: Pair<Int, Int>? = null, batesTotal: String = "", pages: List<Int>? = null) {
        val fonts = E5Fonts(context, doc)
        val n = doc.numberOfPages
        val targets = pages ?: DecorExtras.targets(doc, spec.range, spec.subset, spec.everyStart, spec.everyN)
        if (targets.isEmpty()) return
        val first = targets.first()
        val labels = runCatching { doc.documentCatalog.pageLabels?.labelsByPageIndices }.getOrNull()
        val info = doc.documentInformation
        val now = java.time.LocalDate.now(); val time = java.time.LocalTime.now()
        val cells = listOf(spec.topLeft, spec.topCenter, spec.topRight, spec.bottomLeft, spec.bottomCenter, spec.bottomRight)
        val usesBates = cells.any { Tokens.usesBates(it) }
        val ownTotal = if (bates == null && usesBates) targets.size.toString() else batesTotal
        val pic = if (spec.imagePath.isBlank()) null else HFPicture.load(doc, File(spec.imagePath), spec.imagePage)
        val gs = if (spec.opacity < 1f) com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = spec.opacity; strokingAlphaConstant = spec.opacity } else null
        for ((ordinal, i) in targets.withIndex()) {
            val page = doc.getPage(i)
            if (spec.shrink > 0f) shrinkContent(doc, page, spec.shrink)
            val batesText = bates?.invoke(i) ?: if (usesBates) Bates.format(spec.bates, spec.bates.start + ordinal) else ""
            val ctx = TokenContext(page = spec.startNumber + (i - first), pages = n - first + spec.startNumber - 1, date = now, time = time,
                file = fileName, title = info.title ?: "", author = info.author ?: "", user = spec.user,
                bates = batesText, label = labels?.getOrNull(i) ?: "", arabic = spec.arabicNames, ummAlQura = spec.ummAlQura,
                path = path, subject = info.subject ?: "", keywords = info.keywords ?: "",
                batchPage = batch?.let { it.first + i + 1 } ?: 0, batchPages = batch?.second ?: 0, batesTotal = ownTotal)
            val even = (i + 1) % 2 == 0
            fun cell(s: String) = if (s.isBlank()) null else E5Line(fonts, Tokens.expand(s, ctx, spec.arabicDigits), spec.style).takeIf { !it.isEmpty }
            var tl = spec.topLeft; var tr = spec.topRight; var bl = spec.bottomLeft; var br = spec.bottomRight
            if (spec.mirrorEven && even) { tl = spec.topRight; tr = spec.topLeft; bl = spec.bottomRight; br = spec.bottomLeft }
            val top = listOf(cell(tl), cell(spec.topCenter), cell(tr))
            val bottom = listOf(cell(bl), cell(spec.bottomCenter), cell(br))
            val picTop = pic != null && spec.imageSlot < 3
            if (top.all { it == null } && bottom.all { it == null } && pic == null) continue
            if (spec.growPage) {
                val textH = spec.style.size * 1.2f
                val needTop = if (top.any { it != null } || picTop) spec.marginTop + maxOf(if (top.any { it != null }) textH else 0f, if (picTop) spec.imageHeight else 0f) + 4f else 0f
                val needBottom = if (bottom.any { it != null } || (pic != null && !picTop)) spec.marginBottom + maxOf(if (bottom.any { it != null }) textH else 0f, if (pic != null && !picTop) spec.imageHeight else 0f) + 4f else 0f
                Grown.grow(page, needTop, needBottom)
            }
            val frame = DecorExtras.frameOf(page, spec.ignoreRotation)
            val w = frame.width; val h = frame.height
            val mode = if (spec.behind) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
            PDPageContentStream(doc, page, mode, true, !spec.behind).use { cs ->
                cs.saveGraphicsState()
                cs.transform(frame.toUser)
                gs?.let { cs.setGraphicsStateParameters(it) }
                fun turned(cx: Float, cy: Float, block: () -> Unit) {
                    if (spec.rotation == 0f) { block(); return }
                    cs.saveGraphicsState(); cs.transform(com.smartdocs.editor.core.edit.Affine.rotateAround(spec.rotation, cx, cy)); block(); cs.restoreGraphicsState()
                }
                fun place(l: E5Line, x: Float, y: Float) = turned(x + l.width / 2f, y + spec.style.size * 0.35f) { l.draw(cs, x, y) }
                fun row(cells: List<E5Line?>, y: Float, sub: String) {
                    if (cells.all { it == null }) return
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.open(kind, sub))
                    cells[0]?.let { place(it, spec.marginSide, y) }
                    cells[1]?.let { place(it, (w - it.width) / 2f, y) }
                    cells[2]?.let { place(it, w - spec.rightMargin - it.width, y) }
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.CLOSE)
                }
                row(top, h - spec.marginTop - spec.style.size * 0.8f, "Header")
                row(bottom, spec.marginBottom, "Footer")
                if (pic != null) {
                    val ih = spec.imageHeight.coerceAtLeast(4f); val iw = ih * pic.aspect
                    val x = when (spec.imageSlot % 3) { 0 -> spec.marginSide; 1 -> (w - iw) / 2f; else -> w - spec.rightMargin - iw }
                    val y = if (picTop) h - spec.marginTop - ih else spec.marginBottom
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.open(kind, if (picTop) "Header" else "Footer"))
                    turned(x + iw / 2f, y + ih / 2f) { pic.draw(cs, x, y, iw, ih) }
                    @Suppress("DEPRECATION") cs.appendRawCommands(Marked.CLOSE)
                }
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
     * Look and placement of Bates numbers: [template] is the cell text (`{bates}`, `{batestotal}` and every other
     * token), margins per side in points, colour/size/font in [style], [opacity], [rotation], [behind] the content,
     * [growPage] (enlarge the page instead of covering it), and the pages of each file that get a number ([range],
     * [subset]).
     */
    data class BatesLayout(
        val slot: Slot = Slot.BOTTOM_RIGHT,
        val style: E5TextStyle = E5TextStyle(size = 9f, color = 0x212121),
        val marginTop: Float = 20f, val marginBottom: Float = 20f, val marginLeft: Float = 20f, val marginRight: Float = 20f,
        val opacity: Float = 1f, val rotation: Float = 0f, val behind: Boolean = false, val growPage: Boolean = false,
        val range: String = "", val subset: PageSubset = PageSubset.ALL,
        val template: String = "{bates}",
    )

    /**
     * Bates over [files] in order ([BatesSpec.mode]: continue, one number per document, or restart per document;
     * [BatesSpec.increment] may be negative). [outFor] names each output from the file and its first / last Bates
     * number. [passwords] gives the password of each locked file (asked once per file by the screen); outputs are
     * protected like their source unless [protection] is REMOVE. Returns each output with its first and last number.
     */
    suspend fun bates(context: Context, files: List<File>, outFor: (File, String, String) -> File, bates: BatesSpec, layout: BatesLayout,
                      passwords: (File) -> String? = { null }, protection: OutProtection = OutProtection.KEEP): List<Triple<File, String, String>> = withContext(Dispatchers.IO) {
        val stamped = files.map { f -> E5Io.load(f, passwords(f)).use { d -> d.numberOfPages to PageSet.filter(PageSet.parse(layout.range, d.numberOfPages), layout.subset) { E5Io.isLandscape(d.getPage(it)) } } }
        val plan = Bates.plan(bates, stamped)
        val total = Bates.total(plan).toString()
        files.mapIndexed { k, f ->
            val numbers = plan[k]
            val ordered = numbers.keys.sorted().map { numbers.getValue(it) }
            val firstText = ordered.firstOrNull()?.let { Bates.format(bates, it) } ?: ""
            val lastText = ordered.lastOrNull()?.let { Bates.format(bates, it) } ?: ""
            val out = outFor(f, firstText, lastText)
            E5Io.edit(f, out, passwords(f)) { doc ->
                E5Locks.applyTo(doc, protection, passwords(f))
                doc.pages.forEach { p -> Marked.strip(doc, p) { it == KIND_BATES } }
                Grown.restore(doc)
                val cell = layout.template.ifBlank { "{bates}" }
                val sl = layout.slot
                val spec = HFSpec(
                    topLeft = if (sl == Slot.TOP_LEFT) cell else "", topCenter = if (sl == Slot.TOP_CENTER) cell else "", topRight = if (sl == Slot.TOP_RIGHT) cell else "",
                    bottomLeft = if (sl == Slot.BOTTOM_LEFT) cell else "", bottomCenter = if (sl == Slot.BOTTOM_CENTER) cell else "", bottomRight = if (sl == Slot.BOTTOM_RIGHT) cell else "",
                    style = layout.style, marginTop = layout.marginTop, marginBottom = layout.marginBottom, marginSide = layout.marginLeft, marginRight = layout.marginRight,
                    opacity = layout.opacity, rotation = layout.rotation, behind = layout.behind, growPage = layout.growPage)
                draw(context, doc, spec, KIND_BATES, f.name, { i -> numbers[i]?.let { Bates.format(bates, it) } ?: "" },
                    path = f.absolutePath, batesTotal = total, pages = numbers.keys.sorted())
                val first = numbers.values.minOrNull() ?: bates.start; val last = numbers.values.maxOrNull() ?: (first - 1)
                Marked.putSetting(doc, "BATES", org.json.JSONObject().put("prefix", bates.prefix).put("suffix", bates.suffix).put("digits", bates.digits)
                    .put("first", first).put("last", last).toString())
            }
            Triple(out, firstText, lastText)
        }
    }

    /** Nitro "Remove Bates Numbering": our Bates numbers (and Acrobat's), and pages grown for them get their size back. */
    suspend fun removeBates(src: File, out: File, password: String?): Int = withContext(Dispatchers.IO) {
        var n = 0
        E5Io.edit(src, out, password) { doc ->
            doc.pages.forEach { p -> if (Marked.strip(doc, p) { k -> k == KIND_BATES || k == "pdf:BatesN" }) n++ }
            Grown.restore(doc)
            Marked.putSetting(doc, "BATES", null)
        }
        n
    }

    /** Whether [src] carries Bates numbers (ours or Acrobat's). */
    suspend fun hasBates(src: File, password: String?): Boolean = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc -> doc.pages.any { p -> Marked.kinds(p).any { it == KIND_BATES || it == "pdf:BatesN" } } }
    }
}

/** An image or a page of another PDF drawn in a header/footer cell. */
internal class HFPicture private constructor(val aspect: Float, private val image: com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject?, private val form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject?) {
    fun draw(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        image?.let { cs.drawImage(it, x, y, w, h) }
        form?.let { DecorExtras.drawForm(cs, it, x, y, w, h) }
    }

    companion object {
        fun load(doc: PDDocument, file: File, page: Int): HFPicture? {
            if (!file.exists()) return null
            if (file.extension.equals("pdf", true)) {
                val f = DecorExtras.importPage(doc, file, page)
                return HFPicture(f.bBox.width / f.bBox.height.coerceAtLeast(1f), null, f)
            }
            val img = WatermarkOps.loadImage(doc, file.absolutePath) ?: return null
            return HFPicture(img.width.toFloat() / img.height.coerceAtLeast(1), img, null)
        }
    }
}

/**
 * Pages enlarged for header/footer rows ([HFSpec.growPage]) remember their original boxes in /SDE5Grown, so a re-run
 * does not grow them twice and removal gives them their size back.
 */
internal object Grown {
    private val KEY = COSName.getPDFName("SDE5Grown")

    fun grow(page: PDPage, top: Float, bottom: Float) {
        if (page.cosObject.containsKey(KEY)) return
        val saved = COSDictionary()
        saved.setItem(COSName.MEDIA_BOX, page.mediaBox.cosArray)
        saved.setItem(COSName.CROP_BOX, page.cropBox.cosArray)
        DecorExtras.grow(page, top, bottom)
        page.cosObject.setItem(KEY, saved)
    }

    fun restore(doc: PDDocument) {
        doc.pages.forEach { p ->
            val saved = p.cosObject.getDictionaryObject(KEY) as? COSDictionary ?: return@forEach
            (saved.getDictionaryObject(COSName.MEDIA_BOX) as? COSArray)?.let { p.mediaBox = PDRectangle(it) }
            (saved.getDictionaryObject(COSName.CROP_BOX) as? COSArray)?.let { p.cropBox = PDRectangle(it) }
            p.cosObject.removeItem(KEY)
        }
    }
}
