package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.edit.OutlineModel
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** A heading candidate: text, zero-based page, font size and the top of the line in user space (for XYZ). */
data class Heading(val text: String, val page: Int, val size: Float, val top: Float?)

/** Automatic bookmarks from headings, and a clickable table-of-contents page. */
object OutlineTools {

    private class Collector : PDFTextStripper() {
        val lines = ArrayList<Heading>()
        init { sortByPosition = true; suppressDuplicateOverlappingText = true }
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || text.isBlank()) return
            val size = textPositions.map { it.fontSizeInPt.takeIf { s -> s > 0.5f } ?: it.heightDir }.sorted()[textPositions.size / 2]
            val first = textPositions.first()
            val top = if (first.rotation == 0) first.pageHeight - first.yDirAdj + size else null
            lines += Heading(text.trim(), currentPageNo - 1, (size * 2).roundToInt() / 2f, top)
        }
    }

    /**
     * Headings = text lines in a larger size than the body text (the most common size). The largest [levels]
     * heading sizes become levels 0..levels-1. Lines that are too long, numeric-only or repeated on most pages
     * (running headers) are skipped.
     */
    suspend fun detectHeadings(src: File, password: String?, levels: Int = 3, minRatio: Float = 1.15f, pages: List<Int>? = null): List<Pair<Heading, Int>> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val c = Collector()
            if (pages != null && pages.isNotEmpty()) { c.startPage = pages.min() + 1; c.endPage = pages.max() + 1 }
            runCatching { c.getText(doc) }
            val all = c.lines.filter { pages == null || it.page in pages }
            if (all.isEmpty()) return@withContext emptyList()
            val weights = HashMap<Float, Int>()
            all.forEach { weights[it.size] = (weights[it.size] ?: 0) + it.text.length }
            val body = weights.maxByOrNull { it.value }!!.key
            val pageCount = all.maxOf { it.page } + 1
            val repeated = all.groupingBy { it.text }.eachCount().filter { it.value > maxOf(3, pageCount / 2) }.keys
            val cand = all.filter { h ->
                h.size >= body * minRatio && h.text.length in 2..140 && h.text.any { it.isLetter() } && h.text !in repeated
            }
            val sizes = cand.map { it.size }.distinct().sortedDescending().take(levels.coerceIn(1, 6))
            // merge consecutive lines of the same heading (wrapped titles)
            val out = ArrayList<Pair<Heading, Int>>()
            for (h in cand) {
                val lvl = sizes.indexOf(h.size); if (lvl < 0) continue
                val last = out.lastOrNull()
                val lt = last?.first?.top; val ht = h.top
                if (last != null && last.first.page == h.page && last.second == lvl && last.first.size == h.size && lt != null && ht != null &&
                    lt - ht in 0f..h.size * 1.8f) {
                    out[out.size - 1] = last.first.copy(text = last.first.text + " " + h.text) to lvl
                } else out += h to lvl
            }
            out
        }
    }

    fun toBookmarks(found: List<Pair<Heading, Int>>): List<E5Bookmark> = found.map { (h, l) ->
        E5Bookmark(OutlineModel.newKey(), h.text, h.page, l, bold = l == 0, top = h.top, fit = if (h.top != null) DestFit.XYZ else DestFit.FIT)
    }.let { list -> OutlineModel.normalize(list.map { it.row() }).let { n -> list.mapIndexed { i, b -> b.copy(level = n[i].level) } } }

    /**
     * Insert table-of-contents page(s) at [at] built from [entries] (the outline), with dot leaders, page numbers
     * (shifted by the inserted pages) and a real link on every row. [rtl] lays the rows out right-to-left.
     */
    suspend fun insertToc(context: Context, src: File, out: File, password: String?, entries: List<E5Bookmark>, title: String, at: Int = 0,
                          maxLevel: Int = 2, rtl: Boolean = true, arabicDigits: Boolean = false, font: E5Font = E5Font.NASKH, addBookmark: Boolean = true) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val rows = entries.filter { it.level <= maxLevel && it.page >= 0 }
            val fonts = E5Fonts(context, doc)
            val ref = if (doc.numberOfPages > 0) PageInfo.of(doc.getPage(0)) else null
            val w = ref?.displayW ?: 595.28f; val h = ref?.displayH ?: 841.89f
            val margin = 56f; val rowH = 20f
            val firstTop = h - margin - 48f
            val perFirst = ((firstTop - margin) / rowH).toInt().coerceAtLeast(1)
            val perNext = ((h - 2 * margin) / rowH).toInt().coerceAtLeast(1)
            val tocPages = if (rows.size <= perFirst) 1 else 1 + (rows.size - perFirst + perNext - 1) / perNext
            val targets = rows.map { doc.getPage(it.page) }            // page objects before inserting
            val insertAt = at.coerceIn(0, doc.numberOfPages)
            var idx = 0
            val created = ArrayList<PDPage>()
            for (p in 0 until tocPages) {
                val page = PDPage(PDRectangle(w, h)); PageOps5.insertAt(doc, page, insertAt + p); created += page
                val links = ArrayList<PDAnnotationLink>()
                PDPageContentStream(doc, page).use { cs ->
                    var y = if (p == 0) firstTop else h - margin
                    if (p == 0) {
                        val t = E5Line(fonts, title, E5TextStyle(font, 20f, 0x212121, bold = true))
                        t.draw(cs, if (rtl) w - margin - t.width else margin, h - margin - 20f)
                    }
                    val count = if (p == 0) perFirst else perNext
                    repeat(count) {
                        if (idx >= rows.size) return@repeat
                        val b = rows[idx]; val target = targets[idx]; idx++
                        val num = b.page + 1 + (if (b.page >= insertAt) tocPages else 0)   // number after the TOC pages are in
                        val numText = if (arabicDigits) Digits.toArabic(num.toString()) else num.toString()
                        val size = if (b.level == 0) 12f else 11f
                        val indent = 18f * b.level
                        val style = E5TextStyle(font, size, if (b.level == 0) 0x212121 else 0x424242, bold = b.level == 0)
                        val label = E5Line(fonts, b.title.take(90), style)
                        val n = E5Line(fonts, numText, style)
                        val dot = E5Line(fonts, ".", style.copy(bold = false))
                        val textStart: Float; val textEnd: Float
                        if (rtl) {
                            label.draw(cs, w - margin - indent - label.width, y); n.draw(cs, margin, y)
                            textStart = margin + n.width + 6f; textEnd = w - margin - indent - label.width - 6f
                        } else {
                            label.draw(cs, margin + indent, y); n.draw(cs, w - margin - n.width, y)
                            textStart = margin + indent + label.width + 6f; textEnd = w - margin - n.width - 6f
                        }
                        if (dot.width > 0f) { var x = textStart; while (x + dot.width < textEnd) { dot.draw(cs, x, y); x += dot.width * 2.2f } }
                        val link = PDAnnotationLink()
                        link.rectangle = PDRectangle(margin, y - 5f, w - 2 * margin, rowH - 2f)
                        link.cosObject.setItem(COSName.DEST, COSArray().apply {
                            add(target.cosObject); add(COSName.getPDFName("XYZ")); add(COSNull.NULL); add(b.top?.let { COSFloat(it) } ?: COSNull.NULL); add(COSNull.NULL)
                        })
                        link.cosObject.setItem(COSName.BS, com.tom_roush.pdfbox.cos.COSDictionary().apply { setFloat(COSName.W, 0f) })
                        link.setPage(page); link.isPrinted = true
                        links += link
                        y -= rowH
                    }
                }
                page.annotations = links.toMutableList()
            }
            if (addBookmark) {
                val shifted = entries.map { b -> if (b.page >= insertAt) b.copy(page = b.page + tocPages) else b }
                OutlineStore.write(doc, listOf(E5Bookmark(OutlineModel.newKey(), title, insertAt, 0, bold = true, fit = DestFit.FIT)) + shifted)
            }
        }
    }
}
