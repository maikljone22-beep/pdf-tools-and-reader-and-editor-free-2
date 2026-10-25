package com.smartdocs.editor.core.ai5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.AnswerPdf
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A text block of a page: its text and bounds in PDF space (left, top = higher y, right, bottom). */
data class TextBlock(val id: Int, val page: Int, val text: String, val rect: RectF, val fontSize: Float)

enum class TranslateMode { OVERLAY, TEXT_ONLY }

/**
 * Whole-document translation that keeps the layout: every text block is covered with a white box and its
 * translation is drawn in the same place (wrapped and shrunk to fit, right-aligned for Arabic). Blocks are sent
 * in batches of about [BATCH_CHARS] characters as `{"id":"text"}` JSON, so a 30-page document costs ~10 requests.
 * Text inside images is not translated (it would need OCR first).
 */
object DocTranslator {
    const val BATCH_CHARS = 5000

    /** Blocks of one page from PDFium char boxes: chars → lines (break on newline / vertical jump) → blocks (close lines). */
    fun blocks(page: Int, text: String, boxes: List<RectF>, startId: Int): List<TextBlock> {
        data class Line(val sb: StringBuilder, val r: RectF)
        val lines = ArrayList<Line>()
        var cur: Line? = null
        for (i in text.indices) {
            val c = text[i]
            val b = boxes.getOrNull(i)
            if (c == '\n' || c == '\r') { cur = null; continue }
            if (b == null || (b.width() <= 0f && b.height() == 0f)) { cur?.sb?.append(c); continue }
            val hi = maxOf(b.top, b.bottom); val lo = minOf(b.top, b.bottom)
            val l = cur
            if (l == null || (lo + hi) / 2f !in (l.r.bottom - 1f)..(l.r.top + 1f)) {
                cur = Line(StringBuilder().append(c), RectF(b.left, hi, b.right, lo)); lines += cur!!
            } else {
                l.sb.append(c); l.r.left = minOf(l.r.left, b.left); l.r.right = maxOf(l.r.right, b.right)
                l.r.top = maxOf(l.r.top, hi); l.r.bottom = minOf(l.r.bottom, lo)
            }
        }
        val out = ArrayList<TextBlock>()
        var bt: StringBuilder? = null; var br: RectF? = null; var lh = 0f; var n = 0
        fun flush() {
            val t = bt?.toString()?.replace(Regex("\\s+"), " ")?.trim(); val r = br
            if (!t.isNullOrEmpty() && r != null && t.any { it.isLetter() }) out += TextBlock(startId + out.size, page, t, r, (lh / n.coerceAtLeast(1)).coerceIn(5f, 40f))
            bt = null; br = null; lh = 0f; n = 0
        }
        for (l in lines) {
            val h = (l.r.top - l.r.bottom).coerceAtLeast(1f)
            val r = br
            val close = r != null && (r.bottom - l.r.top) < h * 1.1f && (r.bottom - l.r.top) > -h * 0.5f &&
                l.r.left < r.right && l.r.right > r.left && kotlin.math.abs(h - lh / n.coerceAtLeast(1)) < h * 0.4f
            if (!close) flush()
            val sb = bt
            val rr = br
            if (sb == null || rr == null) { bt = StringBuilder().append(l.sb); br = RectF(l.r) }
            else {
                sb.append(' ').append(l.sb)
                rr.left = minOf(rr.left, l.r.left); rr.right = maxOf(rr.right, l.r.right)
                rr.top = maxOf(rr.top, l.r.top); rr.bottom = minOf(rr.bottom, l.r.bottom)
            }
            lh += h; n++
        }
        flush()
        return out
    }

    /** Batches of blocks under [BATCH_CHARS] characters each. */
    fun batches(blocks: List<TextBlock>): List<List<TextBlock>> {
        val out = ArrayList<List<TextBlock>>(); var cur = ArrayList<TextBlock>(); var len = 0
        blocks.forEach { b -> if (cur.isNotEmpty() && len + b.text.length > BATCH_CHARS) { out += cur; cur = ArrayList(); len = 0 }; cur += b; len += b.text.length }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    suspend fun readBlocks(ctx: Context, src: File, password: String?, pages: List<Int>?): List<TextBlock> = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try {
            val list = ArrayList<TextBlock>()
            (pages ?: (0 until s.pageCount).toList()).filter { it in 0 until s.pageCount }.forEach { p ->
                list += blocks(p, s.text(p), s.charBoxes(p), list.size)
            }
            list
        } finally { s.closeAsync() }
    }

    /** Requests a whole translation would cost (for the quota check before starting). */
    suspend fun estimate(ctx: Context, src: File, password: String?, pages: List<Int>?): Int = batches(readBlocks(ctx, src, password, pages)).size

    suspend fun translate(
        ctx: Context, client: AiClient, src: File, password: String?, targetLang: String, mode: TranslateMode, out: File,
        pages: List<Int>? = null, progress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val blocks = readBlocks(ctx, src, password, pages)
        if (blocks.isEmpty()) throw IllegalStateException("no text")
        val groups = batches(blocks)
        if (!AiCalls.fits(ctx, client, groups.size)) throw AiQuotaException("need ${groups.size}")
        val tr = HashMap<Int, String>()
        groups.forEachIndexed { gi, g ->
            progress(gi, groups.size)
            val payload = buildJsonObject { g.forEach { put(it.id.toString(), it.text) } }
            val answer = AiCalls.complete(ctx, client, listOf(
                ChatMessage("system", AiCalls.SYSTEM),
                ChatMessage("user", "ترجم قيم كائن JSON التالي إلى ${AiLang.name(targetLang)}. احتفظ بالمفاتيح كما هي، وترجم كل قيمة ترجمة دقيقة وطبيعية، " +
                    "ولا تترجم الأرقام أو الرموز أو أسماء العلم الأجنبية الشائعة. أعد JSON فقط بالمفاتيح نفسها.\n\n$payload"),
            ))
            (AiJson.extract(answer, '{') as? JsonObject)?.forEach { (k, v) -> k.toIntOrNull()?.let { id -> (v as? JsonPrimitive)?.contentOrNull?.let { tr[id] = it } } }
        }
        progress(groups.size, groups.size)
        when (mode) {
            TranslateMode.TEXT_ONLY -> {
                val text = blocks.groupBy { it.page }.entries.joinToString("\n\n") { (p, bs) -> "[صفحة ${p + 1}]\n" + bs.joinToString("\n") { tr[it.id] ?: it.text } }
                AnswerPdf.write(ctx, src.nameWithoutExtension, text, out)
            }
            TranslateMode.OVERLAY -> overlay(ctx, src, password, blocks, tr, out)
        }
        out
    }

    private fun overlay(ctx: Context, src: File, password: String?, blocks: List<TextBlock>, tr: Map<Int, String>, out: File) {
        PdfOps.load(src, password).use { doc ->
            val ar = PdfOps.arabicFont(ctx, doc)
            blocks.groupBy { it.page }.forEach { (p, bs) ->
                val page = doc.getPage(p)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    bs.forEach { b ->
                        val t = tr[b.id] ?: return@forEach
                        val r = b.rect
                        cs.setNonStrokingColor(255, 255, 255)
                        cs.addRect(r.left - 1f, r.bottom - 1.5f, r.width() + 2f, (r.top - r.bottom) + 3f); cs.fill()
                        drawFitted(cs, ar, t, r, b.fontSize)
                    }
                }
            }
            doc.save(out)
        }
    }

    private fun fontFor(ar: PDFont, line: String): PDFont = if (ArabicShaper.isArabic(line) || canEncode(ar, line)) ar else PDType1Font.HELVETICA

    private fun canEncode(f: PDFont, s: String) = runCatching { f.encode(s); true }.getOrDefault(false)

    private fun latinSafe(s: String) = s.map { c -> if (c.code in 32..126 || c.code in 160..255) c else '?' }.joinToString("")

    private fun width(f: PDFont, s: String, size: Float) = runCatching { f.getStringWidth(s) / 1000f * size }.getOrDefault(s.length * size * 0.5f)

    private fun prepared(ar: PDFont, line: String): Pair<PDFont, String> {
        val f = fontFor(ar, line)
        return f to if (f === ar) ArabicShaper.shape(line) else latinSafe(line)
    }

    private fun wrap(ar: PDFont, text: String, size: Float, maxW: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = ArrayList<String>(); var cur = ""
        words.forEach { w ->
            val cand = if (cur.isEmpty()) w else "$cur $w"
            val (f, s) = prepared(ar, cand)
            if (width(f, s, size) <= maxW || cur.isEmpty()) cur = cand else { lines += cur; cur = w }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /** Shrink from the source size until the wrapped translation fits the block height. */
    private fun drawFitted(cs: PDPageContentStream, ar: PDFont, text: String, r: RectF, srcSize: Float) {
        val w = r.width().coerceAtLeast(20f); val h = (r.top - r.bottom).coerceAtLeast(srcSize)
        var size = srcSize.coerceIn(5f, 28f)
        var lines = wrap(ar, text, size, w)
        while (size > 4.5f && lines.size * size * 1.2f > h + size * 0.3f) { size -= 0.5f; lines = wrap(ar, text, size, w) }
        val rtl = ArabicShaper.isArabic(text)
        cs.setNonStrokingColor(20, 20, 20)
        var y = r.top - size * 0.95f
        lines.forEach { line ->
            val (f, s) = prepared(ar, line)
            val lw = width(f, s, size)
            val x = if (rtl) r.right - lw else r.left
            runCatching {
                cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(x, y)
                if (f === ar) cs.showArabic(line, s) else cs.showText(s)
                cs.endText()
            }
            y -= size * 1.2f
        }
    }
}
