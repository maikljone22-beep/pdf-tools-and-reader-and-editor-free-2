package com.smartdocs.editor.core.convert6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.PdfContentExtractor
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.CCITTFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.coroutines.coroutineContext

class UnsupportedImage(val reason: String) : Exception(reason)

/** Page setup of image → PDF. */
data class PageSetup(val size: PageSize6 = PageSize6.A4, val orient: Orient6 = Orient6.AUTO, val fit: Fit6 = Fit6.CONTAIN, val marginPt: Float = 0f)

object ImageIo6 {

    @Suppress("DEPRECATION")
    fun compressFormat(f: ImgFmt): Bitmap.CompressFormat = when (f) {
        ImgFmt.JPG -> Bitmap.CompressFormat.JPEG
        ImgFmt.PNG -> Bitmap.CompressFormat.PNG
        ImgFmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }

    /** All pages of an image file as bitmaps (TIFF: every page; others: one), long side ≤ [maxDim]. */
    fun decodeAll(f: File, maxDim: Int): List<Bitmap> {
        val kind = FileKind6.of(f)
        if (kind == Kind6.TIFF) {
            val pages = TiffDecoder.decode(f.readBytes())
            val out = pages.mapNotNull { p -> p.argb?.let { scaled(Bitmap.createBitmap(it, p.width, p.height, Bitmap.Config.ARGB_8888), maxDim) } }
            if (out.isEmpty()) throw UnsupportedImage(if (pages.any { it.ccitt }) "tiff-ccitt" else "tiff")
            return out
        }
        return listOf(decode(f, maxDim))
    }

    /** One picture, EXIF-rotated, long side ≤ [maxDim]. HEIC/HEIF needs Android 9+. */
    fun decode(f: File, maxDim: Int): Bitmap {
        val kind = FileKind6.of(f)
        if (kind == Kind6.TIFF) return decodeAll(f, maxDim).first()
        PdfOps.decodeScaled(f, maxDim)?.let { return it }
        if (Build.VERSION.SDK_INT >= 28) {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = Images6.sampleSize(info.size.width, info.size.height, maxDim)
                    if (s > 1) d.setTargetSampleSize(s)
                }
            }.getOrNull()
            if (bmp != null) return scaled(bmp, maxDim)
        }
        throw UnsupportedImage(if (kind == Kind6.HEIC) "heic" else kind.name.lowercase())
    }

    private fun scaled(b: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxDim) return b
        val (w, h) = Images6.fitWithin(b.width, b.height, maxDim, maxDim)
        return Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
    }

    fun onWhite(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    fun encode(b: Bitmap, fmt: ImgFmt, quality: Int): ByteArray {
        val src = if (fmt == ImgFmt.JPG) onWhite(b) else b
        val bos = ByteArrayOutputStream()
        src.compress(compressFormat(fmt), quality.coerceIn(10, 100), bos)
        if (src !== b) src.recycle()
        return bos.toByteArray()
    }

    private fun exifRotation(f: File) = runCatching { androidx.exifinterface.media.ExifInterface(f.absolutePath).rotationDegrees }.getOrDefault(0)

    /**
     * Images → PDF with page size / orientation / fit / margins. Original JPEGs are embedded byte-for-byte when they
     * need no rotation; TIFF G3/G4 pages go through CCITTFactory. [quality] for re-encoded pictures.
     */
    suspend fun imagesToPdf(files: List<File>, out: File, setup: PageSetup, quality: Int = 88, onProgress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            files.forEachIndexed { k, f ->
                coroutineContext.ensureActive()
                onProgress(k, files.size)
                val kind = FileKind6.of(f)
                if (kind == Kind6.TIFF) {
                    val pages = TiffDecoder.decode(f.readBytes())
                    pages.forEachIndexed { pi, p ->
                        val img: PDImageXObject? = when {
                            p.argb != null -> { val b = Bitmap.createBitmap(p.argb, p.width, p.height, Bitmap.Config.ARGB_8888); val s = scaled(b, 3000); xobject(doc, s, quality).also { s.recycle() } }
                            p.ccitt -> runCatching { CCITTFactory.createFromFile(doc, f, pi) }.getOrNull()
                            else -> null
                        }
                        if (img != null) addPage(doc, img, setup, if (p.dpiX > 0f) p.dpiX else 200f)
                    }
                    return@forEachIndexed
                }
                val rawJpeg = kind == Kind6.JPG && exifRotation(f) == 0 && f.length() < 25L * 1024 * 1024
                val img = if (rawJpeg) runCatching { f.inputStream().use { JPEGFactory.createFromStream(doc, it) } }.getOrNull() else null
                if (img != null) addPage(doc, img, setup, 96f)
                else { val b = decode(f, 3000); addPage(doc, xobject(doc, b, quality), setup, 96f); b.recycle() }
            }
            onProgress(files.size, files.size)
            if (doc.numberOfPages == 0) throw UnsupportedImage("empty")
            doc.documentInformation.creator = "SmartDocs"
            doc.save(out)
        }
        out
    }

    private fun xobject(doc: PDDocument, b: Bitmap, quality: Int): PDImageXObject =
        if (b.hasAlpha()) LosslessFactory.createFromImage(doc, b) else JPEGFactory.createFromImage(doc, b, quality / 100f)

    private fun addPage(doc: PDDocument, img: PDImageXObject, setup: PageSetup, dpi: Float) {
        val p = Images6.place(img.width, img.height, setup.size, setup.orient, setup.fit, setup.marginPt, dpi)
        val page = PDPage(PDRectangle(p.pageW, p.pageH))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            if (setup.fit == Fit6.COVER) { cs.saveGraphicsState(); cs.addRect(setup.marginPt, setup.marginPt, p.pageW - 2 * setup.marginPt, p.pageH - 2 * setup.marginPt); cs.clip() }
            cs.drawImage(img, p.x, p.y, p.w, p.h)
            if (setup.fit == Fit6.COVER) cs.restoreGraphicsState()
        }
    }

    /** Resize / re-encode one picture. [percent] 1..100 or [maxSide] > 0. Returns the written file. */
    suspend fun convertImage(src: File, outDir: File, fmt: ImgFmt?, quality: Int, percent: Int = 100, maxSide: Int = 0, suffix: String = ""): List<File> = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(src.absolutePath, it) }
        val srcMax = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: 8000
        val target = when { maxSide > 0 -> minOf(maxSide, srcMax); percent < 100 -> (srcMax * percent / 100).coerceAtLeast(16); else -> minOf(srcMax, 8000) }
        val bitmaps = decodeAll(src, maxOf(target, 16))
        val outs = ArrayList<File>()
        bitmaps.forEachIndexed { i, b0 ->
            coroutineContext.ensureActive()
            val (w, h) = Images6.fitWithin(b0.width, b0.height, target, target)
            val b = if (w != b0.width || h != b0.height) Bitmap.createScaledBitmap(b0, w, h, true).also { if (it !== b0) b0.recycle() } else b0
            val f = Images6.outFormat(src.extension, b.hasAlpha(), fmt)
            val base = src.nameWithoutExtension + suffix + if (bitmaps.size > 1) "_" + (i + 1) else ""
            val out = unique(outDir, base, f.ext)
            out.writeBytes(encode(b, f, quality))
            b.recycle()
            outs += out
        }
        outs
    }

    fun unique(dir: File, base: String, ext: String): File {
        dir.mkdirs()
        var f = File(dir, "$base.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "$base ($n).$ext"); n++ }
        return f
    }

    /** Selected pages stitched top-to-bottom into one tall picture (total size capped for memory). */
    suspend fun longImage(ctx: Context, src: File, password: String?, pages: List<Int>, fmt: ImgFmt, dpi: Int, quality: Int, out: File, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try {
            val list = pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
            val sizes = list.map { s.pageSize(it) }
            val width = (sizes.maxOf { it.first } * dpi / 72f).toInt().coerceIn(200, 2400)
            val (w, heights) = Images6.stitch(sizes, width, 60_000_000L)
            val big = Bitmap.createBitmap(w, heights.sum().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(big); canvas.drawColor(Color.WHITE)
            var y = 0
            list.forEachIndexed { k, i ->
                coroutineContext.ensureActive()
                onProgress(k, list.size)
                val bmp = s.render(i, w, heights[k], annotations = true)
                canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
                s.invalidateCache()
                y += heights[k]
            }
            onProgress(list.size, list.size)
            out.outputStream().use { big.compress(compressFormat(fmt), quality.coerceIn(10, 100), it) }
            big.recycle()
        } finally { s.closeAsync() }
        out
    }

    /**
     * PDF pages → SVG: the page picture (PNG) plus an invisible, selectable / searchable text layer at the glyph
     * positions. Not vector artwork (PDFium renders to pixels).
     */
    suspend fun svgPages(ctx: Context, src: File, password: String?, pages: List<Int>, dpi: Int, outDir: File, onProgress: (Int, Int) -> Unit): List<File> = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        val outs = ArrayList<File>()
        try {
            PdfFlow6.load(src, password).use { doc ->
                val ex = PdfContentExtractor(doc)
                val list = pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
                list.forEachIndexed { k, i ->
                    coroutineContext.ensureActive()
                    onProgress(k, list.size)
                    val content = runCatching { ex.extract(i, false) }.getOrNull()
                    val (pw, ph) = content?.let { it.width to it.height } ?: s.pageSize(i)
                    val scale = minOf(dpi / 72f, 4000f / maxOf(pw, ph))
                    val bmp = s.render(i, (pw * scale).toInt().coerceAtLeast(1), (ph * scale).toInt().coerceAtLeast(1), annotations = true)
                    val flat = onWhite(bmp)
                    val png = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    if (flat !== bmp) flat.recycle()
                    s.invalidateCache()
                    val sb = StringBuilder()
                    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"${pw}pt\" height=\"${ph}pt\" viewBox=\"0 0 $pw $ph\">\n")
                    sb.append("<image x=\"0\" y=\"0\" width=\"$pw\" height=\"$ph\" xlink:href=\"data:image/png;base64,").append(Base64.getEncoder().encodeToString(png)).append("\"/>\n")
                    if (content != null && content.glyphs.isNotEmpty()) {
                        sb.append("<g fill=\"#000\" fill-opacity=\"0\" font-family=\"sans-serif\">\n")
                        runCatching { LayoutAnalyzer.buildLines(content.glyphs) }.getOrDefault(emptyList()).forEach { l ->
                            val t = l.text; if (t.isBlank()) return@forEach
                            sb.append("<text x=\"").append(if (l.rtl) l.x1 else l.x0).append("\" y=\"").append(l.baseline).append("\" font-size=\"").append(l.fontSize)
                                .append("\" textLength=\"").append(l.x1 - l.x0).append("\" lengthAdjust=\"spacingAndGlyphs\"").append(if (l.rtl) " direction=\"rtl\" text-anchor=\"start\"" else "")
                                .append(">").append(Text6.xml(t)).append("</text>\n")
                        }
                        sb.append("</g>\n")
                    }
                    sb.append("</svg>\n")
                    val f = unique(outDir, src.nameWithoutExtension + "_" + (i + 1).toString().padStart(3, '0'), "svg")
                    f.writeText(sb.toString())
                    outs += f
                }
                onProgress(list.size, list.size)
            }
        } finally { s.closeAsync() }
        outs
    }

    /** Page pictures (for ODP / PPTX picture slides). PDFium bitmaps are copied before the cache is cleared. */
    suspend fun pagePictures(ctx: Context, src: File, password: String?, pages: List<Int>, dpi: Int, onEach: suspend (index: Int, w: Float, h: Float, jpeg: ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try {
            val list = pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
            list.forEach { i ->
                coroutineContext.ensureActive()
                val (w, h) = s.pageSize(i)
                val k = minOf(dpi / 72f, 2400f / maxOf(w, h))
                val bmp = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = true)
                val bytes = encode(bmp, ImgFmt.JPG, 88)
                s.invalidateCache()
                onEach(i, w, h, bytes)
            }
        } finally { s.closeAsync() }
    }
}
