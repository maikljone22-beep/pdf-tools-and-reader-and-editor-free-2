package com.smartdocs.editor.core.ai

import android.content.Context
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A [DocHost] over a plain file (no open reader): used by the full-screen assistant. Edits replace the file in
 * place with a backup for undo; page text is read with a short-lived [PdfSession].
 */
class FileDocHostState(private val ctx: Context, private val docs: DocumentRepository, val file: File, initialPassword: String? = null) {
    @Volatile var password: String? = initialPassword; private set
    @Volatile var pageCount: Int = 0; private set
    @Volatile var currentPage: Int = 0
    private val mutex = Mutex()
    private val textCache = HashMap<Int, String>()

    suspend fun refresh() {
        pageCount = runCatching { PdfOps.pageCount(file, password) }.getOrDefault(0)
        synchronized(textCache) { textCache.clear() }
        currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    private suspend fun text(i: Int): String {
        synchronized(textCache) { textCache[i] }?.let { return it }
        val all = withContext(Dispatchers.IO) {
            val s = PdfSession.open(ctx, file, password)
            try { s.allText() } finally { s.closeAsync() }
        }
        synchronized(textCache) { all.forEachIndexed { k, t -> textCache[k] = t } }
        return all.getOrElse(i) { "" }
    }

    private suspend fun apply(e: PdfEdit): File? = mutex.withLock {
        val out = File(file.parentFile, ".${file.nameWithoutExtension}.agent.pdf")
        val backup = docs.newTemp("undo")
        try {
            withContext(Dispatchers.IO) { file.copyTo(backup, overwrite = true); out.delete() }
            e.op(file, out, password)
            if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty result")
            withContext(Dispatchers.IO) { out.copyTo(file, overwrite = true); out.delete() }
            if (e.changesPassword) password = e.newPassword
            refresh()
            backup
        } catch (t: Throwable) { out.delete(); backup.delete(); throw t }
    }

    private suspend fun restore(backup: File, pw: String?) = mutex.withLock {
        withContext(Dispatchers.IO) { backup.copyTo(file, overwrite = true) }
        password = pw
        refresh()
    }

    fun host(openFile: (File) -> Unit, share: (List<File>, String) -> Unit, goToPage: (Int) -> Unit = { currentPage = it }): DocHost = DocHost(
        file = { file }, password = { password }, pageCount = { pageCount }, currentPage = { currentPage },
        apply = { apply(it) }, restore = { b, pw -> restore(b, pw) },
        goToPage = { currentPage = it; goToPage(it) }, pageText = { text(it) },
        openFile = openFile, share = share,
    )
}
