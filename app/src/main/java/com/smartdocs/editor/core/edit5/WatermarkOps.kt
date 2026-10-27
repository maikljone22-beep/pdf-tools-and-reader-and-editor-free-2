package com.smartdocs.editor.core.edit5

import android.content.Context
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class DecorType { WATERMARK, BACKGROUND, SECURITY }
enum class DecorSource { TEXT, IMAGE, COLOR, PDF }

/**
 * Watermark / background / security watermark. Text may use tokens ([Tokens]: `{user} {date} {time} {datetime}
 * {page} {file}` …) which are resolved per page when applied ("dynamic" watermark; re-apply with update to refresh).
 * [scale] = width of the text/image as a fraction of the page width (0 = use [style] size for text).
 * [hAlign]/[vAlign] -1 left/bottom, 0 centre, 1 right/top. [behind] draws under the page content.
 * Nitro additions: [pdfPath]/[pdfPage] (a page of another PDF as the watermark), [lineSpacing] (1, 1.5, 2) and
 * [wrapWidth] (fraction of the page width, 0 = no wrap) for text, a [border] around the watermark, a [click] action,
 * "from page [everyStart] every [everyN] pages", and [ignoreRotation] (place on the unrotated page).
 */
data class DecorSpec(
    val type: DecorType = DecorType.WATERMARK,
    val source: DecorSource = DecorSource.TEXT,
    val text: String = "",
    val style: E5TextStyle = E5TextStyle(size = 48f, color = 0x9E9E9E),
    val imagePath: String = "",
    val fill: Int = 0xFFF8E1,
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val scale: Float = 0f,
    val hAlign: Int = 0, val vAlign: Int = 0,
    val offsetX: Float = 0f, val offsetY: Float = 0f,
    val tile: Boolean = false,
    val tileGap: Float = 60f,
    val behind: Boolean = false,
    val onScreen: Boolean = true,
    val onPrint: Boolean = true,
    val range: String = "",
    val subset: PageSubset = PageSubset.ALL,
    val arabicDigits: Boolean = false,
    val user: String = "",
    /** Security watermark only: a microtext line along every edge. */
    val microtext: Boolean = true,
    /**
     * Live watermark (text watermark only): drawn by read-only form fields whose value a document script refreshes on
     * open and before printing ([LiveWatermark]); the static appearance keeps the values of the last apply.
     */
    val live: Boolean = false,
    val pdfPath: String = "",
    val pdfPage: Int = 0,
    val lineSpacing: Float = 1f,
    val wrapWidth: Float = 0f,
    val border: Int? = null,
    val borderWidth: Float = 1f,
    val borderDashed: Boolean = false,
    val click: ClickAction = ClickAction.NONE,
    val clickTarget: String = "",
    val clickPage: Int = 1,
    val everyStart: Int = 1,
    val everyN: Int = 0,
    val ignoreRotation: Boolean = false,
) {
    /** True when this spec is written as a live (JavaScript-updated) watermark. */
    val isLive: Boolean get() = live && type == DecorType.WATERMARK && source == DecorSource.TEXT

    val kind: String get() = when (type) { DecorType.WATERMARK -> "wm"; DecorType.BACKGROUND -> "bg"; DecorType.SECURITY -> "sec" }

    fun toJson(): String = JSONObject().apply {
        put("type", type.name); put("source", source.name); put("text", text); put("font", style.font.name); put("size", style.size.toDouble()); put("color", style.color); put("bold", style.bold)
        put("image", imagePath); put("fill", fill); put("opacity", opacity.toDouble()); put("rotation", rotation.toDouble()); put("scale", scale.toDouble())
        put("h", hAlign); put("v", vAlign); put("ox", offsetX.toDouble()); put("oy", offsetY.toDouble()); put("tile", tile); put("gap", tileGap.toDouble())
        put("behind", behind); put("screen", onScreen); put("print", onPrint); put("range", range); put("subset", subset.name); put("arDigits", arabicDigits); put("micro", microtext); put("live", live)
        put("underline", style.underline); style.putExtras(this)
        put("pdf", pdfPath); put("pdfPage", pdfPage); put("spacing", lineSpacing.toDouble()); put("wrap", wrapWidth.toDouble())
        put("border", border ?: -1); put("borderW", borderWidth.toDouble()); put("dashed", borderDashed)
        put("click", click.name); put("clickTarget", clickTarget); put("clickPage", clickPage); put("everyStart", everyStart); put("everyN", everyN); put("noRot", ignoreRotation)
    }.toString()

    companion object {
        fun fromJson(s: String?): DecorSpec? = s?.let {
            runCatching {
                val o = JSONObject(it)
                DecorSpec(DecorType.valueOf(o.getString("type")), DecorSource.valueOf(o.getString("source")), o.optString("text"),
                    E5TextStyle(runCatching { E5Font.valueOf(o.optString("font")) }.getOrDefault(E5Font.NASKH), o.optDouble("size", 48.0).toFloat(), o.optInt("color", 0x9E9E9E), o.optBoolean("bold")),
                    o.optString("image"), o.optInt("fill", 0xFFF8E1), o.optDouble("opacity", 0.3).toFloat(), o.optDouble("rotation", 45.0).toFloat(), o.optDouble("scale", 0.0).toFloat(),
                    o.optInt("h"), o.optInt("v"), o.optDouble("ox", 0.0).toFloat(), o.optDouble("oy", 0.0).toFloat(), o.optBoolean("tile"), o.optDouble("gap", 60.0).toFloat(),
                    o.optBoolean("behind"), o.optBoolean("screen", true), o.optBoolean("print", true), o.optString("range"),
                    runCatching { PageSubset.valueOf(o.optString("subset")) }.getOrDefault(PageSubset.ALL), o.optBoolean("arDigits"), microtext = o.optBoolean("micro", true), live = o.optBoolean("live"))
                    .let { d -> d.copy(style = d.style.copy(underline = o.optBoolean("underline")).withExtras(o),
                        pdfPath = o.optString("pdf"), pdfPage = o.optInt("pdfPage"), lineSpacing = o.optDouble("spacing", 1.0).toFloat(), wrapWidth = o.optDouble("wrap", 0.0).toFloat(),
                        border = o.optInt("border", -1).takeIf { it >= 0 }, borderWidth = o.optDouble("borderW", 1.0).toFloat(), borderDashed = o.optBoolean("dashed"),
                        click = runCatching { ClickAction.valueOf(o.optString("click")) }.getOrDefault(ClickAction.NONE), clickTarget = o.optString("clickTarget"),
                        clickPage = o.optInt("clickPage", 1), everyStart = o.optInt("everyStart", 1), everyN = o.optInt("everyN"), ignoreRotation = o.optBoolean("noRot")) }
            }.getOrNull()
        }

        /** Security watermark preset: tiled, diagonal, faint, with who/when/which-file and a trace id. */
        fun security(user: String, traceId: String, base: DecorSpec = DecorSpec()): DecorSpec = base.copy(
            type = DecorType.SECURITY, source = DecorSource.TEXT, tile = true, rotation = 35f, opacity = 0.12f, behind = false,
            text = "{user} • {datetime} • {file} • $traceId", user = user,
            style = base.style.copy(size = 14f, color = 0x607D8B), tileGap = 40f)
    }
}

object WatermarkOps {
    fun settingKey(t: DecorType) = "DECOR_" + t.name

    suspend fun stored(src: File, password: String?, type: DecorType): DecorSpec? = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { DecorSpec.fromJson(Marked.getSetting(it, settingKey(type))) }
    }

    /**
     * Add (or update when [replace]) a watermark / background / security watermark. [fileName] is the name shown by
     * the `{file}` token (the screens work on a temp copy, so they pass the original name).
     */
    suspend fun apply(context: Context, src: File, out: File, password: String?, spec: DecorSpec, replace: Boolean = true, fileName: String = src.name) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            if (replace) {
                doc.pages.forEach { p -> Marked.strip(doc, p) { it == spec.kind } }
                DecorExtras.removeLinks(doc, spec.kind)
                if (spec.type == DecorType.WATERMARK) LiveWatermark.remove(doc)
            }
            if (spec.isLive) LiveWatermark.install(doc, spec, fileName, liveFace(context, doc, spec)) else draw(context, doc, spec, fileName)
            Marked.putSetting(doc, settingKey(spec.type), spec.toJson())
            if (spec.type == DecorType.SECURITY) doc.documentInformation.setCustomMetadataValue("SmartDocsTrace", spec.text.substringAfterLast('•').trim())
        }
    }

    /** Remove; [others] also removes other producers' /Watermark and /Background artifacts. Returns pages changed. */
    suspend fun remove(src: File, out: File, password: String?, type: DecorType, others: Boolean = false): Int = withContext(Dispatchers.IO) {
        val kind = DecorSpec(type = type).kind
        val foreign = if (type == DecorType.BACKGROUND) "pdf:Background" else "pdf:Watermark"
        var n = 0
        E5Io.edit(src, out, password) { doc ->
            doc.pages.forEach { p -> if (Marked.strip(doc, p) { it == kind || (others && it == foreign) }) n++ }
            DecorExtras.removeLinks(doc, kind)
            if (type == DecorType.WATERMARK) n += LiveWatermark.remove(doc)
            Marked.putSetting(doc, settingKey(type), null)
        }
        n
    }

    /** Face of a live watermark: shaped Arabic/Latin static text, Arabic font fully embedded for viewers that redraw. */
    fun liveFace(context: Context, doc: PDDocument, spec: DecorSpec): (E5TextStyle) -> LiveFace {
        val fonts = E5Fonts(context, doc)
        val arabic = ArabicShaper.isArabic(spec.text + spec.user)
        return { style -> AppLiveFace(context, doc, fonts, style, arabic) }
    }

    private class AppLiveFace(private val context: Context, private val doc: PDDocument, private val fonts: E5Fonts, private val style: E5TextStyle, private val arabic: Boolean) : LiveFace {
        private val lines = HashMap<String, E5Line>()
        private fun line(s: String) = lines.getOrPut(s) { E5Line(fonts, s, style) }
        override val size: Float get() = style.size
        override fun width(line: String): Float = line(line).width
        override fun draw(cs: PDPageContentStream, res: PDResources, line: String, x: Float, y: Float) = line(line).draw(cs, x, y)
        override fun daFont(): COSBase {
            val latin = if (style.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            if (!arabic) return latin.cosObject
            // full font (not a subset): a viewer redrawing the field needs glyphs for any user name or date
            return runCatching { context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { PDType0Font.load(doc, it, false).cosObject } }.getOrElse { latin.cosObject }
        }
    }

    private fun draw(context: Context, doc: PDDocument, spec: DecorSpec, fileName: String) {
        val n = doc.numberOfPages
        val targets = DecorExtras.targets(doc, spec.range, spec.subset, spec.everyStart, spec.everyN)
        if (targets.isEmpty()) return
        val fonts = E5Fonts(context, doc)
        val image: PDImageXObject? = if (spec.source == DecorSource.IMAGE) loadImage(doc, spec.imagePath) else null
        if (spec.source == DecorSource.IMAGE && image == null) throw IllegalArgumentException("image")
        val form = if (spec.source == DecorSource.PDF) DecorExtras.importPage(doc, File(spec.pdfPath), spec.pdfPage) else null
        val ocg = if (!spec.onScreen || !spec.onPrint) visibilityGroup(doc, spec) else null
        val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = spec.opacity; strokingAlphaConstant = spec.opacity }
        val now = java.time.LocalDate.now(); val time = java.time.LocalTime.now()
        val info = doc.documentInformation
        val subtype = if (spec.type == DecorType.BACKGROUND) "Background" else "Watermark"
        for (i in targets) {
            val page = doc.getPage(i)
            val frame = DecorExtras.frameOf(page, spec.ignoreRotation)
            val w = frame.width; val h = frame.height
            val ctx = TokenContext(page = i + 1, pages = n, date = now, time = time, file = fileName, title = info.title ?: "", author = info.author ?: "", user = spec.user,
                subject = info.subject ?: "", keywords = info.keywords ?: "")
            var placed: Pair<Float, Float>? = null; var boxW = 0f; var boxH = 0f
            val mode = if (spec.behind || spec.type == DecorType.BACKGROUND) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
            PDPageContentStream(doc, page, mode, true, mode == PDPageContentStream.AppendMode.APPEND).use { cs ->
                cs.saveGraphicsState()
                @Suppress("DEPRECATION") cs.appendRawCommands(Marked.open(spec.kind, subtype))
                if (ocg != null) { val nm = page.resources.add(ocg); @Suppress("DEPRECATION") cs.appendRawCommands("/OC /${nm.name} BDC\n") }
                cs.transform(frame.toUser)
                cs.setGraphicsStateParameters(gs)
                when (spec.source) {
                    DecorSource.COLOR -> { val c = E5Io.rgb(spec.fill); cs.setNonStrokingColor(c[0], c[1], c[2]); cs.addRect(0f, 0f, w, h); cs.fill() }
                    DecorSource.IMAGE -> {
                        val img = image!!
                        val iw = (if (spec.scale > 0f) w * spec.scale else img.width.toFloat()).coerceAtLeast(4f)
                        val ih = iw * img.height / img.width.coerceAtLeast(1)
                        boxW = iw; boxH = ih
                        placed = placeEach(cs, spec, w, h, iw, ih) { cs.drawImage(img, -iw / 2f, -ih / 2f, iw, ih); border(cs, spec, iw, ih) }
                    }
                    DecorSource.PDF -> {
                        val f = form!!
                        val fw = (if (spec.scale > 0f) w * spec.scale else f.bBox.width).coerceAtLeast(4f)
                        val fh = fw * f.bBox.height / f.bBox.width.coerceAtLeast(1f)
                        boxW = fw; boxH = fh
                        placed = placeEach(cs, spec, w, h, fw, fh) { DecorExtras.drawForm(cs, f, -fw / 2f, -fh / 2f, fw, fh); border(cs, spec, fw, fh) }
                    }
                    DecorSource.TEXT -> {
                        var lines = Tokens.expand(spec.text, ctx, spec.arabicDigits).split('\n').filter { it.isNotBlank() }
                        if (lines.isNotEmpty()) {
                            var style = spec.style
                            if (spec.wrapWidth > 0f) lines = DecorExtras.wrap(lines.joinToString("\n"), w * spec.wrapWidth) { E5Line(fonts, it, spec.style).width }.filter { it.isNotBlank() }
                            if (spec.scale > 0f) {
                                val probe = lines.maxOf { E5Line(fonts, it, style).width }.coerceAtLeast(1f)
                                style = style.copy(size = style.size * (w * spec.scale) / probe)
                            }
                            val laid = lines.map { E5Line(fonts, it, style) }
                            val lh = style.size * 1.25f * spec.lineSpacing.coerceIn(0.8f, 3f)
                            val bw = laid.maxOf { it.width }; val bh = lh * laid.size
                            boxW = bw; boxH = bh
                            placed = placeEach(cs, spec, w, h, bw, bh) {
                                laid.forEachIndexed { k, l -> l.draw(cs, -l.width / 2f, bh / 2f - lh * (k + 1) + (lh - style.size) / 2f + style.size * 0.25f) }
                                border(cs, spec, bw, bh)
                            }
                        }
                        if (spec.type == DecorType.SECURITY && spec.microtext && lines.isNotEmpty()) microtext(cs, fonts, lines.joinToString("  "), w, h, spec.style.color)
                    }
                }
                if (ocg != null) @Suppress("DEPRECATION") cs.appendRawCommands("EMC\n")
                @Suppress("DEPRECATION") cs.appendRawCommands(Marked.CLOSE)
                cs.restoreGraphicsState()
            }
            placed?.let { (cx, cy) -> DecorExtras.addLink(doc, page, frame, spec.kind, cx, cy, boxW, boxH, spec.rotation, spec.click, spec.clickTarget, spec.clickPage) }
        }
    }

    /** Nitro "Border": a frame around the placed watermark box (centred on the origin), solid or dashed. */
    private fun border(cs: PDPageContentStream, spec: DecorSpec, bw: Float, bh: Float) {
        val c = spec.border ?: return
        val pad = 4f + spec.borderWidth
        val rgb = E5Io.rgb(c)
        cs.saveGraphicsState()
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]); cs.setLineWidth(spec.borderWidth.coerceIn(0.25f, 20f))
        if (spec.borderDashed) cs.setLineDashPattern(floatArrayOf(spec.borderWidth * 4f, spec.borderWidth * 3f), 0f)
        cs.addRect(-bw / 2f - pad, -bh / 2f - pad, bw + 2 * pad, bh + 2 * pad); cs.stroke()
        cs.restoreGraphicsState()
    }

    /**
     * Run [block] with the origin at the centre of each placement (once, or tiled over the page) and the rotation
     * applied. Returns the centre of a single placement (display space), null when tiled.
     */
    private fun placeEach(cs: PDPageContentStream, spec: DecorSpec, w: Float, h: Float, bw: Float, bh: Float, block: () -> Unit): Pair<Float, Float>? {
        if (!spec.tile) {
            val cx = when (spec.hAlign) { -1 -> bw / 2f + 18f; 1 -> w - bw / 2f - 18f; else -> w / 2f } + spec.offsetX
            val cy = when (spec.vAlign) { -1 -> bh / 2f + 18f; 1 -> h - bh / 2f - 18f; else -> h / 2f } + spec.offsetY
            cs.saveGraphicsState()
            cs.transform(Affine.mul(Affine.rotateAround(spec.rotation, 0f, 0f), Affine.translate(cx, cy)))
            block()
            cs.restoreGraphicsState()
            return cx to cy
        }
        // tiles on a rotated grid centred on the page, covering the page diagonal
        val diag = Math.hypot(w.toDouble(), h.toDouble()).toFloat()
        val sx = bw + spec.tileGap; val sy = bh + spec.tileGap
        cs.saveGraphicsState()
        cs.transform(Affine.mul(Affine.rotateAround(spec.rotation, 0f, 0f), Affine.translate(w / 2f, h / 2f)))
        var row = 0
        var y = -diag / 2f
        while (y <= diag / 2f) {
            var x = -diag / 2f + if (row % 2 == 1) sx / 2f else 0f
            while (x <= diag / 2f) {
                cs.saveGraphicsState(); cs.transform(Affine.translate(x, y)); block(); cs.restoreGraphicsState()
                x += sx
            }
            y += sy; row++
        }
        cs.restoreGraphicsState()
        return null
    }

    /** A 3-point text line along each page edge (hard to copy or remove by scanning). */
    private fun microtext(cs: PDPageContentStream, fonts: E5Fonts, text: String, w: Float, h: Float, color: Int) {
        val style = E5TextStyle(size = 3f, color = color)
        val unit = E5Line(fonts, "$text   ", style)
        if (unit.isEmpty || unit.width <= 0f) return
        fun along(len: Float, place: (Float) -> Unit) { var x = 0f; while (x < len) { place(x); x += unit.width } }
        along(w) { x -> unit.draw(cs, x, 6f) }
        along(w) { x -> unit.draw(cs, x, h - 9f) }
        cs.saveGraphicsState(); cs.transform(Affine.mul(Affine.rotateAround(90f, 0f, 0f), Affine.translate(9f, 0f)))
        along(h) { y -> unit.draw(cs, y, 0f) }
        cs.restoreGraphicsState()
        cs.saveGraphicsState(); cs.transform(Affine.mul(Affine.rotateAround(90f, 0f, 0f), Affine.translate(w - 4f, 0f)))
        along(h) { y -> unit.draw(cs, y, 0f) }
        cs.restoreGraphicsState()
    }

    internal fun loadImage(doc: PDDocument, path: String): PDImageXObject? {
        val f = File(path)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val bmp = PdfOps.decodeScaled(f, 2000) ?: return null
        return runCatching {
            if (opts.outMimeType == "image/jpeg") JPEGFactory.createFromImage(doc, bmp, 0.9f) else LosslessFactory.createFromImage(doc, bmp)
        }.getOrNull().also { bmp.recycle() }
    }

    /**
     * Optional content group controlling screen/print visibility: /Usage /View /Print plus /D /AS auto-states
     * (Acrobat), and /D /OFF when hidden on screen (honoured by PDFium and most viewers).
     */
    private fun visibilityGroup(doc: PDDocument, spec: DecorSpec): PDOptionalContentGroup {
        val cat = doc.documentCatalog
        val props = cat.ocProperties ?: PDOptionalContentProperties().also { cat.ocProperties = it }
        val name = "SmartDocs " + (if (spec.type == DecorType.BACKGROUND) "Background" else "Watermark")
        val g = props.getGroup(name) ?: PDOptionalContentGroup(name).also { props.addGroup(it) }
        val usage = COSDictionary()
        usage.setItem(COSName.getPDFName("View"), COSDictionary().apply { setName("ViewState", if (spec.onScreen) "ON" else "OFF") })
        usage.setItem(COSName.getPDFName("Print"), COSDictionary().apply { setName("PrintState", if (spec.onPrint) "ON" else "OFF") })
        g.cosObject.setItem(COSName.getPDFName("Usage"), usage)
        val d = props.cosObject.getCOSDictionary(COSName.D) ?: COSDictionary().also { props.cosObject.setItem(COSName.D, it) }
        val asArr = COSArray()
        listOf("View", "Print").forEach { ev ->
            asArr.add(COSDictionary().apply {
                setName("Event", ev)
                setItem(COSName.getPDFName("Category"), COSArray().apply { add(COSName.getPDFName(ev)) })
                setItem(COSName.getPDFName("OCGs"), COSArray().apply { add(g.cosObject) })
            })
        }
        d.setItem(COSName.getPDFName("AS"), asArr)
        props.setGroupEnabled(g, spec.onScreen)
        return g
    }
}
