package com.smartdocs.editor.core.share5

import android.app.ActivityManager
import android.content.Context
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.view5.Trash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Storage settings (O5): what the app keeps on the device, by category, and how to free it. */
object StorageManager {
    enum class Kind { THUMBS, TEMP, AI_INDEX, AI_VOICE, OTHER_CACHE, CLOUD, OCR, TRASH, REVIEWS, DOCUMENTS }

    /** [clearable] categories can be emptied from the storage screen (documents and reviews are user data). */
    data class Bucket(val kind: Kind, val bytes: Long, val clearable: Boolean, val files: Int, val provider: ProviderId? = null)

    private val THUMB_DIRS = setOf("thumbs", "tpl_thumbs")
    private val TEMP_DIRS = setOf("tmp", "share5", "sync_up", "annot2_open", "t5_batch_tmp")
    private val AI_DIRS = setOf("ai5_index")
    private val VOICE_DIRS = setOf("aivoice")

    private fun size(f: File): Pair<Long, Int> {
        if (!f.exists()) return 0L to 0
        if (f.isFile) return f.length() to 1
        var b = 0L; var n = 0
        f.walkTopDown().filter { it.isFile }.forEach { b += it.length(); n++ }
        return b to n
    }

    private fun sum(files: List<File>): Pair<Long, Int> = files.map(::size).fold(0L to 0) { a, x -> (a.first + x.first) to (a.second + x.second) }

    private fun docsDir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "Documents")
    private fun trash(c: Context) = Trash(File(c.applicationContext.filesDir, "trash"))

    private fun cacheGroup(c: Context, kind: Kind): List<File> {
        val kids = (c.cacheDir.listFiles() ?: emptyArray()).toList()
        return when (kind) {
            Kind.THUMBS -> kids.filter { it.name in THUMB_DIRS }
            Kind.TEMP -> kids.filter { it.name in TEMP_DIRS || (it.isFile && (it.name.startsWith("tpl_") || it.name.startsWith("sig_") || it.name.startsWith("sync_dl_") || it.name.startsWith("t5_"))) }
            Kind.AI_INDEX -> kids.filter { it.name in AI_DIRS }
            Kind.AI_VOICE -> kids.filter { it.name in VOICE_DIRS }
            Kind.OTHER_CACHE -> kids.filter { k -> k.name !in THUMB_DIRS && k.name !in TEMP_DIRS && k.name !in AI_DIRS && k.name !in VOICE_DIRS &&
                cacheGroup(c, Kind.TEMP).none { it == k } } + listOfNotNull(c.externalCacheDir)
            else -> emptyList()
        }
    }

    suspend fun scan(c: Context): List<Bucket> = withContext(Dispatchers.IO) {
        val out = ArrayList<Bucket>()
        for (k in listOf(Kind.THUMBS, Kind.TEMP, Kind.AI_INDEX, Kind.AI_VOICE, Kind.OTHER_CACHE)) {
            val (b, n) = sum(cacheGroup(c, k)); out += Bucket(k, b, true, n)
        }
        ProviderId.entries.forEach { p ->
            val (b, n) = size(File(docsDir(c), "Cloud/${p.key}"))
            if (n > 0) out += Bucket(Kind.CLOUD, b, true, n, p)
        }
        size(File(c.filesDir, "tessdata")).let { (b, n) -> out += Bucket(Kind.OCR, b, true, n) }
        val t = trash(c)
        out += Bucket(Kind.TRASH, runCatching { t.totalSize() }.getOrDefault(0L), true, runCatching { t.list().size }.getOrDefault(0))
        size(CollabStore.reviewsDir(c)).let { (b, n) -> out += Bucket(Kind.REVIEWS, b, false, n) }
        val docs = docsDir(c)
        val (all, allN) = size(docs)
        val (cloud, cloudN) = size(File(docs, "Cloud"))
        val (rev, revN) = size(File(docs, "Reviews"))
        out += Bucket(Kind.DOCUMENTS, all - cloud - rev, false, allN - cloudN - revN)
        out
    }

    /** Frees one category. Returns the bytes freed (approximate). */
    suspend fun clear(c: Context, b: Bucket): Long = withContext(Dispatchers.IO) {
        when (b.kind) {
            Kind.THUMBS, Kind.TEMP, Kind.AI_INDEX, Kind.AI_VOICE -> cacheGroup(c, b.kind).forEach { it.deleteRecursively() }
            Kind.OTHER_CACHE -> { cacheGroup(c, Kind.OTHER_CACHE).forEach { if (it == c.externalCacheDir) it.listFiles()?.forEach { f -> f.deleteRecursively() } else it.deleteRecursively() } }
            Kind.CLOUD -> b.provider?.let { p -> File(docsDir(c), "Cloud/${p.key}").listFiles()?.forEach { it.deleteRecursively() } }
            Kind.OCR -> File(c.filesDir, "tessdata").listFiles()?.forEach { it.deleteRecursively() }
            Kind.TRASH -> trash(c).empty()
            Kind.REVIEWS, Kind.DOCUMENTS -> Unit
        }
        b.bytes
    }

    /** Clears every cache category (not documents, reviews, OCR languages or the trash). */
    suspend fun clearCaches(c: Context): Long {
        var n = 0L
        for (b in scan(c)) if (b.kind in setOf(Kind.THUMBS, Kind.TEMP, Kind.AI_INDEX, Kind.AI_VOICE, Kind.OTHER_CACHE)) n += clear(c, b)
        return n
    }

    /** Signs out of a cloud provider; optionally deletes its local working copies. */
    suspend fun unlink(c: Context, id: ProviderId, deleteCopies: Boolean) {
        runCatching { Clouds.provider(c, id).disconnect() }
        if (deleteCopies) withContext(Dispatchers.IO) { File(docsDir(c), "Cloud/${id.key}").listFiles()?.forEach { it.deleteRecursively() } }
    }

    /** Wipes ALL app data (documents included) and kills the process — the system does it, like "Clear storage". */
    fun clearAllData(c: Context): Boolean = (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
}
