package com.smartdocs.editor.core.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The page changed since it was analysed (another edit happened): the UI should reload the page model. */
class StalePageException : IllegalStateException("page changed")

/**
 * Real content editing (not annotations): every operation reads [src], rewrites the page content stream and
 * writes the result to [out] (never to [src]). Coordinates are display space (see [PageFrame]).
 */
object ContentEdit {
    private const val MARGIN = 18f

    private suspend fun <T> edit(src: File, out: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc -> val r = block(doc); EditIo.save(doc, out); r }
    }

    private fun findBlock(a: PageAnalysis, ops: IntArray): TextBlock =
        a.model.blocks.firstOrNull { it.ops.contentEquals(ops) } ?: throw StalePageException()

    private fun findImage(a: PageAnalysis, op: Int): RawImage =
        a.scanner.images.firstOrNull { it.op == op } ?: throw StalePageException()

    // ---------------- text ----------------

    /**
     * Replace the text block drawn by [ops] on [page] with [text] in [style]. An empty [text] deletes the block.
     * A one-line block may grow sideways (to the page margin); a paragraph is re-wrapped to its width.
     */
    suspend fun replaceText(context: Context, src: File, out: File, password: String?, page: Int, ops: IntArray, text: String, style: EditTextStyle) =
        edit(src, out, password) { doc ->
            val a = PageTextModel.analyze(doc, page)
            val block = findBlock(a, ops)
            // NVE-text: rotated rows and form XObject text are rewritten in their own frame / stream
            if (block.angle != 0f || block.inForm) { TextEdit5.replaceIn(context, doc, page, a, block, text, style); return@edit }
            val p = doc.getPage(page)
            val rw = ContentRewriter(doc, p)
            block.ops.forEach { rw.removeText(it, a.scanner.textOps[it]) }
            rw.commit()
            if (text.isNotBlank()) {
                val original = a.fonts[block.id]?.takeIf { block.fontReusable && style.bold == block.bold && style.italic == block.italic && style.family == block.family }
                val st = style.copy(lineHeight = style.lineHeight ?: if (block.lineCount > 1) block.lineHeight * (style.size / block.size) else null)
                val (left, right) = if (block.lineCount > 1) block.rect.left to block.rect.right
                    else when (style.align) {
                        TextAlign.LEFT -> block.rect.left to (a.frame.width - MARGIN).coerceAtLeast(block.rect.right)
                        TextAlign.RIGHT -> MARGIN.coerceAtMost(block.rect.left) to block.rect.right
                        TextAlign.CENTER, TextAlign.JUSTIFY -> block.rect.left to block.rect.right   // NVE-text: justify
                    }
                // keep the baseline of the first line
                writeText(context, doc, p, a.frame, original, text, st, left, right, block.firstBaseline, block.rtl)
            }
        }

    /** Add new text as page content. [box]: left/right bound the lines, top is the top of the first line. */
    suspend fun addText(context: Context, src: File, out: File, password: String?, page: Int, text: String, style: EditTextStyle, box: RectF) =
        edit(src, out, password) { doc ->
            if (text.isBlank()) return@edit
            val p = doc.getPage(page)
            val frame = PageFrame.of(p)
            val rtl = text.firstOrNull { it.isLetter() }?.let { PageTextModel.isArabicChar(it) } ?: false
            writeText(context, doc, p, frame, null, text, style, box.left, box.right, box.top - style.size * 0.85f, rtl)
        }

    /** Delete the text block drawn by [ops]. */
    suspend fun deleteText(src: File, out: File, password: String?, page: Int, ops: IntArray) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val block = findBlock(a, ops)
        if (block.inForm) { TextEdit5.removeOps(doc, page, a, block.ops); return@edit }      // NVE-text: form XObject text
        val rw = ContentRewriter(doc, doc.getPage(page))
        block.ops.forEach { rw.removeText(it, a.scanner.textOps[it]) }
        rw.commit()
    }

    private fun writeText(context: Context, doc: PDDocument, page: PDPage, frame: PageFrame, original: com.tom_roush.pdfbox.pdmodel.font.PDFont?,
                          text: String, style: EditTextStyle, left: Float, right: Float, baseline: Float, defaultRtl: Boolean) {
        // NVE-text: rich text / advanced formatting / custom fonts / embedding choice
        if (RichText.isRich(style)) { TextEdit5.writeRich(context, doc, page, frame, original, text, style, left, right, baseline, defaultRtl); return }
        val fonts = FontSet(context, doc, original, style)
        val layout = TextLayout(fonts, style)
        val lines = layout.layout(text, (right - left).coerceAtLeast(style.size * 2), defaultRtl)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(frame.toUser)
            layout.draw(cs, lines, left, right, baseline)
            cs.restoreGraphicsState()
        }
    }

    // ---------------- images ----------------

    /** Remove the image painted by operator [op]. */
    suspend fun deleteImage(src: File, out: File, password: String?, page: Int, op: Int) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        findImage(a, op)
        val rw = ContentRewriter(doc, doc.getPage(page))
        rw.remove(op)
        rw.commit()
    }

    /**
     * Move / resize / rotate the image of operator [op] by [displayAffine] (6 values, display space, applied
     * after the current placement). Implemented as `q <m> cm /Im Do Q` around the original operator.
     */
    suspend fun transformImage(src: File, out: File, password: String?, page: Int, op: Int, displayAffine: FloatArray) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val img = findImage(a, op)
        val p = img.ctm
        val inner = Affine.mul(Affine.mul(a.frame.toDisplay, Affine.fromValues(displayAffine)), a.frame.toUser)
        val n = Affine.mul(Affine.mul(p, inner), Affine.inverse(p))
        val rw = ContentRewriter(doc, doc.getPage(page))
        rw.wrapWithMatrix(op, n)
        rw.commit()
    }

    /** Replace the image of operator [op] by [bitmap], aspect-fit inside the old placement. */
    suspend fun replaceImage(src: File, out: File, password: String?, page: Int, op: Int, bitmap: Bitmap) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val img = findImage(a, op)
        val p = doc.getPage(page)
        val xo = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
        val res = p.resources ?: PDResources().also { p.resources = it }
        val name: COSName = res.add(xo, "Im")
        // aspect-fit in image space (the unit square of the old placement)
        val v = Affine.values(img.ctm)
        val boxW = kotlin.math.hypot(v[0], v[1]); val boxH = kotlin.math.hypot(v[2], v[3])
        val boxAr = if (boxH > 0f) boxW / boxH else 1f
        val ar = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val fit = if (ar > boxAr) { val s = boxAr / ar; Affine.mul(Affine.scale(1f, s), Affine.translate(0f, (1f - s) / 2f)) }
                  else { val s = ar / boxAr; Affine.mul(Affine.scale(s, 1f), Affine.translate((1f - s) / 2f, 0f)) }
        val rw = ContentRewriter(doc, p)
        rw.wrapWithMatrix(op, fit, name)
        rw.commit()
    }

    /** Insert [bitmap] as page content into [rect] (display space). */
    suspend fun addImage(src: File, out: File, password: String?, page: Int, bitmap: Bitmap, rect: RectF) = edit(src, out, password) { doc ->
        val p = doc.getPage(page)
        val frame = PageFrame.of(p)
        val xo = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
        PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(frame.toUser)
            cs.drawImage(xo, rect.left, rect.bottom, rect.width(), rect.top - rect.bottom)
            cs.restoreGraphicsState()
        }
    }

    /** The bitmap of the image painted by operator [op] (for export / share). */
    suspend fun imageBitmap(src: File, password: String?, page: Int, op: Int): Bitmap? = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc ->
            val a = PageTextModel.analyze(doc, page)
            a.scanner.images.firstOrNull { it.op == op }?.let { runCatching { it.image.image }.getOrNull() }
        }
    }
}
