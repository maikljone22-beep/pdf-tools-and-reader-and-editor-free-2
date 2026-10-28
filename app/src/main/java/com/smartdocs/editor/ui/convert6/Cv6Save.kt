package com.smartdocs.editor.ui.convert6

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.smartdocs.editor.core.convert6.Images6
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Saving extracted pictures: ZIP, a user-picked folder (SAF tree), the gallery (MediaStore), share copies. */
object Cv6Save {

    /** Entry names made unique (a duplicate name broke the ZIP — convert audit B27). */
    suspend fun zip(items: List<Pair<File, String>>, out: File): File = withContext(Dispatchers.IO) {
        val names = com.smartdocs.editor.core.convert6.ImageNaming.dedupe(items.map { it.second })
        ZipOutputStream(out.outputStream().buffered()).use { z -> items.forEachIndexed { i, (f, _) -> z.putNextEntry(ZipEntry(names[i])); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        out
    }

    /** Android 9 and older write to the gallery only with the storage permission (convert audit B28). */
    fun needsStoragePermission(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 &&
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Writes every file into the picked tree folder; returns how many were written. */
    suspend fun toTree(ctx: Context, tree: Uri, items: List<Pair<File, String>>): Int = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var n = 0
        items.forEach { (f, name) ->
            runCatching {
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, Images6.mimeOf(f.extension), name) ?: return@runCatching
                ctx.contentResolver.openOutputStream(doc)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                n += 1
            }
        }
        n
    }

    /** Pictures/SmartDocs in the gallery (Android 10+ without permission; older versions need storage permission). */
    suspend fun toGallery(ctx: Context, items: List<Pair<File, String>>): Int = withContext(Dispatchers.IO) {
        var n = 0
        items.forEach { (f, name) ->
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, Images6.mimeOf(f.extension))
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartDocs")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                try {
                    (ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("no output")).use { o -> f.inputStream().use { it.copyTo(o) } }
                    if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    // no ghost "pending" entry in the gallery when the copy fails (convert audit B28)
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                    throw e
                }
                n += 1
            }
        }
        n
    }

    /**
     * Copies under their final names into a NEW sub-folder of [dir] (so the share target sees real file names); an
     * earlier share's folder is left for the app that may still be reading it (convert audit B28) and removed at the
     * next start once it is a day old.
     */
    suspend fun named(items: List<Pair<File, String>>, dir: File): List<File> = withContext(Dispatchers.IO) {
        val sub = File(dir, System.currentTimeMillis().toString()).apply { mkdirs() }
        val names = com.smartdocs.editor.core.convert6.ImageNaming.dedupe(items.map { it.second })
        items.mapIndexed { i, (f, _) -> File(sub, names[i]).also { f.copyTo(it, overwrite = true) } }
    }

    /** Gallery-friendly: JPEG/PNG/GIF/WebP only (JPX / EMF / TIFF pictures are skipped there). */
    fun galleryOk(ext: String) = ext.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}
