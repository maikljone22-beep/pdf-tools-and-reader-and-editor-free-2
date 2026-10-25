package com.smartdocs.editor.core.annot

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handwriting → text with ML Kit Digital Ink Recognition (on-device after a one-time model download per
 * language). Strokes are given in PDF space (y up) and flipped to the y-down space ML Kit expects.
 */
object InkRecognizer {

    /** Languages offered in the chip row (BCP-47 tags ML Kit knows). */
    val LANGUAGES = listOf("ar", "en", "fr", "de", "es", "tr-TR", "fa", "ur", "zh-Hani-CN", "ja", "ru", "hi")

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    fun model(tag: String): DigitalInkRecognitionModel? {
        val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() ?: return null
        return DigitalInkRecognitionModel.builder(id).build()
    }

    suspend fun isDownloaded(tag: String): Boolean {
        val m = model(tag) ?: return false
        return runCatching { RemoteModelManager.getInstance().isModelDownloaded(m).await() }.getOrDefault(false)
    }

    /** Download the model for [tag] (no progress is reported by the API). */
    suspend fun download(tag: String) {
        val m = model(tag) ?: throw IllegalArgumentException("unsupported language: $tag")
        RemoteModelManager.getInstance().download(m, DownloadConditions.Builder().build()).await()
    }

    suspend fun delete(tag: String) {
        val m = model(tag) ?: return
        runCatching { RemoteModelManager.getInstance().deleteDownloadedModel(m).await() }
    }

    /** Recognise [strokes] (PDF space). Returns the candidates, best first. */
    suspend fun recognize(tag: String, strokes: List<List<Pair<Float, Float>>>): List<String> {
        val m = model(tag) ?: throw IllegalArgumentException("unsupported language: $tag")
        val ink = Ink.builder()
        var t = System.currentTimeMillis()
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        strokes.forEach { s -> s.forEach { (x, y) -> minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, -y); maxY = maxOf(maxY, -y) } }
        strokes.forEach { s ->
            if (s.isEmpty()) return@forEach
            val sb = Ink.Stroke.builder()
            // synthetic timestamps keep the stroke order and a plausible writing speed
            s.forEach { (x, y) -> sb.addPoint(Ink.Point.create(x - minX, -y - minY, t)); t += 10 }
            ink.addStroke(sb.build())
            t += 150
        }
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
        try {
            val ctx = RecognitionContext.builder().setPreContext("")
                .setWritingArea(WritingArea((maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f))).build()
            val result = recognizer.recognize(ink.build(), ctx).await()
            return result.candidates.mapNotNull { it.text?.takeIf { s -> s.isNotBlank() } }
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
