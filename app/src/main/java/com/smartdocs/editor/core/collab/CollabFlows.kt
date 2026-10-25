package com.smartdocs.editor.core.collab

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Create / join flows used by the review screen and the `smartdocs://review/<id>?c=<code>` deep link. */
object CollabFlows {
    private const val MAX_BYTES = 20L * 1024 * 1024

    class TooLarge : IllegalStateException("file larger than 20 MB")
    class Encrypted : IllegalStateException("encrypted PDF")

    /** Copies [source] into the Reviews folder, uploads it and registers the review as owner. */
    suspend fun create(c: Context, source: File, title: String, deadline: Long): Pair<ReviewRef, CreatedReview> = withContext(Dispatchers.IO) {
        if (source.length() > MAX_BYTES) throw TooLarge()
        val dir = CollabStore.reviewsDir(c)
        val local = unique(dir, source.name)
        // give every comment an /NM before sharing so others can reply to / merge them by id
        PDDocument.load(source).use { doc ->
            if (doc.isEncrypted) throw Encrypted()
            CollabAnnots.ensureNames(doc)
            doc.save(local)
        }
        val created = try { CollabClient.create(local, title, CollabStore.authorName(c), deadline) } catch (e: Exception) { local.delete(); throw e }
        val ref = ReviewRef(created.id, created.code, title, local.name, local.absolutePath, created.ownerKey, deadline, System.currentTimeMillis())
        CollabStore.upsert(c, ref)
        CollabStore.markAllPublished(c, ref)
        ref to created
    }

    /** Downloads the shared PDF (once) and the current comments. Returns the local review. */
    suspend fun join(c: Context, id: String, code: String): ReviewRef = withContext(Dispatchers.IO) {
        CollabStore.get(c, id)?.takeIf { File(it.localPath).exists() }?.let { existing ->
            runCatching { CollabStore.fetch(c, existing) }
            return@withContext CollabStore.get(c, id) ?: existing
        }
        val meta = CollabClient.meta(id, code)
        val local = unique(CollabStore.reviewsDir(c), meta.fileName.ifBlank { "review.pdf" })
        CollabClient.download(id, code, local)
        val ref = ReviewRef(id, code, meta.title.ifBlank { meta.fileName }, local.name, local.absolutePath, null, meta.deadline, 0L, meta.closed)
        CollabStore.upsert(c, ref)
        CollabStore.markAllForeign(c, ref)
        runCatching { CollabStore.fetch(c, ref) }
        CollabStore.get(c, id) ?: ref
    }

    private fun unique(dir: File, name: String): File {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val base = clean.substringBeforeLast('.'); val ext = clean.substringAfterLast('.', "pdf")
        var f = File(dir, "$base.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "$base ($n).$ext"); n++ }
        return f
    }
}
