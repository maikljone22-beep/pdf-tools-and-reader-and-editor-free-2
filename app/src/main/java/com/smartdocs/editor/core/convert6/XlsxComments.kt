package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.OoxmlPackage
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Row 103 (Excel add-in "convert comments to note comments"): the cell comments (notes) of an .xlsx become note
 * comments of the printed PDF, next to their cell's text where it can be found, else at the top of the sheet's page.
 */
object XlsxComments {
    data class Comment(val ref: String, val row: Int, val col: Int, val author: String, val text: String)

    /** "B12" → (row 11, col 1), zero-based; null for anything else. Pure. */
    fun cellRef(ref: String): Pair<Int, Int>? {
        val m = Regex("""^\$?([A-Za-z]{1,3})\$?(\d{1,7})$""").find(ref.trim()) ?: return null
        val col = m.groupValues[1].uppercase().fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1
        return (m.groupValues[2].toInt() - 1) to col
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace("&amp;", "&")

    /** A comments part (xl/commentsN.xml): its comments in document order. Pure. */
    fun parse(xml: String): List<Comment> {
        val authors = Regex("""<(?:\w+:)?author>(.*?)</(?:\w+:)?author>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { unescape(it.groupValues[1]).trim() }.toList()
        val tRx = Regex("""<(?:\w+:)?t(?:\s[^>]*)?>(.*?)</(?:\w+:)?t>""", RegexOption.DOT_MATCHES_ALL)
        return Regex("""<(?:\w+:)?comment\s([^>]*)>(.*?)</(?:\w+:)?comment>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val ref = Regex("""\bref="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val (r, c) = cellRef(ref) ?: return@mapNotNull null
            val author = Regex("""\bauthorId="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.let { authors.getOrNull(it) }.orEmpty()
            val text = tRx.findAll(m.groupValues[2]).joinToString("") { unescape(it.groupValues[1]) }.trim()
            if (text.isEmpty()) null else Comment(ref, r, c, author, text)
        }.toList()
    }

    /** The sheets of [xml] (workbook.xml) in order: (name, relationship id). Pure. */
    fun sheets(xml: String): List<Pair<String, String>> =
        Regex("""<(?:\w+:)?sheet\s([^>]*?)/?>""").findAll(xml).mapNotNull { m ->
            val a = m.groupValues[1]
            val name = Regex("""\bname="([^"]*)"""").find(a)?.groupValues?.get(1)?.let { unescape(it) } ?: return@mapNotNull null
            val id = Regex("""\br:id="([^"]*)"""").find(a)?.groupValues?.get(1) ?: Regex("""\b\w+:id="([^"]*)"""").find(a)?.groupValues?.get(1) ?: return@mapNotNull null
            name to id
        }.toList()

    /** Sheet name → its comments, for the sheets that have any. */
    fun read(f: File): List<Pair<String, List<Comment>>> = runCatching {
        OoxmlPackage(f).use { pkg ->
            val wb = pkg.bytes("xl/workbook.xml")?.toString(Charsets.UTF_8) ?: return@use emptyList()
            val wbRels = pkg.rels("xl/workbook.xml")
            sheets(wb).mapNotNull { (name, id) ->
                // rels() gives targets already resolved against their part's folder
                val sheetPath = wbRels[id]?.target ?: return@mapNotNull null
                val rel = pkg.rels(sheetPath).values.firstOrNull { it.type.endsWith("/comments") } ?: return@mapNotNull null
                val list = pkg.bytes(rel.target)?.toString(Charsets.UTF_8)?.let { parse(it) }.orEmpty()
                if (list.isEmpty()) null else name to list
            }
        }
    }.getOrDefault(emptyList())

    private class Line(val page: Int, val text: String, val x: Float, val top: Float)

    /** Every printed text line with the position of its first glyph (PDF user space, y up). */
    private fun lines(doc: com.tom_roush.pdfbox.pdmodel.PDDocument): List<Line> {
        val out = ArrayList<Line>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                val p = positions.firstOrNull() ?: return
                val h = currentPage.mediaBox.height
                out += Line(currentPageNo - 1, text, p.xDirAdj, h - p.yDirAdj + p.heightDir)
            }
        }
        runCatching { st.getText(doc) }
        return out
    }

    /**
     * Add [sheets]' comments to [pdf] in place: each next to the first printed line (from its sheet's first page on)
     * that holds the cell's text ([valueOf] sheet, row, col), else at the top left of that page. Returns the count.
     */
    suspend fun apply(pdf: File, sheets: List<Pair<String, List<Comment>>>, valueOf: (String, Int, Int) -> String?, multiSheet: Boolean, label: String): Int =
        withContext(Dispatchers.IO) {
            if (sheets.isEmpty()) return@withContext 0
            val tmp = File(pdf.parentFile, ".${pdf.name}.cm.pdf")
            try {
                val n = PdfOps.loadRaw(pdf, null).use { d ->
                    val all = lines(d)
                    val pageTexts = (0 until d.numberOfPages).map { p -> all.filter { it.page == p }.joinToString("\n") { it.text } }
                    val starts = if (multiSheet) OfficeOutline.locate(pageTexts, sheets.map { OfficeOutline.Title(1, it.first) }).associate { it.first.text to it.second } else emptyMap()
                    var count = 0
                    sheets.forEach { (sheet, comments) ->
                        val start = starts[sheet] ?: 0
                        var stack = 0
                        comments.forEach { c ->
                            val v = valueOf(sheet, c.row, c.col)?.let { OfficeOutline.norm(it) }.orEmpty()
                            val hit = if (v.isEmpty()) null else all.firstOrNull { it.page >= start && OfficeOutline.norm(it.text).contains(v) }
                            val page = hit?.page ?: start
                            if (page !in 0 until d.numberOfPages) return@forEach
                            val box = d.getPage(page).cropBox
                            val x = hit?.let { (it.x - 20f).coerceAtLeast(box.lowerLeftX + 2f) } ?: (box.lowerLeftX + 8f)
                            val top = hit?.top ?: (box.upperRightY - 30f - 22f * stack++)
                            d.getPage(page).annotations.add(PDAnnotationText().apply {
                                contents = "${c.ref}: ${c.text}"; titlePopup = c.author.ifBlank { label }; subject = label
                                rectangle = PDRectangle(x, (top - 18f).coerceAtLeast(box.lowerLeftY), 18f, 18f)
                                color = PDColor(floatArrayOf(1f, 0.92f, 0.23f), PDDeviceRGB.INSTANCE)
                                name = PDAnnotationText.NAME_COMMENT
                            })
                            count++
                        }
                    }
                    if (count > 0) d.save(tmp)
                    count
                }
                if (n > 0 && !tmp.renameTo(pdf)) tmp.copyTo(pdf, overwrite = true)
                n
            } finally { tmp.delete() }
        }
}
