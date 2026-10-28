package com.smartdocs.editor.core.share5

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.annot.CommentExchange
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.smartdocs.editor.core.share5.ShareChoice.Format
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds the files handed to the share sheet for one set of [ShareChoice] options. Everything is written to a fresh
 * folder under cache/share5 (cleared on the next build), so the original document is never modified.
 */
object ShareBuilder {
    class TooManyPages : IllegalStateException("too many pages for images")
    class BadRange : IllegalArgumentException("bad page range")

    data class Result(val files: List<File>, val mime: String)

    private fun outDir(c: Context): File = File(c.cacheDir, "share5").apply { deleteRecursively(); mkdirs() }

    fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    /**
     * [pages] zero-based (null = all). [password] opens [src]. Subsets keep the source's protection (audit 5 T-05);
     * "as is" with every page shares a plain copy of the file (still protected when the source is).
     * [strip] (nav-attach, "remove hidden information on share"): what [Sanitizer] removes from PDF results
     * (metadata, scripts, attachments…); the shared copy only, never the original.
     */
    suspend fun build(c: Context, src: File, password: String?, pageCount: Int, pages: List<Int>?, format: Format,
                      onProgress: (Float) -> Unit = {}, strip: Set<SanitizeItem> = emptySet()): Result = withContext(Dispatchers.IO) {
        val dir = outDir(c)
        val sel = pages ?: (0 until pageCount).toList()
        if (sel.isEmpty()) throw BadRange()
        val all = sel.size == pageCount && sel == (0 until pageCount).toList()
        val base = safe(src.nameWithoutExtension)
        val name = ShareChoice.outName(base, sel, pageCount, format)
        // working copy limited to the chosen pages (decrypted when it is a subset)
        var work = src; var pw = password
        if (!all && format != Format.IMAGES) {
            // audit 5 T-05: the subset keeps the source's protection, so it opens with the same password
            work = File(dir, ".subset.pdf"); PdfOps.keepPages(src, sel, work, password)
        }
        when (format) {
            Format.AS_IS -> {
                val out = File(dir, name); work.copyTo(out, overwrite = true)
                Result(listOf(out), "application/pdf")
            }
            Format.COMPRESSED -> { val out = File(dir, name); PdfOps.compress(work, out, quality = 55, maxDim = 1400, password = pw); Result(listOf(out), "application/pdf") }
            Format.FLATTENED -> { val out = File(dir, name); PdfOps.flattenAnnotations(work, out, pw); Result(listOf(out), "application/pdf") }
            Format.NO_COMMENTS -> { val out = File(dir, name); stripComments(work, pw, out); Result(listOf(out), "application/pdf") }
            Format.COMMENTS_XFDF -> {
                val out = File(dir, name)
                CommentExchange.exportXfdf(work, pw, out)
                val (idHex, rid) = documentIds(src, password)
                if (idHex != null) out.writeText(EmailMatch.withIds(out.readText(), idHex, rid))
                Result(listOf(out), "application/vnd.adobe.xfdf")
            }
            Format.IMAGES -> {
                if (sel.size > ShareChoice.MAX_IMAGE_PAGES) throw TooManyPages()
                Result(images(c, src, password, sel, dir, name, onProgress), "image/jpeg")
            }
        }.also { r ->
            if (work != src) work.delete()
            if (strip.isNotEmpty() && r.mime == "application/pdf") r.files.forEach { out -> stripInPlace(out, pw, strip) }
        }
    }

    /** Removes [items] from the shared PDF [f] (opened with [pw]; its protection is kept). */
    private suspend fun stripInPlace(f: File, pw: String?, items: Set<SanitizeItem>) {
        val tmp = File(f.parentFile, ".${f.name}.strip")
        Sanitizer.cleanFile(f, tmp, pw, items)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** Removes every markup annotation (comments, highlights, drawings, stamps); links and form widgets stay. */
    private fun stripComments(src: File, pw: String?, out: File) {
        PdfOps.load(src, pw).use { doc ->
            doc.pages.forEach { page ->
                val list = runCatching { page.annotations }.getOrNull() ?: return@forEach
                val keep = list.filter { it.subtype == "Link" || it.subtype == "Widget" }
                if (keep.size != list.size) page.setAnnotations(keep)
            }
            doc.save(out)
        }
    }

    private suspend fun images(c: Context, src: File, pw: String?, sel: List<Int>, dir: File, pattern: String, onProgress: (Float) -> Unit): List<File> {
        val session = PdfSession.open(c, src, pw)
        val out = ArrayList<File>()
        try {
            sel.forEachIndexed { i, p ->
                val (w, h) = session.pageSize(p)
                val scale = minOf(150f / 72f, 2400f / maxOf(w, h))
                val bmp = session.render(p, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), annotations = true)
                val f = File(dir, pattern.replace("%d", (p + 1).toString()))
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                out += f
                session.invalidateCache()
                onProgress((i + 1f) / sel.size)
            }
        } finally { session.closeAsync() }
        return out
    }

    /** Trailer /ID[0] (upper-case hex) and the /Info SDReviewId written by [EmailReview.prepare], when present. */
    fun documentIds(f: File, pw: String?): Pair<String?, String?> = runCatching {
        (if (pw.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, pw)).use { doc ->
            val id = doc.document.documentID?.let { arr -> (arr.getObject(0) as? com.tom_roush.pdfbox.cos.COSString)?.bytes }
            val hex = id?.joinToString("") { "%02X".format(it) }
            hex to doc.documentInformation?.getCustomMetadataValue(EmailReview.INFO_KEY)
        }
    }.getOrDefault(null to null)
}
