package com.smartdocs.editor.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import com.smartdocs.editor.core.fillsign2.CombLayout
import com.smartdocs.editor.core.fillsign2.RoundRect
import com.smartdocs.editor.core.fillsign2.StampRead

/** Annotation kinds the editor supports. */
enum class AnnotKind {
    HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY, INK, NOTE, FREETEXT, LINE, ARROW, RECT, OVAL, STAMP_IMAGE, STAMP_TEXT, REDACT,
    // written by core/annot/Annot2Writer (MODULE_annot2)
    POLYGON, POLYLINE, CLOUD, CARET, REPLACE, FILE_ATTACH, SOUND, VIDEO,
}

/**
 * An annotation in page space (PDF points, origin bottom-left). Kept in memory while editing and
 * committed to the file with [AnnotationWriter.commit].
 */
data class Annot(
    val id: Long = System.nanoTime(),
    val kind: AnnotKind,
    val page: Int,
    val color: Int = 0xFFFF00,
    val opacity: Float = 1f,
    val strokeWidth: Float = 2f,
    val rects: List<RectF> = emptyList(),      // text markup quads, rect/oval bounds, stamp bounds
    val paths: List<List<Pair<Float, Float>>> = emptyList(),   // ink strokes
    val points: List<Pair<Float, Float>> = emptyList(),        // line/arrow endpoints
    val text: String = "",                      // note content / free text / stamp text
    val fontSize: Float = 14f,
    val author: String = "",
    val image: Bitmap? = null,
    val created: Long = System.currentTimeMillis(),
    val fillColor: Int? = null,                 // polygon / cloud interior (null = transparent)
    val cloud: Int = 0,                         // cloudy border intensity 0 (none), 1, 2
    val cloudInward: Boolean = false,           // Foxit 24.33: scallops point into the shape
    val filePath: String? = null,               // FILE_ATTACH / SOUND / VIDEO: file embedded on commit
    val mime: String = "",
    val groupId: Long? = null,                  // pending annotations grouped together (/IRT + /RT /Group on commit)
    val nm: String? = null,                     // /NM unique name, kept across edit round trips
    // ---- section 7 (core/annot/AnnotStyle) ----
    val subject: String = "",                   // /Subj
    val dash: Int = 0,                          // border dash: core.view5.DashStyle ordinal (0 solid)
    val noBorder: Boolean = false,              // rect / oval / polygon drawn by their fill only (/BS /W 0)
    val lineStart: String = "None",             // LINE / ARROW: /LE start ending (PDF name)
    val lineEnd: String = "",                   // LINE / ARROW: /LE end ending ("" = default of the kind)
    val locked: Boolean = false,                // /F Locked + LockedContents
    val icon: String = "",                      // NOTE: /Name icon ("" = Comment)
    val bold: Boolean = false,                  // FREETEXT rich text (/RC /DS)
    val italic: Boolean = false,
    val underline: Boolean = false,
    // ---- FREETEXT formatting (Foxit 24.99-24.107), written to the appearance and to /RC /DS ----
    val fontFamily: Int = 0,                    // 0 sans (Helvetica / Naskh), 1 serif (Times), 2 mono (Courier)
    val strike: Boolean = false,
    val align: Int = -1,                        // -1 auto (by script), 0 start, 1 centre, 2 end, 3 justify
    val lineSpacing: Float = 1.3f,              // line height in font sizes
    val charSpacing: Float = 0f,                // Tc, points
    val wordSpacing: Float = 0f,                // Tw, points
    val hScale: Float = 1f,                     // Tz / 100
    val bullets: Boolean = false,
    val script: Int = 0,                        // Foxit 24.102: 0 normal, 1 superscript, 2 subscript
    // ---- LINE / ARROW: show the note text on the line (/Cap, Foxit 24.42) ----
    val caption: Boolean = false,
    val video: com.smartdocs.editor.core.annot.VideoPdf.Options? = null,   // VIDEO: in-page player options
    val posterPath: String? = null,             // VIDEO: poster frame JPEG (appearance in other viewers)
    // nitro-fillsign2
    val corner: Float = 0f,                     // RECT: corner radius in points (Fill & Sign rounded rectangle)
    val comb: Int = 0,                          // FREETEXT: number of comb cells (0 = ordinary text)
    val sigPath: String? = null,                // STAMP_IMAGE: the QuickSign signature it was placed from (never written to the file)
    val sigRef: String? = null,                 // STAMP_IMAGE: a saved signature stamp read back (/SDSigRef, StampRead.refOf of the image)
) {
    /** A placed QuickSign signature / initials (pending, or read back from the file). */
    val isSignature: Boolean get() = kind == AnnotKind.STAMP_IMAGE && (sigPath != null || sigRef != null)
}

/** Private keys of nitro-fillsign2 annotations (second-class names, ignored by other viewers). */
object FillSignKeys {
    /** /Square: corner radius of a rounded rectangle. */
    val ROUND: COSName = COSName.getPDFName("SDRound")
    /** /FreeText: number of comb cells. */
    val COMB: COSName = COSName.getPDFName("SDComb")
    /** /Stamp: a placed QuickSign signature / initials (Apply All Signatures, flatten on save). */
    val SIG: COSName = COSName.getPDFName("SDSig")
}

/** /LE end ending of a line annotation: explicit [Annot.lineEnd] or the default of the kind. */
fun Annot.endStyle(): String = lineEnd.ifBlank { if (kind == AnnotKind.ARROW) "OpenArrow" else "None" }

object AnnotationWriter {

    private fun rgb(c: Int) = PDColor(floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f), PDDeviceRGB.INSTANCE)

    private fun union(rects: List<RectF>): PDRectangle {
        val r = RectF(rects.first()); rects.forEach { r.union(it) }
        return PDRectangle(r.left, r.bottom.coerceAtMost(r.top), r.width(), kotlin.math.abs(r.height()))
    }

    private fun norm(r: RectF): RectF = RectF(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom))

    /**
     * Outcome of [commit] (audit DI-05): [failed] annotations could not be written and are NOT in [out]; the caller
     * must keep them pending and tell the user instead of dropping them.
     */
    data class CommitResult(val written: Int, val failed: List<Annot>, val errors: List<String> = emptyList()) {
        /** For one-shot callers (AI note, search marks): any failure is an error. */
        fun orThrow() { if (failed.isNotEmpty()) throw java.io.IOException("${failed.size} annotation(s) could not be written: ${errors.firstOrNull().orEmpty()}") }
    }

    /**
     * Write every annotation into a copy of [src] saved at [out]. A signed [src] is updated incrementally so its
     * signatures stay valid (audit AN-28 / DI-27; [SignedSave]); throws [SignedDocumentException] when that is not
     * possible (certified without annotation rights, or the update failed) \u2014 the caller should offer a copy.
     */
    suspend fun commit(context: Context, src: File, annots: List<Annot>, out: File, password: String? = null, fullSave: Boolean = false): CommitResult = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "commit: out must differ from src" }
        saveChanged(src, out, password, fullSave) { d -> writeAll(d, annots) { page, a -> write(context, d, page, a) } to annots.map { it.page } }
    }

    /**
     * Load [src], apply [change] (returns its result and the zero-based pages it touched) and save to [out]: a full
     * save (encrypted files re-protected like the source, [PdfOps.reprotect]) or, for a signed file, an incremental
     * update that leaves the signed bytes unchanged. Never writes into [src].
     */
    internal fun <T> saveChanged(src: File, out: File, password: String?, fullSave: Boolean = false, change: (PDDocument) -> Pair<T, List<Int>>): T =
        PdfOps.loadRaw(src, password).use { d ->
            // [fullSave]: a separate copy ("save a copy" of a signed file) \u2014 an ordinary save, the signatures of the copy
            // become invalid but the signed original is left untouched
            val signed = !fullSave && SignedSave.isSigned(d)
            if (signed) SignedSave.requireAnnotationsAllowed(d) else PdfOps.reprotect(d, password)
            val (r, pages) = change(d)
            if (signed) SignedSave.saveIncremental(d, pages.distinct().filter { it in 0 until d.numberOfPages }.map { d.getPage(it) }, out)
            else d.save(out)
            r
        }

    /** The per-annotation loop of [commit], separate so a failing writer can be unit tested (audit DI-05). */
    internal fun writeAll(d: PDDocument, annots: List<Annot>, writeOne: (PDPage, Annot) -> Unit): CommitResult {
        val failed = ArrayList<Annot>(); val errors = ArrayList<String>(); var written = 0
        annots.groupBy { it.page }.forEach { (pi, list) ->
            if (pi !in 0 until d.numberOfPages) { failed += list; errors += "page ${pi + 1} does not exist"; return@forEach }
            val page = d.getPage(pi)
            val groups = HashMap<Long, com.tom_roush.pdfbox.cos.COSDictionary>()
            list.forEach { a ->
                val before = runCatching { page.annotations.size }.getOrDefault(0)
                runCatching {
                    writeOne(page, a)
                    // /NM for every new annotation (merge de-duplication) and /IRT /RT /Group for grouped ones
                    com.smartdocs.editor.core.annot.Annot2Writer.afterWrite(page, before, a, groups)
                }.onSuccess { written++ }.onFailure { e ->
                    // drop whatever a half-written annotation appended, so a retry does not duplicate it
                    runCatching { val l = page.annotations; if (l.size > before) page.annotations = l.subList(0, before).toMutableList() }
                    failed += a; errors += (e.message ?: e.toString())
                }
            }
        }
        return CommitResult(written, failed, errors)
    }

    internal fun write(context: Context, doc: PDDocument, page: PDPage, a: Annot) {
        val annotations = page.annotations
        val now = Calendar.getInstance()
        // tab audit D9: text, comb and image appearances are drawn upright for the reader of a page with /Rotate
        val rot = com.smartdocs.editor.core.fillsign2.FsGeom.norm(runCatching { page.rotation }.getOrDefault(0))
        when (a.kind) {
            AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY -> {
                val sub = when (a.kind) {
                    AnnotKind.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                    AnnotKind.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    AnnotKind.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                    else -> PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
                }
                val rects = a.rects.map(::norm); if (rects.isEmpty()) return
                val m = PDAnnotationTextMarkup(sub)
                // audit 5 C-6: PDF quad order is upper-left, upper-right, lower-left, lower-right AS THE TEXT IS READ:
                // after norm() r.top is the LOWER y, so the upper corners are at r.bottom (max y); on a /Rotate 90/180/270
                // page "up" on screen is another user-space direction (MarkupFrame)
                val rot = runCatching { page.rotation }.getOrDefault(0)
                val frames = rects.map { r -> MarkupFrame.of(r.left, r.top, r.right, r.bottom, rot) }
                val quads = FloatArray(rects.size * 8)
                frames.forEachIndexed { i, f -> f.quad().copyInto(quads, i * 8) }
                m.quadPoints = quads
                m.rectangle = union(rects)
                m.color = rgb(a.color); m.constantOpacity = a.opacity
                m.contents = a.text; m.titlePopup = a.author; m.setCreationDate(now); m.setModifiedDate(now)
                m.appearance = apDict(markupAppearance(doc, m.rectangle, rects, frames, a))
                annotations.add(m)
            }
            AnnotKind.INK -> {
                if (a.paths.isEmpty()) return
                val ink = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "Ink") }
                val all = ArrayList<RectF>()
                val inkList = COSArray()
                a.paths.forEach { stroke ->
                    val arr = COSArray()
                    val r = RectF(stroke.first().first, stroke.first().second, stroke.first().first, stroke.first().second)
                    stroke.forEach { (x, y) -> arr.add(COSFloat(x)); arr.add(COSFloat(y)); r.union(x, y) }
                    inkList.add(arr); all += r
                }
                ink.cosObject.setItem(COSName.INKLIST, inkList)
                val bounds = union(all).let { PDRectangle(it.lowerLeftX - a.strokeWidth, it.lowerLeftY - a.strokeWidth, it.width + 2 * a.strokeWidth, it.height + 2 * a.strokeWidth) }
                ink.rectangle = bounds
                ink.color = rgb(a.color); ink.constantOpacity = a.opacity
                ink.borderStyle = PDBorderStyleDictionary().apply { width = a.strokeWidth }
                if (a.dash > 0) com.smartdocs.editor.core.annot.AnnotStyle.applyBorder(ink.cosObject, a.strokeWidth, a.dash)
                ink.contents = a.text; ink.titlePopup = a.author; ink.setCreationDate(now); ink.setModifiedDate(now)
                ink.appearance = apDict(inkAppearance(doc, bounds, a))
                annotations.add(ink)
            }
            AnnotKind.NOTE -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                val n = PDAnnotationText()
                // the icon hangs below the anchor (the larger y), like the on-screen preview
                n.rectangle = PDRectangle(r.left, maxOf(r.top, r.bottom) - 20f, 20f, 20f)
                n.name = a.icon.ifBlank { PDAnnotationText.NAME_COMMENT }
                n.contents = a.text; n.titlePopup = a.author; n.color = rgb(a.color)
                n.setCreationDate(now); n.setModifiedDate(now)
                n.appearance = apDict(noteAppearance(doc, n.rectangle, a))
                annotations.add(n)
            }
            AnnotKind.FREETEXT, AnnotKind.STAMP_TEXT -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                val ft = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "FreeText") }
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                ft.rectangle = rect
                ft.contents = a.text; ft.titlePopup = a.author; ft.setCreationDate(now); ft.setModifiedDate(now)
                ft.constantOpacity = a.opacity
                // default appearance keeps font size and colour so the annotation can be read back for editing
                ft.cosObject.setString(COSName.DA, String.format(java.util.Locale.US, "/Helv %.1f Tf %.3f %.3f %.3f rg",
                    a.fontSize, ((a.color shr 16) and 0xFF) / 255f, ((a.color shr 8) and 0xFF) / 255f, (a.color and 0xFF) / 255f) +
                    // comb: character spacing that advances one cell per (average) glyph, for viewers that rebuild from /DA
                    (if (a.comb > 0) String.format(java.util.Locale.US, " %.2f Tc", CombLayout.charSpacing(rect.width / a.comb, a.fontSize * 0.55f)) else ""))
                if (a.comb > 0) ft.cosObject.setInt(FillSignKeys.COMB, a.comb)
                if (a.kind == AnnotKind.STAMP_TEXT) ft.cosObject.setName(name("IT"), "FreeTextStamp")
                else if (a.comb <= 0) com.smartdocs.editor.core.annot.AnnotStyle.applyRich(ft.cosObject, a.text,
                    com.smartdocs.editor.core.annot.AnnotStyle.Rich(a.fontSize, a.color, a.bold, a.italic, a.underline, a.strike, a.fontFamily, a.align,
                        a.lineSpacing, a.charSpacing, a.wordSpacing, a.bullets, a.script), ArabicShaper.isArabic(a.text))
                // Acrobat's /Rotate of a FreeText on a turned page (viewers that rebuild the appearance from /DA)
                if (rot != 0) ft.cosObject.setInt(COSName.ROTATE, rot)
                ft.appearance = apDict(if (a.comb > 0) combAppearance(context, doc, rect, a, rot) else textAppearance(context, doc, rect, a, rot))
                annotations.add(ft)
            }
            AnnotKind.LINE, AnnotKind.ARROW -> {
                if (a.points.size < 2) return
                val (p1, p2) = a.points
                val line = PDAnnotationLine()
                line.line = floatArrayOf(p1.first, p1.second, p2.first, p2.second)
                val pad = maxOf(8f + a.strokeWidth, a.strokeWidth * 4f + 8f)
                val r = norm(RectF(p1.first, p1.second, p2.first, p2.second)).apply { inset(-pad, -pad) }
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                line.rectangle = rect
                line.color = rgb(a.color); line.constantOpacity = a.opacity
                line.borderStyle = PDBorderStyleDictionary().apply { width = a.strokeWidth }
                com.smartdocs.editor.core.annot.AnnotStyle.applyBorder(line.cosObject, a.strokeWidth, a.dash)
                com.smartdocs.editor.core.annot.AnnotStyle.setLineEnds(line.cosObject, a.lineStart, a.endStyle())
                if (a.caption && a.text.isNotBlank()) line.cosObject.setBoolean(COSName.getPDFName("Cap"), true)
                line.contents = a.text; line.titlePopup = a.author; line.setCreationDate(now); line.setModifiedDate(now)
                line.appearance = apDict(lineAppearance(doc, rect, a, if (a.caption && a.text.isNotBlank()) PdfOps.arabicFont(context, doc) else null))
                annotations.add(line)
            }
            AnnotKind.RECT, AnnotKind.OVAL -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                if (a.kind == AnnotKind.OVAL && a.subject == com.smartdocs.editor.core.annot.AnnotStyle.TICKMARK) {
                    // numbered / lettered / symbol tickmark (CMT-19): its own appearance with the label
                    com.smartdocs.editor.core.annot.AnnotStyle.writeTickmark(doc, page, (r.left + r.right) / 2, (r.top + r.bottom) / 2, a.text, a.color,
                        size = kotlin.math.abs(r.height()).coerceAtLeast(8f), author = a.author)
                    return
                }
                val sq = PDAnnotationSquareCircle(if (a.kind == AnnotKind.RECT) PDAnnotationSquareCircle.SUB_TYPE_SQUARE else PDAnnotationSquareCircle.SUB_TYPE_CIRCLE)
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                sq.rectangle = rect
                sq.color = rgb(a.color); sq.constantOpacity = a.opacity
                com.smartdocs.editor.core.annot.AnnotStyle.applyBorder(sq.cosObject, a.strokeWidth, a.dash, a.noBorder)
                com.smartdocs.editor.core.annot.AnnotStyle.setInterior(sq.cosObject, a.fillColor)
                if (a.cloud > 0 && a.corner <= 0f) com.smartdocs.editor.core.annot.AnnotStyle.setCloudy(sq.cosObject, a.cloud, a.cloudInward)   // Nitro 7.27: ovals too
                sq.contents = a.text; sq.titlePopup = a.author; sq.setCreationDate(now); sq.setModifiedDate(now)
                if (a.kind == AnnotKind.RECT && a.corner > 0f) {
                    // nitro-fillsign2 rounded rectangle (its own appearance)
                    sq.cosObject.setFloat(FillSignKeys.ROUND, a.corner)
                    sq.appearance = apDict(roundShapeAppearance(doc, rect, a))
                } else sq.appearance = apDict(com.smartdocs.editor.core.annot.AnnotStyle.shapeStream(doc, rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height,
                    a.kind == AnnotKind.OVAL, a.color, a.fillColor, a.strokeWidth, a.dash, a.noBorder, a.opacity, a.cloud, a.cloudInward))
                annotations.add(sq)
            }
            AnnotKind.STAMP_IMAGE -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                val bmp = a.image ?: return
                val st = PDAnnotationRubberStamp()
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                st.rectangle = rect; st.name = "Image"; st.constantOpacity = a.opacity
                if (a.isSignature) {
                    st.cosObject.setBoolean(FillSignKeys.SIG, true)
                    st.cosObject.setString(StampRead.SIG_REF, a.sigRef ?: StampRead.refOf(a.sigPath!!))
                }
                st.titlePopup = a.author; st.setCreationDate(now); st.setModifiedDate(now)
                val (form, uw, uh) = uprightForm(doc, rect, rot)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, form).use { cs -> cs.drawImage(img, 0f, 0f, uw, uh) }
                st.appearance = apDict(form)
                annotations.add(st)
            }
            AnnotKind.REDACT -> { /* applied through PdfOps.redact, never stored as an annotation */ }
            AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD, AnnotKind.CARET, AnnotKind.REPLACE,
            AnnotKind.FILE_ATTACH, AnnotKind.SOUND, AnnotKind.VIDEO -> com.smartdocs.editor.core.annot.Annot2Writer.write(doc, page, a)
        }
    }

    private fun newForm(doc: PDDocument, rect: PDRectangle): PDAppearanceStream = PDAppearanceStream(doc).apply {
        bBox = PDRectangle(0f, 0f, rect.width, rect.height)
        resources = com.tom_roush.pdfbox.pdmodel.PDResources()
        setMatrix(com.tom_roush.harmony.awt.geom.AffineTransform.getTranslateInstance(rect.lowerLeftX.toDouble(), rect.lowerLeftY.toDouble()))
    }

    /**
     * Tab audit D9: an appearance drawn upright for the reader of a page turned by [rot] (0/90/180/270): the BBox is
     * the displayed (upright) size and the /Matrix turns it with the page ([com.smartdocs.editor.core.sign.SigFields.matrixFor],
     * as the signature widgets do); the viewer fits the turned BBox onto /Rect. Returns the form and its upright size.
     */
    private fun uprightForm(doc: PDDocument, rect: PDRectangle, rot: Int): Triple<PDAppearanceStream, Float, Float> {
        val m = com.smartdocs.editor.core.sign.SigFields.matrixFor(rot) ?: return Triple(newForm(doc, rect), rect.width, rect.height)
        val (uw, uh) = com.smartdocs.editor.core.fillsign2.FsGeom.uprightSize(rot, rect.width, rect.height)
        val form = PDAppearanceStream(doc).apply {
            bBox = PDRectangle(0f, 0f, uw, uh)
            resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            cosObject.setItem(COSName.MATRIX, COSArray().apply { m.forEach { add(COSFloat(it)) } })
        }
        return Triple(form, uw, uh)
    }

    private fun apDict(stream: PDAppearanceStream): PDAppearanceDictionary = PDAppearanceDictionary().apply { setNormalAppearance(stream) }

    private fun alpha(cs: PDPageContentStream, a: Annot, blend: BlendMode? = null) {
        val gs = PDExtendedGraphicsState()
        gs.nonStrokingAlphaConstant = a.opacity; gs.strokingAlphaConstant = a.opacity
        if (blend != null) gs.blendMode = blend
        cs.setGraphicsStateParameters(gs)
    }

    private fun setColors(cs: PDPageContentStream, c: Int) {
        cs.setStrokingColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        cs.setNonStrokingColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    private fun markupAppearance(doc: PDDocument, bounds: PDRectangle, rects: List<RectF>, frames: List<MarkupFrame>, a: Annot): PDAppearanceStream {
        val form = newForm(doc, bounds)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a, if (a.kind == AnnotKind.HIGHLIGHT) BlendMode.MULTIPLY else null)
            setColors(cs, a.color)
            val ox = bounds.lowerLeftX; val oy = bounds.lowerLeftY
            fun move(f: MarkupFrame, u: Float, v: Float) { val (x, y) = f.point(u, v); cs.moveTo(x - ox, y - oy) }
            fun line(f: MarkupFrame, u: Float, v: Float) { val (x, y) = f.point(u, v); cs.lineTo(x - ox, y - oy) }
            rects.forEachIndexed { i, r ->
                // rects are normalised with top = smaller y, so the lower edge in PDF space is min(top, bottom)
                val x = r.left - ox; val y = minOf(r.top, r.bottom) - oy
                // audit 5 C-6: lines follow the text direction (frame u = along the text, v = towards its top)
                val f = frames[i]
                when (a.kind) {
                    AnnotKind.HIGHLIGHT -> { cs.addRect(x, y, r.width(), r.height()); cs.fill() }
                    AnnotKind.UNDERLINE -> { cs.setLineWidth(1.2f); move(f, 0f, 1f); line(f, f.w, 1f); cs.stroke() }
                    AnnotKind.STRIKEOUT -> { cs.setLineWidth(1.2f); move(f, 0f, f.h / 2); line(f, f.w, f.h / 2); cs.stroke() }
                    else -> {
                        cs.setLineWidth(1f); var uu = 0f; var up = true; move(f, 0f, 0f)
                        while (uu < f.w) { uu += 2.5f; line(f, uu, if (up) 2.5f else 0f); up = !up }
                        cs.stroke()
                    }
                }
            }
        }
        return form
    }

    private fun inkAppearance(doc: PDDocument, bounds: PDRectangle, a: Annot): PDAppearanceStream {
        val form = newForm(doc, bounds)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a); setColors(cs, a.color)
            cs.setLineWidth(a.strokeWidth); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
            dashOf(a)?.let { cs.setLineDashPattern(it, 0f) }
            a.paths.forEach { stroke ->
                stroke.forEachIndexed { i, (x, y) ->
                    val px = x - bounds.lowerLeftX; val py = y - bounds.lowerLeftY
                    if (i == 0) cs.moveTo(px, py) else cs.lineTo(px, py)
                }
                cs.stroke()
            }
        }
        return form
    }

    private fun noteAppearance(doc: PDDocument, rect: PDRectangle, a: Annot): PDAppearanceStream {
        val form = newForm(doc, rect)
        PDPageContentStream(doc, form).use { cs ->
            setColors(cs, a.color)
            cs.addRect(1f, 1f, rect.width - 2, rect.height - 2); cs.fill()
            cs.setStrokingColor(60, 60, 60); cs.setLineWidth(1f)
            cs.addRect(1f, 1f, rect.width - 2, rect.height - 2); cs.stroke()
            com.smartdocs.editor.core.annot.AnnotStyle.noteGlyph(cs, a.icon)
        }
        return form
    }

    /** Standard 14 face for Latin-only text in [a]'s family / weight / slant (null: use the embedded Arabic font). */
    private fun std14(a: Annot): com.tom_roush.pdfbox.pdmodel.font.PDFont? {
        if (!com.smartdocs.editor.core.view5.V5Pdf.latinOnly(a.text.replace("\u2022", ""))) return null
        if (a.fontFamily == 0 && !a.bold && !a.italic) return null
        return when (a.fontFamily) {
            1 -> when { a.bold && a.italic -> PDType1Font.TIMES_BOLD_ITALIC; a.bold -> PDType1Font.TIMES_BOLD; a.italic -> PDType1Font.TIMES_ITALIC; else -> PDType1Font.TIMES_ROMAN }
            2 -> when { a.bold && a.italic -> PDType1Font.COURIER_BOLD_OBLIQUE; a.bold -> PDType1Font.COURIER_BOLD; a.italic -> PDType1Font.COURIER_OBLIQUE; else -> PDType1Font.COURIER }
            else -> when { a.bold && a.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE; a.bold -> PDType1Font.HELVETICA_BOLD; a.italic -> PDType1Font.HELVETICA_OBLIQUE; else -> PDType1Font.HELVETICA }
        }
    }

    private fun textAppearance(context: Context, doc: PDDocument, rect0: PDRectangle, a: Annot, rot: Int = 0): PDAppearanceStream {
        val (form, uw, uh) = uprightForm(doc, rect0, rot)
        val rect = PDRectangle(uw, uh)      // the upright box the text is laid out in
        val std = std14(a)
        val font = std ?: PdfOps.arabicFont(context, doc)
        // the Arabic font has one face: bold is drawn as fill + stroke and italic as a slant
        val fakeBold = a.bold && std == null
        val fakeItalic = a.italic && std == null
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a)
            if (a.kind == AnnotKind.STAMP_TEXT) {
                setColors(cs, a.color); cs.setLineWidth(2f)
                cs.addRect(1f, 1f, rect.width - 2, rect.height - 2); cs.stroke()
            }
            // 24.102: super- / subscript text is smaller and raised / lowered; lines keep the full line height
            val full = a.fontSize
            val size = com.smartdocs.editor.core.annot.AnnotStyle.scriptSize(full, a.script)
            val lines = a.text.split('\n').map { if (a.bullets && it.isNotBlank()) "\u2022 $it" else it }
            val rtl = ArabicShaper.isArabic(a.text)
            val avail = rect.width - 8f
            var y = rect.height - full - 4f + com.smartdocs.editor.core.annot.AnnotStyle.scriptRise(full, a.script)
            val decorations = ArrayList<FloatArray>()      // x0, x1, y of underline / strike
            lines.forEachIndexed { li, raw ->
                val line = com.smartdocs.editor.core.view5.V5Pdf.sanitize(font, raw)
                val shaped = if (std == null) ArabicShaper.shape(line) else line
                val glyphW = runCatching { font.getStringWidth(shaped) / 1000f * size }.getOrDefault(0f) * a.hScale
                val gaps = (shaped.length - 1).coerceAtLeast(0)
                val spaces = shaped.count { it == ' ' }
                val justify = a.align == 3 && li < lines.size - 1 && gaps > 0
                var tc = a.charSpacing
                if (justify) tc += ((avail - glyphW - a.charSpacing * gaps - a.wordSpacing * spaces) / gaps).coerceAtLeast(0f)
                val w = if (justify) avail else glyphW + tc * gaps + (if (std != null) a.wordSpacing * spaces else 0f)
                val start = if (rtl) (rect.width - w - 4f) else 4f
                val x = when (a.align) {
                    1 -> (rect.width - w) / 2
                    2 -> if (rtl) 4f else rect.width - w - 4f
                    3 -> 4f
                    0 -> start
                    else -> start
                }.coerceAtLeast(2f)
                cs.beginText(); cs.setFont(font, size); setColors(cs, a.color)
                cs.setCharacterSpacing(tc)
                if (std != null) cs.setWordSpacing(a.wordSpacing)
                if (a.hScale != 1f) cs.setHorizontalScaling(a.hScale * 100f)
                if (fakeBold) { cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL_STROKE); cs.setLineWidth(size * 0.035f) }
                cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix(1f, 0f, if (fakeItalic) 0.2f else 0f, 1f, x, y))
                if (std == null) cs.showArabic(line, shaped) else cs.showText(shaped)
                cs.endText()
                if (a.underline) decorations += floatArrayOf(x, x + w, y - size * 0.15f)
                if (a.strike) decorations += floatArrayOf(x, x + w, y + size * 0.3f)
                y -= full * a.lineSpacing.coerceIn(0.8f, 4f)
            }
            if (decorations.isNotEmpty()) {
                setColors(cs, a.color); cs.setLineWidth((size / 16f).coerceAtLeast(0.6f))
                decorations.forEach { d -> cs.moveTo(d[0], d[2]); cs.lineTo(d[1], d[2]) }
                cs.stroke()
            }
        }
        return form
    }

    private fun dashOf(a: Annot): FloatArray? = com.smartdocs.editor.core.view5.DashStyle.entries.getOrElse(a.dash) { com.smartdocs.editor.core.view5.DashStyle.SOLID }.scaled(a.strokeWidth)

    private fun lineAppearance(doc: PDDocument, rect: PDRectangle, a: Annot, captionFont: com.tom_roush.pdfbox.pdmodel.font.PDFont? = null): PDAppearanceStream {
        val form = newForm(doc, rect)
        val (p1, p2) = a.points
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a); setColors(cs, a.color); cs.setLineWidth(a.strokeWidth); cs.setLineCapStyle(1)
            val x1 = p1.first - rect.lowerLeftX; val y1 = p1.second - rect.lowerLeftY
            val x2 = p2.first - rect.lowerLeftX; val y2 = p2.second - rect.lowerLeftY
            dashOf(a)?.let { cs.setLineDashPattern(it, 0f) }
            cs.moveTo(x1, y1); cs.lineTo(x2, y2); cs.stroke()
            val q1 = com.smartdocs.editor.core.view5.P(x1.toDouble(), y1.toDouble()); val q2 = com.smartdocs.editor.core.view5.P(x2.toDouble(), y2.toDouble())
            com.smartdocs.editor.core.view5.V5Pdf.drawEnd(cs, com.smartdocs.editor.core.view5.LineEnd.of(a.endStyle()), q1, q2, a.strokeWidth)
            com.smartdocs.editor.core.view5.V5Pdf.drawEnd(cs, com.smartdocs.editor.core.view5.LineEnd.of(a.lineStart), q2, q1, a.strokeWidth)
            if (captionFont != null) {
                // the note text written on the line (Foxit "show text in line")
                val mid = com.smartdocs.editor.core.view5.Geom.midpoint(q1, q2)
                com.smartdocs.editor.core.view5.V5Pdf.label(cs, a.text.replace('\n', ' ').take(120), mid.x.toFloat(), mid.y.toFloat() + 3f,
                    Math.atan2(y2 - y1.toDouble(), (x2 - x1).toDouble()), (a.fontSize * 0.75f).coerceIn(6f, 24f), a.color, captionFont)
            }
        }
        return form
    }

    /** Comb text (nitro-fillsign2): one glyph centred in each of [Annot.comb] equal cells ([CombLayout]). */
    private fun combAppearance(context: Context, doc: PDDocument, rect0: PDRectangle, a: Annot, rot: Int = 0): PDAppearanceStream {
        val (form, uw, uh) = uprightForm(doc, rect0, rot)
        val rect = PDRectangle(uw, uh)      // the upright box the cells are laid out in
        val font = PdfOps.arabicFont(context, doc)
        val rtl = ArabicShaper.isArabic(a.text)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a)
            cs.beginText(); cs.setFont(font, a.fontSize); setColors(cs, a.color)
            val y = (rect.height - a.fontSize) / 2f + a.fontSize * 0.2f
            CombLayout.place(a.text, a.comb, rtl).forEach { (cell, ch) ->
                val shaped = ArabicShaper.shape(ch)
                val w = runCatching { font.getStringWidth(shaped) / 1000f * a.fontSize }.getOrDefault(a.fontSize * 0.55f)
                cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(CombLayout.glyphX(0f, rect.width, a.comb, cell, w), y))
                cs.showArabic(ch, shaped)
            }
            cs.endText()
        }
        return form
    }

    /** nitro-fillsign2 rounded rectangle (the shape appearance of main). */
    private fun roundShapeAppearance(doc: PDDocument, rect: PDRectangle, a: Annot): PDAppearanceStream {
        val form = newForm(doc, rect)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a); setColors(cs, a.color); cs.setLineWidth(a.strokeWidth)
            val w = rect.width; val h = rect.height; val i = a.strokeWidth / 2
            if (a.kind == AnnotKind.RECT && a.corner > 0f) {
                // nitro-fillsign2: rounded rectangle
                RoundRect.path(i, i, w - 2 * i, h - 2 * i, a.corner).forEach { s ->
                    when (s) {
                        is RoundRect.Seg.Move -> cs.moveTo(s.x, s.y)
                        is RoundRect.Seg.Line -> cs.lineTo(s.x, s.y)
                        is RoundRect.Seg.Curve -> cs.curveTo(s.x1, s.y1, s.x2, s.y2, s.x3, s.y3)
                    }
                }
                cs.closePath(); cs.stroke()
            } else if (a.kind == AnnotKind.RECT) { cs.addRect(i, i, w - 2 * i, h - 2 * i); cs.stroke() }
            else {
                val k = 0.5523f; val rx = (w - 2 * i) / 2; val ry = (h - 2 * i) / 2; val cx = w / 2; val cy = h / 2
                cs.moveTo(cx + rx, cy)
                cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
                cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
                cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
                cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
                cs.closePath(); cs.stroke()
            }
        }
        return form
    }

    /** Read existing annotations of a file for the annotations panel (title, contents, page, kind). */
    data class Existing(val page: Int, val subtype: String, val author: String, val contents: String, val rect: RectF, val index: Int,
                        /** Non-null for a video annotation (Screen / RichMedia / video attachment): played in the page. */
                        val video: com.smartdocs.editor.core.annot.VideoPdf.Info? = null,
                        /** /F Locked: cannot be moved, resized or deleted until unlocked (section 7.58). */
                        val locked: Boolean = false,
                        /** /Subj, for tickmarks and the properties dialog. */
                        val subject: String = "")

    suspend fun list(src: File, password: String? = null): List<Existing> = withContext(Dispatchers.IO) {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)
        doc.use { d ->
            val out = ArrayList<Existing>()
            d.pages.forEachIndexed { pi, page ->
                // a single malformed annotation (missing /Rect, bad dictionary) must not hide the whole list
                val list = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                list.forEachIndexed { ai, an ->
                    runCatching {
                        if (an.subtype == "Link" || an.subtype == "Popup" || an.subtype == "Widget") return@forEachIndexed
                        // replies and review states are hidden children of another comment: not selectable on the page
                        if (com.smartdocs.editor.core.annot.Annot2Writer.isReplyOrState(an)) return@forEachIndexed
                        val r = an.rectangle ?: PDRectangle()
                        out += Existing(pi, an.subtype ?: "", (an as? PDAnnotationMarkup)?.titlePopup ?: an.cosObject.getString(COSName.T) ?: "", an.contents ?: "",
                            RectF(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY), ai, com.smartdocs.editor.core.annot.VideoPdf.detect(an),
                            com.smartdocs.editor.core.annot.AnnotStyle.isLocked(an.cosObject), com.smartdocs.editor.core.annot.AnnotStyle.subjectOf(an.cosObject))
                    }
                }
            }
            out
        }
    }

    /**
     * Read stored annotation [index] of [page] back into an in-memory [Annot] so it can be edited and
     * re-committed. Returns null for kinds the editor cannot rewrite (stamps, widgets, attachments, ...).
     * Rects use the editor's convention (PDF space, top = larger y).
     */
    suspend fun extract(src: File, page: Int, index: Int, password: String? = null, media: Boolean = true): Annot? = withContext(Dispatchers.IO) {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)
        doc.use { d ->
            if (page !in 0 until d.numberOfPages) return@use null
            val list = runCatching { d.getPage(page).annotations }.getOrNull() ?: return@use null
            val an = list.getOrNull(index) ?: return@use null
            runCatching { toAnnot(an, page, media, runCatching { d.getPage(page).rotation }.getOrDefault(0))?.copy(nm = an.annotationName) }.getOrNull()
        }
    }

    private fun name(n: String): COSName = COSName.getPDFName(n)

    private fun floats(cos: com.tom_roush.pdfbox.cos.COSDictionary, key: String): FloatArray? =
        (cos.getDictionaryObject(name(key)) as? COSArray)?.let { arr ->
            FloatArray(arr.size()) { i -> (arr.getObject(i) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 0f }
        }

    private fun colorOf(c: PDColor?): Int? {
        val v = c?.components ?: return null
        fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return when (v.size) {
            1 -> b(v[0]).let { (it shl 16) or (it shl 8) or it }
            3 -> (b(v[0]) shl 16) or (b(v[1]) shl 8) or b(v[2])
            4 -> { val k = 1f - v[3]; (b((1f - v[0]) * k) shl 16) or (b((1f - v[1]) * k) shl 8) or b((1f - v[2]) * k) }
            else -> null
        }
    }

    /** [media] false: a video's embedded file is not extracted (selection preview only; the copy needs it). */
    internal fun toAnnot(an: PDAnnotation, page: Int, media: Boolean = true, pageRotation: Int = 0): Annot? {
        val cos = an.cosObject
        val sub = an.subtype ?: return null
        val r = an.rectangle ?: return null
        val rect = RectF(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY)
        val color = colorOf(runCatching { an.color }.getOrNull())
        val opacity = cos.getFloat(name("CA"), 1f).coerceIn(0.05f, 1f)
        val author = cos.getString(name("T")) ?: ""
        val contents = an.contents ?: ""
        val width = (cos.getDictionaryObject(name("BS")) as? com.tom_roush.pdfbox.cos.COSDictionary)?.getFloat(name("W"), 1f)
            ?: floats(cos, "Border")?.getOrNull(2) ?: 1f
        val st = com.smartdocs.editor.core.annot.AnnotStyle
        val base = Annot(kind = AnnotKind.RECT, page = page, color = color ?: 0xF44336, opacity = opacity, strokeWidth = width.coerceAtLeast(0.5f),
            author = author, text = contents, subject = st.subjectOf(cos), dash = st.dashIndexOf(cos), noBorder = st.isNoBorder(cos),
            locked = st.isLocked(cos))
        return when (sub) {
            "Highlight", "Underline", "StrikeOut", "Squiggly" -> {
                val kind = when (sub) { "Highlight" -> AnnotKind.HIGHLIGHT; "Underline" -> AnnotKind.UNDERLINE; "StrikeOut" -> AnnotKind.STRIKEOUT; else -> AnnotKind.SQUIGGLY }
                val q = floats(cos, "QuadPoints")
                val rects = if (q != null && q.size >= 8) (0 until q.size / 8).map { i ->
                    val xs = floatArrayOf(q[i * 8], q[i * 8 + 2], q[i * 8 + 4], q[i * 8 + 6]); val ys = floatArrayOf(q[i * 8 + 1], q[i * 8 + 3], q[i * 8 + 5], q[i * 8 + 7])
                    RectF(xs.min(), ys.max(), xs.max(), ys.min())
                } else listOf(rect)
                base.copy(kind = kind, rects = rects, color = color ?: if (kind == AnnotKind.HIGHLIGHT) 0xFFEB3B else 0xF44336)
            }
            "Ink" -> {
                val ink = cos.getDictionaryObject(COSName.INKLIST) as? COSArray ?: return null
                val paths = (0 until ink.size()).mapNotNull { i ->
                    val arr = ink.getObject(i) as? COSArray ?: return@mapNotNull null
                    val f = FloatArray(arr.size()) { k -> (arr.getObject(k) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 0f }
                    (0 until f.size / 2).map { k -> f[k * 2] to f[k * 2 + 1] }.takeIf { it.isNotEmpty() }
                }
                if (paths.isEmpty()) null else base.copy(kind = AnnotKind.INK, paths = paths)
            }
            "Line" -> {
                val l = floats(cos, "L")?.takeIf { it.size >= 4 } ?: return null
                val le = (cos.getDictionaryObject(name("LE")) as? COSArray)?.let { a -> (0 until a.size()).map { (a.getObject(it) as? COSName)?.name ?: "" } } ?: emptyList()
                fun arrow(s: String?) = s == "OpenArrow" || s == "ClosedArrow"
                var p1 = l[0] to l[1]; var p2 = l[2] to l[3]
                val endArrow = arrow(le.getOrNull(1)); val startArrow = arrow(le.getOrNull(0))
                // the legacy arrow-at-start case is normalised to an end arrow; any other pair of endings is kept as is
                val simple = le.all { it == "None" || it == "OpenArrow" || it == "ClosedArrow" || it.isBlank() }
                val swap = simple && startArrow && !endArrow
                if (swap) { val t = p1; p1 = p2; p2 = t }
                val ends = if (swap) "None" to (le.getOrNull(0) ?: "OpenArrow") else (le.getOrNull(0) ?: "None") to (le.getOrNull(1) ?: "None")
                base.copy(kind = if (startArrow || endArrow) AnnotKind.ARROW else AnnotKind.LINE, points = listOf(p1, p2), lineStart = ends.first, lineEnd = ends.second,
                    caption = cos.getBoolean(name("Cap"), false))
            }
            "Square", "Circle" -> {
                val rd = floats(cos, "RD")
                val box = if (rd != null && rd.size >= 4) RectF(rect.left + rd[0], rect.top - rd[1], rect.right - rd[2], rect.bottom + rd[3]) else rect
                if (sub == "Circle" && base.subject == st.TICKMARK)
                    return base.copy(kind = AnnotKind.OVAL, rects = listOf(rect), text = cos.getString(name("SDTick")) ?: contents, fillColor = st.interiorOf(cos))
                base.copy(kind = if (sub == "Square") AnnotKind.RECT else AnnotKind.OVAL, rects = listOf(box), fillColor = st.interiorOf(cos),
                    cloud = st.cloudyOf(cos), cloudInward = st.cloudInwardOf(cos),
                    corner = if (sub == "Square") cos.getFloat(FillSignKeys.ROUND, 0f).coerceAtLeast(0f) else 0f)
            }
            // nitro-fillsign2: our image stamps (placed signatures above all) become editable again
            "Stamp" -> readImageStamp(an, page, base, rect, pageRotation) ?: com.smartdocs.editor.core.annot.Annot2Writer.read(an, page, base, rect, media)
            "FreeText" -> {
                val da = cos.getString(COSName.DA) ?: ""
                val size = Regex("""([0-9.]+)\s+Tf""").find(da)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 1f } ?: 12f
                val rgb = Regex("""([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+rg""").find(da)?.groupValues?.let { g ->
                    colorOf(PDColor(floatArrayOf(g[1].toFloat(), g[2].toFloat(), g[3].toFloat()), PDDeviceRGB.INSTANCE))
                } ?: Regex("""([0-9.]+)\s+g(\s|$)""").find(da)?.groupValues?.get(1)?.toFloatOrNull()?.let { v -> colorOf(PDColor(floatArrayOf(v), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE)) }
                val stamp = cos.getNameAsString(name("IT")) == "FreeTextStamp"
                val rich = if (stamp) null else st.readRich(cos)
                base.copy(kind = if (stamp) AnnotKind.STAMP_TEXT else AnnotKind.FREETEXT, rects = listOf(rect), fontSize = size, color = rgb ?: 0x212121,
                    bold = rich?.bold ?: false, italic = rich?.italic ?: false, underline = rich?.underline ?: false,
                    strike = rich?.strike ?: false, fontFamily = rich?.family ?: 0, align = rich?.align ?: -1, lineSpacing = rich?.lineHeight ?: 1.3f,
                    charSpacing = rich?.letterSpacing ?: 0f, wordSpacing = rich?.wordSpacing ?: 0f, bullets = rich?.bullets ?: false, script = rich?.script ?: 0,
                    text = rich?.takeIf { it.bullets }?.let { contents.split('\n').joinToString("\n") { l -> l.removePrefix("\u2022 ") } } ?: contents,
                    comb = if (stamp) 0 else cos.getInt(FillSignKeys.COMB, 0).coerceIn(0, CombLayout.MAX_CELLS))
            }
            "Text" -> base.copy(kind = AnnotKind.NOTE, rects = listOf(RectF(rect.left, rect.top, rect.left + 20f, rect.top - 20f)), color = color ?: 0xFFEB3B,
                icon = st.noteIconOf(cos).takeIf { it != "Comment" } ?: "")
            else -> com.smartdocs.editor.core.annot.Annot2Writer.read(an, page, base, rect, media)
        }
    }

    /** A saved image stamp of ours as a pending STAMP_IMAGE (image, rect; a rotated appearance is turned into the image). */
    private fun readImageStamp(an: PDAnnotation, page: Int, base: Annot, rect: RectF, pageRotation: Int = 0): Annot? {
        val img = StampRead.imageOf(an) ?: return null
        var bmp = runCatching { img.image }.getOrNull() ?: return null
        val m = runCatching { an.normalAppearanceStream.matrix }.getOrNull()
        // tab audit D9: the reader shows the image upright on the turned page, so the page's /Rotate counts too
        val rot = StampRead.displayRotation(if (m == null) 0 else StampRead.rotationOf(m.scaleX, m.shearY), pageRotation)
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
        val ref = an.cosObject.getString(StampRead.SIG_REF)?.takeIf { it.isNotBlank() }
            ?: if (an.cosObject.getBoolean(FillSignKeys.SIG, false)) "" else null      // signature from before /SDSigRef: still a signature
        return base.copy(kind = AnnotKind.STAMP_IMAGE, rects = listOf(rect), image = bmp, sigRef = ref, color = base.color)
    }

    suspend fun delete(src: File, page: Int, index: Int, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "delete: out must differ from src" }
        saveChanged(src, out, password) { d ->
            if (page in 0 until d.numberOfPages) {
                val p = d.getPage(page)
                if (index in p.annotations.indices) com.smartdocs.editor.core.annot.AnnotStyle.removeWithPopups(p, listOf(index))
            }
            Unit to listOf(page)
        }
    }
}

/**
 * audit 5 C-6: the text frame of one text-markup rectangle ([minX]..[maxX] × [minY]..[maxY], user space) on a page
 * shown with /Rotate [rotation]: origin at the lower-left corner as the text is read, u = along the text (length [w]),
 * v = towards the top of the text (length [h]). Pure (no Android types), see MarkupFrameTest.
 */
internal class MarkupFrame(val ox: Float, val oy: Float, val ux: Float, val uy: Float, val vx: Float, val vy: Float, val w: Float, val h: Float) {
    /** User-space point at (u along the text, v up from its lower edge). */
    fun point(u: Float, v: Float): Pair<Float, Float> = (ox + u * ux + v * vx) to (oy + u * uy + v * vy)

    /** QuadPoints of the rectangle: upper-left, upper-right, lower-left, lower-right (in reading orientation). */
    fun quad(): FloatArray {
        val (ulx, uly) = point(0f, h); val (urx, ury) = point(w, h)
        val (llx, lly) = point(0f, 0f); val (lrx, lry) = point(w, 0f)
        return floatArrayOf(ulx, uly, urx, ury, llx, lly, lrx, lry)
    }

    companion object {
        fun of(minX: Float, minY: Float, maxX: Float, maxY: Float, rotation: Int): MarkupFrame {
            val x0 = minOf(minX, maxX); val x1 = maxOf(minX, maxX); val y0 = minOf(minY, maxY); val y1 = maxOf(minY, maxY)
            val dx = x1 - x0; val dy = y1 - y0
            // /Rotate turns the page clockwise for display: 90 → user +y reads to the right, user −x is up
            return when (((rotation % 360) + 360) % 360 / 90 * 90) {
                90 -> MarkupFrame(x1, y0, 0f, 1f, -1f, 0f, dy, dx)
                180 -> MarkupFrame(x1, y1, -1f, 0f, 0f, -1f, dx, dy)
                270 -> MarkupFrame(x0, y1, 0f, -1f, 1f, 0f, dy, dx)
                else -> MarkupFrame(x0, y0, 1f, 0f, 0f, 1f, dx, dy)
            }
        }
    }
}
