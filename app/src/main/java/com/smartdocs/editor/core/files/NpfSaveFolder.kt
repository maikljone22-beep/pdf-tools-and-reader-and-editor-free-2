package com.smartdocs.editor.core.files

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.data.NpfPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * nav-prefs (Nitro "default locations", Foxit "preferred Save As location"): a default save folder.
 *
 * Every tool still writes its output into the app's Documents folder through `DocumentRepository.uniqueFile(docsDir, …)`
 * (PdfBox and PDFium need a real file, and the result sheets, recents and history keep working unchanged). When the
 * user picks a folder (an SAF tree) in Settings, a copy of each new or changed output also goes to that folder:
 *  - a [FileObserver] on Documents and Documents/Scans sees every finished write (close-after-write or rename into
 *    the folder), waits until the file has been quiet for a few seconds and copies it (same name: replaced);
 *  - reader saves arrive through `SaveHooks.onSaved` → [copySoon];
 *  - after a restart, [catchUp] copies what changed while the app was not running ([NpfPrefs.saveLast]).
 * Working copies of documents opened from elsewhere (they have an origin and are written back there), hidden temp
 * files and sub-folders (cloud copies, user folders) are never copied. The same observer queues the full-text index
 * of new PDFs (old plan: `IndexWorker.indexSoon` had no caller for tool outputs).
 */
object NpfSaveFolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, Job>()
    private val observers = ArrayList<FileObserver>()

    /** Called once from SmartDocsApp.onCreate. */
    fun start(app: SmartDocsApp) {
        val dirs = listOf(app.documents.docsDir, app.documents.scansDir)
        dirs.forEach { dir ->
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
            @Suppress("DEPRECATION")
            val o = object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    val name = path ?: return
                    if (!isCandidateName(name)) return
                    val f = File(dir, name)
                    schedule(app, f, fromObserver = true)
                }
            }
            runCatching { o.startWatching(); observers += o }
        }
        scope.launch { delay(4_000); runCatching { catchUp(app) } }
    }

    /** A reader save (or any caller) of [file]: copy it to the save folder after it settles. */
    fun copySoon(context: Context, file: File) {
        val app = context.applicationContext as? SmartDocsApp ?: return
        schedule(app, file, fromObserver = false)
    }

    private fun schedule(app: SmartDocsApp, f: File, fromObserver: Boolean) {
        synchronized(pending) {
            pending.remove(f.absolutePath)?.cancel()
            pending[f.absolutePath] = scope.launch {
                delay(QUIET_MS)
                synchronized(pending) { pending.remove(f.absolutePath) }
                if (!f.isFile) return@launch
                if (fromObserver && f.extension.equals("pdf", true)) runCatching { com.smartdocs.editor.core.search.IndexWorker.indexSoon(app, f) }
                runCatching { copy(app, f) }
            }
        }
    }

    /** Files of Documents / Scans changed since the last copy (the app was not running). */
    suspend fun catchUp(app: SmartDocsApp) {
        if (NpfPrefs.saveTree.isBlank()) return
        val since = NpfPrefs.saveLast
        listOf(app.documents.docsDir, app.documents.scansDir).flatMap { d -> d.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && isCandidateName(it.name) && it.lastModified() > since }
            .sortedBy { it.lastModified() }
            .forEach { runCatching { copy(app, it) } }
    }

    /** Copy [f] into the chosen tree. False when nothing was copied (no folder, not an output, no access). */
    fun copy(app: SmartDocsApp, f: File): Boolean {
        val tree = NpfPrefs.saveTree.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return false
        val docs = app.documents
        if (!isOutput(f, docs.docsDir, docs.scansDir) || docs.originOf(f) != null) return false
        val root = DocumentFile.fromTreeUri(app, tree)?.takeIf { it.canWrite() } ?: return false
        val existing = root.findFile(f.name)?.takeIf { it.isFile }
        val target = existing ?: root.createFile(mimeOf(f.name), f.name) ?: return false
        val cr = app.contentResolver
        val out = runCatching { cr.openOutputStream(target.uri, "wt") }.getOrNull() ?: cr.openOutputStream(target.uri, "w") ?: return false
        val n = out.use { o -> f.inputStream().use { it.copyTo(o) } }
        if (n != f.length()) return false
        if (f.lastModified() > NpfPrefs.saveLast) NpfPrefs.saveLast = f.lastModified()
        return true
    }

    /** Pure: is [name] a finished user-visible file (not a hidden temp / import / replace file)? */
    fun isCandidateName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith(".") && !name.endsWith(".tmp", true) && !name.endsWith(".part", true) && name.contains('.')

    /** Pure: outputs live directly in Documents or Documents/Scans (sub-folders are cloud copies or user folders). */
    fun isOutput(f: File, docsDir: File, scansDir: File): Boolean {
        val parent = f.parentFile?.absolutePath ?: return false
        return isCandidateName(f.name) && (parent == docsDir.absolutePath || parent == scansDir.absolutePath)
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private const val QUIET_MS = 3_000L
}
