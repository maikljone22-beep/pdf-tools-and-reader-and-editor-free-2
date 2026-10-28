package com.smartdocs.editor.core.convert6

import android.content.Context
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.edit5.E5Io
import com.smartdocs.editor.core.edit5.OutlineTools
import com.smartdocs.editor.core.edit5.PageOrganize
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Runs the [Export7] writers on a PDF (page pictures from PDFium, text and structure from PdfBox). */
object Export7Run {

    private suspend fun rasters(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, each: suspend (Int, Raster, Float, Float) -> Unit) {
        var k = 0
        ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg ->
            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@pagePictures
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val r = Raster(b.width, b.height, px, dpi); b.recycle()
            each(k++, r, w, h)
        }
    }

    suspend fun tiff(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, color: TiffColor, out: File, p: (Int, Int) -> Unit): File {
        val enc = ArrayList<TiffWriter.Encoded>()
        rasters(ctx, f, pw, pages, dpi) { i, r, _, _ -> enc += TiffWriter.encode(r, color); p(i + 1, maxOf(pages.size, i + 2)) }
        withContext(Dispatchers.IO) { out.outputStream().buffered().use { TiffWriter.writeEncoded(enc, color, it) } }
        return out
    }

    suspend fun bmp(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, dir: File, base: String, p: (Int, Int) -> Unit): List<File> {
        dir.mkdirs()
        val files = ArrayList<File>()
        rasters(ctx, f, pw, pages, dpi) { i, r, _, _ ->
            val o = File(dir, "${base}_%03d.bmp".format(i + 1)); withContext(Dispatchers.IO) { o.writeBytes(BmpWriter.write(r)) }; files += o
            p(i + 1, maxOf(pages.size, i + 2))
        }
        return files
    }

    /** PostScript (one file) or EPS (one file per page). */
    suspend fun ps(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, gray: Boolean, eps: Boolean, out: (String, String) -> File, base: String, p: (Int, Int) -> Unit): List<File> {
        val list = ArrayList<PsWriter.Page>()
        val outs = ArrayList<File>()
        ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            val pg = PsWriter.Page(jpeg, o.outWidth, o.outHeight, w, h, gray = false)
            if (eps) { val e = out(base + "_%03d".format(outs.size + 1), "eps"); withContext(Dispatchers.IO) { e.writeText(PsWriter.write(listOf(pg), true, base), Charsets.US_ASCII) }; outs += e }
            else list += pg
            p(outs.size + list.size, maxOf(pages.size, outs.size + list.size + 1))
        }
        if (!eps) { val o = out(base, "ps"); withContext(Dispatchers.IO) { o.writeText(PsWriter.write(list, false, base), Charsets.US_ASCII) }; outs += o }
        return outs
    }

    suspend fun xps(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, out: File, p: (Int, Int) -> Unit): File {
        val list = ArrayList<XpsWriter.Page>()
        ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg -> list += XpsWriter.Page(jpeg, w, h); p(list.size, maxOf(pages.size, list.size + 1)) }
        withContext(Dispatchers.IO) { out.outputStream().buffered().use { XpsWriter.write(list, it) } }
        return out
    }

    suspend fun xml(f: File, pw: String?, pages: List<Int>, out: File): File = withContext(Dispatchers.IO) {
        val lines = OutlineTools.textLines(f, pw, pages.ifEmpty { null })
        E5Io.load(f, pw).use { d ->
            val keep = pages.ifEmpty { (0 until d.numberOfPages).toList() }.toSet()
            val sizes = (0 until d.numberOfPages).map { val b = d.getPage(it).cropBox; b.width to b.height }
            val info = d.documentInformation.let { i -> listOfNotNull(i.title?.let { "title" to it }, i.author?.let { "author" to it }, i.subject?.let { "subject" to it }, i.keywords?.let { "keywords" to it }) }.toMap()
            val marks = marksOf(d)
            val xml = PdfXml.write(f.nameWithoutExtension, info, sizes.filterIndexed { i, _ -> i in keep }.ifEmpty { sizes },
                marks, lines.filter { it.page in keep }.map { PdfXml.Line(it.page, it.text, it.left, it.top, it.size, it.font) })
            out.writeText(xml)
        }
        out
    }

    private fun marksOf(d: PDDocument): List<PdfXml.Mark> {
        val idx = d.pages.withIndex().associate { it.value.cosObject to it.index }
        val out = ArrayList<PdfXml.Mark>()
        fun walk(n: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, lvl: Int) {
            if (lvl > 20) return
            n.children().forEach { c -> out += PdfXml.Mark(c.title ?: "", PageOrganize.itemPage(d, c.cosObject)?.let { idx[it] } ?: -1, lvl); walk(c, lvl + 1) }
        }
        d.documentCatalog.documentOutline?.let { walk(it, 0) }
        return out
    }

    /**
     * Accessible text (Foxit "export to accessible text", for braille / screen readers): the text of each page in
     * reading order, with the alternate text of its figures and the expansions of abbreviations from the tags.
     */
    suspend fun accessibleText(f: File, pw: String?, pages: List<Int>, out: File, figureLabel: String, pageLabel: String): File = withContext(Dispatchers.IO) {
        E5Io.load(f, pw).use { d ->
            val idx = d.pages.withIndex().associate { it.value.cosObject to it.index }
            val alts = HashMap<Int, MutableList<String>>()
            fun walk(n: PDStructureNode, depth: Int, page: Int?) {
                if (depth > 60) return
                n.kids.forEach { k ->
                    if (k is PDStructureElement) {
                        val pg = (k.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.PG) as? COSDictionary)?.let { idx[it] } ?: page
                        val alt = k.alternateDescription?.takeIf { it.isNotBlank() } ?: k.actualText?.takeIf { it.isNotBlank() && k.structureType == "Figure" }
                        if (alt != null && (k.structureType == "Figure" || k.structureType == "Formula" || k.structureType == "Form")) alts.getOrPut(pg ?: -1) { ArrayList() } += alt.trim()
                        walk(k, depth + 1, pg)
                    }
                }
            }
            d.documentCatalog.structureTreeRoot?.let { runCatching { walk(it, 0, null) } }
            val list = pages.ifEmpty { (0 until d.numberOfPages).toList() }
            val st = PDFTextStripper().apply { sortByPosition = true }
            val sb = StringBuilder()
            for (i in list) {
                kotlin.coroutines.coroutineContext.ensureActive()   // Cancel stops between pages
                st.startPage = i + 1; st.endPage = i + 1
                sb.append(pageLabel.format(i + 1)).append("\n\n")
                sb.append(runCatching { st.getText(d) }.getOrDefault("").trim()).append("\n")
                alts[i]?.forEach { sb.append('[').append(figureLabel).append(": ").append(it).append("]\n") }
                sb.append('\n')
            }
            alts[-1]?.forEach { sb.append('[').append(figureLabel).append(": ").append(it).append("]\n") }
            out.writeText(sb.toString())
        }
        out
    }

    suspend fun xml2003(f: File, pw: String?, pages: List<Int>, onePerTable: Boolean, sheetName: (Int) -> String, out: File, p: (Int, Int) -> Unit): Pair<File, Int> {
        val pp = PdfFlow6.pages(f, pw, pages, false, true, p)
        val sheets = ArrayList<Pair<String, List<List<String>>>>()
        pp.layouts.forEach { l ->
            val tables = LayoutFlow.tables(listOf(l))
            if (onePerTable) tables.forEachIndexed { t, rows -> if (rows.isNotEmpty()) sheets += "${sheetName(l.index + 1)}-${t + 1}" to rows }
            else tables.flatMap { it + listOf(emptyList()) }.takeIf { it.isNotEmpty() }?.let { sheets += sheetName(l.index + 1) to it }
        }
        withContext(Dispatchers.IO) { out.writeText(Xml2003.write(sheets, pp.layouts.firstOrNull()?.rtl == true)) }
        return out to sheets.size
    }

    suspend fun htmlSet(f: File, pw: String?, pages: List<Int>, images: Boolean, out: File, p: (Int, Int) -> Unit): File {
        val doc = PdfFlow6.flow(f, pw, pages, images, pageBreaks = false, onProgress = p)
        withContext(Dispatchers.IO) { out.outputStream().buffered().use { HtmlSet.write(doc, it) } }
        return out
    }
}
