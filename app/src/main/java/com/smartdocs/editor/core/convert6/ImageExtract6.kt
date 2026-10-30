package com.smartdocs.editor.core.convert6

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.convert.P2
import com.smartdocs.editor.core.convert.Winding
import com.smartdocs.editor.core.convert.newPoint
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** One extracted picture (bytes already written to [file] in the work folder). */
class Extracted(
    val file: File, val page: Int, val indexOnPage: Int, val resource: String?, val caption: String?,
    val width: Int, val height: Int, val ext: String,
) { val size: Long get() = file.length() }

/**
 * «Extract all images»: embedded pictures of a PDF at their ORIGINAL resolution (DCT kept byte-for-byte as .jpg,
 * JPX as .jp2, everything else lossless PNG with the soft mask applied), de-duplicated by object and SHA-1, with
 * their XObject name and nearby caption; and the media of DOCX / PPTX / XLSX / ODT / ODP / EPUB packages.
 */
object ImageExtract6 {

    /** s6 "output format": the extension a picture gets for [fmt] (null = keep the original encoding). Pure. */
    fun targetExt(fmt: ImgFmt?, current: String): String = when (fmt) {
        null -> current
        ImgFmt.JPG -> "jpg"; ImgFmt.PNG -> "png"; ImgFmt.WEBP -> "webp"
        else -> current
    }

    /**
     * Re-encode [items] into [fmt] (JPG / PNG / WebP at [quality]); pictures already in that format, and ones Android
     * cannot decode (JPEG 2000, CMYK TIFF …), stay as they are. Returns the new list (files replaced in the work folder).
     */
    suspend fun convertAll(items: List<Extracted>, fmt: ImgFmt?, quality: Int, gray: Boolean = false, maxSide: Int = 0): List<Extracted> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        // FINAL_CHECK_2 Low (B #32): colour space (grayscale) and resolution (longest side) on top of the format
        if (fmt == null && !gray && maxSide <= 0) return@withContext items
        items.map { e ->
            coroutineContext.ensureActive()
            val ext = targetExt(fmt, e.ext)
            val needResize = maxSide > 0 && maxOf(e.width, e.height) > maxSide
            if ((ext == e.ext || (ext == "jpg" && e.ext == "jpeg")) && !gray && !needResize) return@map e
            var bmp = runCatching { android.graphics.BitmapFactory.decodeFile(e.file.path) }.getOrNull() ?: return@map e
            try {
                if (needResize) {
                    val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                    val s = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                    if (s !== bmp) { bmp.recycle(); bmp = s }
                }
                if (gray) {
                    val g = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true); if (g !== bmp) { bmp.recycle(); bmp = g }
                    val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    com.smartdocs.editor.core.tools5.PixelOps.toGray(px); bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                }
                val outFmt = fmt ?: when (ext) { "png" -> ImgFmt.PNG; "webp" -> ImgFmt.WEBP; "jpg", "jpeg" -> ImgFmt.JPG; else -> ImgFmt.PNG }
                val outExt = targetExt(outFmt, ext)
                val out = File(e.file.parentFile, e.file.nameWithoutExtension + "." + outExt)
                val tmp = File(out.parentFile, out.name + ".part")
                val ok = tmp.outputStream().use { os -> bmp.compress(ImageIo6.compressFormat(outFmt), if (outFmt == ImgFmt.PNG) 100 else quality.coerceIn(30, 100), os) }
                if (!ok) { tmp.delete(); return@map e }
                if (out != e.file) e.file.delete()
                tmp.renameTo(out)
                Extracted(out, e.page, e.indexOnPage, e.resource, e.caption, bmp.width, bmp.height, outExt)
            } finally { bmp.recycle() }
        }
    }

    private class Seen { val objs = IdentityHashMap<Any, Boolean>(); val hashes = HashSet<String>() }

    /** Pictures larger than this are decoded subsampled (ARGB memory). */
    private const val MAX_DECODE_PIXELS = 24_000_000L

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** Encoded bytes + extension of a PDF image, keeping the original encoding when possible. */
    private fun encode(img: PDImage): Pair<ByteArray, String>? {
        if (img is PDImageXObject) {
            val filters = runCatching { img.stream.filters.map { it.name } }.getOrDefault(emptyList())
            val hasMask = img.cosObject.containsKey(COSName.SMASK) || img.cosObject.containsKey(COSName.MASK)
            val comps = runCatching { img.colorSpace.numberOfComponents }.getOrDefault(3)
            if (!hasMask && filters.lastOrNull() == "DCTDecode" && comps != 4) {
                runCatching { img.stream.createInputStream(listOf("DCTDecode")).use { it.readBytes() } }.getOrNull()
                    ?.takeIf { it.size > 4 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }?.let { return it to "jpg" }
            }
            if (!hasMask && filters.lastOrNull() == "JPXDecode") {
                runCatching { img.stream.createInputStream(listOf("JPXDecode")).use { it.readBytes() } }.getOrNull()?.takeIf { it.size > 12 }?.let { return it to "jp2" }
            }
            // fax scans (CCITT G3/G4): the stream as a TIFF, never decoded (convert audit B18: 1-bit pages ran out of memory)
            if (!hasMask && filters.size == 1 && filters[0] == "CCITTFaxDecode") runCatching {
                val parms = (img.cosObject.getDictionaryObject(COSName.DECODE_PARMS) as? com.tom_roush.pdfbox.cos.COSDictionary)
                    ?: ((img.cosObject.getDictionaryObject(COSName.DECODE_PARMS) as? com.tom_roush.pdfbox.cos.COSArray)?.getObject(0) as? com.tom_roush.pdfbox.cos.COSDictionary)
                val k = parms?.getInt(COSName.K, 0) ?: 0
                val black = parms?.getBoolean(COSName.getPDFName("BlackIs1"), false) ?: false
                val inverted = (img.cosObject.getDictionaryObject(COSName.DECODE) as? com.tom_roush.pdfbox.cos.COSArray)?.let { it.size() >= 1 && (it.getObject(0) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() == 1f } == true
                val raw = img.stream.createInputStream(listOf("CCITTFaxDecode")).use { it.readBytes() }
                if (raw.isNotEmpty()) return TiffWriter.ccitt(raw, img.width, img.height, k, black != inverted) to "tif"
            }
        }
        // big pictures are decoded subsampled instead of failing (convert audit B18)
        val px = img.width.toLong() * img.height
        val sub = if (px > MAX_DECODE_PIXELS) Math.ceil(Math.sqrt(px.toDouble() / MAX_DECODE_PIXELS)).toInt().coerceAtLeast(2) else 1
        val bmp: Bitmap = (if (sub > 1) runCatching { img.getImage(null, sub) }.getOrNull() else null)
            ?: runCatching { if (sub == 1) img.image else null }.getOrNull() ?: return null
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray() to "png"
    }

    private class Placed(val img: PDImage, val name: String?, val x0: Float, val top: Float, val x1: Float, val bottom: Float)

    /** Records every drawn image (incl. inside forms and inline images) with its box (top-left page space). */
    private class Placer(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val placed = ArrayList<Placed>()
        private var lastName: String? = null
        private val llx = page.cropBox.lowerLeftX
        private val ury = page.cropBox.upperRightY

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == "Do") lastName = (operands.firstOrNull() as? COSName)?.name else if (operator.name == "BI") lastName = null
            super.processOperator(operator, operands)
        }

        override fun drawImage(pdImage: PDImage) {
            if (placed.size > 3000) return
            val m = graphicsState.currentTransformationMatrix
            val xs = floatArrayOf(m.translateX, m.translateX + m.scaleX, m.translateX + m.shearX, m.translateX + m.scaleX + m.shearX)
            val ys = floatArrayOf(m.translateY, m.translateY + m.shearY, m.translateY + m.scaleY, m.translateY + m.shearY + m.scaleY)
            placed += Placed(pdImage, lastName, xs.min() - llx, ury - ys.max(), xs.max() - llx, ury - ys.min())
            lastName = null
        }

        override fun appendRectangle(p0: P2, p1: P2, p2: P2, p3: P2) = Unit
        override fun clip(windingRule: Winding) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): P2 = newPoint(0f, 0f)
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun shadingFill(shadingName: COSName) = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Winding) = Unit
        override fun fillAndStrokePath(windingRule: Winding) = Unit
    }

    /** Words of one page grouped into lines (top-left page space). */
    private class LineStripper : PDFTextStripper() {
        val words = ArrayList<TextLine>()
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val tp = textPositions ?: return
            if (text.isNullOrBlank() || tp.isEmpty()) return
            words += TextLine(text, tp.minOf { it.xDirAdj }, tp.minOf { it.yDirAdj - it.heightDir }, tp.maxOf { it.xDirAdj + it.widthDirAdj }, tp.maxOf { it.yDirAdj })
        }

        fun lines(doc: PDDocument, page: Int): List<TextLine> {
            sortByPosition = true; startPage = page + 1; endPage = page + 1
            words.clear(); getText(doc)
            val out = ArrayList<MutableList<TextLine>>()
            words.sortedBy { it.top }.forEach { w ->
                val l = out.lastOrNull()
                if (l != null && kotlin.math.abs(l.first().top - w.top) < 3f) l += w else out += mutableListOf(w)
            }
            return out.map { ws ->
                val rtl = Text6.rtlDominant(ws.joinToString(" ") { it.text })
                val ordered = if (rtl) ws.sortedByDescending { it.x0 } else ws.sortedBy { it.x0 }
                TextLine(ordered.joinToString(" ") { it.text }, ws.minOf { it.x0 }, ws.minOf { it.top }, ws.maxOf { it.x1 }, ws.maxOf { it.bottom })
            }
        }
    }

    /** [onSkipped] is told about each picture that could not be extracted (unsupported JBIG2, out of memory …). */
    suspend fun fromPdf(src: File, password: String?, pages: List<Int>, minSide: Int, workDir: File, onProgress: (Int, Int) -> Unit, onSkipped: () -> Unit = {}): List<Extracted> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val out = ArrayList<Extracted>()
        val seen = Seen()
        PdfFlow6.load(src, password).use { doc ->
            val list = pages.filter { it in 0 until doc.numberOfPages }.ifEmpty { (0 until doc.numberOfPages).toList() }
            val stripper = LineStripper()
            list.forEachIndexed { k, pi ->
                coroutineContext.ensureActive()
                onProgress(k, list.size)
                val page = doc.getPage(pi)
                val placer = Placer(page)
                runCatching { placer.processPage(page) }
                var n = 0
                var lines: List<TextLine>? = null
                fun take(img: PDImage, name: String?, box: Placed?) {
                    if (img.isStencil) return
                    val key: Any = (img as? PDImageXObject)?.cosObject ?: img
                    if (seen.objs.put(key, true) != null) return
                    if (minOf(img.width, img.height) < minSide) return
                    val enc = try { encode(img) } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
                    if (enc == null) { onSkipped(); return }
                    if (!seen.hashes.add(sha1(enc.first))) return
                    n++
                    val caption = if (box != null && page.rotation % 360 == 0) {
                        if (lines == null) lines = runCatching { stripper.lines(doc, pi) }.getOrDefault(emptyList())
                        ImageNaming.caption(lines!!, box.x0, box.top, box.x1, box.bottom)
                    } else null
                    val f = File(workDir, "x_${out.size + 1}_${System.nanoTime()}.${enc.second}")
                    f.writeBytes(enc.first)
                    out += Extracted(f, pi + 1, n, name, caption, img.width, img.height, enc.second)
                }
                placer.placed.forEach { take(it.img, it.name, it) }
                // pictures that are not drawn by the page itself: unused resources, tiling patterns, annotation appearances
                fun walk(res: PDResources?, depth: Int) {
                    if (res == null || depth > 10) return
                    res.xObjectNames.forEach { nm ->
                        when (val x = runCatching { res.getXObject(nm) }.getOrNull()) {
                            is PDImageXObject -> take(x, nm.name, null)
                            is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject -> walk(x.resources, depth + 1)
                            else -> {}
                        }
                    }
                    res.patternNames.forEach { nm -> (runCatching { res.getPattern(nm) }.getOrNull() as? PDTilingPattern)?.let { walk(it.resources, depth + 1) } }
                }
                walk(page.resources, 0)
                runCatching { page.annotations }.getOrDefault(emptyList()).forEach { a ->
                    runCatching { a.normalAppearanceStream?.resources }.getOrNull()?.let { walk(it, 1) }
                }
            }
            onProgress(list.size, list.size)
        }
        out
    }

    private fun resolve(base: String, href: String): String {
        val h = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        if (h.startsWith("/")) return h.trimStart('/')
        val parts = (base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() } + h.split('/')).toMutableList()
        val out = ArrayList<String>()
        parts.forEach { p ->
            when (p) {
                ".", "" -> {}
                ".." -> { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
                else -> out += p
            }
        }
        return out.joinToString("/")
    }

    private fun attr(tag: String, name: String): String? = Regex("""(?i)\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    /** Media of an Office / ODF / EPUB package, named by alt text / slide number when known. */
    suspend fun fromPackage(src: File, kind: Kind6, minSide: Int, workDir: File): List<Extracted> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val out = ArrayList<Extracted>()
        ZipFile(src).use { z ->
            fun text(name: String): String? = z.getEntry(name)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val alt = HashMap<String, String>(); val slide = HashMap<String, Int>()
            val names = z.entries().asSequence().map { it.name }.toList()
            val media: List<String> = when (kind) {
                Kind6.DOCX, Kind6.PPTX, Kind6.XLSX -> {
                    val prefix = when (kind) { Kind6.DOCX -> "word/"; Kind6.PPTX -> "ppt/"; else -> "xl/" }
                    names.filter { it.endsWith(".xml") && it.startsWith(prefix) && !it.contains("/_rels/") }.forEach { part ->
                        val rels = text(part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels") ?: return@forEach
                        val idTarget = Regex("""<Relationship\b[^>]*>""").findAll(rels).mapNotNull { m -> val id = attr(m.value, "Id"); val t = attr(m.value, "Target"); if (id != null && t != null && attr(m.value, "TargetMode") != "External") id to resolve(part, t) else null }.toMap()
                        val xml = text(part) ?: return@forEach
                        val slideNo = Regex("""slide(\d+)\.xml$""").find(part)?.groupValues?.get(1)?.toIntOrNull()
                        var lastDescr: String? = null
                        Regex("""\b(?:descr|title)\s*=\s*"([^"]*)"|r:(?:embed|link)\s*=\s*"([^"]+)"""").findAll(xml).forEach { m ->
                            if (m.groupValues[1].isNotEmpty()) lastDescr = HtmlFlow.decode(m.groupValues[1])
                            else if (m.groupValues[2].isNotEmpty()) {
                                val target = idTarget[m.groupValues[2]]
                                if (target != null) {
                                    lastDescr?.takeIf { it.isNotBlank() }?.let { alt.putIfAbsent(target, it) }
                                    if (slideNo != null) slide.putIfAbsent(target, slideNo)
                                }
                                lastDescr = null
                            }
                        }
                    }
                    names.filter { it.startsWith(prefix + "media/") }
                }
                Kind6.ODT, Kind6.ODP, Kind6.ODS -> {
                    text("content.xml")?.let { xml ->
                        var pageNo = 0
                        Regex("""<draw:page\b|<draw:frame\b[^>]*>(.*?)</draw:frame>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                            if (m.value.startsWith("<draw:page")) { pageNo++; return@forEach }
                            val href = Regex("""xlink:href="([^"]+)"""").find(m.value)?.groupValues?.get(1) ?: return@forEach
                            val title = Regex("""<svg:(?:title|desc)>(.*?)</svg:""", RegexOption.DOT_MATCHES_ALL).find(m.value)?.groupValues?.get(1)
                                ?: attr(m.value.substringBefore('>'), "draw:name")
                            title?.let { alt.putIfAbsent(href.removePrefix("./"), HtmlFlow.decode(it)) }
                            if (pageNo > 0) slide.putIfAbsent(href.removePrefix("./"), pageNo)
                        }
                    }
                    names.filter { it.startsWith("Pictures/") }
                }
                Kind6.EPUB -> {
                    names.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }.forEachIndexed { ci, part ->
                        val x = text(part) ?: return@forEachIndexed
                        Regex("""(?is)<(img|image)\b[^>]*>""").findAll(x).forEach { m ->
                            val s = attr(m.value, "src") ?: attr(m.value, "xlink:href") ?: attr(m.value, "href") ?: return@forEach
                            val p = resolve(part, s)
                            attr(m.value, "alt")?.takeIf { it.isNotBlank() }?.let { alt.putIfAbsent(p, HtmlFlow.decode(it)) }
                            slide.putIfAbsent(p, ci + 1)
                        }
                    }
                    names.filter { n -> n.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp") }
                }
                else -> emptyList()
            }
            val seen = HashSet<String>()
            media.sortedWith(compareBy({ slide[it] ?: Int.MAX_VALUE }, { it })).forEachIndexed { i, path ->
                coroutineContext.ensureActive()
                val e = z.getEntry(path) ?: return@forEachIndexed
                if (e.size > 200L * 1024 * 1024) return@forEachIndexed
                val bytes = z.getInputStream(e).use { it.readBytes() }
                if (!seen.add(sha1(bytes))) return@forEachIndexed
                val ext = (Images6.sniffExt(bytes) ?: path.substringAfterLast('.', "bin")).lowercase()
                val dims = ImageDims.of(bytes) ?: BitmapFactory.Options().apply { inJustDecodeBounds = true }.let { o -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o); if (o.outWidth > 0) o.outWidth to o.outHeight else null }
                if (dims != null && minOf(dims.first, dims.second) < minSide) return@forEachIndexed
                val f = File(workDir, "x_${i + 1}_${System.nanoTime()}.$ext")
                f.writeBytes(bytes)
                val pg = slide[path] ?: 1
                out += Extracted(f, pg, out.count { it.page == pg } + 1, path.substringAfterLast('/').substringBeforeLast('.'), alt[path], dims?.first ?: 0, dims?.second ?: 0, ext)
            }
        }
        out
    }

    /** Final file names for [items] in [mode], de-duplicated. */
    fun names(items: List<Extracted>, mode: NameMode, docName: String, overrides: Map<Int, String> = emptyMap()): List<String> =
        ImageNaming.dedupe(items.mapIndexed { i, it ->
            val base = overrides[i]?.let { o -> ImageNaming.sanitize(o) }?.ifEmpty { null } ?: ImageNaming.baseName(mode, docName, it.page, it.indexOnPage, it.resource, it.caption)
            base + "." + it.ext
        })
}
