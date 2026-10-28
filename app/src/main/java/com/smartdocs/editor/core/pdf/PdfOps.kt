package com.smartdocs.editor.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Page-level and document-level editing operations built on PdfBox. Every operation writes to [out] and never touches [src] in place. */
object PdfOps {

    /**
     * Load for editing. PdfBox 2.0 refuses to save an encrypted document that was merely opened with a
     * password ("PDF contains an encryption dictionary, please remove it … or set a protection policy"),
     * so an encrypted document is re-protected with the same password up front. `setAllSecurityToBeRemoved`
     * (used by [removeSecurity]) and a later [PDDocument.protect] (used by [protect]) both override this.
     */
    internal fun load(src: File, password: String? = null): PDDocument {
        val doc = loadRaw(src, password)
        reprotect(doc, password)
        return doc
    }

    /** Load without re-protecting: for incremental saves, which keep the original security handler ([SignedSave]). */
    internal fun loadRaw(src: File, password: String? = null): PDDocument =
        // new large streams (embedded videos, 100+ MB) spill to temp files instead of the heap
        PDDocument.load(src, password ?: "", com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20))

    /** Re-protect an encrypted [doc] (loaded with [loadRaw]) like its source, so a full save keeps its protection. */
    internal fun reprotect(doc: PDDocument, password: String?) {
        if (doc.isEncrypted) runCatching { doc.protect(keepProtection(doc, password)) }
    }

    /**
     * The protection a full save gives an encrypted document (audit DI-28 / AN-29): the source's key length, cipher
     * (RC4 or AES) and permission bits (/P), never a downgrade.
     * - Opened with the owner password: that owner password is kept. The user password is recovered for RC4/AES-128
     *   files (revision 2–4: it is stored in /O under the owner key) or kept when it is empty; for an AES-256 file
     *   with a non-empty user password it cannot be recovered, so the owner password is used for both (limitation).
     * - Opened with the user password (or none): the owner password is unknown. A random one is set, so the
     *   restrictions stay in force; the original owner password no longer lifts them (limitation; the original
     *   is never changed by this, only the saved output).
     */
    internal fun keepProtection(doc: PDDocument, password: String?): StandardProtectionPolicy {
        val enc = doc.encryption
        val pw = password.orEmpty()
        val bits = enc.length.let { if (enc.version <= 1 || it <= 40) 40 else if (it <= 128) 128 else 256 }
        val cfm = runCatching { enc.stdCryptFilterDictionary?.cryptFilterMethod?.name }.getOrNull()
        val aes = bits == 256 || (enc.version >= 4 && cfm != null && cfm.startsWith("AESV"))
        val perms = AccessPermission(enc.permissions)
        val asOwner = doc.currentAccessPermission?.isOwnerPermission == true
        val (user, owner) = if (asOwner) (recoverUserPassword(doc, pw) ?: pw) to pw
            else pw to java.util.UUID.randomUUID().toString().replace("-", "")
        return StandardProtectionPolicy(owner, user, perms).apply { encryptionKeyLength = bits; setPreferAES(aes) }
    }

    /** The user password of a document opened with its owner password [ownerPw], when it can be determined. */
    private fun recoverUserPassword(doc: PDDocument, ownerPw: String): String? = runCatching {
        val enc = doc.encryption
        val h = com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler()
        val id = (doc.document.documentID?.getObject(0) as? com.tom_roush.pdfbox.cos.COSString)?.bytes ?: ByteArray(0)
        val len = if (enc.version == 1) 5 else enc.length / 8
        fun isUser(c: String) = h.isUserPassword(c.toByteArray(Charsets.ISO_8859_1), enc.userKey, enc.ownerKey, enc.permissions, id, enc.revision, len, enc.isEncryptMetaData)
        if (enc.revision >= 5) return@runCatching "".takeIf { isUser(it) }
        val padded = h.getUserPassword(ownerPw.toByteArray(Charsets.ISO_8859_1), enc.ownerKey, enc.revision, len)
        // strip the standard 32-byte padding (Algorithm 2 step a): the password is the part before it
        (0..padded.size).map { String(padded, 0, it, Charsets.ISO_8859_1) }.firstOrNull { isUser(it) }
    }.getOrNull()

    /** Write [doc] to [out] safely even when [out] is the file the document was loaded from. */
    private fun saveTo(doc: PDDocument, out: File) {
        val tmp = File(out.parentFile ?: out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
        try {
            doc.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** Parse "1-3, 5, 8" into zero-based page indexes within [count]. */
    fun parseRange(spec: String, count: Int): List<Int> {
        if (spec.isBlank()) return (0 until count).toList()
        val out = LinkedHashSet<Int>()
        spec.replace('،', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val m = Regex("""^(\d+)\s*[-–]\s*(\d+)$""").find(part)
            if (m != null) {
                // toIntOrNull + clamp: "99999999999" overflowed toInt(), "1-2000000000" looped 2e9 times
                val a = m.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE; val b = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
                val lo = minOf(a, b).coerceAtLeast(1); val hi = maxOf(a, b).coerceAtMost(count)
                for (i in lo..hi) out += i - 1
            } else part.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }
        }
        return out.toList()
    }

    suspend fun pageCount(src: File, password: String? = null): Int = withContext(Dispatchers.IO) {
        load(src, password).use { it.numberOfPages }
    }

    suspend fun merge(sources: List<File>, out: File) = withContext(Dispatchers.IO) {
        val m = PDFMergerUtility()
        sources.forEach { m.addSource(it) }
        // write to a sibling temp first so a source that equals [out] is never read and written at once
        val tmp = File(out.parentFile ?: out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
        try {
            m.destinationFileName = tmp.absolutePath
            m.mergeDocuments(null)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** Keep only [pages] (zero-based) in the given order. Used for extract / reorder / delete. */
    suspend fun keepPages(src: File, pages: List<Int>, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            PDDocument().use { nd ->
                pages.forEach { nd.importPage(doc.getPage(it)) }
                nd.documentInformation = doc.documentInformation
                saveTo(nd, out)
            }
        }
    }

    /** Delete [pages]; bookmarks and links that pointed at them are removed too (they would lead nowhere). */
    suspend fun deletePages(src: File, pages: Set<Int>, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            // nav-prefs: Preferences → bookmarks "remove related bookmarks when deleting pages" (links to them always go)
            com.smartdocs.editor.core.edit5.PageOrganize.deleteIn(doc, pages, removeBookmarks = com.smartdocs.editor.core.data.NpfPrefs.removeBookmarksOnDelete)
            saveTo(doc, out)
        }
    }

    suspend fun rotatePages(src: File, pages: List<Int>, degrees: Int, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            pages.forEach { i -> val p = doc.getPage(i); p.rotation = (p.rotation + degrees).mod(360) }
            saveTo(doc, out)
        }
    }

    /**
     * Reorder (and, for paste, duplicate) the pages of [src] in place on its own page tree (audit AN-22): the
     * catalog — outline, AcroForm, page labels, named destinations, structure tree — and the encryption stay, unlike
     * [keepPages], which builds a new document and is meant for extract / split only.
     */
    suspend fun reorder(src: File, order: List<Int>, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            arrangeInPlace(doc, order)
            saveTo(doc, out)
        }
    }

    private val INHERITABLE = listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)

    /**
     * Make the page tree of [doc] hold the pages [order] (zero-based indexes into the current pages, each at least
     * once; an index listed again gets a copy of that page). The same page dictionaries are kept, so outline / link destinations and form
     * widgets still point at them. The tree is flattened under its root after the inheritable attributes were pushed
     * down to each page.
     */
    internal fun arrangeInPlace(doc: PDDocument, order: List<Int>) {
        val pages = (0 until doc.numberOfPages).map { doc.getPage(it).cosObject }
        // every page stays (deleting is [deletePages]); indexes may repeat (paste)
        require(order.all { it in pages.indices } && order.toSet().size == pages.size) { "bad page order" }
        val root = doc.pages.cosObject
        for (p in pages) for (key in INHERITABLE) if (!p.containsKey(key)) inherited(p, key)?.let { p.setItem(key, it) }
        val used = HashSet<Int>()
        val kids = COSArray()
        for (i in order) {
            val page = if (used.add(i)) pages[i] else copyPage(pages[i])
            page.setItem(COSName.PARENT, root)
            kids.add(page)
        }
        root.setItem(COSName.KIDS, kids)
        root.setInt(COSName.COUNT, kids.size())
    }

    private fun inherited(page: COSDictionary, key: COSName): COSBase? {
        var node = page.getDictionaryObject(COSName.PARENT) as? COSDictionary
        var depth = 0
        while (node != null && depth++ < 64) {
            node.getDictionaryObject(key)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return null
    }

    /** A pasted duplicate: shares content and resources; gets its own copies of the non-form annotations. */
    private fun copyPage(page: COSDictionary): COSDictionary {
        val copy = COSDictionary()
        for ((k, v) in page.entrySet()) if (k != COSName.ANNOTS && k != COSName.STRUCT_PARENTS && k != COSName.B) copy.setItem(k, v)
        val annots = page.getDictionaryObject(COSName.ANNOTS) as? COSArray
        if (annots != null) {
            val out = COSArray()
            for (i in 0 until annots.size()) {
                val a = annots.getObject(i) as? COSDictionary ?: continue
                val sub = a.getNameAsString(COSName.SUBTYPE)
                if (sub == "Widget" || sub == "Popup") continue   // form fields belong to one page; popups to their parent
                val c = COSDictionary()
                for ((k, v) in a.entrySet()) if (k.name != "Popup" && k != COSName.STRUCT_PARENT) c.setItem(k, v)
                c.setItem(COSName.P, copy)
                out.add(c)
            }
            if (out.size() > 0) copy.setItem(COSName.ANNOTS, out)
        }
        return copy
    }


    /** Split into one file per page or per range chunk. Returns the files created. */
    suspend fun split(src: File, outDir: File, baseName: String, chunk: Int = 1): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val files = ArrayList<File>()
        load(src).use { doc ->
            var start = 0; var n = 1
            while (start < doc.numberOfPages) {
                val end = minOf(start + chunk, doc.numberOfPages)
                PDDocument().use { nd ->
                    for (i in start until end) nd.importPage(doc.getPage(i))
                    val f = File(outDir, "${baseName}_$n.pdf"); nd.save(f); files += f
                }
                start = end; n++
            }
        }
        files
    }

    enum class PageStyle { BLANK, LINED, GRID, DOTS }

    /** Create a new PDF with [count] pages of the given style. */
    suspend fun createBlank(out: File, count: Int, size: PDRectangle = PDRectangle.A4, landscape: Boolean = false, style: PageStyle = PageStyle.BLANK, bgColor: Int = 0xFFFFFF) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            repeat(count) { doc.addPage(stylePage(doc, size, landscape, style, bgColor)) }
            saveTo(doc, out)
        }
    }

    private fun stylePage(doc: PDDocument, size: PDRectangle, landscape: Boolean, style: PageStyle, bg: Int): PDPage {
        val rect = if (landscape) PDRectangle(size.height, size.width) else size
        val page = PDPage(rect)
        PDPageContentStream(doc, page).use { cs ->
            if (bg != 0xFFFFFF) {
                cs.setNonStrokingColor((bg shr 16) and 0xFF, (bg shr 8) and 0xFF, bg and 0xFF)
                cs.addRect(0f, 0f, rect.width, rect.height); cs.fill()
            }
            cs.setStrokingColor(200, 200, 200); cs.setLineWidth(0.5f)
            val step = 24f
            when (style) {
                PageStyle.LINED -> { var y = rect.height - 60f; while (y > 40f) { cs.moveTo(40f, y); cs.lineTo(rect.width - 40f, y); y -= step }; cs.stroke() }
                PageStyle.GRID -> {
                    var y = 0f; while (y < rect.height) { cs.moveTo(0f, y); cs.lineTo(rect.width, y); y += step }
                    var x = 0f; while (x < rect.width) { cs.moveTo(x, 0f); cs.lineTo(x, rect.height); x += step }
                    cs.stroke()
                }
                PageStyle.DOTS -> {
                    cs.setNonStrokingColor(170, 170, 170)
                    var y = step; while (y < rect.height) { var x = step; while (x < rect.width) { cs.addRect(x - 0.7f, y - 0.7f, 1.4f, 1.4f); x += step }; y += step }
                    cs.fill()
                }
                PageStyle.BLANK -> Unit
            }
        }
        return page
    }

    suspend fun insertBlankPage(src: File, atIndex: Int, out: File, style: PageStyle = PageStyle.BLANK, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val ref = if (doc.numberOfPages > 0) doc.getPage(atIndex.coerceIn(0, doc.numberOfPages - 1)).mediaBox else PDRectangle.A4
            val p = stylePage(doc, PDRectangle(ref.width, ref.height), false, style, 0xFFFFFF)
            if (atIndex >= doc.numberOfPages) doc.addPage(p) else doc.pages.insertBefore(p, doc.getPage(atIndex))
            saveTo(doc, out)
        }
    }

    /** Insert all pages of [other] into [src] at [atIndex], with its bookmarks and attachments. */
    suspend fun insertPagesFrom(src: File, other: File, atIndex: Int, out: File, password: String? = null, keepBookmarks: Boolean = true, keepAttachments: Boolean = true) =
        com.smartdocs.editor.core.edit5.PageOrganize.insertFile(src, out, password, other, null, atIndex, keepBookmarks, keepAttachments)

    /** Build a PDF from images; each image becomes a page sized to the image (A4-fitted when [fitA4]). */
    suspend fun imagesToPdf(images: List<File>, out: File, fitA4: Boolean = true, jpegQuality: Int = 85) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            images.forEach { f ->
                val bmp = decodeScaled(f, 2200) ?: return@forEach
                val img = JPEGFactory.createFromImage(doc, bmp, jpegQuality / 100f)
                val page = if (fitA4) {
                    val portrait = bmp.height >= bmp.width
                    PDPage(if (portrait) PDRectangle.A4 else PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
                } else PDPage(PDRectangle(bmp.width.toFloat(), bmp.height.toFloat()))
                doc.addPage(page)
                val box = page.mediaBox
                val scale = minOf(box.width / bmp.width, box.height / bmp.height)
                val w = bmp.width * scale; val h = bmp.height * scale
                PDPageContentStream(doc, page).use { it.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
                bmp.recycle()
            }
            saveTo(doc, out)
        }
    }

    fun decodeScaled(f: File, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = runCatching { androidx.exifinterface.media.ExifInterface(f.absolutePath).rotationDegrees }.getOrDefault(0)
        if (exif == 0) return bmp
        val m = Matrix().apply { postRotate(exif.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Draw an image (signature, stamp, photo) on a page. Coordinates in PDF points, origin bottom-left. */
    suspend fun stampImage(src: File, pageIndex: Int, image: Bitmap, x: Float, y: Float, w: Float, h: Float, out: File, opacity: Float = 1f, rotation: Float = 0f, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val page = doc.getPage(pageIndex)
            val img = LosslessFactory.createFromImage(doc, image)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                if (opacity < 1f) {
                    val gs = PDExtendedGraphicsState(); gs.nonStrokingAlphaConstant = opacity; gs.strokingAlphaConstant = opacity
                    cs.setGraphicsStateParameters(gs)
                }
                if (rotation != 0f) {
                    cs.saveGraphicsState()
                    cs.transform(PdfMatrix.getRotateInstance(Math.toRadians(rotation.toDouble()), x + w / 2, y + h / 2))
                    cs.drawImage(img, -w / 2, -h / 2, w, h)
                    cs.restoreGraphicsState()
                } else cs.drawImage(img, x, y, w, h)
            }
            saveTo(doc, out)
        }
    }

    /** Font that supports Arabic. Falls back to Helvetica for Latin-only text. */
    fun arabicFont(context: Context, doc: PDDocument): PDFont =
        runCatching { PDType0Font.load(doc, context.assets.open("fonts/NotoNaskhArabic-Regular.ttf")) }.getOrElse { PDType1Font.HELVETICA }

    /** Draw text on a page. Arabic text is shaped+reordered via [ArabicShaper] before drawing. */
    suspend fun stampText(context: Context, src: File, pageIndex: Int, text: String, x: Float, y: Float, fontSize: Float, color: Int, out: File, opacity: Float = 1f, rotation: Float = 0f, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val page = doc.getPage(pageIndex)
            val font = arabicFont(context, doc)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                if (opacity < 1f) { val gs = PDExtendedGraphicsState(); gs.nonStrokingAlphaConstant = opacity; cs.setGraphicsStateParameters(gs) }
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                if (rotation != 0f) cs.setTextMatrix(PdfMatrix.getRotateInstance(Math.toRadians(rotation.toDouble()), x, y)) else cs.newLineAtOffset(x, y)
                cs.showArabic(text, ArabicShaper.shape(text))
                cs.endText()
            }
            saveTo(doc, out)
        }
    }

    suspend fun watermark(context: Context, src: File, text: String, out: File, fontSize: Float = 48f, color: Int = 0x9E9E9E, opacity: Float = 0.3f, rotation: Float = 45f, pages: List<Int>? = null, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val font = arabicFont(context, doc)
            val shaped = ArabicShaper.shape(text)
            val targets = pages ?: (0 until doc.numberOfPages).toList()
            targets.forEach { i ->
                val page = doc.getPage(i); val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val gs = PDExtendedGraphicsState(); gs.nonStrokingAlphaConstant = opacity; cs.setGraphicsStateParameters(gs)
                    val tw = runCatching { font.getStringWidth(shaped) / 1000f * fontSize }.getOrDefault(fontSize * text.length * 0.5f)
                    cs.beginText(); cs.setFont(font, fontSize)
                    cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                    cs.setTextMatrix(PdfMatrix.getRotateInstance(Math.toRadians(rotation.toDouble()), box.width / 2 - tw / 2 * Math.cos(Math.toRadians(rotation.toDouble())).toFloat(), box.height / 2 - tw / 2 * Math.sin(Math.toRadians(rotation.toDouble())).toFloat()))
                    cs.showArabic(text, shaped); cs.endText()
                }
            }
            saveTo(doc, out)
        }
    }

    enum class NumberPosition { BOTTOM_CENTER, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_CENTER }

    suspend fun addPageNumbers(context: Context, src: File, out: File, position: NumberPosition = NumberPosition.BOTTOM_CENTER, start: Int = 1, fontSize: Float = 11f, arabicDigits: Boolean = false, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val font = arabicFont(context, doc)
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i); val box = page.mediaBox
                var label = (start + i).toString()
                if (arabicDigits) label = label.map { '٠' + (it - '0') }.joinToString("")
                val tw = runCatching { font.getStringWidth(label) / 1000f * fontSize }.getOrDefault(fontSize * label.length * 0.6f)
                val (x, y) = when (position) {
                    NumberPosition.BOTTOM_CENTER -> (box.width - tw) / 2 to 24f
                    NumberPosition.BOTTOM_RIGHT -> box.width - tw - 36f to 24f
                    NumberPosition.BOTTOM_LEFT -> 36f to 24f
                    NumberPosition.TOP_CENTER -> (box.width - tw) / 2 to box.height - 30f
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText(); cs.setFont(font, fontSize); cs.setNonStrokingColor(60, 60, 60); cs.newLineAtOffset(x, y); cs.showText(label); cs.endText()
                }
            }
            saveTo(doc, out)
        }
    }

    /** Encrypt with an open password and/or an owner (permissions) password. */
    suspend fun protect(src: File, out: File, openPassword: String, ownerPassword: String, allowPrint: Boolean = true, allowCopy: Boolean = true, allowModify: Boolean = true, allowAnnotate: Boolean = true, allowFillForms: Boolean = true, currentPassword: String? = null) = withContext(Dispatchers.IO) {
        load(src, currentPassword).use { doc ->
            val ap = AccessPermission().apply {
                setCanPrint(allowPrint); setCanExtractContent(allowCopy); setCanModify(allowModify)
                setCanModifyAnnotations(allowAnnotate); setCanFillInForm(allowFillForms); setCanAssembleDocument(allowModify)
            }
            val policy = StandardProtectionPolicy(ownerPassword.ifEmpty { openPassword.ifEmpty { "owner" } }, openPassword, ap).apply {
                encryptionKeyLength = 128; setPreferAES(true)
            }
            doc.protect(policy)
            saveTo(doc, out)
        }
    }

    suspend fun removeSecurity(src: File, password: String, out: File) = withContext(Dispatchers.IO) {
        load(src, password).use { doc -> doc.isAllSecurityToBeRemoved = true; saveTo(doc, out) }
    }

    /** Re-encode every image at [quality] and scaled to at most [maxDim] pixels. */
    /**
     * Reduce the file size. Uses the shared optimizer, which (unlike the old per-page JPEG re-encode that could make
     * files BIGGER) keeps one copy of images shared by many pages, leaves 1-bit scans and images with transparency
     * as they are, only replaces an image when the new one is smaller, compresses uncompressed streams, and drops
     * thumbnails/private data. Protected files stay protected with the same password. When the result is not smaller
     * than the original, the original is kept (copied to [out]). Returns true when the file got smaller.
     */
    suspend fun compress(src: File, out: File, quality: Int = 60, maxDim: Int = 1400, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        // old callers pass a pixel limit for an A4-ish page; the optimizer works in dpi of each page
        val dpi = (maxDim / 11.7f).toInt().coerceIn(72, 300)
        val opts = com.smartdocs.editor.core.tools5.OptimizeOptions(imageDpi = dpi, jpegQuality = quality.coerceIn(20, 95),
            stripMetadata = false, stripJavaScript = false, onlyIfSmaller = false, keepProtection = false)
        val tmp = File(out.absoluteFile.parentFile, ".${out.nameWithoutExtension}.${System.nanoTime()}.opt.pdf")
        try {
            com.smartdocs.editor.core.tools5.Optimizer.optimize(src, password, tmp, opts)
            val result = if (!password.isNullOrEmpty()) {
                // the optimizer saves without encryption: protect again with the same password
                val prot = File(tmp.parentFile, tmp.name + ".p.pdf")
                protect(tmp, prot, password, password); tmp.delete(); prot
            } else tmp
            val smaller = result.length() in 1 until src.length()
            if (smaller) { if (!result.renameTo(out)) { result.copyTo(out, overwrite = true); result.delete() } }
            else { result.delete(); src.copyTo(out, overwrite = true) }
            smaller
        } finally { tmp.delete() }
    }

    /** Flatten: render each page to an image so annotations/forms become part of the page. */
    suspend fun flatten(context: Context, src: File, out: File, dpi: Int = 150, password: String? = null) = withContext(Dispatchers.IO) {
        // One page at a time: holding every rendered page bitmap in memory OOMs on long documents.
        val session = PdfSession.open(context, src, password)
        try {
            PDDocument().use { doc ->
                for (i in 0 until session.pageCount) {
                    val (w, h) = session.pageSize(i)
                    val bmp = session.render(i, (w * dpi / 72).toInt().coerceAtLeast(1), (h * dpi / 72).toInt().coerceAtLeast(1), annotations = true)
                    val page = PDPage(PDRectangle(bmp.width * 72f / dpi, bmp.height * 72f / dpi)); doc.addPage(page)
                    val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                    PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
                    session.invalidateCache()
                }
                saveTo(doc, out)
            }
        } finally { session.closeAsync() }
    }

    /** Flatten AcroForm fields only (keeps vector content). */
    suspend fun flattenForms(src: File, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc -> doc.documentCatalog.acroForm?.flatten(); saveTo(doc, out) }
    }

    /** Black out rectangles (page points) and remove the text beneath by rasterising those pages. */
    suspend fun redact(context: Context, src: File, out: File, regions: Map<Int, List<android.graphics.RectF>>, password: String? = null) = withContext(Dispatchers.IO) {
        // Rasterise affected pages so underlying text is truly removed, then paint black boxes.
        val session = PdfSession.open(context, src, password)
        val rendered = HashMap<Int, Bitmap>()
        try {
            regions.keys.forEach { i ->
                if (i !in 0 until session.pageCount) return@forEach
                val (w, h) = session.pageSize(i)   // PDFium size: already rotated by /Rotate
                rendered[i] = session.render(i, (w * 2).toInt().coerceAtLeast(1), (h * 2).toInt().coerceAtLeast(1))
                session.invalidateCache()           // the bitmap is ours now; keep the cache from holding more
            }
        } finally { session.closeAsync() }
        load(src, password).use { doc ->
            regions.forEach { (i, rects) ->
                val bmp = rendered[i] ?: return@forEach
                val page = doc.getPage(i); val box = page.mediaBox
                val rot = ((page.rotation % 360) + 360) % 360
                val canvas = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.FILL }
                val bw = box.width; val bh = box.height; val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
                // PDF user space (origin bottom-left, unrotated) -> rendered bitmap pixels (origin top-left, /Rotate applied)
                fun toPx(x0: Float, y0: Float): Pair<Float, Float> {
                    val x = x0 - box.lowerLeftX; val y = y0 - box.lowerLeftY
                    return when (rot) {
                        90 -> (y / bh * W) to (x / bw * H)
                        180 -> ((bw - x) / bw * W) to (y / bh * H)
                        270 -> ((bh - y) / bh * W) to ((bw - x) / bw * H)
                        else -> (x / bw * W) to ((bh - y) / bh * H)
                    }
                }
                rects.forEach { r ->
                    val (ax, ay) = toPx(r.left, r.top); val (bx, by) = toPx(r.right, r.bottom)
                    // normalise: callers may pass top<bottom (android convention) or PDF top>bottom
                    canvas.drawRect(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by), paint)
                }
                // the replacement page is the rendered (already rotated) raster, so it needs no /Rotate
                val newPage = PDPage(PDRectangle(W / 2f, H / 2f))
                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                PDPageContentStream(doc, newPage).use { it.drawImage(img, 0f, 0f, newPage.mediaBox.width, newPage.mediaBox.height) }
                doc.pages.insertAfter(newPage, page); doc.removePage(page)
                bmp.recycle()
            }
            saveTo(doc, out)
        }
    }

    data class Info(var title: String = "", var author: String = "", var subject: String = "", var keywords: String = "", var creator: String = "", var producer: String = "")

    suspend fun setInfo(src: File, info: Info, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            doc.documentInformation.apply { title = info.title; author = info.author; subject = info.subject; keywords = info.keywords; creator = info.creator; producer = info.producer }
            // tab-view-core E-17: an existing XMP packet gets the same values (apps reading XMP show the new title)
            runCatching {
                val meta = doc.documentCatalog.metadata ?: return@runCatching
                val xml = meta.exportXMPMetadata().use { String(it.readBytes(), Charsets.UTF_8) }
                val upd = XmpSync.update(xml, XmpSync.Values(info.title, info.author, info.subject, info.keywords, info.creator, info.producer))
                if (upd != xml) doc.documentCatalog.metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(doc).also { it.importXMPMetadata(upd.toByteArray(Charsets.UTF_8)) }
            }
            saveTo(doc, out)
        }
    }

    /** Replace the outline (table of contents) with a flat/nested list of (title, pageIndex, level). */
    suspend fun setOutline(src: File, items: List<Triple<String, Int, Int>>, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val outline = PDDocumentOutline(); doc.documentCatalog.documentOutline = outline
            val stack = ArrayList<Pair<Int, PDOutlineItem>>()
            items.forEach { (title, page, level) ->
                val it = PDOutlineItem().apply { this.title = title; setDestination(doc.getPage(page.coerceIn(0, doc.numberOfPages - 1))) }
                while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
                if (stack.isEmpty()) outline.addLast(it) else stack.last().second.addLast(it)
                stack += level to it
            }
            outline.openNode()
            saveTo(doc, out)
        }
    }

    suspend fun extractText(src: File, password: String? = null): String = withContext(Dispatchers.IO) {
        load(src, password).use { doc -> com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc) }
    }

    // ---------------- additive helpers for the one-step reader tools ----------------

    /** True when the file carries an encryption dictionary (open password and/or permissions password). */
    suspend fun isEncrypted(src: File, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { it.isEncrypted }
    }

    /**
     * Flatten annotations and form fields into the page content while keeping vector text: form fields are
     * flattened by the AcroForm, every other visible annotation's normal appearance is drawn into the page
     * (mapped from its /BBox × /Matrix onto /Rect, as a viewer does) and the annotation removed.
     * Links and popups are kept. Returns the number of annotations flattened.
     */
    suspend fun flattenAnnotations(src: File, out: File, password: String? = null): Int = withContext(Dispatchers.IO) {
        var n = 0
        load(src, password).use { doc ->
            runCatching { doc.documentCatalog.acroForm?.let { af -> af.needAppearances = false; af.flatten(); n += 1 } }
            // the per-page core is shared with "Apply All Signatures" (nitro-fillsign2)
            n += com.smartdocs.editor.core.fillsign2.SignatureFlatten.flattenIn(doc) { true }
            saveTo(doc, out)
        }
        n
    }

    /**
     * "Apply All Signatures" (nitro-fillsign2): only the placed QuickSign signature / initials stamps are flattened
     * into the pages (everything else stays editable). Returns how many; [out] is written only when there were any.
     * The caller must not use it on a digitally signed file (a full save breaks the signatures; see SignedSave).
     */
    suspend fun flattenSignatureStamps(src: File, out: File, password: String? = null): Int = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val n = com.smartdocs.editor.core.fillsign2.SignatureFlatten.flattenSignatures(doc)
            if (n > 0) saveTo(doc, out)
            n
        }
    }

    fun copyStream(input: InputStream, out: File) { out.outputStream().use { o -> input.copyTo(o) } }

    fun bitmapBytes(b: Bitmap, q: Int = 90): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
}
