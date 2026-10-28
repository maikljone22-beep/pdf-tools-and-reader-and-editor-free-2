package com.smartdocs.editor.ui.openall

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.ui.home.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Thumbnails and tile kinds for non-PDF files in the Files tab and Recents (open-all). */
object OpenAllThumbs {
    private const val WIDTH = 240
    private val gate = Semaphore(2)
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Tile kind used by ThumbBox. */
    fun kind(t: FType): String = when (t) {
        FType.PDF -> "pdf"
        FType.IMAGE, FType.SVG, FType.TIFF, FType.HEIF, FType.AVIF, FType.RAW -> "image"
        FType.VIDEO -> "video"; FType.AUDIO -> "audio"
        FType.XLSX, FType.XLS, FType.ODS, FType.CSV -> "sheet"
        FType.PPTX, FType.PPT, FType.ODP -> "slides"
        FType.ZIP, FType.SEVENZ, FType.TAR, FType.GZIP, FType.BZIP2, FType.XZ, FType.RAR -> "archive"
        FType.EPUB, FType.FB2, FType.MOBI, FType.CHM, FType.DJVU, FType.CBZ, FType.CB7, FType.CBR -> "book"
        FType.CODE, FType.HTML, FType.MHT -> "code"
        FType.EML, FType.MSG -> "mail"
        FType.FONT, FType.WOFF, FType.WOFF2 -> "font"
        FType.DXF, FType.DWG -> "cad"
        FType.VCF -> "contact"; FType.ICS -> "calendar"
        FType.TEXT, FType.MARKDOWN -> "text"
        FType.UNKNOWN -> "unknown"
        else -> "doc"
    }

    fun icon(kind: String): ImageVector = iconFor(when (kind) {
        "video" -> FType.VIDEO; "audio" -> FType.AUDIO; "sheet" -> FType.XLSX; "slides" -> FType.PPTX; "archive" -> FType.ZIP
        "book" -> FType.EPUB; "code" -> FType.CODE; "mail" -> FType.EML; "font" -> FType.FONT; "cad" -> FType.DXF
        "contact" -> FType.VCF; "calendar" -> FType.ICS; "text" -> FType.TEXT; "unknown" -> FType.UNKNOWN; else -> FType.DOCX
    })

    private fun videoFrame(src: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(src.absolutePath)
            r.getFrameAtTime(1_000_000) ?: r.frameAtTime
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Renders (once) a small JPEG for pictures the image loader cannot show directly, SVG and video frames. */
    suspend fun render(ctx: Context, src: File): File? = withContext(Dispatchers.IO) {
        if (!src.isFile) return@withContext null
        val t = FileSniffer.byExtension(src.name)
        if (!(t.isImage || t == FType.VIDEO)) return@withContext null
        val out = Thumbnails.fileFor(ctx, src)
        if (out.exists()) return@withContext out
        if (out.absolutePath in failed) return@withContext null
        gate.withPermit {
            runCatching {
                val bmp: Bitmap = if (t == FType.VIDEO) videoFrame(src) ?: throw IllegalStateException("no frame")
                else ImageLoad.decode(ctx, src, WIDTH * 2) ?: throw IllegalStateException("no image")
                val h = (WIDTH * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(1, WIDTH * 3)
                val small = Bitmap.createScaledBitmap(bmp, WIDTH, h, true)
                val tmp = File(out.parentFile, out.name + ".tmp")
                tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
                out
            }.getOrElse { failed += out.absolutePath; null }
        }
    }
}
