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
)

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
     * possible (certified without annotation rights, or the update failed) — the caller should offer a copy.
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
            // [fullSave]: a separate copy ("save a copy" of a signed file) — an ordinary save, the signatures of the copy
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
                val quads = FloatArray(rects.size * 8)
                rects.forEachIndexed { i, r ->
                    val o = i * 8
                    quads[o] = r.left; quads[o + 1] = r.bottom; quads[o + 2] = r.right; quads[o + 3] = r.bottom
                    quads[o + 4] = r.left; quads[o + 5] = r.top; quads[o + 6] = r.right; quads[o + 7] = r.top
                }
                // PDF quad order: upper-left, upper-right, lower-left, lower-right
                rects.forEachIndexed { i, r ->
                    val o = i * 8
                    quads[o] = r.left; quads[o + 1] = r.top; quads[o + 2] = r.right; quads[o + 3] = r.top
                    quads[o + 4] = r.left; quads[o + 5] = r.bottom; quads[o + 6] = r.right; quads[o + 7] = r.bottom
                }
                m.quadPoints = quads
                m.rectangle = union(rects)
                m.color = rgb(a.color); m.constantOpacity = a.opacity
                m.contents = a.text; m.titlePopup = a.author; m.setCreationDate(now); m.setModifiedDate(now)
                m.appearance = apDict(markupAppearance(doc, m.rectangle, rects, a))
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
                    a.fontSize, ((a.color shr 16) and 0xFF) / 255f, ((a.color shr 8) and 0xFF) / 255f, (a.color and 0xFF) / 255f))
                if (a.kind == AnnotKind.STAMP_TEXT) ft.cosObject.setName(name("IT"), "FreeTextStamp")
                else com.smartdocs.editor.core.annot.AnnotStyle.applyRich(ft.cosObject, a.text,
                    com.smartdocs.editor.core.annot.AnnotStyle.Rich(a.fontSize, a.color, a.bold, a.italic, a.underline), ArabicShaper.isArabic(a.text))
                ft.appearance = apDict(textAppearance(context, doc, rect, a))
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
                line.contents = a.text; line.titlePopup = a.author; line.setCreationDate(now); line.setModifiedDate(now)
                line.appearance = apDict(lineAppearance(doc, rect, a))
                annotations.add(line)
            }
            AnnotKind.RECT, AnnotKind.OVAL -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                val sq = PDAnnotationSquareCircle(if (a.kind == AnnotKind.RECT) PDAnnotationSquareCircle.SUB_TYPE_SQUARE else PDAnnotationSquareCircle.SUB_TYPE_CIRCLE)
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                sq.rectangle = rect
                sq.color = rgb(a.color); sq.constantOpacity = a.opacity
                com.smartdocs.editor.core.annot.AnnotStyle.applyBorder(sq.cosObject, a.strokeWidth, a.dash, a.noBorder)
                com.smartdocs.editor.core.annot.AnnotStyle.setInterior(sq.cosObject, a.fillColor)
                if (a.kind == AnnotKind.RECT && a.cloud > 0) com.smartdocs.editor.core.annot.AnnotStyle.setCloudy(sq.cosObject, a.cloud)
                sq.contents = a.text; sq.titlePopup = a.author; sq.setCreationDate(now); sq.setModifiedDate(now)
                sq.appearance = apDict(com.smartdocs.editor.core.annot.AnnotStyle.shapeStream(doc, rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height,
                    a.kind == AnnotKind.OVAL, a.color, a.fillColor, a.strokeWidth, a.dash, a.noBorder, a.opacity, if (a.kind == AnnotKind.RECT) a.cloud else 0))
                annotations.add(sq)
            }
            AnnotKind.STAMP_IMAGE -> {
                val r = a.rects.firstOrNull()?.let(::norm) ?: return
                val bmp = a.image ?: return
                val st = PDAnnotationRubberStamp()
                val rect = PDRectangle(r.left, r.top, r.width(), r.height())
                st.rectangle = rect; st.name = "Image"; st.constantOpacity = a.opacity
                st.titlePopup = a.author; st.setCreationDate(now); st.setModifiedDate(now)
                val form = newForm(doc, rect)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, form).use { cs -> cs.drawImage(img, 0f, 0f, rect.width, rect.height) }
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

    private fun markupAppearance(doc: PDDocument, bounds: PDRectangle, rects: List<RectF>, a: Annot): PDAppearanceStream {
        val form = newForm(doc, bounds)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a, if (a.kind == AnnotKind.HIGHLIGHT) BlendMode.MULTIPLY else null)
            setColors(cs, a.color)
            rects.forEach { r ->
                // rects are normalised with top = smaller y, so the lower edge in PDF space is min(top, bottom)
                val x = r.left - bounds.lowerLeftX; val y = minOf(r.top, r.bottom) - bounds.lowerLeftY
                when (a.kind) {
                    AnnotKind.HIGHLIGHT -> { cs.addRect(x, y, r.width(), r.height()); cs.fill() }
                    AnnotKind.UNDERLINE -> { cs.setLineWidth(1.2f); cs.moveTo(x, y + 1f); cs.lineTo(x + r.width(), y + 1f); cs.stroke() }
                    AnnotKind.STRIKEOUT -> { cs.setLineWidth(1.2f); cs.moveTo(x, y + r.height() / 2); cs.lineTo(x + r.width(), y + r.height() / 2); cs.stroke() }
                    else -> { cs.setLineWidth(1f); var xx = x; var up = true; cs.moveTo(xx, y); while (xx < x + r.width()) { xx += 2.5f; cs.lineTo(xx, if (up) y + 2.5f else y); up = !up }; cs.stroke() }
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
            cs.moveTo(5f, 13f); cs.lineTo(15f, 13f); cs.moveTo(5f, 9f); cs.lineTo(15f, 9f); cs.moveTo(5f, 5f); cs.lineTo(11f, 5f); cs.stroke()
        }
        return form
    }

    private fun textAppearance(context: Context, doc: PDDocument, rect: PDRectangle, a: Annot): PDAppearanceStream {
        val form = newForm(doc, rect)
        val font = PdfOps.arabicFont(context, doc)
        PDPageContentStream(doc, form).use { cs ->
            alpha(cs, a)
            if (a.kind == AnnotKind.STAMP_TEXT) {
                setColors(cs, a.color); cs.setLineWidth(2f)
                cs.addRect(1f, 1f, rect.width - 2, rect.height - 2); cs.stroke()
            }
            cs.beginText(); cs.setFont(font, a.fontSize); setColors(cs, a.color)
            val lines = a.text.split('\n')
            val rtl = ArabicShaper.isArabic(a.text)
            var y = rect.height - a.fontSize - 4f
            lines.forEach { line ->
                val shaped = ArabicShaper.shape(line)
                val w = runCatching { font.getStringWidth(shaped) / 1000f * a.fontSize }.getOrDefault(0f)
                val x = if (rtl) (rect.width - w - 4f).coerceAtLeast(2f) else 4f
                cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, y))
                cs.showArabic(line, shaped)
                y -= a.fontSize * 1.3f
            }
            cs.endText()
        }
        return form
    }

    private fun dashOf(a: Annot): FloatArray? = com.smartdocs.editor.core.view5.DashStyle.entries.getOrElse(a.dash) { com.smartdocs.editor.core.view5.DashStyle.SOLID }.scaled(a.strokeWidth)

    private fun lineAppearance(doc: PDDocument, rect: PDRectangle, a: Annot): PDAppearanceStream {
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
        }
        return form
    }

    /** Read existing annotations of a file for the annotations panel (title, contents, page, kind). */
    data class Existing(val page: Int, val subtype: String, val author: String, val contents: String, val rect: RectF, val index: Int)

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
                        out += Existing(pi, an.subtype ?: "", (an as? PDAnnotationMarkup)?.titlePopup ?: "", an.contents ?: "", RectF(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY), ai)
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
    suspend fun extract(src: File, page: Int, index: Int, password: String? = null): Annot? = withContext(Dispatchers.IO) {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)
        doc.use { d ->
            if (page !in 0 until d.numberOfPages) return@use null
            val list = runCatching { d.getPage(page).annotations }.getOrNull() ?: return@use null
            val an = list.getOrNull(index) ?: return@use null
            runCatching { toAnnot(an, page)?.copy(nm = an.annotationName) }.getOrNull()
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

    internal fun toAnnot(an: PDAnnotation, page: Int): Annot? {
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
                base.copy(kind = if (startArrow || endArrow) AnnotKind.ARROW else AnnotKind.LINE, points = listOf(p1, p2), lineStart = ends.first, lineEnd = ends.second)
            }
            "Square", "Circle" -> {
                val rd = floats(cos, "RD")
                val box = if (rd != null && rd.size >= 4) RectF(rect.left + rd[0], rect.top - rd[1], rect.right - rd[2], rect.bottom + rd[3]) else rect
                base.copy(kind = if (sub == "Square") AnnotKind.RECT else AnnotKind.OVAL, rects = listOf(box), fillColor = st.interiorOf(cos),
                    cloud = if (sub == "Square") st.cloudyOf(cos) else 0)
            }
            "FreeText" -> {
                val da = cos.getString(COSName.DA) ?: ""
                val size = Regex("""([0-9.]+)\s+Tf""").find(da)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 1f } ?: 12f
                val rgb = Regex("""([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+rg""").find(da)?.groupValues?.let { g ->
                    colorOf(PDColor(floatArrayOf(g[1].toFloat(), g[2].toFloat(), g[3].toFloat()), PDDeviceRGB.INSTANCE))
                } ?: Regex("""([0-9.]+)\s+g(\s|$)""").find(da)?.groupValues?.get(1)?.toFloatOrNull()?.let { v -> colorOf(PDColor(floatArrayOf(v), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE)) }
                val stamp = cos.getNameAsString(name("IT")) == "FreeTextStamp"
                val rich = if (stamp) null else st.readRich(cos)
                base.copy(kind = if (stamp) AnnotKind.STAMP_TEXT else AnnotKind.FREETEXT, rects = listOf(rect), fontSize = size, color = rgb ?: 0x212121,
                    bold = rich?.bold ?: false, italic = rich?.italic ?: false, underline = rich?.underline ?: false)
            }
            "Text" -> base.copy(kind = AnnotKind.NOTE, rects = listOf(RectF(rect.left, rect.top, rect.left + 20f, rect.top - 20f)), color = color ?: 0xFFEB3B,
                icon = st.noteIconOf(cos).takeIf { it != "Comment" } ?: "")
            else -> com.smartdocs.editor.core.annot.Annot2Writer.read(an, page, base, rect)
        }
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
