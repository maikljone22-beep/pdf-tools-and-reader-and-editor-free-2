package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A word with its page and box (display space, points, origin top-left). */
class PWord(val page: Int, val text: String, val box: RectF)

/** Words of a PDF in reading order (Arabic lines right-to-left), from PdfBox text positions. */
class WordExtractor : PDFTextStripper() {
    val words = ArrayList<PWord>()
    private var page = 0
    private val lineBuf = ArrayList<PWord>()

    init { sortByPosition = true }

    /** Visual segments (left → right) of the glyphs of one writeString call. */
    private fun segments(tp: List<TextPosition>): List<RectF> {
        val out = ArrayList<RectF>()
        var box: RectF? = null; var lastRight = Float.NaN
        tp.forEach { p ->
            val u = p.unicode ?: return@forEach
            val x = p.xDirAdj; val w = p.widthDirAdj; val top = p.yDirAdj - p.heightDir; val bottom = p.yDirAdj
            val gap = if (lastRight.isNaN()) 0f else x - lastRight
            if (u.isBlank() || gap > p.fontSizeInPt * 0.25f || gap < -p.fontSizeInPt * 2f) { box?.let { out += it }; box = null }
            if (u.isNotBlank()) box = box?.apply { union(x, top, x + w, bottom) } ?: RectF(x, top, x + w, bottom)
            lastRight = x + w
        }
        box?.let { out += it }
        return out.sortedBy { it.left }
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        val tokens = WordDiff.tokenize(text ?: return)
        if (tokens.isEmpty()) return
        val segs = segments(textPositions.orEmpty())
        val all = segs.fold(null as RectF?) { acc, r -> acc?.apply { union(r) } ?: RectF(r) } ?: RectF()
        val rtl = tokens.count { TextNorm.hasArabic(it) } * 2 > tokens.size
        if (segs.size == tokens.size) {
            // PdfBox gives logical text; for RTL the first token is the rightmost segment
            val ordered = if (rtl) segs.reversed() else segs
            tokens.forEachIndexed { i, t -> lineBuf += PWord(page, t, ordered[i]) }
        } else tokens.forEach { t -> lineBuf += PWord(page, t, RectF(all)) }
    }

    private fun flushLine() {
        if (lineBuf.isEmpty()) return
        val rtl = lineBuf.count { TextNorm.hasArabic(it.text) } * 2 > lineBuf.size
        // reading order inside a line follows the position: right → left for Arabic lines
        val sorted = if (rtl) lineBuf.sortedByDescending { it.box.centerX() } else lineBuf.sortedBy { it.box.centerX() }
        words += sorted
        lineBuf.clear()
    }

    override fun writeLineSeparator() { flushLine(); super.writeLineSeparator() }

    override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) { flushLine(); super.startPage(page); this.page = currentPageNo - 1 }

    override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) { flushLine(); super.endPage(page) }

    companion object {
        fun extract(doc: PDDocument): List<PWord> {
            val w = WordExtractor()
            w.getText(doc)
            w.flushLine()
            return w.words
        }
    }
}

/** Highlight boxes per page of one side. */
class SideMarks(val boxes: Map<Int, List<Pair<RectF, ChangeType>>>)

class CompareResult(
    val oldWords: List<PWord>, val newWords: List<PWord>, val changes: List<WordChange>, val stats: DiffStats,
    val oldMarks: SideMarks, val newMarks: SideMarks, val oldPages: Int, val newPages: Int,
) {
    val changedPages: List<Int> get() = (oldMarks.boxes.keys + newMarks.boxes.keys).toSortedSet().toList()
    fun describe(max: Int = 200) = WordDiff.describe(changes, oldWords.map { it.text }, newWords.map { it.text }, max)
}

object CompareEngine {

    suspend fun compare(old: File, oldPw: String?, new: File, newPw: String?): CompareResult = withContext(Dispatchers.IO) {
        fun load(f: File, pw: String?) = if (pw.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, pw)
        val (ow, op) = load(old, oldPw).use { WordExtractor.extract(it) to it.numberOfPages }
        val (nw, np) = load(new, newPw).use { WordExtractor.extract(it) to it.numberOfPages }
        val changes = WordDiff.diff(ow.map { it.text }, nw.map { it.text })
        val om = HashMap<Int, MutableList<Pair<RectF, ChangeType>>>(); val nm = HashMap<Int, MutableList<Pair<RectF, ChangeType>>>()
        changes.forEach { c ->
            if (c.type == ChangeType.EQUAL) return@forEach
            c.a.forEach { i -> ow[i].let { w -> om.getOrPut(w.page) { ArrayList() } += w.box to c.type } }
            c.b.forEach { i -> nw[i].let { w -> nm.getOrPut(w.page) { ArrayList() } += w.box to c.type } }
            // a pure insert / delete also marks where it happened on the other side (a thin caret at the neighbour word)
            if (c.a.isEmpty()) neighbour(ow, c.a.first)?.let { w -> om.getOrPut(w.page) { ArrayList() } += RectF(w.box.right, w.box.top, w.box.right + 2f, w.box.bottom) to ChangeType.INSERT }
            if (c.b.isEmpty()) neighbour(nw, c.b.first)?.let { w -> nm.getOrPut(w.page) { ArrayList() } += RectF(w.box.right, w.box.top, w.box.right + 2f, w.box.bottom) to ChangeType.DELETE }
        }
        CompareResult(ow, nw, changes, WordDiff.stats(changes), SideMarks(om), SideMarks(nm), op, np)
    }

    private fun neighbour(words: List<PWord>, at: Int): PWord? = words.getOrNull(at - 1) ?: words.getOrNull(at)

    /**
     * Visual overlay of two renders of the same size: ink in both → dark grey, only in old → red, only in new → blue.
     */
    fun overlay(a: Bitmap, b: Bitmap): Bitmap {
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        val pa = IntArray(w * h); val pb = IntArray(w * h)
        a.getPixels(pa, 0, a.width, 0, 0, w, h); b.getPixels(pb, 0, b.width, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in out.indices) {
            val da = PixelOps.lum(pa[i]) < 160; val db = PixelOps.lum(pb[i]) < 160
            out[i] = when {
                da && db -> 0xFF505050.toInt()
                da -> 0xFFE53935.toInt()
                db -> 0xFF1E88E5.toInt()
                else -> -1
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Render page [i] of [f] at [widthPx] (white background, annotations on). */
    suspend fun render(ctx: Context, f: File, pw: String?, i: Int, widthPx: Int): Bitmap? {
        val s = PdfSession.open(ctx, f, pw)
        try {
            if (i !in 0 until s.pageCount) return null
            val (w, h) = s.pageSize(i)
            val bmp = s.render(i, widthPx, (widthPx * h / w).toInt().coerceAtLeast(1), annotations = true)
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            s.invalidateCache()
            return copy
        } finally { s.closeAsync() }
    }

    /** AI summary of the changes (hook: any OpenAI-compatible endpoint configured in the app). */
    suspend fun aiSummary(ctx: Context, settings: AppSettings, r: CompareResult, langArabic: Boolean): String {
        val client = AiClient(settings)
        val system = if (langArabic) "أنت مساعد يلخص الفروق بين نسختين من مستند. اكتب ملخصا\u064B موجزا\u064B بالعربية في نقاط: ما الذي أضيف وما الذي حذف وما الذي تغي\u0651ر، مع التركيز على التغييرات المهمة (أرقام، تواريخ، أسماء، التزامات)."
        else "You summarise the differences between two versions of a document. Write a short bullet summary: what was added, removed and changed, focusing on important changes (numbers, dates, names, obligations)."
        val user = "Changes (- removed, + added, ~ old → new):\n" + r.describe(300).take(12_000)
        return AiCalls.ask(ctx, client, system, user)
    }
}
