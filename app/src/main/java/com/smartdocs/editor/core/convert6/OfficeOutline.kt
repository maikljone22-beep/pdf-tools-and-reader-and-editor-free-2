package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.OutlineStore
import com.smartdocs.editor.core.office.Block
import com.smartdocs.editor.core.office.DocModel
import com.smartdocs.editor.core.office.Inline
import com.smartdocs.editor.core.office.PresentationModel
import com.smartdocs.editor.core.office.SlideShape
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/**
 * Bookmarks for Office → PDF (Nitro "convert headings to bookmarks", Excel "bookmark per sheet", PowerPoint
 * "bookmark per slide"): the WebView printer writes no outline, so the titles come from the Office model and their
 * pages are found in the printed PDF — Word headings and sheet names by their text, in order; slides one per page.
 */
object OfficeOutline {
    data class Title(val level: Int, val text: String)

    /** Word: Title (-1) and Heading 1..6 paragraphs, in document order (levels 1..6, Title = 1). */
    fun docxTitles(model: DocModel): List<Title> = model.blocks.mapNotNull { b ->
        val p = (b as? Block.Para)?.p ?: return@mapNotNull null
        if (p.heading == 0) return@mapNotNull null
        val text = p.inlines.filterIsInstance<Inline.Text>().joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) null else Title(if (p.heading < 0) 1 else p.heading.coerceIn(1, 6), text.take(200))
    }

    /** PowerPoint: one title per slide — the first line of its top-most text box, else [fallback] (e.g. "Slide 3"). */
    fun slideTitles(model: PresentationModel, fallback: (Int) -> String): List<String> = model.slides.mapIndexed { i, s ->
        s.shapes.filterIsInstance<SlideShape.TextBox>().sortedWith(compareBy({ it.y }, { it.x })).firstNotNullOfOrNull { tb ->
            tb.paragraphs.asSequence().map { p -> p.runs.filterIsInstance<Inline.Text>().joinToString("") { it.text }.trim() }.firstOrNull { it.isNotEmpty() }
        }?.take(200) ?: fallback(i + 1)
    }

    /** Text compared without spaces, tatweel, diacritics and presentation forms (the printer may shape Arabic). */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC)
        return buildString { n.forEach { c -> if (!c.isWhitespace() && c != 'ـ' && c !in 'ً'..'ٟ' && c != 'ٰ') append(c.lowercaseChar()) } }
    }

    /**
     * The page of each title in [pages] (text per page), searched in document order: a title is looked for from where
     * the previous one was found; titles that are not found are left out. Long titles match on their first 40 characters.
     */
    fun locate(pages: List<String>, titles: List<Title>): List<Pair<Title, Int>> {
        val normPages = pages.map { norm(it) }
        val out = ArrayList<Pair<Title, Int>>()
        var page = 0; var offset = 0
        for (t in titles) {
            val key = norm(t.text).take(40)
            if (key.isEmpty()) continue
            var p = page; var o = offset; var found = -1
            while (p < normPages.size) {
                val i = normPages[p].indexOf(key, o)
                if (i >= 0) { found = p; o = i + key.length; break }
                p++; o = 0
            }
            if (found >= 0) { out += t to found; page = found; offset = o }
        }
        return out
    }

    /** Bookmarks for located titles (levels made contiguous: a level-3 under a level-1 becomes level 2). */
    fun bookmarks(found: List<Pair<Title, Int>>): List<E5Bookmark> {
        val stack = ArrayList<Int>()     // original levels of the open parents
        return found.mapIndexed { i, (t, page) ->
            while (stack.isNotEmpty() && stack.last() >= t.level) stack.removeAt(stack.lastIndex)
            val level = stack.size
            stack += t.level
            E5Bookmark(key = i.toLong() + 1, title = t.text, page = page, level = level)
        }
    }

    private fun pageTexts(pdf: File): List<String> = PdfOps.loadRaw(pdf, null).use { d ->
        val st = PDFTextStripper()
        (1..d.numberOfPages).map { p -> st.startPage = p; st.endPage = p; runCatching { st.getText(d) }.getOrDefault("") }
    }

    private fun write(pdf: File, list: List<E5Bookmark>) {
        if (list.isEmpty()) return
        val tmp = File(pdf.parentFile, ".${pdf.name}.bm.pdf")
        try {
            PdfOps.loadRaw(pdf, null).use { d -> OutlineStore.write(d, list); d.save(tmp) }
            if (!tmp.renameTo(pdf)) tmp.copyTo(pdf, overwrite = true)
        } finally { tmp.delete() }
    }

    /** Add bookmarks for [titles] found by their text in [pdf] (in place). Returns how many were added. */
    suspend fun applyByText(pdf: File, titles: List<Title>): Int = withContext(Dispatchers.IO) {
        if (titles.isEmpty()) return@withContext 0
        val list = bookmarks(locate(pageTexts(pdf), titles))
        write(pdf, list); list.size
    }

    /** One bookmark per page (slides): [titles][i] → page i. */
    suspend fun applyPerPage(pdf: File, titles: List<String>): Int = withContext(Dispatchers.IO) {
        if (titles.isEmpty()) return@withContext 0
        val n = PdfOps.loadRaw(pdf, null).use { it.numberOfPages }
        val list = titles.take(n).mapIndexed { i, t -> E5Bookmark(key = i.toLong() + 1, title = t, page = i, level = 0) }
        write(pdf, list); list.size
    }
}
