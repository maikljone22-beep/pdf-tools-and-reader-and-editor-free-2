package com.smartdocs.editor.core.convert6

import android.content.Context
import android.graphics.BitmapFactory
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import java.io.ByteArrayOutputStream
import java.io.File

/** Android glue of the converters (set once at start-up). */
object Cv6Platform {

    fun install(ctx: Context) {
        // pictures the pure writers cannot keep as they are (WebP / odd BMP for Word, …) become PNG / JPEG
        PortableImage.platform = { bytes ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0 || o.outWidth.toLong() * o.outHeight > 40_000_000L) return@runCatching null
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                try {
                    val bos = ByteArrayOutputStream()
                    if (b.hasAlpha()) { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos); bos.toByteArray() to "png" }
                    else { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos); bos.toByteArray() to "jpg" }
                } finally { b.recycle() }
            }.getOrNull()
        }
        refreshLabels(ctx)
    }

    /** Placeholders of the Office → HTML renderers in the app language (convert audit A-M5). */
    fun refreshLabels(ctx: Context) {
        com.smartdocs.editor.core.office.OfficeHtml.labels = mapOf("image" to ctx.getString(R.string.cvf_lbl_image), "chart" to ctx.getString(R.string.cvf_lbl_chart))
    }

    /**
     * Start-up cleanup: outputs of a run the system killed (journal), converter temp files older than a day in the
     * cache (shared copies, scanner pictures, work folders) and hidden ".cv6_" copies an old version left in Documents.
     */
    fun cleanUp(ctx: Context, docs: DocumentRepository) {
        Cv6Engine.journalOf(ctx).purge()
        val day = 24L * 3600 * 1000
        OutputJournal.purgeOld(docs.tempDir, day)
        File(ctx.cacheDir, "tmp/shared").takeIf { it.isDirectory }?.let { OutputJournal.purgeOld(it, day) }
        docs.docsDir.listFiles()?.filter { it.name.startsWith(".cv6_") || (it.name.startsWith(".") && it.name.endsWith(".part")) }?.forEach { OutputJournal.deleteQuietly(it) }
    }
}
