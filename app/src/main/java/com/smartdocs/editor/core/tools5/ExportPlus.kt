package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import com.smartdocs.editor.core.convert.Align
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.ParagraphBlock
import com.smartdocs.editor.core.convert.PdfContentExtractor
import com.smartdocs.editor.core.convert.TableBlock
import com.smartdocs.editor.core.convert.TextRuns
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

enum class ImageFormat(val ext: String) { PNG("png"), JPG("jpg"), WEBP("webp") }

data class ImageExportOptions(
    val format: ImageFormat = ImageFormat.PNG,
    val dpi: Int = 150,
    val gray: Boolean = false,
    val quality: Int = 90,
    /** Zero-based pages; empty = all. */
    val pages: List<Int> = emptyList(),
    val zip: Boolean = false,
    /** Draw annotations (comments, stamps) into the pictures. */
    val annotations: Boolean = true,
)

/** PDF → page pictures (PNG/JPG/WebP, any dpi, colour/grey, optional ZIP) and PDF → RTF. */
object ExportPlus {

    suspend fun images(ctx: Context, src: File, password: String?, o: ImageExportOptions, outDir: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val s = PdfSession.open(ctx, src, password)
        val files = ArrayList<File>()
        try {
            val pages = o.pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
            pages.forEachIndexed { n, i ->
                coroutineContext.ensureActive()
                onProgress(n, pages.size)
                val (w, h) = s.pageSize(i)
                val k = minOf(o.dpi / 72f, 6000f / maxOf(w, h))
                val raw = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = o.annotations)
                // flatten on white (JPG has no alpha; transparent PNG backgrounds confuse viewers)
                val bmp = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { drawColor(Color.WHITE); drawBitmap(raw, 0f, 0f, null) }
                s.invalidateCache()
                if (o.gray) { val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height); PixelOps.toGray(px); bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                val f = File(outDir, src.nameWithoutExtension + "_" + (i + 1).toString().padStart(3, '0') + "." + o.format.ext)
                @Suppress("DEPRECATION")
                val fmt = when (o.format) {
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    ImageFormat.JPG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                }
                f.outputStream().use { bmp.compress(fmt, o.quality.coerceIn(10, 100), it) }
                bmp.recycle()
                files += f
            }
            onProgress(pages.size, pages.size)
        } finally { s.closeAsync() }
        if (!o.zip) return@withContext files
        val zip = File(outDir.parentFile ?: outDir, src.nameWithoutExtension + "_images_" + System.currentTimeMillis() + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z -> files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() } }
        listOf(zip)
    }

    /** PDF → RTF through the converter's layout analysis (paragraphs, headings, tables, RTL). */
    suspend fun rtf(src: File, password: String?, out: File, pages: List<Int> = emptyList(), onProgress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)
        doc.use { d ->
            val ex = PdfContentExtractor(d)
            val list = pages.filter { it in 0 until d.numberOfPages }.ifEmpty { (0 until d.numberOfPages).toList() }
            val blocks = ArrayList<RtfBlock>()
            list.forEachIndexed { n, i ->
                coroutineContext.ensureActive()
                onProgress(n, list.size)
                if (n > 0) blocks += RtfPageBreak
                val layout = LayoutAnalyzer.analyze(ex.extract(i, false), true)
                layout.blocks.forEach { b ->
                    when (b) {
                        is ParagraphBlock -> {
                            val runs = TextRuns.ofParagraph(b.lines).map { RtfRun(it.text, it.bold, it.italic, it.size) }
                            val align = when (b.align) { Align.CENTER -> RtfAlign.CENTER; Align.END -> RtfAlign.END; else -> RtfAlign.START }
                            blocks += RtfPara(runs, b.rtl, b.headingLevel, align)
                        }
                        is TableBlock -> {
                            val grid = Array(b.rows) { Array(b.cols) { "" } }
                            b.cells.forEach { c -> if (c.row in 0 until b.rows && c.col in 0 until b.cols) grid[c.row][c.col] = c.text }
                            // grid columns are in visual order (0 = leftmost): RTL tables are reversed into reading order
                            val rows = grid.map { r -> if (b.rtl) r.reversed() else r.toList() }
                            blocks += RtfTable(rows, rtl = b.rtl, header = LayoutAnalyzer.headerLikely(b))
                        }
                        else -> {}
                    }
                }
            }
            onProgress(list.size, list.size)
            out.writeText(RtfWriter.write(blocks, title = d.documentInformation.title ?: src.nameWithoutExtension, author = d.documentInformation.author.orEmpty()), Charsets.US_ASCII)
        }
        out
    }
}
