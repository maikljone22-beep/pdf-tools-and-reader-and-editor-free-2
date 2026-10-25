package com.smartdocs.editor.core.convert

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android entry point of the on-device converter: PdfBox-Android for content, PDFium ([PdfSession]) for
 * page pictures, [ConvertPipeline] for analysis + writing. Writes to a temp file and moves it to [out]
 * only on success.
 */
object ConvertEngine {

    class PasswordNeeded : Exception("password")

    suspend fun pageCount(src: File, password: String?): Int = withContext(Dispatchers.IO) {
        try {
            PDDocument.load(src, password.orEmpty()).use { it.numberOfPages }
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw PasswordNeeded() }
    }

    suspend fun convert(
        context: Context,
        src: File,
        password: String?,
        opts: ConvertOptions,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ConvertStats = withContext(Dispatchers.IO) {
        val doc = try { PDDocument.load(src, password.orEmpty()) } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw PasswordNeeded() }
        val needPictures = opts.format == TargetFormat.PPTX && !opts.pptxEditable
        val session = if (needPictures) {
            try { PdfSession.open(context, src, password) } catch (e: Exception) { runCatching { doc.close() }; throw e }
        } else null
        val tmp = File(out.parentFile, ".${out.name}.part")
        try {
            val ex = PdfContentExtractor(doc)
            val source = object : PageSource {
                override val pageCount = ex.pageCount
                override fun pageSize(index: Int) = ex.pageSize(index)
                override fun content(index: Int, withImages: Boolean) = ex.extract(index, withImages)
                override fun render(index: Int, scale: Float): Pair<ByteArray, String>? {
                    val s = session ?: return null
                    return runBlocking {
                        val (w, h) = s.pageSize(index)
                        val k = minOf(scale, 2400f / maxOf(w, h))
                        val bmp = s.render(index, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), nightMode = false, annotations = true)
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos) // bitmap belongs to the session cache: never recycle
                        s.invalidateCache()
                        bos.toByteArray() to "jpeg"
                    }
                }
            }
            val stats = tmp.outputStream().use { os ->
                ConvertPipeline.run(source, opts, os, out.nameWithoutExtension, onProgress, isCancelled)
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            stats
        } finally {
            if (tmp.exists()) tmp.delete()
            runCatching { doc.close() }
            session?.closeAsync()
        }
    }
}
