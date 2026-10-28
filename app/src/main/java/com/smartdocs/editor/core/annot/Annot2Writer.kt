package com.smartdocs.editor.core.annot

import android.graphics.RectF
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderEffectDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.io.File
import java.util.Calendar

/**
 * PDF writing / reading of the annotation kinds added by MODULE_annot2: Polygon, PolyLine, cloudy Polygon,
 * Caret, Replace (Caret + StrikeOut grouped), page file attachments, sound and video (file attachments with
 * our own icon). Every annotation gets an appearance stream so every viewer shows it the same way.
 */
object Annot2Writer {

    private val IRT = COSName.getPDFName("IRT")
    private val RT = COSName.getPDFName("RT")
    private val IT = COSName.getPDFName("IT")
    private val MEDIA = COSName.getPDFName("SDMedia")      // private key: Audio / Video, so we know how to play it

    /** Subtypes that get a (closed) pop-up annotation when written (ISO 32000 12.5.6.14). */
    val POPUP_KINDS = setOf("Text", "Highlight", "Underline", "StrikeOut", "Squiggly", "Ink", "Line", "Square", "Circle",
        "Polygon", "PolyLine", "Caret", "Stamp", "FileAttachment")

    // ---------------- small helpers ----------------

    private fun rgb(c: Int) = PDColor(floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f), PDDeviceRGB.INSTANCE)

    private fun form(doc: PDDocument, rect: PDRectangle): PDAppearanceStream = PDAppearanceStream(doc).apply {
        bBox = PDRectangle(0f, 0f, rect.width, rect.height)
        resources = PDResources()
        setMatrix(com.tom_roush.harmony.awt.geom.AffineTransform.getTranslateInstance(rect.lowerLeftX.toDouble(), rect.lowerLeftY.toDouble()))
    }

    private fun ap(s: PDAppearanceStream) = PDAppearanceDictionary().apply { setNormalAppearance(s) }

    private fun alpha(cs: PDPageContentStream, o: Float) {
        if (o >= 0.999f) return
        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = o; nonStrokingAlphaConstant = o })
    }

    private fun stroke(cs: PDPageContentStream, c: Int) = cs.setStrokingColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun fill(cs: PDPageContentStream, c: Int) = cs.setNonStrokingColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    private fun common(m: PDAnnotationMarkup, a: Annot) {
        val now = Calendar.getInstance()
        m.titlePopup = a.author; m.contents = a.text
        m.setCreationDate(now); m.setModifiedDate(now)
        m.color = rgb(a.color); m.constantOpacity = a.opacity
        m.isPrinted = true
    }

    private fun norm(r: RectF) = RectF(minOf(r.left, r.right), maxOf(r.top, r.bottom), maxOf(r.left, r.right), minOf(r.top, r.bottom))

    /** PDF rect from an editor rect (top = larger y). */
    private fun pdRect(r: RectF): PDRectangle { val n = norm(r); return PDRectangle(n.left, n.bottom, n.right - n.left, n.top - n.bottom) }

    // ---------------- write ----------------

    fun write(doc: PDDocument, page: PDPage, a: Annot) {
        when (a.kind) {
            AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD -> writePoly(doc, page, a)
            AnnotKind.CARET -> page.annotations.add(caret(doc, a, a.rects.firstOrNull() ?: return, null))
            AnnotKind.REPLACE -> writeReplace(doc, page, a)
            AnnotKind.FILE_ATTACH, AnnotKind.SOUND -> writeAttachment(doc, page, a)
            AnnotKind.VIDEO -> writeVideo(doc, page, a)
            else -> {}
        }
    }

    private fun writePoly(doc: PDDocument, page: PDPage, a: Annot) {
        val closed = a.kind != AnnotKind.POLYLINE
        val pts = a.points
        if (pts.size < (if (closed) 3 else 2)) return
        val cloud = if (a.kind == AnnotKind.CLOUD) a.cloud.coerceIn(1, 3) else 0
        val pad = if (cloud > 0) CloudPath.margin(cloud, a.strokeWidth) else a.strokeWidth + 2f
        val b = CloudPath.bounds(pts, pad)
        val rect = PDRectangle(b[0], b[1], b[2] - b[0], b[3] - b[1])
        val m = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, if (closed) "Polygon" else "PolyLine") }
        common(m, a)
        m.rectangle = rect
        m.vertices = FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].first else pts[i / 2].second }
        val noBorder = a.noBorder && closed && a.fillColor != null
        AnnotStyle.applyBorder(m.cosObject, a.strokeWidth, a.dash, noBorder)
        if (closed && a.fillColor != null) m.interiorColor = rgb(a.fillColor)
        if (cloud > 0) {
            // /I is 0..2 (ISO 32000): cloud levels 1..3 of the picker
            m.borderEffect = PDBorderEffectDictionary().apply { style = PDBorderEffectDictionary.STYLE_CLOUDY; intensity = (cloud - 1).toFloat() }
            m.intent = "PolygonCloud"
            AnnotStyle.setCloudInward(m.cosObject, a.cloudInward)
        }
        val f = form(doc, rect)
        PDPageContentStream(doc, f).use { cs ->
            alpha(cs, a.opacity); stroke(cs, a.color); a.fillColor?.let { fill(cs, it) }
            cs.setLineWidth(a.strokeWidth); cs.setLineJoinStyle(1); cs.setLineCapStyle(1)
            com.smartdocs.editor.core.view5.DashStyle.entries.getOrElse(a.dash) { com.smartdocs.editor.core.view5.DashStyle.SOLID }.scaled(a.strokeWidth)
                ?.let { if (!noBorder) cs.setLineDashPattern(it, 0f) }
            val ox = rect.lowerLeftX; val oy = rect.lowerLeftY
            if (cloud > 0) {
                val segs = CloudPath.build(pts, cloud, a.strokeWidth, a.cloudInward)
                segs.forEachIndexed { i, c ->
                    if (i == 0) cs.moveTo(c.x0 - ox, c.y0 - oy)
                    cs.curveTo(c.x1 - ox, c.y1 - oy, c.x2 - ox, c.y2 - oy, c.x3 - ox, c.y3 - oy)
                }
                cs.closePath()
            } else {
                pts.forEachIndexed { i, (x, y) -> if (i == 0) cs.moveTo(x - ox, y - oy) else cs.lineTo(x - ox, y - oy) }
                if (closed) cs.closePath()
            }
            when {
                noBorder -> cs.fill()
                closed && a.fillColor != null -> cs.fillAndStroke()
                else -> cs.stroke()
            }
        }
        m.appearance = ap(f)
        page.annotations.add(m)
    }

    /** Caret annotation in [box] (editor rect). [intentName] is "Replace" for the caret of a replace group. */
    private fun caret(doc: PDDocument, a: Annot, box: RectF, intentName: String?): PDAnnotationMarkup {
        val rect = pdRect(box)
        val m = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Caret") }
        common(m, a)
        m.rectangle = rect
        m.cosObject.setName(COSName.getPDFName("Sy"), "None")
        if (intentName != null) m.cosObject.setName(IT, intentName)
        val f = form(doc, rect)
        PDPageContentStream(doc, f).use { cs ->
            alpha(cs, a.opacity); fill(cs, a.color)
            val w = rect.width; val h = rect.height
            // the classic proofreading caret: two curved strokes meeting at the top
            cs.moveTo(0f, 0f)
            cs.curveTo(w * 0.25f, h * 0.15f, w * 0.42f, h * 0.55f, w * 0.5f, h)
            cs.curveTo(w * 0.58f, h * 0.55f, w * 0.75f, h * 0.15f, w, 0f)
            cs.curveTo(w * 0.7f, h * 0.12f, w * 0.55f, h * 0.35f, w * 0.5f, h * 0.55f)
            cs.curveTo(w * 0.45f, h * 0.35f, w * 0.3f, h * 0.12f, 0f, 0f)
            cs.fill()
        }
        m.appearance = ap(f)
        return m
    }

    /** Default caret box for a text line (editor rect, top = larger y): centred on x at the line's baseline. */
    fun caretBox(x: Float, lineBottom: Float, lineHeight: Float): RectF {
        val h = (lineHeight * 0.55f).coerceIn(5f, 30f); val w = h * 0.9f
        return RectF(x - w / 2, lineBottom + h * 0.5f, x + w / 2, lineBottom - h * 0.5f)
    }

    /** Replace text = Caret (with the new text, /IT /Replace) + StrikeOut over the old text grouped to it. */
    private fun writeReplace(doc: PDDocument, page: PDPage, a: Annot) {
        val rects = a.rects.map(::norm); if (rects.isEmpty()) return
        val last = rects.last()
        val c = caret(doc, a, caretBox(last.right, last.bottom, last.top - last.bottom), "Replace")
        val list = page.annotations
        list.add(c)
        val so = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT)
        common(so, a)
        val quads = FloatArray(rects.size * 8)
        rects.forEachIndexed { i, r ->
            val o = i * 8
            quads[o] = r.left; quads[o + 1] = r.top; quads[o + 2] = r.right; quads[o + 3] = r.top
            quads[o + 4] = r.left; quads[o + 5] = r.bottom; quads[o + 6] = r.right; quads[o + 7] = r.bottom
        }
        so.quadPoints = quads
        val l = rects.minOf { it.left }; val r = rects.maxOf { it.right }
        val t = rects.maxOf { it.top }; val bt = rects.minOf { it.bottom }
        val rect = PDRectangle(l, bt, r - l, t - bt)
        so.rectangle = rect
        so.cosObject.setItem(IRT, c.cosObject)
        so.cosObject.setName(RT, "Group")
        so.cosObject.setName(IT, "StrikeOutTextEdit")
        val f = form(doc, rect)
        PDPageContentStream(doc, f).use { cs ->
            alpha(cs, a.opacity); stroke(cs, a.color); cs.setLineWidth(1.2f)
            rects.forEach { r ->
                val y = (r.bottom + r.top) / 2 - rect.lowerLeftY
                cs.moveTo(r.left - rect.lowerLeftX, y); cs.lineTo(r.right - rect.lowerLeftX, y)
            }
            cs.stroke()
        }
        so.appearance = ap(f)
        list.add(so)
        page.annotations = list
    }

    private fun writeAttachment(doc: PDDocument, page: PDPage, a: Annot) {
        val file = a.filePath?.let(::File)?.takeIf { it.isFile } ?: return
        val box = a.rects.firstOrNull() ?: return
        val rect = pdRect(box)
        val spec = PDComplexFileSpecification()
        spec.file = file.name; spec.fileUnicode = file.name
        spec.fileDescription = a.text
        val ef = file.inputStream().use { PDEmbeddedFile(doc, it) }
        ef.subtype = a.mime.ifBlank { "application/octet-stream" }
        ef.size = file.length().toInt()
        ef.creationDate = Calendar.getInstance(); ef.modDate = Calendar.getInstance()
        spec.embeddedFile = ef; spec.embeddedFileUnicode = ef
        val an = PDAnnotationFileAttachment()
        common(an, a)
        an.contents = a.text.ifBlank { file.name }
        an.file = spec
        an.rectangle = rect
        an.attachmentName = a.icon.takeIf { it in AnnotStyle.ATTACH_ICONS } ?: PDAnnotationFileAttachment.ATTACHMENT_NAME_PAPERCLIP
        when (a.kind) {
            AnnotKind.SOUND -> { an.cosObject.setName(MEDIA, "Audio"); an.subject = "Sound" }
            AnnotKind.VIDEO -> { an.cosObject.setName(MEDIA, "Video"); an.subject = "Video" }
            else -> an.subject = "Attachment"
        }
        val f = form(doc, rect)
        PDPageContentStream(doc, f).use { cs -> alpha(cs, a.opacity); icon(cs, a.kind, a.color, rect.width, rect.height, an.attachmentName ?: "Paperclip") }
        an.appearance = ap(f)
        page.annotations.add(an)
    }

    /**
     * Video = Screen annotation + Rendition action with the file embedded and a poster appearance ([VideoPdf]).
     * Throws when the file is gone, so the annotation stays pending instead of being dropped.
     */
    private fun writeVideo(doc: PDDocument, page: PDPage, a: Annot) {
        val file = a.filePath?.let(::File)?.takeIf { it.isFile } ?: throw java.io.FileNotFoundException("video file missing")
        val box = a.rects.firstOrNull() ?: throw IllegalArgumentException("video without a box")
        val r = pdRect(box)
        val poster = a.posterPath?.let(::File)?.takeIf { it.isFile }?.let { pf -> posterOf(pf.readBytes()) }
            ?: runCatching { VideoPoster.makeJpeg(file)?.let(::posterOf) }.getOrNull()
        val name = a.text.ifBlank { file.name.substringAfter('_') }
        val an = VideoPdf.writeScreen(doc, page, r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY, file, name,
            a.mime.ifBlank { VideoPdf.videoMime(file.name) }, a.video ?: VideoPdf.Options(), poster,
            title = a.author.ifBlank { name }, contents = name, color = a.color)
        an.cosObject.setDate(COSName.M, Calendar.getInstance())
    }

    private fun posterOf(jpeg: ByteArray): VideoPdf.Poster? = VideoPdf.jpegSize(jpeg)?.let { (w, h) -> VideoPdf.Poster(jpeg, w, h) }

    /** Vector icons (paperclip / speaker / film) drawn into a w×h box. */
    private fun icon(cs: PDPageContentStream, kind: AnnotKind, c: Int, w: Float, h: Float, name: String = "Paperclip") {
        fill(cs, 0xFFFFFF); stroke(cs, c); cs.setLineWidth(1.4f); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
        cs.addRect(0.7f, 0.7f, w - 1.4f, h - 1.4f); cs.fillAndStroke()
        when (kind) {
            AnnotKind.SOUND -> {
                fill(cs, c)
                cs.moveTo(w * 0.18f, h * 0.38f); cs.lineTo(w * 0.32f, h * 0.38f); cs.lineTo(w * 0.52f, h * 0.2f)
                cs.lineTo(w * 0.52f, h * 0.8f); cs.lineTo(w * 0.32f, h * 0.62f); cs.lineTo(w * 0.18f, h * 0.62f); cs.closePath(); cs.fill()
                cs.moveTo(w * 0.62f, h * 0.36f); cs.curveTo(w * 0.7f, h * 0.44f, w * 0.7f, h * 0.56f, w * 0.62f, h * 0.64f)
                cs.moveTo(w * 0.7f, h * 0.26f); cs.curveTo(w * 0.85f, h * 0.4f, w * 0.85f, h * 0.6f, w * 0.7f, h * 0.74f)
                cs.stroke()
            }
            AnnotKind.VIDEO -> {
                cs.addRect(w * 0.16f, h * 0.24f, w * 0.68f, h * 0.52f); cs.stroke()
                fill(cs, c)
                cs.moveTo(w * 0.42f, h * 0.36f); cs.lineTo(w * 0.64f, h * 0.5f); cs.lineTo(w * 0.42f, h * 0.64f); cs.closePath(); cs.fill()
            }
            else -> if (name == "PushPin") {
                cs.moveTo(w * 0.5f, h * 0.15f); cs.lineTo(w * 0.5f, h * 0.45f)
                cs.moveTo(w * 0.3f, h * 0.45f); cs.lineTo(w * 0.7f, h * 0.45f)
                cs.addRect(w * 0.38f, h * 0.45f, w * 0.24f, h * 0.38f); cs.stroke()
            } else if (name == "Graph") {
                fill(cs, c)
                cs.addRect(w * 0.2f, h * 0.2f, w * 0.14f, h * 0.3f); cs.addRect(w * 0.43f, h * 0.2f, w * 0.14f, h * 0.55f); cs.addRect(w * 0.66f, h * 0.2f, w * 0.14f, h * 0.42f)
                cs.fill()
            } else if (name == "Tag") {
                cs.moveTo(w * 0.2f, h * 0.5f); cs.lineTo(w * 0.42f, h * 0.78f); cs.lineTo(w * 0.8f, h * 0.78f); cs.lineTo(w * 0.8f, h * 0.22f)
                cs.lineTo(w * 0.42f, h * 0.22f); cs.closePath(); cs.stroke()
            } else {
                // paperclip: two nested rounded loops
                val x1 = w * 0.36f; val x2 = w * 0.64f; val k = (x2 - x1) / 2
                cs.moveTo(x2, h * 0.3f); cs.lineTo(x2, h * 0.72f)
                cs.curveTo(x2, h * 0.72f + k * 1.3f, x1, h * 0.72f + k * 1.3f, x1, h * 0.72f)
                cs.lineTo(x1, h * 0.22f)
                cs.curveTo(x1, h * 0.22f - k * 1.6f, x2 + k * 0.4f, h * 0.22f - k * 1.6f, x2 + k * 0.4f, h * 0.22f)
                cs.lineTo(x2 + k * 0.4f, h * 0.62f)
                cs.stroke()
            }
        }
    }

    // ---------------- after write: /NM and groups ----------------

    /**
     * Called by AnnotationWriter.commit after each annotation is written: annotations appended from [before]
     * on get an /NM (kept from [a].nm when present) and, for grouped pending annotations, /IRT + /RT /Group
     * pointing to the first annotation written for that group.
     */
    fun afterWrite(page: PDPage, before: Int, a: Annot, groups: HashMap<Long, COSDictionary>) {
        val list = runCatching { page.annotations }.getOrNull() ?: return
        if (list.size <= before) return
        for (i in before until list.size) {
            val an = list[i]
            if (an.annotationName.isNullOrBlank()) an.annotationName = if (i == before) (a.nm ?: "sd-${a.id}") else "sd-${a.id}-${i - before}"
        }
        // section 7: subject, lock flags and a closed pop-up for markup comments
        val first = list[before].cosObject
        if (a.subject.isNotBlank()) first.setString(COSName.getPDFName("Subj"), a.subject)
        if (a.locked) AnnotStyle.setLocked(first, true)
        if (list[before].subtype in POPUP_KINDS) runCatching { AnnotStyle.addPopup(page, first, false) }
        if (list.size <= before) return
        val gid = a.groupId ?: return
        if (a.kind == AnnotKind.REPLACE) return          // already a group of its own
        val parent = groups[gid]
        if (parent == null) groups[gid] = first
        else if (parent !== first) { first.setItem(IRT, parent); first.setName(RT, "Group") }
    }

    /** Hidden replies / review states (children that are not drawn on the page). */
    fun isReplyOrState(an: PDAnnotation): Boolean {
        val cos = an.cosObject
        if (!cos.containsKey(IRT)) return false
        if (cos.getNameAsString(RT) == "Group") return false
        return an.isHidden || cos.containsKey(COSName.getPDFName("State")) || an.subtype == "Text"
    }

    // ---------------- read back ----------------

    private fun floats(cos: COSDictionary, key: String): FloatArray? =
        (cos.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)?.let { arr -> FloatArray(arr.size()) { i -> (arr.getObject(i) as? COSNumber)?.floatValue() ?: 0f } }

    private fun colorOf(arr: FloatArray?): Int? {
        if (arr == null || arr.size != 3) return null
        fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (b(arr[0]) shl 16) or (b(arr[1]) shl 8) or b(arr[2])
    }

    /** Kinds of this module read back into an [Annot] for editing; null for anything else. */
    fun read(an: PDAnnotation, page: Int, base: Annot, rect: RectF, media: Boolean = true): Annot? {
        val cos = an.cosObject
        VideoPdf.detect(an)?.let { v -> return readVideo(an, v, base, rect, media) }
        return when (an.subtype) {
            "Polygon", "PolyLine" -> {
                val v = floats(cos, "Vertices")?.takeIf { it.size >= 4 } ?: return null
                val pts = (0 until v.size / 2).map { v[it * 2] to v[it * 2 + 1] }
                val be = cos.getDictionaryObject(COSName.getPDFName("BE")) as? COSDictionary
                val cloudy = be?.getNameAsString(COSName.S) == "C"
                val intensity = if (cloudy) (be!!.getFloat(COSName.getPDFName("I"), 0f).toInt() + 1).coerceIn(1, 3) else 0
                val kind = when { an.subtype == "PolyLine" -> AnnotKind.POLYLINE; cloudy -> AnnotKind.CLOUD; else -> AnnotKind.POLYGON }
                base.copy(kind = kind, points = pts, cloud = intensity, cloudInward = cloudy && AnnotStyle.cloudInwardOf(cos), fillColor = colorOf(floats(cos, "IC")))
            }
            "Caret" -> base.copy(kind = AnnotKind.CARET, rects = listOf(rect), color = base.color)
            "FileAttachment" -> {
                val fa = an as? PDAnnotationFileAttachment ?: return null
                val spec = runCatching { fa.file as? PDComplexFileSpecification }.getOrNull() ?: return null
                val ef = spec.embeddedFileUnicode ?: spec.embeddedFile ?: return null
                val name = (spec.filename ?: "attachment").replace(Regex("""[\\/:*?"<>|]"""), "_")
                // the embedded bytes must survive the round trip through a pending annotation (streamed: files can be large)
                val out = File(extractDir(), "${System.nanoTime()}_$name")
                runCatching { out.outputStream().use { o -> ef.createInputStream().use { it.copyTo(o, 1 shl 16) } } }.getOrElse { out.delete(); return null }
                val mime = ef.subtype ?: ""
                val media = cos.getNameAsString(MEDIA)
                val kind = when {
                    media == "Audio" || mime.startsWith("audio/") -> AnnotKind.SOUND
                    media == "Video" || mime.startsWith("video/") -> AnnotKind.VIDEO
                    else -> AnnotKind.FILE_ATTACH
                }
                base.copy(kind = kind, rects = listOf(rect), filePath = out.absolutePath, mime = mime, text = an.contents ?: name, icon = fa.attachmentName?.takeIf { it != "Paperclip" } ?: "")
            }
            else -> null
        }
    }

    private fun extractDir() = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "annot2_extract").apply { mkdirs() }

    /**
     * A stored video (any form) as a pending VIDEO annotation: the video is streamed out to a temp file (skipped when
     * [media] is false: selection preview only) together with our poster. Flash-only RichMedia is not editable (null).
     */
    private fun readVideo(an: PDAnnotation, v: VideoPdf.Info, base: Annot, rect: RectF, media: Boolean): Annot? {
        if (!v.supported) return null
        val cos = an.cosObject
        val safe = v.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "video.mp4" }
        var path: String? = null
        var poster: String? = null
        if (media) {
            val out = File(extractDir(), "${System.nanoTime()}_$safe")
            val n = runCatching { out.outputStream().use { VideoPdf.copyVideo(cos, it) } }.getOrElse { out.delete(); return null }
            if (n < 0) { out.delete(); return null }
            path = out.absolutePath
            VideoPdf.posterJpeg(cos)?.let { j -> poster = File(extractDir(), "${System.nanoTime()}_poster.jpg").also { it.writeBytes(j) }.absolutePath }
        }
        val title = cos.getString(COSName.T) ?: ""
        return base.copy(kind = AnnotKind.VIDEO, rects = listOf(rect), filePath = path, mime = v.mime,
            text = an.contents?.takeIf { it.isNotBlank() } ?: v.name, author = title.ifBlank { base.author },
            video = v.options, posterPath = poster, color = if (v.ours) base.color else 0x000000)
    }

    /** Whether a stored annotation is one of ours with a playable / openable embedded file. */
    fun mediaKind(an: PDAnnotation): AnnotKind? {
        if (VideoPdf.detect(an) != null) return AnnotKind.VIDEO
        if (an.subtype != "FileAttachment") return null
        return when (an.cosObject.getNameAsString(MEDIA)) { "Audio" -> AnnotKind.SOUND; "Video" -> AnnotKind.VIDEO; else -> AnnotKind.FILE_ATTACH }
    }
}
