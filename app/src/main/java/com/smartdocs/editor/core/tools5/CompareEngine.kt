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

/** A word with its page and box (display space, points, origin top-left), and the font and size it is set in. */
class PWord(val page: Int, val text: String, val box: RectF, val font: String = "", val size: Float = 0f)

/** Words of a PDF in reading order (Arabic lines right-to-left), from PdfBox text positions. */
class WordExtractor : PDFTextStripper() {
    val words = ArrayList<PWord>()
    private var page = 0
    private val lineBuf = ArrayList<PWord>()

    init { sortByPosition = true }

    private class Seg(val box: RectF, val font: String, val size: Float)

    /** Visual segments (left → right) of the glyphs of one writeString call, with the font of their first glyph. */
    private fun segments(tp: List<TextPosition>): List<Seg> {
        val out = ArrayList<Seg>()
        var box: RectF? = null; var lastRight = Float.NaN
        var font = ""; var size = 0f
        tp.forEach { p ->
            val u = p.unicode ?: return@forEach
            val x = p.xDirAdj; val w = p.widthDirAdj; val top = p.yDirAdj - p.heightDir; val bottom = p.yDirAdj
            val gap = if (lastRight.isNaN()) 0f else x - lastRight
            if (u.isBlank() || gap > p.fontSizeInPt * 0.25f || gap < -p.fontSizeInPt * 2f) { box?.let { out += Seg(it, font, size) }; box = null }
            if (u.isNotBlank()) {
                if (box == null) { font = runCatching { p.font?.name }.getOrNull().orEmpty(); size = p.fontSizeInPt }
                box = box?.apply { union(x, top, x + w, bottom) } ?: RectF(x, top, x + w, bottom)
            }
            lastRight = x + w
        }
        box?.let { out += Seg(it, font, size) }
        return out.sortedBy { it.box.left }
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        val tokens = WordDiff.tokenize(text ?: return)
        if (tokens.isEmpty()) return
        val segs = segments(textPositions.orEmpty())
        val all = segs.fold(null as RectF?) { acc, r -> acc?.apply { union(r.box) } ?: RectF(r.box) } ?: RectF()
        val rtl = tokens.count { TextNorm.hasArabic(it) } * 2 > tokens.size
        if (segs.size == tokens.size) {
            // PdfBox gives logical text; for RTL the first token is the rightmost segment
            val ordered = if (rtl) segs.reversed() else segs
            tokens.forEachIndexed { i, t -> lineBuf += PWord(page, t, ordered[i].box, ordered[i].font, ordered[i].size) }
        } else {
            val f = segs.firstOrNull()
            tokens.forEach { t -> lineBuf += PWord(page, t, RectF(all), f?.font.orEmpty(), f?.size ?: 0f) }
        }
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
class SideMarks(val boxes: Map<Int, List<Mark>>)

class CompareResult(
    val oldWords: List<PWord>, val newWords: List<PWord>, val changes: List<WordChange>, val stats: DiffStats,
    val oldMarks: SideMarks, val newMarks: SideMarks, val oldPages: Int, val newPages: Int,
    /** Old page ↔ new page, in new-document order (unmatched pages have a null side). */
    val pairs: List<PagePair> = emptyList(),
    val pageChanges: List<PageChange> = emptyList(),
    val objectChanges: List<ObjChange> = emptyList(),
    val formatChanges: List<FormatChange> = emptyList(),
    val tableChanges: List<TableChange> = emptyList(),
) {
    val changedPages: List<Int> get() = (oldMarks.boxes.keys + newMarks.boxes.keys).toSortedSet().toList()

    /** Pairs that carry a change of one of [kinds] (in new-document order). */
    fun changedPairs(kinds: Set<ChangeKind> = ChangeKind.values().toSet()): List<PagePair> = pairs.filter { p ->
        (p.old != null && oldMarks.boxes[p.old].orEmpty().any { it.kind in kinds }) || (p.new != null && newMarks.boxes[p.new].orEmpty().any { it.kind in kinds })
    }

    fun describe(max: Int = 200) = WordDiff.describe(changes, oldWords.map { it.text }, newWords.map { it.text }, max)
}

object CompareEngine {

    /** Text-only compare of whole documents, pages matched in order (the earlier behaviour). */
    suspend fun compare(old: File, oldPw: String?, new: File, newPw: String?): CompareResult =
        compare(old, oldPw, new, newPw, CompareOptions(images = false, annotations = false, formatting = false, autoMatch = false))

    suspend fun compare(old: File, oldPw: String?, new: File, newPw: String?, o: CompareOptions): CompareResult = withContext(Dispatchers.IO) {
        fun load(f: File, pw: String?) = if (pw.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, pw)
        load(old, oldPw).use { od -> load(new, newPw).use { nd -> CompareCore.run(od, nd, o) } }
    }

    internal fun neighbour(words: List<PWord>, at: Int): PWord? = words.getOrNull(at - 1) ?: words.getOrNull(at)

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

    /** Pixel overlay of page [oldPage] of [old] against [newPage] of [new] (either may be null: the other is shown). */
    suspend fun overlayPages(ctx: Context, old: File, oldPw: String?, oldPage: Int?, new: File, newPw: String?, newPage: Int?, widthPx: Int): Bitmap? {
        val a = oldPage?.let { render(ctx, old, oldPw, it, widthPx) }
        val b = newPage?.let { render(ctx, new, newPw, it, widthPx) }
        return if (a != null && b != null) withContext(Dispatchers.Default) { overlay(a, b) } else a ?: b
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
