package com.smartdocs.editor.core.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
        // a new origin invalidates what we knew about the old one (sync record, imported name)
        origins.edit().putString("l:" + local.absolutePath, origin.toString()).putString("o:$origin", local.absolutePath)
            .remove("s:" + local.absolutePath).remove("n:" + local.absolutePath).apply()
    }

    /** Last state in which [local] and its source were identical (audit DI-02); null for legacy copies. */
    private fun syncRecord(local: File): SyncRecord? = SyncRecord.decode(origins.getString("s:" + local.absolutePath, null))

    /** Record that [local] and [origin] now hold the same bytes (after an import, a write-back or a "save a copy"). */
    fun markSynced(local: File, origin: Uri) {
        val rec = SyncRecord(FileFingerprint.of(local), querySize(origin).takeIf { it >= 0 } ?: local.length(), queryMtime(origin))
        origins.edit().putString("s:" + local.absolutePath, rec.encode()).apply()
    }

    /** The display name the source had when [local] was imported, for checking a re-picked "original" (audit DI-13). */
    fun importedNameOf(local: File): String? = origins.getString("n:" + local.absolutePath, null)

    /** The source size recorded when [local] and its origin were last in sync, or -1. */
    fun syncedSourceSizeOf(local: File): Long = syncRecord(local)?.srcSize ?: -1L

    private fun pendingKey(local: File) = "p:" + local.absolutePath

    /**
     * Rename a document in place, never replacing another file (audit DI-04), and carry its origin link along so a
     * later re-open of the source neither loses write-back nor writes into whatever file takes the old name (DI-03).
     */
    fun rename(f: File, newName: String): SafeRename.Result {
        val before = f.absolutePath
        val r = SafeRename.rename(f, newName)
        if (r is SafeRename.Result.Ok && r.file.absolutePath != before) moveOriginKeys(before, r.file.absolutePath)
        return r
    }

    private fun moveOriginKeys(from: String, to: String) {
        val e = origins.edit()
        origins.getString("l:$from", null)?.let { uri -> e.putString("l:$to", uri).putString("o:$uri", to) }
        for (k in listOf("s:", "n:")) origins.getString(k + from, null)?.let { e.putString(k + to, it) }
        if (origins.contains("p:$from")) e.putBoolean("p:$to", true)
        listOf("l:", "s:", "n:", "p:").forEach { e.remove(it + from) }
        e.apply()
    }

    /** Drop the origin link of a document that was deleted or moved to the trash (audit DI-03). */
    fun forgetOrigin(f: File) {
        val path = f.absolutePath
        val e = origins.edit()
        origins.getString("l:$path", null)?.let { uri -> if (origins.getString("o:$uri", null) == path) e.remove("o:$uri") }
        listOf("l:", "s:", "n:", "p:").forEach { e.remove(it + path) }
        e.apply()
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
        var replace: File? = null
        if (known != null && known.exists()) {
            // never overwrite a working copy that holds edits the source lacks (read-only Telegram/e-mail sources,
            // or a source we truncated in an interrupted write-back) — audit DI-02 / DI-01 / DI-20
            val action = ImportDecision.decide(
                syncRecord(known), FileFingerprint.of(known), srcSize, queryMtime(uri),
                writeBackPending = origins.contains(pendingKey(known)),
            )
            when (action) {
                ImportDecision.Action.REUSE -> return@withContext known
                ImportDecision.Action.REFRESH -> replace = known
                // keep the edited copy as a standalone document so its next save cannot overwrite the newer source
                ImportDecision.Action.IMPORT_AS_NEW -> origins.edit().remove("l:" + known.absolutePath).remove("s:" + known.absolutePath).apply()
            }
        }
        val name = displayName(uri) ?: "document.pdf"
        // office5: keep the real extension (Office/text imports were all saved as *.pdf)
        val ext = name.substringAfterLast('.', "pdf").lowercase().takeIf { it.length in 2..5 } ?: "pdf"
        val target = replace?.takeIf { it.parentFile?.exists() == true } ?: uniqueFile(docsDir, name.substringBeforeLast('.'), ext)
        val tmp = File(target.parentFile, ".${target.name}.import")
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            ?: throw IllegalStateException("cannot open $uri")
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        setOrigin(target, uri)
        setImportedName(target, name)
        markSynced(target, uri)
        target
    }

    /** Size the provider reports for [uri], or -1. */
    fun sourceSize(uri: Uri): Long = querySize(uri)

    /** Remember [name] as the display name of [local]'s source (checked when the user re-picks the original). */
    fun setImportedName(local: File, name: String) { origins.edit().putString("n:" + local.absolutePath, name).apply() }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun queryMtime(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
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
        // The origin is truncated and rewritten in place (content URIs have no atomic rename), so keep a backup of
        // its current bytes first and put them back if the write fails or the written size is wrong.
        if (!local.exists() || local.length() == 0L) return@withContext WriteBack.Failed("empty working copy")
        val backup = File(tempDir, "writeback_${System.nanoTime()}.bak")
        // Journal (audit DI-01/DI-02): while this marker exists the origin may be half-written by us, so localFile must
        // keep the working copy instead of re-importing the origin over it. It is written synchronously so it survives
        // a kill mid-write, and only removed once the origin is known to be whole (written, or restored).
        origins.edit().putBoolean(pendingKey(local), true).commit()
        var originWhole = true
        try {
            val backedUp = runCatching { cr.openInputStream(origin)?.use { i -> backup.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
            fun restore() {
                if (!backedUp) return
                runCatching {
                    (runCatching { cr.openOutputStream(origin, "wt") }.getOrNull() ?: cr.openOutputStream(origin, "w"))?.use { o -> backup.inputStream().use { it.copyTo(o) } }
                        ?: error("no output stream")
                }.onSuccess { originWhole = true }
            }
            try {
                val out = runCatching { cr.openOutputStream(origin, "wt") }.getOrNull() ?: cr.openOutputStream(origin, "w")
                    ?: return@withContext WriteBack.Failed("no output stream")
                originWhole = false
                out.use { o -> local.inputStream().use { it.copyTo(o) }; o.flush() }
            } catch (e: SecurityException) { if (!originWhole) restore(); throw e } catch (e: Exception) { restore(); throw e }
            // verify: the origin now has exactly the working copy's size (when the provider reports a size)
            val written = runCatching { cr.openFileDescriptor(origin, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            if (written >= 0 && written != local.length()) {
                restore()
                return@withContext WriteBack.Failed("write-back size mismatch ($written != ${local.length()})")
            }
            originWhole = true
            runCatching { markSynced(local, origin) }
            WriteBack.Done
        } catch (e: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException)
                WriteBack.NeedsPermission(e.userAction.actionIntent.intentSender)
            else if (android.os.Build.VERSION.SDK_INT >= 30 && origin.authority == MediaStore.AUTHORITY)
                runCatching { WriteBack.NeedsPermission(MediaStore.createWriteRequest(cr, listOf(origin)).intentSender) }.getOrElse { WriteBack.Failed(e.message ?: "denied") }
            else WriteBack.Failed(e.message ?: "denied")
        } catch (e: Exception) {
            WriteBack.Failed(e.message ?: e.toString())
        } finally {
            backup.delete()
            if (originWhole) origins.edit().remove(pendingKey(local)).apply()
        }
    }

    /**
     * Replace [target] with [result] (atomically). Used by tools' "save in the same file"; the caller then writes it back
     * to its source. A copy of the old [target] goes to the trash first (audit UX-02): if that copy cannot be made the
     * document is left untouched and this throws. Returns the trash entry holding the previous version.
     */
    suspend fun replaceOriginal(target: File, result: File): com.smartdocs.editor.core.view5.Trash.Entry = withContext(Dispatchers.IO) {
        val backup = com.smartdocs.editor.core.view5.Trash(File(context.filesDir, "trash")).copyToTrash(target)
            ?: throw java.io.IOException("cannot keep a copy of ${target.name} (storage full?)")
        val tmp = File(target.parentFile, ".${target.name}.replace")
        try {
            result.copyTo(tmp, overwrite = true)
            if (tmp.length() != result.length()) throw java.io.IOException("short copy of ${result.name}")
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally { tmp.delete() }
        if (result.absolutePath != target.absolutePath && result.parentFile == docsDir) runCatching { result.delete() }
        backup
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

    fun listFolder(dir: File): List<File> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory || it.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "txt", "docx", "xlsx", "pptx", "csv", "odt", "rtf", "doc", "xls", "ppt", "md") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(files: List<File>, mime: String = "application/pdf"): Intent {
        val uris = ArrayList(files.map { shareUri(it) })
        return if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mime; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Write a finished file back to a user-chosen SAF location. */
    suspend fun exportTo(file: File, target: Uri): Long = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val out = runCatching { cr.openOutputStream(target, "wt") }.getOrNull() ?: cr.openOutputStream(target, "w")
            ?: throw java.io.IOException("cannot write to $target")
        val n = out.use { o -> file.inputStream().use { it.copyTo(o) }.also { o.flush() } }
        // audit DI-29: never report a short / empty copy as saved
        val written = runCatching { cr.openFileDescriptor(target, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        verifyCopied(file.length(), n, written)
        n
    }

    companion object {
        /** Throws when a copy of [expected] bytes came out short: [copied] by the stream, [reported] by the provider (-1 = unknown). */
        fun verifyCopied(expected: Long, copied: Long, reported: Long) {
            if (copied != expected) throw java.io.IOException("copied $copied of $expected bytes")
            if (reported >= 0 && reported != expected) throw java.io.IOException("target has $reported of $expected bytes")
        }

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        }
        fun formatDate(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))
    }
}
