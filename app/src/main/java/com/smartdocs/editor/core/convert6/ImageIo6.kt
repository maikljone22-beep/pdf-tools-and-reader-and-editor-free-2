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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.coroutines.coroutineContext

class UnsupportedImage(val reason: String) : Exception(reason)

/** A picture could not be encoded (Bitmap.compress returned false: size limit of the format, or no space). */
class ImageWriteFailed(msg: String) : java.io.IOException(msg)

/** Page setup of image → PDF. */
data class PageSetup(val size: PageSize6 = PageSize6.A4, val orient: Orient6 = Orient6.AUTO, val fit: Fit6 = Fit6.CONTAIN, val marginPt: Float = 0f)

object ImageIo6 {

    @Suppress("DEPRECATION")
    fun compressFormat(f: ImgFmt): Bitmap.CompressFormat = when (f) {
        ImgFmt.JPG -> Bitmap.CompressFormat.JPEG
        ImgFmt.PNG -> Bitmap.CompressFormat.PNG
        ImgFmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }

    /** Pixels one decoded page may have: a quarter of the heap at 4 bytes per pixel, at most 24 MP. */
    fun pixelBudget(): Long = minOf(24_000_000L, Runtime.getRuntime().maxMemory() / 16).coerceAtLeast(4_000_000L)

    /**
     * Every page of an image file as a bitmap, ONE AT A TIME (TIFF: page by page from the file; others: one picture),
     * long side ≤ [maxDim]. The bitmap is recycled after [each] returns unless [each] returns false (kept by the caller).
     * Pages that cannot be decoded are reported to [skipped] with a reason (convert audit A-H1 / C7 / A-M16 / C23).
     */
    suspend fun forEachPage(f: File, maxDim: Int, skipped: (Int, String) -> Unit = { _, _ -> }, each: suspend (index: Int, bmp: Bitmap) -> Boolean) {
        val kind = FileKind6.of(f)
        if (kind != Kind6.TIFF) { val b = decode(f, maxDim); if (each(0, b)) b.recycle(); return }
        val ctx = coroutineContext
        var any = false
        var lastReason = "tiff"
        TiffDecoder.open(f).use { t ->
            for (i in 0 until t.pageCount) {
                ctx.ensureActive()
                val bmp = runCatching { tiffPage(t, f, i, maxDim) { !ctx.isActive } }.getOrElse { e -> if (e is OutOfMemoryError) null else if (e is kotlinx.coroutines.CancellationException) throw e else null }
                if (bmp == null) { lastReason = tiffReason(t, i); skipped(i, lastReason); continue }
                any = true
                if (each(i, bmp)) bmp.recycle()
            }
        }
        if (!any) throw UnsupportedImage(lastReason)
    }

    private fun tiffReason(t: TiffDecoder.TiffFile, i: Int): String = runCatching {
        val p = TiffDecoder.decodePage(t, i, 64, 4096)
        when { p.ccitt -> "tiff-ccitt"; p.unsupported > 0 -> "tiff-" + p.unsupported; else -> "tiff" }
    }.getOrDefault("tiff")

    /** One TIFF page as a bitmap: decoded pixels, JPEG strips / tiles (BitmapFactory) or a CCITT fax page (PdfBox). */
    private fun tiffPage(t: TiffDecoder.TiffFile, f: File, i: Int, maxDim: Int, cancelled: () -> Boolean): Bitmap? {
        val p = TiffDecoder.decodePage(t, i, maxDim, pixelBudget(), cancelled)
        p.argb?.let { return scaled(Bitmap.createBitmap(it, p.width, p.height, Bitmap.Config.ARGB_8888), maxDim) }
        p.jpeg?.let { pieces -> return jpegPieces(pieces, p.width, p.height, maxDim) }
        if (p.ccitt) return ccittPage(f, i, maxDim)
        return null
    }

    /** JPEG strips / tiles of a JPEG-in-TIFF page composed into one bitmap, subsampled while decoding. */
    private fun jpegPieces(pieces: List<JpegPiece>, w: Int, h: Int, maxDim: Int): Bitmap? {
        if (w <= 0 || h <= 0) return pieces.firstOrNull()?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }?.let { scaled(it, maxDim) }
        val s = Images6.sampleSize(w, h, maxDim)
        if (pieces.size == 1) {
            val b = BitmapFactory.decodeByteArray(pieces[0].bytes, 0, pieces[0].bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            return scaled(b, maxDim)
        }
        val out = Bitmap.createBitmap(maxOf(1, w / s), maxOf(1, h / s), Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        var drawn = 0
        pieces.forEach { pc ->
            val b = BitmapFactory.decodeByteArray(pc.bytes, 0, pc.bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@forEach
            c.drawBitmap(b, (pc.x / s).toFloat(), (pc.y / s).toFloat(), null); b.recycle(); drawn++
        }
        if (drawn == 0) { out.recycle(); return null }
        return scaled(out, maxDim)
    }

    /** A CCITT G3/G4 fax page decoded by PdfBox (convert audit C23: fax TIFFs could not be converted to JPG / OCR'd). */
    private fun ccittPage(f: File, i: Int, maxDim: Int): Bitmap? = runCatching {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { d ->
            val img = CCITTFactory.createFromFile(d, f, i)
            val b = img.image ?: return@use null
            scaled(b, maxDim)
        }
    }.getOrNull()

    /** All pages of an image file as bitmaps (TIFF: every page; others: one), long side ≤ [maxDim]. Prefer [forEachPage]. */
    suspend fun decodeAll(f: File, maxDim: Int): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        forEachPage(f, maxDim) { _, b -> out += b; false }
        return out
    }

    /** One picture, EXIF-rotated, long side ≤ [maxDim]. HEIC/HEIF needs Android 9+. */
    fun decode(f: File, maxDim: Int): Bitmap {
        val kind = FileKind6.of(f)
        if (kind == Kind6.TIFF) {
            TiffDecoder.open(f).use { t -> for (i in 0 until t.pageCount) tiffPage(t, f, i, maxDim) { false }?.let { return it } }
            throw UnsupportedImage("tiff")
        }
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

    /** Pixel size of an image file without decoding it (TIFF: its first page), or null. */
    fun sizeOf(f: File): Pair<Int, Int>? {
        if (FileKind6.of(f) == Kind6.TIFF) return runCatching { TiffDecoder.open(f).use { t -> TiffDecoder.pageSize(t, 0).let { it.first to it.second } } }.getOrNull()?.takeIf { it.first > 0 }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
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
        val ok = src.compress(compressFormat(fmt), quality.coerceIn(10, 100), bos)
        if (src !== b) src.recycle()
        if (!ok || bos.size() == 0) throw ImageWriteFailed("compress " + fmt.name + " " + b.width + "x" + b.height)
        return bos.toByteArray()
    }

    private fun exifRotation(f: File) = runCatching { androidx.exifinterface.media.ExifInterface(f.absolutePath).let { it.rotationDegrees != 0 || it.isFlipped } }.getOrDefault(false)

    /** Declared resolution of a picture (JFIF density or EXIF XResolution), or null. */
    private fun declaredDpi(f: File): Float? = runCatching {
        val head = f.inputStream().use { s -> ByteArray(64).let { b -> val n = s.read(b); b.copyOf(maxOf(0, n)) } }
        Images6.jfifDpi(head) ?: androidx.exifinterface.media.ExifInterface(f.absolutePath).let { e ->
            val unit = e.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_RESOLUTION_UNIT, 2)
            val x = e.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_X_RESOLUTION, 0.0).toFloat()
            if (x <= 0f) null else if (unit == 3) x * 2.54f else x
        }
    }.getOrNull()

    /**
     * Images → PDF with page size / orientation / fit / margins (convert audit A-H7 / A-H1 / A-M2 / A-M16 / A-L11):
     * the PDF keeps its picture streams in a temp file (not the heap), TIFF pages are read one at a time (JPEG-in-TIFF
     * pages are embedded as they are, CCITT pages through CCITTFactory), each bitmap is recycled as soon as its page is
     * written. Original JPEGs are embedded byte-for-byte only at quality ≥ 90 (or when already small) and without EXIF
     * rotation; otherwise they are re-encoded at [quality]. Pages that cannot be read are reported to [skipped];
     * when none could be added, [UnsupportedImage] is thrown.
     */
    suspend fun imagesToPdf(files: List<File>, out: File, setup: PageSetup, quality: Int = 88, onProgress: (Int, Int) -> Unit = { _, _ -> }, skipped: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val ctx = coroutineContext
        var lastReason = "empty"
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            files.forEachIndexed { k, f ->
                ctx.ensureActive()
                onProgress(k, files.size)
                val kind = FileKind6.of(f)
                try {
                    if (kind == Kind6.TIFF) tiffToPdf(doc, f, setup, quality, { !ctx.isActive }) { i, reason -> lastReason = reason; skipped(f.name + " (" + (i + 1) + ")") }
                    else {
                        val small = f.length() < 400L * 1024
                        val rawJpeg = kind == Kind6.JPG && !exifRotation(f) && f.length() < 25L * 1024 * 1024 && (quality >= 90 || small)
                        val img = if (rawJpeg) runCatching { f.inputStream().use { JPEGFactory.createFromStream(doc, it) } }.getOrNull() else null
                        if (img != null) addPage(doc, img, setup, Images6.pageDpi(img.width, img.height, declaredDpi(f)))
                        else {
                            val b = decode(f, 3000)
                            try { addPage(doc, xobject(doc, b, quality), setup, Images6.pageDpi(b.width, b.height, declaredDpi(f))) } finally { b.recycle() }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: UnsupportedImage) { lastReason = e.reason; skipped(f.name) }
                catch (e: OutOfMemoryError) { lastReason = "memory"; skipped(f.name) }
            }
            onProgress(files.size, files.size)
            if (doc.numberOfPages == 0) throw UnsupportedImage(lastReason)
            doc.documentInformation.creator = "SmartDocs"
            ctx.ensureActive()
            doc.save(out)
        }
        out
    }

    private fun tiffToPdf(doc: PDDocument, f: File, setup: PageSetup, quality: Int, cancelled: () -> Boolean, skipped: (Int, String) -> Unit) {
        TiffDecoder.open(f).use { t ->
            for (pi in 0 until t.pageCount) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                val p = TiffDecoder.decodePage(t, pi, 3000, pixelBudget(), cancelled)
                val dpi = if (p.dpiX > 0f) p.dpiX else 200f
                val img: PDImageXObject? = when {
                    p.argb != null -> { val b = Bitmap.createBitmap(p.argb, p.width, p.height, Bitmap.Config.ARGB_8888); try { xobject(doc, b, quality) } finally { b.recycle() } }
                    p.jpeg != null && p.jpeg.size == 1 -> runCatching { JPEGFactory.createFromByteArray(doc, p.jpeg[0].bytes) }.getOrNull()
                    p.jpeg != null -> jpegPieces(p.jpeg, p.width, p.height, 3000)?.let { b -> try { xobject(doc, b, quality) } finally { b.recycle() } }
                    p.ccitt -> runCatching { CCITTFactory.createFromFile(doc, f, pi) }.getOrNull()
                    else -> null
                }
                if (img == null) { skipped(pi, when { p.ccitt -> "tiff-ccitt"; p.unsupported > 0 -> "tiff-" + p.unsupported; else -> "tiff" }); continue }
                // the picture may be subsampled: its page keeps the physical size of the source page
                val effDpi = if (p.srcWidth > 0 && img.width > 0) dpi * img.width / p.srcWidth else dpi
                addPage(doc, img, setup, effDpi)
            }
        }
    }

    private fun xobject(doc: PDDocument, b: Bitmap, quality: Int): PDImageXObject =
        if (b.hasAlpha()) LosslessFactory.createFromImage(doc, b) else JPEGFactory.createFromImage(doc, b, quality.coerceIn(10, 100) / 100f)

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

    /**
     * Resize / re-encode one picture. [percent] 1..100 or [maxSide] > 0; the percentage is taken of the real picture
     * size (TIFF too, convert audit C24). Returns the written files; [capped] is called when the picture was larger
     * than [KEEP_MAX] and was made smaller (convert audit C25).
     */
    suspend fun convertImage(src: File, outDir: File, fmt: ImgFmt?, quality: Int, percent: Int = 100, maxSide: Int = 0, suffix: String = "",
                             out: ((String, String) -> File)? = null, capped: () -> Unit = {}): List<File> = withContext(Dispatchers.IO) {
        val srcMax = sizeOf(src)?.let { maxOf(it.first, it.second) }
        val outs = ArrayList<File>()
        val multi = FileKind6.of(src) == Kind6.TIFF && runCatching { TiffDecoder.open(src).use { it.pageCount } }.getOrDefault(1) > 1
        forEachPage(src, decodeCap(srcMax, percent, maxSide)) { i, b0 ->
            coroutineContext.ensureActive()
            val realMax = srcMax ?: maxOf(b0.width, b0.height)
            if (realMax > KEEP_MAX && maxSide <= 0 && percent >= 100) capped()
            val target = when { maxSide > 0 -> minOf(maxSide, realMax); percent < 100 -> (realMax * percent / 100).coerceAtLeast(16); else -> minOf(realMax, KEEP_MAX) }
            val (w, h) = Images6.fitWithin(b0.width, b0.height, target, target)
            val b = if (w != b0.width || h != b0.height) Bitmap.createScaledBitmap(b0, w, h, true) else b0
            try {
                val f = Images6.outFormat(src.extension, b.hasAlpha(), fmt)
                val base = src.nameWithoutExtension + suffix + if (multi) "_" + (i + 1) else ""
                val o = out?.invoke(base, f.ext) ?: unique(outDir, base, f.ext)
                o.writeBytes(encode(b, f, quality))
                outs += o
            } finally { if (b !== b0) b.recycle() }
            true
        }
        outs
    }

    /** Largest side "keep size" re-encodes at (bigger pictures are made smaller, with a note). */
    const val KEEP_MAX = 8000

    private fun decodeCap(srcMax: Int?, percent: Int, maxSide: Int): Int = when {
        maxSide > 0 -> maxSide
        srcMax != null && percent < 100 -> maxOf(16, srcMax * percent / 100)
        else -> KEEP_MAX
    }

    fun unique(dir: File, base: String, ext: String): File {
        dir.mkdirs()
        return com.smartdocs.editor.core.files.FileNames.unique(dir, base, ext)
    }

    /**
     * Selected pages stitched top-to-bottom into tall pictures (convert audit B06): the width follows the DPI option
     * (≤ 2400 px, never squeezed to an unreadable strip); the result is split into several pictures when it would pass
     * the format's height limit (WebP 16383 px, JPEG 65500 px) or the memory budget. [out] makes each part's file.
     */
    suspend fun longImage(ctx: Context, src: File, password: String?, pages: List<Int>, fmt: ImgFmt, dpi: Int, quality: Int, out: (Int, Int) -> File, onProgress: (Int, Int) -> Unit): List<File> = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        val files = ArrayList<File>()
        try {
            val list = pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
            val sizes = list.map { s.pageSize(it) }
            val width = (sizes.maxOf { it.first } * dpi / 72f).toInt().coerceIn(200, 2400)
            val heights = sizes.map { (pw, ph) -> maxOf(1, Math.round(ph * width / pw)) }
            val limit = Images6.maxSide(fmt)
            val parts = Images6.longParts(heights, width, limit, pixelBudget())
            var done = 0
            parts.forEachIndexed { partNo, range ->
                val total = range.sumOf { heights[it] }
                val k = if (total > limit) limit.toFloat() / total else 1f   // one page taller than the limit on its own
                val w = maxOf(1, (width * k).toInt())
                val big = Bitmap.createBitmap(w, maxOf(1, (total * k).toInt()), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(big); canvas.drawColor(Color.WHITE)
                    var y = 0
                    for (idx in range) {
                        coroutineContext.ensureActive()
                        onProgress(done++, list.size)
                        val h = maxOf(1, (heights[idx] * k).toInt())
                        val bmp = s.render(list[idx], w, h, annotations = true)
                        canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
                        s.invalidateCache()
                        y += h
                    }
                    val f = out(partNo, parts.size)
                    val ok = f.outputStream().use { big.compress(compressFormat(fmt), quality.coerceIn(10, 100), it) }
                    if (!ok || f.length() == 0L) throw ImageWriteFailed("long image " + w + "x" + big.height)
                    files += f
                } finally { big.recycle() }
            }
            onProgress(list.size, list.size)
        } finally { s.closeAsync() }
        files
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
                    val scale = minOf(Images6.renderScale(pw, ph, dpi, pixelBudget()), 4000f / maxOf(pw, ph))
                    val bmp = s.render(i, (pw * scale).toInt().coerceAtLeast(1), (ph * scale).toInt().coerceAtLeast(1), annotations = true)
                    val flat = onWhite(bmp)
                    val png = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    if (flat !== bmp) flat.recycle()
                    s.invalidateCache()
                    val f = unique(outDir, src.nameWithoutExtension + "_" + (i + 1).toString().padStart(3, '0'), "svg")
                    f.bufferedWriter().use { sb ->
                        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"${pw}pt\" height=\"${ph}pt\" viewBox=\"0 0 $pw $ph\">\n")
                        sb.append("<image x=\"0\" y=\"0\" width=\"$pw\" height=\"$ph\" xlink:href=\"data:image/png;base64,").append(Base64.getEncoder().encodeToString(png)).append("\"/>\n")
                        if (content != null && content.glyphs.isNotEmpty()) {
                            sb.append("<g fill=\"#000\" fill-opacity=\"0\" font-family=\"sans-serif\">\n")
                            runCatching { LayoutAnalyzer.buildLines(content.glyphs) }.getOrDefault(emptyList()).forEach { l ->
                                val t = l.text; if (t.isBlank()) return@forEach
                                sb.append("<text x=\"").append((if (l.rtl) l.x1 else l.x0).toString()).append("\" y=\"").append(l.baseline.toString()).append("\" font-size=\"").append(l.fontSize.toString())
                                    .append("\" textLength=\"").append((l.x1 - l.x0).toString()).append("\" lengthAdjust=\"spacingAndGlyphs\"").append(if (l.rtl) " direction=\"rtl\" text-anchor=\"start\"" else "")
                                    .append(">").append(Text6.xml(t)).append("</text>\n")
                            }
                            sb.append("</g>\n")
                        }
                        sb.append("</svg>\n")
                    }
                    outs += f
                }
                onProgress(list.size, list.size)
            }
        } finally { s.closeAsync() }
        outs
    }

    /**
     * Page renders at the DPI option (convert audit B11: no fixed 2400 px cap any more; only the memory budget lowers
     * the resolution, and [each] gets the effective dpi for the resolution tags). The bitmap belongs to the render
     * cache: [each] must not keep or recycle it.
     */
    suspend fun pageBitmaps(ctx: Context, src: File, password: String?, pages: List<Int>, dpi: Int, each: suspend (index: Int, w: Float, h: Float, bmp: Bitmap, effDpi: Int) -> Unit) = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try {
            val list = pages.filter { it in 0 until s.pageCount }.ifEmpty { (0 until s.pageCount).toList() }
            val budget = pixelBudget()
            list.forEach { i ->
                coroutineContext.ensureActive()
                val (w, h) = s.pageSize(i)
                val k = Images6.renderScale(w, h, dpi, budget)
                val bmp = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = true)
                try { each(i, w, h, bmp, Math.round(k * 72f)) } finally { s.invalidateCache() }
            }
        } finally { s.closeAsync() }
    }

    /** Size in points of the first selected page, or null. */
    suspend fun firstPageSize(ctx: Context, src: File, password: String?, pages: List<Int>): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try { (pages.firstOrNull { it in 0 until s.pageCount } ?: 0).takeIf { s.pageCount > 0 }?.let { s.pageSize(it) } } finally { s.closeAsync() }
    }

    /** Number of pages [pages] selects in [src] (for real progress totals, audit B25). */
    suspend fun selectedCount(ctx: Context, src: File, password: String?, pages: List<Int>): Int = withContext(Dispatchers.IO) {
        val s = PdfSession.open(ctx, src, password)
        try { pages.count { it in 0 until s.pageCount }.takeIf { it > 0 } ?: s.pageCount } finally { s.closeAsync() }
    }

    /** Page pictures as JPEG (for ODP / PPTX picture slides, PS / EPS / XPS): (index, page size in pt, jpeg, pixel size). */
    suspend fun pagePictures(ctx: Context, src: File, password: String?, pages: List<Int>, dpi: Int, quality: Int = 88, onEach: suspend (index: Int, w: Float, h: Float, jpeg: ByteArray, pxW: Int, pxH: Int) -> Unit) =
        pageBitmaps(ctx, src, password, pages, dpi) { i, w, h, bmp, _ ->
            val bytes = encode(bmp, ImgFmt.JPG, quality)
            onEach(i, w, h, bytes, bmp.width, bmp.height)
        }
}
