package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Videos on PDF pages, in plain PdfBox (no android.* so it is unit tested on the JVM).
 *
 * Written form (playable in Acrobat / Foxit / Nitro): a `/Screen` annotation whose `/A` is a `/Rendition` action
 * (`/OP 0`) with a media rendition (`/R /S /MR`) → media clip data (`/C /S /MCD`, `/CT` mime, `/P /TF (TEMPACCESS)`)
 * → file specification with the video embedded (`/EF`). `/AA /PV` repeats the action when "autoplay" is on, the
 * MediaPlayParams carry loop (`/RC 0`), mute (`/V 0`) and controls (`/C`). The normal appearance (`/AP /N`) is the
 * poster frame with a play button, so every viewer shows a picture. Private keys: `/SDMedia /Video` and `/SDVideo`
 * (our options).
 *
 * Read forms: the one above, any foreign Screen + Rendition with an embedded clip, RichMedia (`/RichMediaContent
 * /Assets`, mp4/flv/... assets; Flash-only content is reported as unsupported) and the old SmartDocs form
 * (FileAttachment + `/SDMedia /Video`, or any file attachment with a video mime type).
 */
object VideoPdf {

    /**
     * Player options. FINAL_CHECK_2 #15 (§19 video properties / playback settings / media actions): [volume] 0–100,
     * [repeat] plays when not [loop]ing, [keepOpen] the player stays after playback (else the poster comes back),
     * [fullscreen] plays full screen (MediaScreenParams `/W 1`), [startOn] the page trigger of [autoplay]
     * ([START_VISIBLE] `/PV`, [START_OPEN] `/PO`), [stopWhenHidden] stop actions on `/PI` and `/PC`, [poster] mode
     * ([POSTER_AUTO], [POSTER_NONE], [POSTER_FILE]) and [alt] text (`/Contents`, the media clip's `/Alt`).
     */
    data class Options(
        val autoplay: Boolean = false, val loop: Boolean = false, val muted: Boolean = false, val controls: Boolean = true,
        val volume: Int = 100,
        val repeat: Int = 1,
        val keepOpen: Boolean = true,
        val fullscreen: Boolean = false,
        val startOn: Int = START_VISIBLE,
        val stopWhenHidden: Boolean = true,
        val poster: Int = POSTER_AUTO,
        val alt: String = "",
    ) {
        /** Player volume 0..1 (muted → 0). */
        val gain: Float get() = if (muted) 0f else volume.coerceIn(0, 100) / 100f
    }

    const val START_VISIBLE = 0
    const val START_OPEN = 1
    const val POSTER_AUTO = 0
    const val POSTER_NONE = 1
    const val POSTER_FILE = 2
    const val MAX_REPEAT = 99

    /** A baseline JPEG and its pixel size. */
    class Poster(val jpeg: ByteArray, val width: Int, val height: Int)

    enum class Form { SCREEN, RICHMEDIA, ATTACHMENT }

    /**
     * A video annotation found on a page. [rect] is the PDF /Rect (llx, lly, urx, ury). [supported]: an embedded video
     * we can extract and try to play (false for Flash-only RichMedia or a clip that only points to a URL / external file).
     */
    data class Info(
        val form: Form,
        val llx: Float, val lly: Float, val urx: Float, val ury: Float,
        val mime: String,
        val name: String,
        val options: Options,
        val supported: Boolean,
        val size: Long,
        val hasAppearance: Boolean,
        val ours: Boolean,
    )

    val SD_MEDIA: COSName = COSName.getPDFName("SDMedia")
    val SD_VIDEO: COSName = COSName.getPDFName("SDVideo")
    private val N = COSName.getPDFName("N")
    private val S = COSName.S
    private val R = COSName.getPDFName("R")
    private val C = COSName.getPDFName("C")
    private val D = COSName.getPDFName("D")
    private val P = COSName.getPDFName("P")
    private val A = COSName.getPDFName("A")
    private val AA = COSName.getPDFName("AA")
    private val AN = COSName.getPDFName("AN")
    private val OP = COSName.getPDFName("OP")
    private val CT = COSName.getPDFName("CT")
    private val BE = COSName.getPDFName("BE")
    private val MH = COSName.getPDFName("MH")
    private val EF = COSName.getPDFName("EF")
    private val UF = COSName.getPDFName("UF")
    private val FS = COSName.getPDFName("FS")
    private val F = COSName.getPDFName("F")
    private val RC = COSName.getPDFName("RC")
    private val V = COSName.getPDFName("V")
    private val PV = COSName.getPDFName("PV")
    private val PO = COSName.getPDFName("PO")
    private val PI = COSName.getPDFName("PI")
    private val PC = COSName.getPDFName("PC")
    private val SP = COSName.getPDFName("SP")
    private val W = COSName.getPDFName("W")
    private val AP = COSName.getPDFName("AP")
    private val MK = COSName.getPDFName("MK")
    private val CA = COSName.getPDFName("CA")
    private val RM_CONTENT = COSName.getPDFName("RichMediaContent")
    private val RM_SETTINGS = COSName.getPDFName("RichMediaSettings")
    private val ASSETS = COSName.getPDFName("Assets")
    private val NAMES = COSName.getPDFName("Names")
    private val KIDS = COSName.getPDFName("Kids")
    private val ACTIVATION = COSName.getPDFName("Activation")
    private val CONDITION = COSName.getPDFName("Condition")

    private val VIDEO_EXT = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp", "3g2" to "video/3gpp2",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "flv" to "video/x-flv", "f4v" to "video/mp4", "ts" to "video/mp2t",
        "avi" to "video/x-msvideo", "wmv" to "video/x-ms-wmv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ogv" to "video/ogg",
    )

    /** Mime type of a video file name (empty when the extension is not a video one). */
    fun videoMime(name: String): String = VIDEO_EXT[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: ""

    // =====================================================================================================
    // write
    // =====================================================================================================

    /**
     * Append a Screen + Rendition video annotation to [page] in rect (llx, lly, urx, ury) and return it. The video is
     * streamed from [video] into the embedded file (never fully loaded in memory; already compressed, so no filter).
     */
    fun writeScreen(
        doc: PDDocument, page: PDPage,
        llx: Float, lly: Float, urx: Float, ury: Float,
        video: File, name: String, mime: String, options: Options,
        poster: Poster?, title: String = "", contents: String = "", color: Int = 0x000000,
    ): PDAnnotation {
        require(video.isFile) { "video file missing" }
        val l = minOf(llx, urx); val r = maxOf(llx, urx); val b = minOf(lly, ury); val t = maxOf(lly, ury)
        val w = (r - l).coerceAtLeast(1f); val h = (t - b).coerceAtLeast(1f)
        val ct = mime.ifBlank { videoMime(name).ifBlank { "video/mp4" } }
        val label = name.ifBlank { video.name }

        // embedded file stream + file specification
        val ef = doc.document.createCOSStream()
        ef.setName(COSName.TYPE, "EmbeddedFile")
        ef.setName(COSName.SUBTYPE, ct)
        ef.createRawOutputStream().use { o -> video.inputStream().use { it.copyTo(o, 1 shl 16) } }
        ef.setItem(COSName.getPDFName("Params"), COSDictionary().apply { setLong(COSName.getPDFName("Size"), video.length()) })
        val spec = COSDictionary().apply {
            setName(COSName.TYPE, "Filespec")
            setString(F, label); setString(UF, label)
            setString(COSName.getPDFName("Desc"), label)
            setItem(EF, COSDictionary().apply { setItem(F, ef); setItem(UF, ef) })
        }

        val annot = COSDictionary()
        annot.setName(COSName.TYPE, "Annot")
        annot.setName(COSName.SUBTYPE, "Screen")
        annot.setItem(COSName.RECT, PDRectangle(l, b, w, h).cosArray)
        annot.setInt(COSName.F, 4)                         // print
        annot.setItem(P, page.cosObject)
        annot.setString(COSName.T, title.ifBlank { label })
        annot.setString(COSName.CONTENTS, options.alt.ifBlank { contents.ifBlank { label } })
        annot.setItem(COSName.C, COSArray().apply { rgb(color).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
        annot.setName(SD_MEDIA, "Video")
        annot.setItem(SD_VIDEO, optionsDict(options))
        annot.setItem(MK, COSDictionary().apply { setString(CA, label) })

        val rendition = COSDictionary().apply {
            setName(COSName.TYPE, "Rendition"); setName(S, "MR"); setString(N, label)
            setItem(C, COSDictionary().apply {
                setName(COSName.TYPE, "MediaClip"); setName(S, "MCD"); setString(N, label)
                setString(CT, ct)
                setItem(D, spec)
                setItem(P, COSDictionary().apply { setName(COSName.TYPE, "MediaPermissions"); setString(COSName.getPDFName("TF"), "TEMPACCESS") })
                setItem(COSName.getPDFName("Alt"), COSArray().apply { add(COSString("")); add(COSString(options.alt.ifBlank { label })) })
            })
            setItem(P, playParams(options))
            setItem(SP, COSDictionary().apply {
                setName(COSName.TYPE, "MediaScreenParams")
                // play in the annotation's rect (3), or full screen (1)
                setItem(BE, COSDictionary().apply { setInt(W, if (options.fullscreen) 1 else 3) })
            })
        }
        annot.setItem(A, renditionAction(rendition, annot))
        // media actions: start on page open / visible, stop when the page is hidden / closed
        val aa = COSDictionary()
        if (options.autoplay) aa.setItem(if (options.startOn == START_OPEN) PO else PV, renditionAction(rendition, annot))
        if (options.stopWhenHidden) { aa.setItem(PI, stopAction(annot)); aa.setItem(PC, stopAction(annot)) }
        if (aa.size() > 0) annot.setItem(AA, aa)
        annot.setItem(AP, COSDictionary().apply { setItem(N, appearance(doc, w, h, poster)) })

        // PDAnnotationUnknown directly: createAnnotation only logs "unsupported subtype" for /Screen
        val an = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown(annot)
        page.annotations.add(an)
        return an
    }

    private fun renditionAction(rendition: COSDictionary, annot: COSDictionary) = COSDictionary().apply {
        setName(COSName.TYPE, "Action"); setName(S, "Rendition")
        setInt(OP, 0)                                      // play R, associate it with AN
        setItem(R, rendition)
        setItem(AN, annot)
    }

    /** Rendition action that stops the media playing in [annot] (`/OP 1`). */
    private fun stopAction(annot: COSDictionary) = COSDictionary().apply {
        setName(COSName.TYPE, "Action"); setName(S, "Rendition")
        setInt(OP, 1)
        setItem(AN, annot)
    }

    private fun optionsDict(o: Options) = COSDictionary().apply {
        setBoolean(COSName.getPDFName("Autoplay"), o.autoplay)
        setBoolean(COSName.getPDFName("Loop"), o.loop)
        setBoolean(COSName.getPDFName("Muted"), o.muted)
        setBoolean(COSName.getPDFName("Controls"), o.controls)
        setInt(COSName.getPDFName("Volume"), o.volume.coerceIn(0, 100))
        setInt(COSName.getPDFName("Repeat"), o.repeat.coerceIn(1, MAX_REPEAT))
        setBoolean(COSName.getPDFName("KeepOpen"), o.keepOpen)
        setBoolean(COSName.getPDFName("Fullscreen"), o.fullscreen)
        setInt(COSName.getPDFName("StartOn"), o.startOn)
        setBoolean(COSName.getPDFName("StopHidden"), o.stopWhenHidden)
        setInt(COSName.getPDFName("Poster"), o.poster)
        if (o.alt.isNotBlank()) setString(COSName.getPDFName("Alt"), o.alt)
    }

    private fun playParams(o: Options) = COSDictionary().apply {
        setName(COSName.TYPE, "MediaPlayParams")
        val be = COSDictionary().apply {
            setBoolean(C, o.controls)
            setInt(V, if (o.muted) 0 else o.volume.coerceIn(0, 100))
            setInt(RC, if (o.loop) 0 else o.repeat.coerceIn(1, MAX_REPEAT))   // repeat count: 0 = forever
            setBoolean(A, true)                            // start as soon as the rendition is activated
        }
        setItem(BE, be)
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun f(v: Float) = String.format(Locale.US, "%.3f", v)

    /** Form XObject: black box, the poster fitted inside (aspect kept), a translucent disc with a white play triangle. */
    private fun appearance(doc: PDDocument, w: Float, h: Float, poster: Poster?): COSStream {
        val form = doc.document.createCOSStream()
        form.setName(COSName.TYPE, "XObject"); form.setName(COSName.SUBTYPE, "Form")
        form.setItem(COSName.BBOX, PDRectangle(0f, 0f, w, h).cosArray)
        val res = COSDictionary()
        val gs = COSDictionary().apply { setName(COSName.TYPE, "ExtGState"); setFloat(COSName.getPDFName("ca"), 0.55f); setFloat(COSName.getPDFName("CA"), 0.55f) }
        res.setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS0"), gs) })
        val sb = StringBuilder()
        sb.append("q 0 0 0 rg 0 0 ").append(f(w)).append(' ').append(f(h)).append(" re f Q\n")
        if (poster != null && poster.width > 0 && poster.height > 0) {
            val img = doc.document.createCOSStream()
            img.setName(COSName.TYPE, "XObject"); img.setName(COSName.SUBTYPE, "Image")
            img.setInt(COSName.WIDTH, poster.width); img.setInt(COSName.HEIGHT, poster.height)
            img.setName(COSName.COLORSPACE, "DeviceRGB"); img.setInt(COSName.BITS_PER_COMPONENT, 8)
            img.setItem(COSName.FILTER, COSName.DCT_DECODE)
            img.createRawOutputStream().use { it.write(poster.jpeg) }
            res.setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Im0"), img) })
            val k = minOf(w / poster.width, h / poster.height)
            val iw = poster.width * k; val ih = poster.height * k
            sb.append("q ").append(f(iw)).append(" 0 0 ").append(f(ih)).append(' ').append(f((w - iw) / 2)).append(' ').append(f((h - ih) / 2)).append(" cm /Im0 Do Q\n")
        }
        // play button
        val cx = w / 2; val cy = h / 2; val rr = (minOf(w, h) * 0.17f).coerceIn(4f, 60f); val kk = rr * 0.5523f
        sb.append("q /GS0 gs 0 0 0 rg\n")
        sb.append(f(cx + rr)).append(' ').append(f(cy)).append(" m\n")
        sb.append(f(cx + rr)).append(' ').append(f(cy + kk)).append(' ').append(f(cx + kk)).append(' ').append(f(cy + rr)).append(' ').append(f(cx)).append(' ').append(f(cy + rr)).append(" c\n")
        sb.append(f(cx - kk)).append(' ').append(f(cy + rr)).append(' ').append(f(cx - rr)).append(' ').append(f(cy + kk)).append(' ').append(f(cx - rr)).append(' ').append(f(cy)).append(" c\n")
        sb.append(f(cx - rr)).append(' ').append(f(cy - kk)).append(' ').append(f(cx - kk)).append(' ').append(f(cy - rr)).append(' ').append(f(cx)).append(' ').append(f(cy - rr)).append(" c\n")
        sb.append(f(cx + kk)).append(' ').append(f(cy - rr)).append(' ').append(f(cx + rr)).append(' ').append(f(cy - kk)).append(' ').append(f(cx + rr)).append(' ').append(f(cy)).append(" c f Q\n")
        val tr = rr * 0.55f
        sb.append("q 1 1 1 rg ").append(f(cx - tr * 0.6f)).append(' ').append(f(cy - tr)).append(" m ")
            .append(f(cx + tr)).append(' ').append(f(cy)).append(" l ")
            .append(f(cx - tr * 0.6f)).append(' ').append(f(cy + tr)).append(" l h f Q\n")
        form.setItem(COSName.RESOURCES, res)
        form.createOutputStream().use { it.write(sb.toString().toByteArray(Charsets.US_ASCII)) }
        return form
    }

    // =====================================================================================================
    // read
    // =====================================================================================================

    private fun dict(b: COSBase?): COSDictionary? = b as? COSDictionary

    private fun rectOf(cos: COSDictionary): FloatArray? {
        val arr = cos.getDictionaryObject(COSName.RECT) as? COSArray ?: return null
        if (arr.size() < 4) return null
        val v = FloatArray(4) { (arr.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    /** The media rendition (`/S /MR`) of a rendition, descending into selector renditions. */
    private fun mediaRendition(r: COSDictionary?, depth: Int = 0): COSDictionary? {
        if (r == null || depth > 4) return null
        return when (r.getNameAsString(S)) {
            "MR" -> r
            "SR" -> (r.getDictionaryObject(R) as? COSArray)?.let { arr -> (0 until arr.size()).firstNotNullOfOrNull { mediaRendition(dict(arr.getObject(it)), depth + 1) } }
            else -> null
        }
    }

    /** The rendition action of a Screen annotation: /A, then the additional actions (page open / visible first). */
    private fun renditionActions(cos: COSDictionary): List<Pair<String, COSDictionary>> {
        val out = ArrayList<Pair<String, COSDictionary>>()
        fun add(trigger: String, a: COSDictionary?, depth: Int = 0) {
            if (a == null || depth > 4) return
            if (a.getNameAsString(S) == "Rendition" && a.containsKey(R)) out += trigger to a
            // /Next chains (an action or an array of actions)
            when (val n = a.getDictionaryObject(COSName.getPDFName("Next"))) {
                is COSDictionary -> add(trigger, n, depth + 1)
                is COSArray -> for (i in 0 until n.size()) add(trigger, dict(n.getObject(i)), depth + 1)
                else -> {}
            }
        }
        add("A", dict(cos.getDictionaryObject(A)))
        dict(cos.getDictionaryObject(AA))?.let { aa -> aa.keySet().forEach { k -> add(k.name, dict(aa.getDictionaryObject(k))) } }
        return out
    }

    /** Media clip data (`/S /MCD`) of a media rendition, descending into clip sections. */
    private fun clipData(mr: COSDictionary): COSDictionary? {
        var c = dict(mr.getDictionaryObject(C)); var depth = 0
        while (c != null && c.getNameAsString(S) == "MCS" && depth++ < 4) c = dict(c.getDictionaryObject(D))
        return c?.takeIf { it.getNameAsString(S) == null || it.getNameAsString(S) == "MCD" }
    }

    private fun embedded(spec: COSDictionary?): COSStream? {
        val ef = dict(spec?.getDictionaryObject(EF)) ?: return null
        return (ef.getDictionaryObject(UF) as? COSStream) ?: (ef.getDictionaryObject(F) as? COSStream)
            ?: ef.keySet().firstNotNullOfOrNull { ef.getDictionaryObject(it) as? COSStream }
    }

    private fun specName(spec: COSBase?): String = when (spec) {
        is COSDictionary -> spec.getString(UF) ?: spec.getString(F) ?: spec.getString(COSName.getPDFName("DOS")) ?: ""
        is COSString -> spec.string
        else -> ""
    }

    private fun streamSize(s: COSStream): Long {
        val p = dict(s.getDictionaryObject(COSName.getPDFName("Params")))
        val declared = p?.getLong(COSName.getPDFName("Size"), -1L) ?: -1L
        return if (declared > 0) declared else s.length
    }

    private fun bool(d: COSDictionary?, key: String, def: Boolean): Boolean {
        val v = d?.getDictionaryObject(COSName.getPDFName(key)) ?: return def
        return (v as? COSBoolean)?.value ?: def
    }

    private fun int(d: COSDictionary?, key: String, def: Int): Int = (d?.getDictionaryObject(COSName.getPDFName(key)) as? COSNumber)?.intValue() ?: def

    private fun ourOptions(cos: COSDictionary): Options? = dict(cos.getDictionaryObject(SD_VIDEO))?.let {
        Options(bool(it, "Autoplay", false), bool(it, "Loop", false), bool(it, "Muted", false), bool(it, "Controls", true),
            volume = int(it, "Volume", 100).coerceIn(0, 100), repeat = int(it, "Repeat", 1).coerceIn(1, MAX_REPEAT),
            keepOpen = bool(it, "KeepOpen", true), fullscreen = bool(it, "Fullscreen", false),
            startOn = int(it, "StartOn", START_VISIBLE).coerceIn(START_VISIBLE, START_OPEN), stopWhenHidden = bool(it, "StopHidden", true),
            poster = int(it, "Poster", POSTER_AUTO).coerceIn(POSTER_AUTO, POSTER_FILE), alt = it.getString(COSName.getPDFName("Alt")) ?: "")
    }

    /** True when /AA [key] of [cos] holds a rendition action that stops the media (`/OP 1`). */
    private fun stopsOn(cos: COSDictionary, key: COSName): Boolean {
        val a = dict(dict(cos.getDictionaryObject(AA))?.getDictionaryObject(key)) ?: return false
        return a.getNameAsString(S) == "Rendition" && (a.getDictionaryObject(OP) as? COSNumber)?.intValue() == 1
    }

    /** Options from a rendition (MediaPlayParams /MH or /BE, MediaScreenParams), the triggers that start it and the stop actions. */
    private fun renditionOptions(cos: COSDictionary, mr: COSDictionary, clip: COSDictionary, triggers: Collection<String>): Options {
        val pp = dict(mr.getDictionaryObject(P))
        val p = dict(pp?.getDictionaryObject(MH)) ?: dict(pp?.getDictionaryObject(BE))
        val rc = (p?.getDictionaryObject(RC) as? COSNumber)?.floatValue()
        val vol = (p?.getDictionaryObject(V) as? COSNumber)?.intValue()
        val controls = (p?.getDictionaryObject(C) as? COSBoolean)?.value ?: true
        val sp = dict(mr.getDictionaryObject(SP))
        val w = (dict(sp?.getDictionaryObject(MH)) ?: dict(sp?.getDictionaryObject(BE)))?.let { int(it, "W", 3) } ?: 3
        // /Alt is a multi-language text array: (lang, text) pairs
        val alt = (clip.getDictionaryObject(COSName.getPDFName("Alt")) as? COSArray)?.let { arr ->
            (1 until arr.size() step 2).firstNotNullOfOrNull { (arr.getObject(it) as? COSString)?.string?.takeIf { s -> s.isNotBlank() } }
        } ?: ""
        val loop = rc != null && rc <= 0f
        return Options(autoplay = triggers.any { it == "PV" || it == "PO" }, loop = loop, muted = vol == 0, controls = controls,
            volume = (vol ?: 100).coerceIn(0, 100).let { if (it == 0) 100 else it },
            repeat = if (loop || rc == null) 1 else rc.toInt().coerceIn(1, MAX_REPEAT),
            fullscreen = w == 1, startOn = if ("PV" !in triggers && "PO" in triggers) START_OPEN else START_VISIBLE,
            stopWhenHidden = stopsOn(cos, PI) || stopsOn(cos, PC), alt = alt)
    }

    /** Video information of an annotation, or null when it is not a video. Never throws. */
    fun detect(an: PDAnnotation): Info? = runCatching { detectCos(an.cosObject) }.getOrNull()

    fun detectCos(cos: COSDictionary): Info? {
        val rect = rectOf(cos) ?: return null
        val ours = cos.getNameAsString(SD_MEDIA) == "Video"
        val hasAp = dict(cos.getDictionaryObject(AP))?.getDictionaryObject(N) != null
        fun info(form: Form, mime: String, name: String, opts: Options, supported: Boolean, size: Long) =
            Info(form, rect[0], rect[1], rect[2], rect[3], mime, name, opts, supported, size, hasAp, ours)
        return when (cos.getNameAsString(COSName.SUBTYPE)) {
            "Screen" -> {
                val acts = renditionActions(cos)
                val mrs = acts.mapNotNull { (t, a) -> mediaRendition(dict(a.getDictionaryObject(R)))?.let { t to it } }
                val (_, mr) = mrs.firstOrNull() ?: return null
                val clip = clipData(mr) ?: return null
                val d = clip.getDictionaryObject(D)
                val name = specName(d).ifBlank { clip.getString(N) ?: mr.getString(N) ?: "video" }
                val mime = clip.getString(CT)?.ifBlank { null } ?: videoMime(name)
                if (!mime.startsWith("video/") && !ours && !(mime.isBlank() && videoMime(name).isNotEmpty())) return null
                val ef = embedded(d as? COSDictionary)
                val triggers = mrs.filter { it.second === mr }.map { it.first }
                info(Form.SCREEN, mime.ifBlank { "video/mp4" }, name, ourOptions(cos) ?: renditionOptions(cos, mr, clip, triggers), ef != null, ef?.let(::streamSize) ?: 0L)
            }
            "RichMedia" -> {
                val content = dict(cos.getDictionaryObject(RM_CONTENT)) ?: return null
                val assets = richAssets(dict(content.getDictionaryObject(ASSETS)))
                val video = assets.firstOrNull { videoMime(it.first).isNotEmpty() && embedded(it.second) != null }
                val settings = dict(cos.getDictionaryObject(RM_SETTINGS))
                val cond = dict(settings?.getDictionaryObject(ACTIVATION))?.getNameAsString(CONDITION)
                val opts = ourOptions(cos) ?: Options(autoplay = cond == "PO" || cond == "PV")
                if (video != null) {
                    val ef = embedded(video.second)!!
                    info(Form.RICHMEDIA, videoMime(video.first), video.first, opts, true, streamSize(ef))
                } else {
                    val swf = assets.firstOrNull { it.first.lowercase(Locale.ROOT).endsWith(".swf") } ?: return null
                    info(Form.RICHMEDIA, "application/x-shockwave-flash", swf.first, opts, false, 0L)
                }
            }
            "FileAttachment" -> {
                val spec = cos.getDictionaryObject(FS) as? COSDictionary ?: return null
                val ef = embedded(spec) ?: return null
                val name = specName(spec).ifBlank { "video" }
                val mime = ef.getNameAsString(COSName.SUBTYPE)?.takeIf { it.isNotBlank() } ?: videoMime(name)
                if (!ours && !mime.startsWith("video/")) return null
                info(Form.ATTACHMENT, mime.ifBlank { "video/mp4" }, name, ourOptions(cos) ?: Options(), true, streamSize(ef))
            }
            else -> null
        }
    }

    /** (name, file spec) pairs of a RichMedia /Assets name tree. */
    private fun richAssets(node: COSDictionary?, depth: Int = 0): List<Pair<String, COSDictionary>> {
        if (node == null || depth > 6) return emptyList()
        val out = ArrayList<Pair<String, COSDictionary>>()
        (node.getDictionaryObject(NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) {
                val k = (arr.getObject(i) as? COSString)?.string
                val v = dict(arr.getObject(i + 1))
                if (k != null && v != null) out += (k.ifBlank { specName(v) }) to v
                i += 2
            }
        }
        (node.getDictionaryObject(KIDS) as? COSArray)?.let { kids -> for (i in 0 until kids.size()) out += richAssets(dict(kids.getObject(i)), depth + 1) }
        return out
    }

    /** The embedded video stream of a video annotation (any supported form), or null. */
    fun videoStream(cos: COSDictionary): COSStream? = when (cos.getNameAsString(COSName.SUBTYPE)) {
        "Screen" -> renditionActions(cos).firstNotNullOfOrNull { (_, a) -> mediaRendition(dict(a.getDictionaryObject(R)))?.let(::clipData) }
            ?.let { embedded(it.getDictionaryObject(D) as? COSDictionary) }
        "RichMedia" -> richAssets(dict(dict(cos.getDictionaryObject(RM_CONTENT))?.getDictionaryObject(ASSETS)))
            .firstOrNull { videoMime(it.first).isNotEmpty() && embedded(it.second) != null }?.let { embedded(it.second) }
        "FileAttachment" -> embedded(cos.getDictionaryObject(FS) as? COSDictionary)
        else -> null
    }

    /** Stream the decoded video bytes of [cos] to [out]; returns the byte count (-1 when there is no embedded video). */
    fun copyVideo(cos: COSDictionary, out: OutputStream): Long {
        val s = videoStream(cos) ?: return -1L
        return s.createInputStream().use { it.copyTo(out, 1 shl 16) }
    }

    /** The poster JPEG of one of our appearances (the first DCT image of /AP /N), or null. */
    fun posterJpeg(cos: COSDictionary): ByteArray? {
        val n = dict(cos.getDictionaryObject(AP))?.getDictionaryObject(N) as? COSStream ?: return null
        val xo = dict(dict(n.getDictionaryObject(COSName.RESOURCES))?.getDictionaryObject(COSName.XOBJECT)) ?: return null
        for (k in xo.keySet()) {
            val img = xo.getDictionaryObject(k) as? COSStream ?: continue
            if (img.getNameAsString(COSName.SUBTYPE) != "Image") continue
            val filter = img.getDictionaryObject(COSName.FILTER)
            val dct = filter == COSName.DCT_DECODE || (filter as? COSArray)?.let { it.size() == 1 && it.getObject(0) == COSName.DCT_DECODE } == true
            if (dct) return img.createRawInputStream().use { it.readBytes() }
        }
        return null
    }

    /** Pixel size of a JPEG (SOFn marker), or null when it is not a readable JPEG. */
    fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 9 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }

    /** The /Annots dictionaries of [page] in order (no PDAnnotation wrappers; null entries for broken ones). */
    fun annotDicts(page: PDPage): List<COSDictionary?> {
        val arr = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return emptyList()
        return (0 until arr.size()).map { arr.getObject(it) as? COSDictionary }
    }

    /** Index of the annotation on [page] that matches (subtype + rect) [want], preferring [index]. */
    fun findIndex(page: PDPage, index: Int, subtype: String, rect: FloatArray?, tol: Float = 0.6f): Int {
        val list = annotDicts(page)
        fun matches(i: Int): Boolean {
            val c = list.getOrNull(i) ?: return false
            if (c.getNameAsString(COSName.SUBTYPE) != subtype) return false
            if (rect == null) return true
            val r = rectOf(c) ?: return false
            return (0 until 4).all { kotlin.math.abs(r[it] - rect[it]) < tol }
        }
        if (matches(index)) return index
        return list.indices.firstOrNull { matches(it) } ?: -1
    }

}
