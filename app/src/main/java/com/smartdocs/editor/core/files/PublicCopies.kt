package com.smartdocs.editor.core.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * The library lives in the app's own storage, which Android deletes with the app. Every document the app creates
 * there (scans, conversions, merges, new Office files) also gets a copy in the shared "Documents/SmartDocs" folder,
 * which stays after uninstalling. The copy becomes the document's origin, so later saves are written back to it by the
 * existing write-back (no second mechanism). Android 10+ (MediaStore, no storage permission); older versions keep the
 * old behaviour.
 */
object PublicCopies {
    const val FOLDER = "SmartDocs"

    /** Pure: files worth publishing — real documents inside the library, not hidden / temporary / partial ones. */
    fun eligible(name: String, insideLibrary: Boolean, hasOrigin: Boolean): Boolean =
        insideLibrary && !hasOrigin && name.isNotBlank() && !name.startsWith(".") && !name.endsWith(".tmp") &&
            !name.contains(".part_") && !name.endsWith(".import")

    /** Sub-folder under Documents for [file] (scans keep their own folder). */
    fun relativePath(file: File, docsDir: File, scansDir: File): String =
        Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + if (file.parentFile?.absolutePath == scansDir.absolutePath) "/Scans" else ""

    /** Publishes [file] when eligible; returns the new shared uri or null. Never throws. */
    fun publish(ctx: Context, repo: DocumentRepository, file: File): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val docs = repo.docsDir.absolutePath
        val inside = file.isFile && file.absolutePath.startsWith(docs + File.separator)
        if (!eligible(file.name, inside, repo.originOf(file) != null)) return null
        val cr = ctx.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(file, repo.docsDir, repo.scansDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return null
        val ok = runCatching {
            cr.openOutputStream(uri, "w")!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (!ok) { runCatching { cr.delete(uri, null, null) }; return null }
        repo.setOrigin(file, uri)
        repo.markSynced(file, uri)
        return uri
    }

    /** Once: copies the documents already in the library. */
    fun publishExisting(ctx: Context, repo: DocumentRepository) {
        if (Build.VERSION.SDK_INT < 29) return
        val p = ctx.getSharedPreferences("public_copies", Context.MODE_PRIVATE)
        if (p.getBoolean("done_v1", false)) return
        repo.docsDir.walkTopDown().maxDepth(2).filter { it.isFile }.forEach { publish(ctx, repo, it) }
        p.edit().putBoolean("done_v1", true).apply()
    }
}
