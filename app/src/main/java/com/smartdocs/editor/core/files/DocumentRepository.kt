package com.smartdocs.editor.core.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.smartdocs.editor.core.data.AppDatabase
import com.smartdocs.editor.core.data.RecentDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val path: String)

/**
 * Every document the app edits lives as a local file inside the app's documents folder.
 * Content URIs from other apps are imported (copied) once; "save" writes the file back when possible.
 */
class DocumentRepository(private val context: Context, private val db: AppDatabase) {

    val docsDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents").apply { mkdirs() }
    val scansDir: File get() = File(docsDir, "Scans").apply { mkdirs() }
    val tempDir: File get() = File(context.cacheDir, "tmp").apply { mkdirs() }
    val signaturesDir: File get() = File(context.filesDir, "signatures").apply { mkdirs() }

    fun newTemp(prefix: String = "doc", ext: String = "pdf") = File(tempDir, "${prefix}_${System.currentTimeMillis()}.$ext")

    fun uniqueFile(dir: File, baseName: String, ext: String = "pdf"): File {
        val clean = baseName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "document" }
        var f = File(dir, "$clean.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "$clean ($n).$ext"); n++ }
        return f
    }

    // ---- link between a working copy and the document it came from ----
    private val origins = context.getSharedPreferences("origins", Context.MODE_PRIVATE)

    /** The content URI a local working copy was imported from, or null when the file is our own. */
    fun originOf(local: File): Uri? = origins.getString("l:" + local.absolutePath, null)?.let(Uri::parse)

    fun setOrigin(local: File, origin: Uri) {
        origins.edit().putString("l:" + local.absolutePath, origin.toString()).putString("o:$origin", local.absolutePath).apply()
    }

    /**
     * Resolve any URI to a local file PdfBox / PDFium can open. A content URI is copied once into [docsDir] and
     * remembered: opening the same document again reuses its working copy (refreshed when the source changed),
     * and saving writes back to the source through [writeBack].
     */
    suspend fun localFile(uri: Uri): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(uri.path!!)
        val known = origins.getString("o:$uri", null)?.let(::File)
        val srcSize = querySize(uri)
        if (known != null && known.exists() && (srcSize < 0 || srcSize == known.length())) return@withContext known
        val name = displayName(uri) ?: "document.pdf"
        val target = known?.takeIf { it.parentFile?.exists() == true } ?: uniqueFile(docsDir, name.removeSuffix(".pdf").removeSuffix(".PDF"))
        val tmp = File(target.parentFile, ".${target.name}.import")
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            ?: throw IllegalStateException("cannot open $uri")
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        setOrigin(target, uri)
        target
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    sealed interface WriteBack {
        /** The file is the document itself (no external source): nothing to do. */
        data object NotNeeded : WriteBack
        data object Done : WriteBack
        /** The system must ask the user (MediaStore files on Android 10+). Launch [sender], then call writeBack again. */
        class NeedsPermission(val sender: android.content.IntentSender) : WriteBack
        /** The source only granted read access. Ask the user to pick the file (grants write) or save a copy. */
        class Failed(val reason: String) : WriteBack
    }

    /** Copy the edited working copy back over the document it was opened from. */
    suspend fun writeBack(local: File): WriteBack = withContext(Dispatchers.IO) {
        val origin = originOf(local) ?: return@withContext WriteBack.NotNeeded
        val cr = context.contentResolver
        try {
            val out = runCatching { cr.openOutputStream(origin, "wt") }.getOrNull() ?: cr.openOutputStream(origin, "w")
                ?: return@withContext WriteBack.Failed("no output stream")
            out.use { o -> local.inputStream().use { it.copyTo(o) } }
            WriteBack.Done
        } catch (e: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException)
                WriteBack.NeedsPermission(e.userAction.actionIntent.intentSender)
            else if (android.os.Build.VERSION.SDK_INT >= 30 && origin.authority == MediaStore.AUTHORITY)
                runCatching { WriteBack.NeedsPermission(MediaStore.createWriteRequest(cr, listOf(origin)).intentSender) }.getOrElse { WriteBack.Failed(e.message ?: "denied") }
            else WriteBack.Failed(e.message ?: "denied")
        } catch (e: Exception) {
            WriteBack.Failed(e.message ?: e.toString())
        }
    }

    /** Replace [target] with [result] (atomically) and write it back to its source. Used by tools' "save to original". */
    suspend fun replaceOriginal(target: File, result: File): WriteBack = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, ".${target.name}.replace")
        result.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        if (result.absolutePath != target.absolutePath && result.parentFile == docsDir) runCatching { result.delete() }
        writeBack(target)
    }

    fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    suspend fun touchRecent(file: File, pageCount: Int = 0, lastPage: Int? = null) = withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(file).toString()
        val prev = db.docs().get(uri)
        db.docs().upsert(
            RecentDoc(
                uri = uri, name = file.name, size = file.length(), lastOpened = System.currentTimeMillis(),
                lastPage = lastPage ?: prev?.lastPage ?: 0, pageCount = if (pageCount > 0) pageCount else prev?.pageCount ?: 0,
                favorite = prev?.favorite ?: false, thumbPath = prev?.thumbPath,
            )
        )
    }

    suspend fun setThumb(file: File, thumb: File) = withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(file).toString()
        db.docs().get(uri)?.let { db.docs().upsert(it.copy(thumbPath = thumb.absolutePath)) }
    }

    /** Scan MediaStore for every PDF on the device (no broad storage permission needed on Android 10+ for own files; others need READ permission). */
    suspend fun scanDevicePdfs(): List<DeviceFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<DeviceFile>()
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATA)
        val sel = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        runCatching {
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, arrayOf("application/pdf", "%.pdf"), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += DeviceFile(
                        uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString()),
                        name = c.getString(1) ?: "document.pdf", size = c.getLong(2), modified = c.getLong(3) * 1000, path = c.getString(4) ?: "",
                    )
                }
            }
        }
        // plus everything in our own folder
        docsDir.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.forEach { f ->
            if (out.none { it.path == f.absolutePath }) out += DeviceFile(Uri.fromFile(f), f.name, f.length(), f.lastModified(), f.absolutePath)
        }
        out
    }

    fun listFolder(dir: File): List<File> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory || it.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "txt") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(files: List<File>, mime: String = "application/pdf"): Intent {
        val uris = ArrayList(files.map { shareUri(it) })
        return if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mime; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Write a finished file back to a user-chosen SAF location. */
    suspend fun exportTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        }
        fun formatDate(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))
    }
}
