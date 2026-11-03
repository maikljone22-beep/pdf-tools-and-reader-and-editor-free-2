package com.smartdocs.editor.core.tools5

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.pdf.PdfOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pictures → PDF that keeps going in the background and resumes (user request: 1000+ pictures, leaving the app).
 * The pictures are moved into a job folder, converted in chunks of [CHUNK] (each chunk a finished PDF recorded in a
 * manifest), and the chunks joined at the end. A foreground notification keeps the process alive; if the system still
 * stops it, the next start offers to resume from the first unfinished chunk — nothing done is redone.
 */
object ImagesPdfJob {
    const val CHUNK = 40

    /** Pure: chunk k covers pictures [k*CHUNK, (k+1)*CHUNK). */
    fun chunks(count: Int, size: Int = CHUNK): List<IntRange> = (0 until count step size).map { it until minOf(count, it + size) }

    data class Manifest(val images: List<String>, val fitA4: Boolean, val quality: Int, val base: String, val done: Int)

    fun toJson(m: Manifest) = JSONObject().put("images", JSONArray(m.images)).put("fit", m.fitA4).put("q", m.quality).put("base", m.base).put("done", m.done).toString()
    fun fromJson(s: String): Manifest? = runCatching {
        val o = JSONObject(s); val a = o.getJSONArray("images")
        Manifest((0 until a.length()).map { a.getString(it) }, o.getBoolean("fit"), o.getInt("q"), o.getString("base"), o.getInt("done"))
    }.getOrNull()

    private fun dir(ctx: Context) = File(ctx.filesDir, "images_pdf_job")
    private fun manifestFile(ctx: Context) = File(dir(ctx), "manifest.json")

    // ---- state the screen observes
    var running by mutableStateOf(false); private set
    var progress by mutableStateOf(0 to 0); private set
    var result by mutableStateOf<File?>(null)
    var error by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** An interrupted job (pictures done, total), or null. */
    fun pending(ctx: Context): Pair<Int, Int>? {
        if (running) return null
        val m = manifestFile(ctx).takeIf { it.exists() }?.readText()?.let(::fromJson) ?: return null
        if (m.images.none { File(it).exists() }) { discard(ctx); return null }
        return minOf(m.done * CHUNK, m.images.size) to m.images.size
    }

    fun discard(ctx: Context) { if (!running) dir(ctx).deleteRecursively() }

    /** Starts a new job: the pictures are moved into the job folder (copied when they cannot be moved). */
    fun start(ctx: Context, images: List<File>, fitA4: Boolean, quality: Int, base: String) {
        if (running) return
        val app = ctx.applicationContext
        val d = dir(app).apply { deleteRecursively(); mkdirs() }
        val kept = images.mapIndexedNotNull { i, f ->
            val t = File(d, "p%05d_".format(i) + f.name.takeLast(80))
            when {
                f.parentFile?.absolutePath?.startsWith(app.cacheDir.absolutePath) == true && f.renameTo(t) -> t
                runCatching { f.copyTo(t, true) }.isSuccess -> t
                else -> null
            }?.absolutePath
        }
        writeManifest(app, Manifest(kept, fitA4, quality, base, 0))
        resume(app)
    }

    /** Continues the job in the manifest from its first unfinished chunk. */
    fun resume(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val m0 = manifestFile(app).takeIf { it.exists() }?.readText()?.let(::fromJson) ?: return
        running = true; error = null; result = null
        com.smartdocs.editor.ui.convert6.Cv6JobService.start(app, m0.base)
        job = scope.launch {
            try {
                var m = m0
                val parts = chunks(m.images.size)
                progress = minOf(m.done * CHUNK, m.images.size) to m.images.size
                for (k in m.done until parts.size) {
                    coroutineContext.ensureActive()
                    val files = parts[k].map { File(m.images[it]) }.filter { it.exists() }
                    val part = File(dir(app), "part_%04d.pdf".format(k))
                    if (files.isNotEmpty()) PdfOps.imagesToPdf(files, part, m.fitA4, m.quality) else part.delete()
                    m = m.copy(done = k + 1); writeManifest(app, m)
                    progress = minOf((k + 1) * CHUNK, m.images.size) to m.images.size
                }
                val partFiles = parts.indices.map { File(dir(app), "part_%04d.pdf".format(it)) }.filter { it.exists() }
                require(partFiles.isNotEmpty()) { "no pictures" }
                val docs = (app as SmartDocsApp).documents
                val out = docs.uniqueFile(docs.docsDir, m.base)
                if (partFiles.size == 1) partFiles[0].copyTo(out, true) else PdfOps.merge(partFiles, out)
                runCatching { docs.touchRecent(out) }
                dir(app).deleteRecursively()
                result = out
                com.smartdocs.editor.ui.convert6.Cv6JobService.notifyDone(app, true, out.name)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                error = if (e is OutOfMemoryError) app.getString(com.smartdocs.editor.R.string.tttl_out_of_memory) else e.message ?: e.toString()
                com.smartdocs.editor.ui.convert6.Cv6JobService.notifyDone(app, false, error ?: "")
            } finally {
                running = false
                com.smartdocs.editor.ui.convert6.Cv6JobService.stop(app)
            }
        }
    }

    fun cancel() { job?.cancel() }

    private fun writeManifest(ctx: Context, m: Manifest) {
        val f = manifestFile(ctx); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp"); tmp.writeText(toJson(m))
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }
}
