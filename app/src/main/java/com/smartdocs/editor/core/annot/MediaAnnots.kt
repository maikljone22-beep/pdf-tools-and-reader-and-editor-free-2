package com.smartdocs.editor.core.annot

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Embedded files of page attachments (sound / video / file) and the voice recorder used to create them. */
object MediaAnnots {

    /** Extract the file embedded in file-attachment annotation [index] of [page] into [dir]; null when none. */
    suspend fun extractAt(src: File, pw: String?, page: Int, index: Int, dir: File): Pair<File, String>? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val doc = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)
        doc.use { d ->
            if (page !in 0 until d.numberOfPages) return@use null
            val any = d.getPage(page).annotations.getOrNull(index) ?: return@use null
            // native /Sound and Screen audio clips of other PDFs (decoded / extracted by SoundPdf)
            if (any.subtype == "Sound" || any.subtype == "Screen") return@withContext SoundPdf.extractAt(src, pw, page, index, dir)
            val an = any as? PDAnnotationFileAttachment ?: return@use null
            val spec = runCatching { an.file as? PDComplexFileSpecification }.getOrNull() ?: return@use null
            val ef = spec.embeddedFileUnicode ?: spec.embeddedFile ?: return@use null
            val name = (spec.filename ?: "attachment").replace(Regex("""[\\/:*?"<>|]"""), "_")
            val out = File(dir, name)
            out.outputStream().use { o -> o.write(ef.toByteArray()) }
            out to (ef.subtype ?: "")
        }
    }

    /** Mime type from a file name. */
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "m4a", "aac" -> "audio/mp4"; "mp4" -> "video/mp4"; else -> "application/octet-stream"
        }
    }

    /** Thin wrapper over MediaRecorder: AAC in an MPEG-4 (.m4a) container. */
    class Recorder(private val context: Context) {
        private var rec: MediaRecorder? = null
        var file: File? = null; private set
        var startedAt = 0L; private set

        fun start(out: File) {
            stop(discard = true)
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(out.absolutePath)
            r.prepare(); r.start()
            rec = r; file = out; startedAt = System.currentTimeMillis()
        }

        /** Stop recording; returns the file, or null when it failed / was discarded. */
        fun stop(discard: Boolean = false): File? {
            val r = rec ?: return null
            rec = null
            val ok = runCatching { r.stop() }.isSuccess
            runCatching { r.release() }
            val f = file; file = null
            if (!ok || discard) { f?.delete(); return null }
            return f?.takeIf { it.length() > 0 }
        }

        val recording: Boolean get() = rec != null

        fun amplitude(): Int = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
    }
}
