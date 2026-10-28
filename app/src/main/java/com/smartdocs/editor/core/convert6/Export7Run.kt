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

    /**
     * Page rasters straight from the page render at the DPI option, no JPEG round trip (convert audit B11: TIFF / BMP /
     * fax TIFF were JPEG q88 at ≤ 2400 px whatever the DPI, and the tags claimed the requested dpi). The raster's dpi is
     * the effective one. One page in memory at a time.
     */
    private suspend fun rasters(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, each: suspend (Int, Raster, Float, Float) -> Unit) {
        var k = 0
        ImageIo6.pageBitmaps(ctx, f, pw, pages, dpi) { _, w, h, b, eff ->
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            each(k++, Raster(b.width, b.height, px, eff), w, h)
        }
    }

    /** Multi-page TIFF streamed page by page into [out] (convert audit B07). [p] gets the real page total (B25). */
    suspend fun tiff(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, color: TiffColor, out: File, p: (Int, Int) -> Unit): File {
        val total = ImageIo6.selectedCount(ctx, f, pw, pages)
        withContext(Dispatchers.IO) {
            TiffWriter.Stream(out, color).use { s -> rasters(ctx, f, pw, pages, dpi) { i, r, _, _ -> s.add(r); p(i + 1, total) } }
        }
        return out
    }

    /** One BMP per page into [dir] (a work folder the engine moves into Documents on success, audit B14 / B15). */
    suspend fun bmp(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, dir: File, base: String, p: (Int, Int) -> Unit): List<File> {
        dir.mkdirs()
        val total = ImageIo6.selectedCount(ctx, f, pw, pages)
        val files = ArrayList<File>()
        rasters(ctx, f, pw, pages, dpi) { i, r, _, _ ->
            // ASCII digits whatever the app language (audit B12)
            val o = ImageIo6.unique(dir, base + "_" + (i + 1).toString().padStart(3, '0'), "bmp"); withContext(Dispatchers.IO) { o.writeBytes(BmpWriter.write(r)) }; files += o
            p(i + 1, total)
        }
        return files
    }

    /** PostScript (one file, streamed page by page — audit B08) or EPS (one file per page). */
    suspend fun ps(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, gray: Boolean, eps: Boolean, out: (String, String) -> File, base: String, p: (Int, Int) -> Unit): List<File> {
        val total = ImageIo6.selectedCount(ctx, f, pw, pages)
        val outs = ArrayList<File>()
        var n = 0
        if (eps) {
            ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg, pxW, pxH ->
                val pg = PsWriter.Page(jpeg, pxW, pxH, w, h, gray = false)
                val e = out(base + "_" + (outs.size + 1).toString().padStart(3, '0'), "eps")
                withContext(Dispatchers.IO) { e.bufferedWriter(Charsets.US_ASCII).use { PsWriter.Stream(it, true, base, 1, pg).use { s -> s.page(pg) } } }
                outs += e
                p(++n, total)
            }
            return outs
        }
        val o = out(base, "ps")
        withContext(Dispatchers.IO) {
            o.bufferedWriter(Charsets.US_ASCII).use { wr ->
                PsWriter.Stream(wr, false, base, total, null).use { s ->
                    ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg, pxW, pxH -> s.page(PsWriter.Page(jpeg, pxW, pxH, w, h, gray = false)); p(++n, total) }
                }
            }
        }
        outs += o
        return outs
    }

    /** XPS streamed page by page (audit B09), with the picture's pixel size as the brush viewbox (audit B05). */
    suspend fun xps(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, out: File, p: (Int, Int) -> Unit): File {
        val total = ImageIo6.selectedCount(ctx, f, pw, pages)
        var n = 0
        withContext(Dispatchers.IO) {
            out.outputStream().buffered().use { os ->
                XpsWriter.Stream(os).use { s -> ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg, pxW, pxH -> s.page(XpsWriter.Page(jpeg, w, h, pxW, pxH)); p(++n, total) } }
            }
        }
        return out
    }

    /** ODP of page pictures streamed slide by slide (audit B09 / C39), each letterboxed in the slide (audit B26). */
    suspend fun odp(ctx: Context, f: File, pw: String?, pages: List<Int>, dpi: Int, out: File, p: (Int, Int) -> Unit): File {
        val total = ImageIo6.selectedCount(ctx, f, pw, pages)
        // the slide size follows the first page; other pages keep their own shape inside it
        val size = ImageIo6.firstPageSize(ctx, f, pw, pages) ?: (842f to 595f)
        val wCm = 28f; val hCm = wCm * size.second / size.first
        var n = 0
        withContext(Dispatchers.IO) {
            out.outputStream().buffered().use { os ->
                OdfWriter.OdpStream(os, false, wCm, hCm).use { s ->
                    ImageIo6.pagePictures(ctx, f, pw, pages, dpi) { _, w, h, jpeg, _, _ -> s.add(jpeg, w / h); p(++n, total) }
                }
            }
        }
        return out
    }

    /**
     * PDF → XML 1.0: text extracted page by page (Cancel works between pages, real progress — audit B25); the
     * pages keep their source numbers and their own text with a range (audit B04).
     */
    suspend fun xml(f: File, pw: String?, pages: List<Int>, out: File, p: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        PdfFlow6.load(f, pw).use { d ->
            val keep = pages.filter { it in 0 until d.numberOfPages }.ifEmpty { (0 until d.numberOfPages).toList() }
            val info = d.documentInformation.let { i -> listOfNotNull(i.title?.let { "title" to it }, i.author?.let { "author" to it }, i.subject?.let { "subject" to it }, i.keywords?.let { "keywords" to it }) }.toMap()
            val marks = marksOf(d)
            val lines = ArrayList<PdfXml.Line>()
            keep.forEachIndexed { k, i ->
                kotlin.coroutines.coroutineContext.ensureActive()
                p(k, keep.size)
                OutlineTools.textLinesOfPage(d, i).forEach { lines += PdfXml.Line(it.page, it.text, it.left, it.top, it.size, it.font) }
            }
            p(keep.size, keep.size)
            val sized = keep.map { i -> val b = d.getPage(i).cropBox; Triple(i, b.width, b.height) }
            out.bufferedWriter().use { it.write(PdfXml.write(f.nameWithoutExtension, info, sized, marks, lines)) }
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
        // pages without tables do not become text-dump sheets; a document without any table gives its text (audit B21)
        pp.layouts.forEach { l ->
            val tables = LayoutFlow.tables(listOf(l), fallbackText = false)
            if (onePerTable) tables.forEachIndexed { t, rows -> if (rows.isNotEmpty()) sheets += "${sheetName(l.index + 1)}-${t + 1}" to rows }
            else tables.flatMap { it + listOf(emptyList()) }.takeIf { it.isNotEmpty() }?.let { sheets += sheetName(l.index + 1) to it.dropLast(1) }
        }
        if (sheets.isEmpty()) LayoutFlow.tables(pp.layouts, fallbackText = true).flatten().takeIf { it.isNotEmpty() }?.let { sheets += sheetName(pp.layouts.firstOrNull()?.index?.plus(1) ?: 1) to it }
        withContext(Dispatchers.IO) { out.bufferedWriter().use { it.write(Xml2003.write(sheets, pp.layouts.firstOrNull()?.rtl == true)) } }
        return out to sheets.size
    }

    /** HTML set; pictures are spilled to [spill] while the pages are read and go into the zip as files (audit B10 / B30). */
    suspend fun htmlSet(f: File, pw: String?, pages: List<Int>, images: Boolean, out: File, p: (Int, Int) -> Unit, title: String = f.nameWithoutExtension, spill: File? = null): File {
        val doc = PdfFlow6.flow(f, pw, pages, images, pageBreaks = false, onProgress = p, title = title, spillDir = spill)
        withContext(Dispatchers.IO) { out.outputStream().buffered().use { HtmlSet.write(doc, it) } }
        return out
    }
}
