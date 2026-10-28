package com.smartdocs.editor.core.convert

import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FINAL_CHECK_2 #4 "Export selection / region as Word, Excel, PowerPoint": the content of one page inside a region
 * (its glyphs, ruling lines, pictures and fills) is laid out like a whole page by the converter ([LayoutAnalyzer]:
 * paragraphs, tables, headings) and written with the DOCX / XLSX / PPTX builders. Pages turned by /Rotate use the
 * plain selected text for Word / Excel ([fallbackText]).
 */
object RegionExport {
    /** A region in the page's top-left frame (points): left, top, right, bottom. Pure. */
    data class Region(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hasPoint(x: Float, y: Float) = x >= l && x <= r && y >= t && y <= b
    }

    /** PDF user-space rectangle (y up) → frame region of an unrotated page with crop box ([cbX], [cbY], [cbTop]). Pure. */
    fun toFrame(left: Float, bottomY: Float, right: Float, topY: Float, cbX: Float, cbTop: Float, margin: Float = 2f): Region =
        Region(minOf(left, right) - cbX - margin, cbTop - maxOf(topY, bottomY) - margin, maxOf(left, right) - cbX + margin, cbTop - minOf(topY, bottomY) + margin)

    /** [c] reduced to what lies inside [rg] (glyph centres, both segment ends, picture / fill centres). Pure. */
    fun crop(c: PageContent, rg: Region): PageContent = PageContent(
        c.index, c.width, c.height,
        c.glyphs.filter { g -> rg.hasPoint(g.x + g.w / 2f, g.top + g.h / 2f) },
        c.segments.filter { s -> rg.hasPoint(s.x1, s.y1) && rg.hasPoint(s.x2, s.y2) },
        c.images.filter { i -> rg.hasPoint(i.x + i.w / 2f, i.top + i.h / 2f) },
        c.fills.filter { f -> rg.hasPoint((f.rect.x0 + f.rect.x1) / 2f, (f.rect.y0 + f.rect.y1) / 2f) },
        c.rebuiltByOcr,
    )

    /**
     * Write the region of [page] ([left], [bottomY], [right], [topY] in PDF user space) of [src] to [out] as [format]
     * (DOCX, XLSX or PPTX). Returns false when nothing could be laid out (the caller then saves the plain text).
     */
    suspend fun export(src: File, password: String?, page: Int, left: Float, bottomY: Float, right: Float, topY: Float,
                       format: TargetFormat, out: File, title: String, fallbackText: String): Boolean = withContext(Dispatchers.IO) {
        PDDocument.load(src, password.orEmpty()).use { doc ->
            val p = doc.getPage(page)
            val rotated = ((p.rotation % 360) + 360) % 360 != 0
            val layout = if (rotated) null else {
                val cb = p.cropBox
                val content = PdfContentExtractor(doc).extract(page, withImages = format != TargetFormat.XLSX)
                val cut = crop(content, toFrame(left, bottomY, right, topY, cb.lowerLeftX, cb.upperRightY))
                if (cut.glyphs.isEmpty() && cut.images.isEmpty()) null else LayoutAnalyzer.analyze(cut, detectTables = true)
            }
            out.outputStream().buffered().use { os ->
                when (format) {
                    TargetFormat.DOCX -> if (layout != null) DocxBuilder(os, title, PageFurniture.NONE, flowing0 = true).use { b -> b.addPage(layout); b.finish() }
                        else com.smartdocs.editor.core.convert6.FlowDocx.write(com.smartdocs.editor.core.convert6.FlowDoc.fromText(fallbackText, title), os)
                    TargetFormat.XLSX -> if (layout != null) XlsxBuilder(os, title).use { b -> b.addSheet(title, listOf(layout)); b.finish() }
                        else return@withContext false
                    TargetFormat.PPTX -> if (layout != null) PptxBuilder(os, title, layout.width, layout.height).use { b -> b.addEditableSlide(layout); b.finish() }
                        else return@withContext false
                    else -> return@withContext false
                }
            }
            true
        }
    }
}
