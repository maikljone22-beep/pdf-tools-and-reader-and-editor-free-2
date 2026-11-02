package com.smartdocs.editor.ui.pages

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.convert6.ImageIo6
import java.io.File

/** Pages as pictures (200 dpi JPEG): saved to the gallery (Pictures/SmartDocs) or shared. Shared by the organizer and the reader thumbnails. */
object PageImageExport {
    private const val DPI = 200

    /** Saves [pages] (0-based) of [src]; returns how many were saved. Android 9 and older: shared instead (no permission needed). */
    suspend fun save(ctx: Context, src: File, password: String?, pages: List<Int>): Int {
        if (Build.VERSION.SDK_INT < 29) { share(ctx, src, password, pages); return 0 }
        var n = 0
        val cr = ctx.contentResolver
        ImageIo6.pagePictures(ctx, src, password, pages, DPI) { i, _, _, jpeg, _, _ ->
            val name = src.nameWithoutExtension.take(60) + "_p" + (pages.getOrElse(i) { i } + 1) + ".jpg"
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartDocs"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return@pagePictures
            val ok = runCatching {
                cr.openOutputStream(uri, "w")!!.use { it.write(jpeg) }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isSuccess
            if (ok) n++ else runCatching { cr.delete(uri, null, null) }
        }
        return n
    }

    /** Shares [pages] as JPEG pictures. */
    suspend fun share(ctx: Context, src: File, password: String?, pages: List<Int>) {
        val app = ctx.applicationContext as SmartDocsApp
        val dir = File(app.documents.tempDir, "page_images").apply { deleteRecursively(); mkdirs() }
        val files = ArrayList<File>()
        ImageIo6.pagePictures(ctx, src, password, pages, DPI) { i, _, _, jpeg, _, _ ->
            files += File(dir, src.nameWithoutExtension.take(60) + "_p" + (pages.getOrElse(i) { i } + 1) + ".jpg").apply { writeBytes(jpeg) }
        }
        if (files.isNotEmpty()) com.smartdocs.editor.ui.share5.S5Share.shareFiles(ctx, files, "image/jpeg")
    }
}
