package com.smartdocs.editor.core.tools5

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Space categories of the audit. */
enum class SpaceCat { IMAGES, FONTS, CONTENT, FORMS_XOBJECTS, METADATA, ATTACHMENTS, THUMBNAILS, COLOR, ANNOTATIONS, OTHER, OVERHEAD }

class SpaceAudit(val fileSize: Long, val bytes: Map<SpaceCat, Long>) {
    fun share(c: SpaceCat): Float = if (fileSize <= 0) 0f else (bytes[c] ?: 0L).toFloat() / fileSize
}

data class OptimizeOptions(
    val imageDpi: Int = 150,
    val jpegQuality: Int = 70,
    val recompressJpeg: Boolean = true,
    val grayImages: Boolean = false,
    val dedupImages: Boolean = true,
    val stripMetadata: Boolean = true,
    val stripJavaScript: Boolean = true,
    val stripThumbnails: Boolean = true,
    val stripAttachments: Boolean = false,
    val stripPieceInfo: Boolean = true,
    val flateUncompressed: Boolean = true,
    val onlyIfSmaller: Boolean = true,
)

class OptimizeResult(val before: SpaceAudit, val after: SpaceAudit, val imagesChanged: Int, val keptOriginal: Boolean)

/**
 * Space audit and optimizer. "Fast web view" (linearisation) and object streams are NOT FEASIBLE with PdfBox 2.0
 * (it writes neither), so they are reported as unavailable in the UI.
 */
object Optimizer {

    private val fontSubtypes = setOf("Type1C", "CIDFontType0C", "OpenType")

    fun audit(doc: PDDocument, fileSize: Long): SpaceAudit {
        val res = HashMap<SpaceCat, Long>()
        val content = HashSet<COSBase>(); val thumbs = HashSet<COSBase>(); val annotStreams = HashSet<COSBase>()
        doc.pages.forEach { p ->
            runCatching { p.contentStreams.forEach { content += it.cosObject } }
            (p.cosObject.getDictionaryObject(N.Thumb) as? COSStream)?.let { thumbs += it }
            runCatching {
                p.annotations.forEach { a ->
                    val ap = a.cosObject.getDictionaryObject(N.AP) as? COSDictionary ?: return@forEach
                    ap.values.forEach { v ->
                        when (val x = v.deref()) {
                            is COSStream -> annotStreams += x
                            is COSDictionary -> x.values.forEach { s -> (s.deref() as? COSStream)?.let { annotStreams += it } }
                            else -> {}
                        }
                    }
                }
            }
        }
        var sum = 0L
        runCatching {
            doc.document.objects.forEach { o ->
                val s = o.`object` as? COSStream ?: return@forEach
                val len = runCatching { s.length }.getOrDefault(0L)
                val type = (s.getDictionaryObject(N.Type) as? COSName)?.name
                val sub = (s.getDictionaryObject(N.Subtype) as? COSName)?.name
                val cat = when {
                    s in content -> SpaceCat.CONTENT
                    s in thumbs -> SpaceCat.THUMBNAILS
                    s in annotStreams -> SpaceCat.ANNOTATIONS
                    sub == "Image" -> SpaceCat.IMAGES
                    sub == "Form" -> SpaceCat.FORMS_XOBJECTS
                    type == "Metadata" || sub == "XML" -> SpaceCat.METADATA
                    type == "EmbeddedFile" -> SpaceCat.ATTACHMENTS
                    s.containsKey(N.n("Length1")) || s.containsKey(N.n("Length2")) || sub in fontSubtypes -> SpaceCat.FONTS
                    s.containsKey(N.n("N")) && sub == null -> SpaceCat.COLOR
                    else -> SpaceCat.OTHER
                }
                res[cat] = (res[cat] ?: 0L) + len
                sum += len
            }
        }
        res[SpaceCat.OVERHEAD] = (fileSize - sum).coerceAtLeast(0L)
        return SpaceAudit(fileSize, res)
    }

    suspend fun auditFile(src: File, password: String?): SpaceAudit = withContext(Dispatchers.IO) {
        (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { audit(it, src.length()) }
    }

    private fun toGray(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height; val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h); PixelOps.toGray(px)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private class Ctx(val doc: PDDocument, val o: OptimizeOptions) {
        val done = HashMap<COSBase, PDImageXObject>()        // original image stream → replacement
        val byHash = HashMap<String, PDImageXObject>()
        val seenRes = HashSet<COSBase>()
        var changed = 0
    }

    private fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { String.format(java.util.Locale.US, "%02x", it) }

    private fun processImage(c: Ctx, img: PDImageXObject, maxDim: Int): PDImageXObject? {
        c.done[img.cosObject]?.let { return it }
        val raw = runCatching { img.cosObject.createRawInputStream().use { it.readBytes() } }.getOrNull()
        if (c.o.dedupImages && raw != null) {
            val key = md5(raw) + "_" + img.width + "x" + img.height
            val prev = c.byHash[key]
            if (prev != null) { c.done[img.cosObject] = prev; c.changed++; return prev }
            c.byHash[key] = img
        }
        val filter = img.cosObject.getDictionaryObject(N.Filter)
        val isJpeg = filter == COSName.DCT_DECODE || (filter is COSArray && filter.size() == 1 && filter.getObject(0) == COSName.DCT_DECODE)
        val hasMask = img.cosObject.containsKey(N.SMask) || img.cosObject.containsKey(N.n("Mask")) || img.isStencil
        val long = maxOf(img.width, img.height)
        val needScale = long > maxDim * 1.1f
        val bpc = img.bitsPerComponent
        if (hasMask || bpc == 1) return null                    // masks / 1-bit stay as they are (lossless and small)
        if (!needScale && !(isJpeg && c.o.recompressJpeg) && !c.o.grayImages) return null
        val bmp0 = runCatching { img.image }.getOrNull() ?: return null
        var bmp = bmp0
        if (needScale) {
            val k = maxDim.toFloat() / long
            bmp = Bitmap.createScaledBitmap(bmp0, (bmp0.width * k).toInt().coerceAtLeast(1), (bmp0.height * k).toInt().coerceAtLeast(1), true)
        }
        if (c.o.grayImages) { val g = toGray(bmp); if (bmp !== bmp0) bmp.recycle(); bmp = g }
        val made = runCatching { JPEGFactory.createFromImage(c.doc, bmp, c.o.jpegQuality.coerceIn(20, 95) / 100f) }.getOrNull()
        if (bmp !== bmp0) bmp.recycle()
        bmp0.recycle()
        made ?: return null
        val oldLen = raw?.size?.toLong() ?: Long.MAX_VALUE
        val newLen = runCatching { made.cosObject.length }.getOrDefault(Long.MAX_VALUE)
        if (newLen >= oldLen) return null                         // never make an image bigger
        c.done[img.cosObject] = made; c.changed++
        return made
    }

    private fun walk(c: Ctx, res: PDResources?, maxDim: Int, depth: Int) {
        if (res == null || depth > 10 || !c.seenRes.add(res.cosObject)) return
        runCatching {
            res.xObjectNames.toList().forEach { n ->
                when (val x = runCatching { res.getXObject(n) }.getOrNull()) {
                    is PDImageXObject -> processImage(c, x, maxDim)?.let { res.put(n, it) }
                    is PDFormXObject -> walk(c, x.resources, maxDim, depth + 1)
                    else -> {}
                }
            }
        }
    }

    suspend fun optimize(src: File, password: String?, out: File, o: OptimizeOptions, onProgress: (Int, Int) -> Unit = { _, _ -> }): OptimizeResult = withContext(Dispatchers.IO) {
        var before = SpaceAudit(src.length(), emptyMap())
        var changed = 0
        (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { doc ->
            before = audit(doc, src.length())
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val c = Ctx(doc, o)
            val n = doc.numberOfPages
            doc.pages.forEachIndexed { i, p ->
                coroutineContext.ensureActive()
                onProgress(i, n)
                val box = p.cropBox
                val maxDim = (maxOf(box.width, box.height) / 72f * o.imageDpi).toInt().coerceAtLeast(300)
                walk(c, p.resources, maxDim, 0)
                if (o.stripThumbnails) p.cosObject.removeItem(N.Thumb)
                if (o.stripPieceInfo) p.cosObject.removeItem(N.PieceInfo)
                if (o.stripJavaScript) p.cosObject.removeItem(N.AA)
            }
            changed = c.changed
            val cat = doc.documentCatalog.cosObject
            if (o.stripMetadata) {
                cat.removeItem(N.Metadata)
                val info = doc.documentInformation
                // keep the title (shown by readers and needed for accessibility); drop the rest
                val t = info.title
                doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation().apply { if (!t.isNullOrBlank()) title = t }
            }
            if (o.stripPieceInfo) cat.removeItem(N.PieceInfo)
            if (o.stripJavaScript) {
                (cat.getDictionaryObject(N.Names) as? COSDictionary)?.removeItem(N.JavaScript)
                cat.removeItem(N.AA)
                (cat.getDictionaryObject(N.OpenAction) as? COSDictionary)?.let { if (it.getDictionaryObject(N.S) == N.JavaScript) cat.removeItem(N.OpenAction) }
            }
            if (o.stripAttachments) (cat.getDictionaryObject(N.Names) as? COSDictionary)?.removeItem(N.EmbeddedFiles)
            if (o.flateUncompressed) runCatching {
                doc.document.objects.forEach { obj ->
                    val s = obj.`object` as? COSStream ?: return@forEach
                    if (s.getDictionaryObject(N.Filter) != null || (s.getDictionaryObject(N.Type) as? COSName)?.name == "Metadata") return@forEach
                    val data = s.createInputStream().use { it.readBytes() }
                    if (data.size < 256) return@forEach
                    s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
                }
            }
            onProgress(n, n)
            doc.save(out)
        }
        var kept = false
        if (o.onlyIfSmaller && out.length() >= src.length() && password.isNullOrEmpty()) { src.copyTo(out, overwrite = true); kept = true }
        val after = PDDocument.load(out).use { audit(it, out.length()) }
        OptimizeResult(before, after, changed, kept)
    }
}
