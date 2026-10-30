package com.smartdocs.editor.ui.openall

import android.content.Context
import com.smartdocs.editor.core.convert6.ImageIo6
import com.smartdocs.editor.core.convert6.PageSetup
import com.smartdocs.editor.core.convert6.ToPdf6
import com.smartdocs.editor.core.openall.ArchiveReader
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.core.tools5.ToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** "Convert to PDF" of every viewer (open-all): the existing converters where they exist, own renderers otherwise. */
object ViewerConvert {

    suspend fun toPdf(ctx: Context, v: ViewFile, out: File) {
        val f = v.file ?: throw IllegalStateException("no file")
        when (v.type) {
            FType.SVG -> SvgRender.toPdf(f, out)
            FType.DXF -> DxfRender.toPdf(f, out)
            FType.XPS -> XpsRender.toPdf(ctx, f, out)
            FType.CBZ, FType.CB7 -> {
                val fmt = ArchiveReader.formatOf(f, v.type) ?: throw IllegalStateException("format")
                val dir = OpenAll.workDir(ctx, "comic_pdf_${System.nanoTime()}")
                try {
                    val pages = withContext(Dispatchers.IO) {
                        ArchiveReader.list(f, fmt).filter { !it.isDir && !it.unsafe && FileSniffer.isImageName(it.path) }
                            .sortedWith { a, b -> com.smartdocs.editor.core.openall.NaturalOrder.compare(a.path, b.path) }
                            .map { ArchiveReader.extract(f, fmt, it, dir) }
                    }
                    ImageIo6.imagesToPdf(pages, out, PageSetup(), 85)
                } finally { dir.deleteRecursively() }
            }
            FType.RAW, FType.AVIF, FType.HEIF, FType.IMAGE, FType.TIFF -> runCatching { ToPdf6.convert(ctx, f, out) }.getOrElse {
                // formats the convert hub does not know: decode through the platform and print the bitmap
                val b = withContext(Dispatchers.IO) { ImageLoad.decode(ctx, f, 2400) } ?: throw it
                canvasToPdf(out, listOf(b.width * 0.75f to b.height * 0.75f)) { c, _ -> c.scale(0.75f, 0.75f); c.drawBitmap(b, 0f, 0f, null) }
            }
            // fix/files-index: .ppt through the converter (slide layout and pictures), its text print as the fallback
            FType.PPT -> if (runCatching { ToPdf6.convert(ctx, f, out) }.isFailure) ToPdf.html(ctx, withContext(Dispatchers.IO) { WebDocs.forPrint(ctx, v) }, out)
            FType.MHT, FType.MSG, FType.FB2, FType.MOBI, FType.VCF, FType.ICS, FType.CODE, FType.CSV, FType.TEXT, FType.EML, FType.MARKDOWN, FType.HTML -> {
                val html = withContext(Dispatchers.IO) { WebDocs.forPrint(ctx, v) }
                ToPdf.html(ctx, html, out)
            }
            else -> ToPdf6.convert(ctx, f, out)
        }
    }
}
