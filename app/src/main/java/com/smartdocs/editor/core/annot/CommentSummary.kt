package com.smartdocs.editor.core.annot

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.ReviewStatus
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Labels for the summary, resolved by the caller from string resources. */
data class SummaryLabels(
    val title: String,            // "ملخص التعليقات"
    val pageFmt: String,          // "صفحة %1$d"
    val continued: String,        // "(تابع)"
    val repliesLabel: String,     // "رد"
    val statusLabel: (ReviewStatus) -> String,
    val typeLabel: (String) -> String,
    val empty: String,
)

/**
 * Options of the comments summary (Nitro 7.70 / Foxit 24.87) and of "print with comments summary" (7.71 / 7.72):
 * sort order, text size, page thumbnails (or comments only), pages without comments, replies, status, page range,
 * paper size and comment types.
 */
data class SummaryOptions(
    val sort: Sort = Sort.PAGE,
    val textScale: Float = 1f,
    val thumbnails: Boolean = true,
    val includeEmptyPages: Boolean = false,
    val replies: Boolean = true,
    val status: Boolean = true,
    val fromPage: Int = 1,
    val toPage: Int = Int.MAX_VALUE,
    val letter: Boolean = false,
    val types: Set<String>? = null,
    val pageCount: Int = 0,
    val connectors: Boolean = false,      // Foxit 24.87: lines from each numbered marker to its entry
) {
    enum class Sort { PAGE, AUTHOR, TYPE, CREATED_NEW, CREATED_OLD, MODIFIED }
}

/**
 * On-device "Summarize Comments": a new PDF where every page that has comments gets a thumbnail with
 * numbered markers beside a numbered list (type, author, date, status, text, replies). Arabic is shaped
 * and right-aligned with the embedded Naskh font.
 */
object CommentSummary {

    /**
     * Foxit 24.87 connector line from the end of a list entry ([rowX], [rowY]) to its marker ([mx], [my]) on the
     * thumbnail: across to the gutter at [gutterX], then straight to the marker.
     */
    fun connector(rowX: Float, rowY: Float, gutterX: Float, mx: Float, my: Float): List<Pair<Float, Float>> =
        listOf(rowX to rowY, gutterX to rowY, mx to my)

    private var PW = 595f
    private var PH = 842f
    private const val M = 36f

    private class Writer(val doc: PDDocument, val font: PDFont) {
        val supported = HashMap<Int, Boolean>()
        fun safe(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i); val ch = String(Character.toChars(cp))
                val ok = supported.getOrPut(cp) { runCatching { font.encode(ch); true }.getOrDefault(false) }
                append(if (ok) ch else if (cp == '\n'.code) "\n" else "?")
                i += Character.charCount(cp)
            }
        }
        fun width(s: String, size: Float) = runCatching { font.getStringWidth(ArabicShaper.shape(safe(s))) / 1000f * size }.getOrDefault(s.length * size * 0.5f)

        /** Greedy word wrap of logical text into lines no wider than [maxW]. */
        fun wrap(text: String, size: Float, maxW: Float): List<String> {
            val out = ArrayList<String>()
            text.split('\n').forEach { para ->
                var line = ""
                para.split(' ').forEach { w ->
                    val cand = if (line.isEmpty()) w else "$line $w"
                    if (width(cand, size) <= maxW || line.isEmpty()) line = cand else { out += line; line = w }
                }
                out += line
            }
            return out
        }

        fun text(cs: PDPageContentStream, s: String, x: Float, y: Float, size: Float, rightEdge: Float?, rgb: Int = 0x212121) {
            val clean = safe(s); if (clean.isBlank()) return
            val shaped = ArabicShaper.shape(clean)
            val w = runCatching { font.getStringWidth(shaped) / 1000f * size }.getOrDefault(0f)
            val px = if (rightEdge != null && ArabicShaper.isArabic(clean)) rightEdge - w else x
            cs.beginText(); cs.setFont(font, size)
            cs.setNonStrokingColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            cs.setTextMatrix(Matrix.getTranslateInstance(px, y)); cs.showArabic(clean, shaped); cs.endText()
        }
    }

    /**
     * [base] (opened with [pw]) followed by the pages of [summary], written unencrypted to [out] for printing
     * (7.71 "print document with comments summary"). Returns the page count of [out].
     */
    suspend fun appendSummary(base: File, pw: String?, summary: File, out: File): Int = withContext(Dispatchers.IO) {
        (if (pw.isNullOrEmpty()) PDDocument.load(base) else PDDocument.load(base, pw)).use { d ->
            PDDocument.load(summary).use { s ->
                d.isAllSecurityToBeRemoved = true
                com.tom_roush.pdfbox.multipdf.PDFMergerUtility().appendDocument(d, s)
                d.save(out)
                d.numberOfPages
            }
        }
    }

    /**
     * Build the summary of [src] into [out]. [thumb] renders a page (index, width px, height px) or null.
     * Returns the number of comments listed (0 → nothing written).
     */
    suspend fun build(
        context: Context, src: File, pw: String?, out: File, labels: SummaryLabels,
        pageSize: suspend (Int) -> Pair<Float, Float>, thumb: suspend (Int, Int, Int) -> Bitmap?,
        opts: SummaryOptions = SummaryOptions(),
    ): Int {
        PW = if (opts.letter) 612f else 595f; PH = if (opts.letter) 792f else 842f
        val k = opts.textScale.coerceIn(0.7f, 1.6f)
        val items = CommentStore.load(src, pw).filter { it.page + 1 in opts.fromPage..opts.toPage && (opts.types == null || it.subtype in opts.types) }
        if (items.isEmpty()) return 0
        val order: Comparator<CommentItem> = when (opts.sort) {
            SummaryOptions.Sort.PAGE -> compareBy<CommentItem>({ it.page }, { -maxOf(it.rect.top, it.rect.bottom) }, { it.rect.left })
            SummaryOptions.Sort.AUTHOR -> compareBy<CommentItem>({ it.author.lowercase() }, { it.page })
            SummaryOptions.Sort.TYPE -> compareBy<CommentItem>({ it.subtype }, { it.page })
            SummaryOptions.Sort.CREATED_NEW -> compareByDescending<CommentItem> { if (it.created > 0) it.created else it.date }
            SummaryOptions.Sort.CREATED_OLD -> compareBy<CommentItem> { if (it.created > 0) it.created else it.date }
            SummaryOptions.Sort.MODIFIED -> compareByDescending<CommentItem> { it.date }
        }
        // page order keeps one block per page (with its thumbnail); the other orders list every comment in one block
        val byPage: Map<Int, List<CommentItem>> = if (opts.sort == SummaryOptions.Sort.PAGE) {
            val m = items.sortedWith(order).groupBy { it.page }.toSortedMap()
            if (opts.includeEmptyPages && opts.pageCount > 0)
                (opts.fromPage - 1 until minOf(opts.toPage, opts.pageCount)).associateWith { m[it] ?: emptyList() }.toSortedMap()
            else m
        } else sortedMapOf(-1 to items.sortedWith(order))
        // thumbnails are rendered before the PDF is assembled (rendering is suspend, PdfBox writing is blocking)
        val thumbs = HashMap<Int, Pair<Bitmap, Pair<Float, Float>>>()
        if (opts.thumbnails) byPage.keys.filter { it >= 0 }.forEach { p ->
            val sz = pageSize(p)
            val w = 520; val h = (w * sz.second / sz.first).toInt().coerceIn(1, 1400)
            thumb(p, w, h)?.let { thumbs[p] = it to sz }
        }
        return withContext(Dispatchers.IO) {
            PDDocument().use { doc ->
                val wr = Writer(doc, PdfOps.arabicFont(context, doc))
                val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
                val thumbW = 230f; val gap = 14f
                val listLeft = M; val listRight = PW - M - thumbW - gap   // list on the left, thumbnail on the right (RTL reading)
                var number = 0
                var cs: PDPageContentStream? = null
                var y = 0f
                fun newPage(): PDPageContentStream {
                    cs?.close()
                    val pg = PDPage(PDRectangle(PW, PH)); doc.addPage(pg)
                    return PDPageContentStream(doc, pg).also { cs = it; y = PH - M }
                }
                var first = true
                byPage.forEach { (pi, list) ->
                    var s = newPage()
                    if (first) { wr.text(s, labels.title, M, y - 14f, 18f * k, PW - M, 0x1A237E); y -= 30f * k; first = false }
                    if (pi >= 0) { wr.text(s, String.format(Locale.US, labels.pageFmt, pi + 1), M, y - 12f, 13f * k, PW - M, 0x37474F); y -= 22f * k }
                    if (list.isEmpty()) { wr.text(s, labels.empty, M, y - 11f, 10f * k, PW - M, 0x78909C); y -= 18f * k }
                    val top = y
                    val markers = HashMap<Int, Pair<Float, Float>>()     // comment number -> marker centre (connectors)
                    val thumbPage = s
                    var thumbLeft = 0f
                    // thumbnail with numbered markers
                    thumbs[pi]?.let { (bmp, sz) ->
                        val th = (thumbW * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtMost(top - M)
                        val tw = th * bmp.width / bmp.height.coerceAtLeast(1)
                        val tx = PW - M - tw; val ty = top - th
                        thumbLeft = tx
                        runCatching { s.drawImage(JPEGFactory.createFromImage(doc, bmp, 0.85f), tx, ty, tw, th) }
                        s.setStrokingColor(0.6f, 0.6f, 0.6f); s.setLineWidth(0.6f); s.addRect(tx, ty, tw, th); s.stroke()
                        var n = number
                        list.forEach { c ->
                            n++
                            val cx = tx + (c.rect.left / sz.first) * tw; val cy = ty + (c.rect.top / sz.second) * th
                            s.setNonStrokingColor(229, 57, 53)
                            s.addRect(cx - 6f, cy - 6f, 12f, 12f); s.fill()
                            markers[n] = cx to cy
                            wr.text(s, n.toString(), cx - (if (n > 9) 5f else 2.6f), cy - 3.2f, 8f, null, 0xFFFFFF)
                        }
                    }
                    // numbered list
                    val maxW = (if (thumbs[pi] == null) PW - M - listLeft else listRight - listLeft)
                    val rightEdge = listLeft + maxW
                    list.forEach { c ->
                        number++
                        val head = buildString {
                            append(number).append(". ").append(labels.typeLabel(c.subtype))
                            if (c.author.isNotBlank()) append(" — ").append(c.author)
                            if (c.date > 0) append(" — ").append(fmt.format(Date(c.date)))
                            if (opts.status && c.status != ReviewStatus.NONE) append(" — ").append(labels.statusLabel(c.status))
                            if (pi < 0) append(" — ").append(String.format(Locale.US, labels.pageFmt, c.page + 1))
                        }
                        val lines = ArrayList<Pair<String, Float>>()          // text, size
                        wr.wrap(head, 10f * k, maxW).forEach { lines += it to 10f * k }
                        if (c.contents.isNotBlank()) wr.wrap(c.contents, 9.5f * k, maxW - 8f).forEach { lines += it to 9.5f * k }
                        if (opts.replies) c.replies.forEach { r ->
                            val t = "\u21B3 ${labels.repliesLabel} ${r.author}: ${r.text}"
                            wr.wrap(t, 9f * k, maxW - 16f).forEach { lines += it to 9f * k }
                        }
                        lines.forEachIndexed { li, (t, size) ->
                            if (y - size * 1.5f < M) {
                                s = newPage()
                                if (pi >= 0) wr.text(s, String.format(Locale.US, labels.pageFmt, pi + 1) + " " + labels.continued, M, y - 12f, 11f, PW - M, 0x78909C); y -= 22f
                            }
                            val indent = when { li == 0 -> 0f; size < 9.5f * k -> 16f; else -> 8f }
                            // connector from the entry's first line to its marker, while both are on the same sheet
                            if (li == 0 && opts.connectors && s === thumbPage) markers[number]?.let { (mx, my) ->
                                val pts = connector((if (ArabicShaper.isArabic(t)) rightEdge else minOf(rightEdge, listLeft + wr.width(t, size))) + 3f, y - size * 0.65f, thumbLeft - 5f, mx, my)
                                s.setStrokingColor(0.9f, 0.22f, 0.21f); s.setLineWidth(0.5f)
                                pts.forEachIndexed { i, (px, py) -> if (i == 0) s.moveTo(px, py) else s.lineTo(px, py) }
                                s.stroke()
                            }
                            wr.text(s, t, listLeft + indent, y - size, size, rightEdge - indent, if (li == 0) 0x0D47A1 else 0x212121)
                            y -= size * 1.45f
                        }
                        y -= 6f
                    }
                    if (y > top - 10f) y = top
                }
                cs?.close()
                doc.save(out)
                number
            }
        }
    }
}
