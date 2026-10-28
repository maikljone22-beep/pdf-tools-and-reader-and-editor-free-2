package com.smartdocs.editor.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * "Pro offline" OCR engine (PRO_OCR_r5): PaddleOCR PP-OCRv5 mobile text detection + the PP-OCRv5 Arabic mobile
 * recogniser (Arabic, Arabic-Indic and Persian digits, Latin, math signs), run with ONNX Runtime on the CPU. The two
 * models (Apache-2.0, 12.8 MB together) are an optional pinned download ([Models]); without them the app uses
 * Tesseract alone. Post-processing is [ProOcrCore] (unit-tested). Output words are in bitmap pixels, logical order.
 */
class ProOcr(private val context: Context) : AutoCloseable {

    /** A pinned model file: HTTPS URL at a fixed Hugging Face commit, exact size and SHA-256. */
    data class Model(val name: String, val url: String, val size: Long, val sha256: String)

    companion object {
        /** PaddlePaddle/PP-OCRv5_mobile_det_onnx at commit e6f4fa85 (Apache-2.0). */
        val DET = Model("det.onnx", "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/e6f4fa85f00e168c862bc462aebca69eef9b3d3d/inference.onnx",
            4_826_518, "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d")
        /** PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx at commit 14aaedcd (Apache-2.0); its characters are assets/ocr/ppocrv5_arabic_dict.txt. */
        val REC = Model("rec_arabic.onnx", "https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx/resolve/14aaedcd75825982689ecf5cd64ab33ee083215a/inference.onnx",
            7_998_947, "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110")
        val MODELS = listOf(DET, REC)
        const val DICT_ASSET = "ocr/ppocrv5_arabic_dict.txt"

        fun dir(ctx: Context) = File(ctx.filesDir, "ocr_pro").apply { mkdirs() }
        fun file(ctx: Context, m: Model) = File(dir(ctx), m.name)

        /** True when both models are downloaded with their exact size. */
        fun isInstalled(ctx: Context): Boolean = MODELS.all { file(ctx, it).let { f -> f.exists() && f.length() == it.size } }

        /** Download size still missing, in bytes. */
        fun missingBytes(ctx: Context): Long = MODELS.filter { file(ctx, it).length() != it.size }.sumOf { it.size }

        /** Downloads the missing models; every file must match its pinned SHA-256 or it is deleted. [onProgress] 0..1. */
        suspend fun ensureModels(ctx: Context, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
            val todo = MODELS.filter { file(ctx, it).length() != it.size }
            val total = todo.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()
            val cc = coroutineContext
            for (m in todo) {
                val target = file(ctx, m); val tmp = File(target.parentFile, target.name + ".part")
                client.newCall(Request.Builder().url(m.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for ${m.name}")
                    val body = resp.body ?: throw IllegalStateException("empty body")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                cc.ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (tmp.length() != m.size || !OcrEngine.sha256(tmp).equals(m.sha256, ignoreCase = true)) { tmp.delete(); throw IllegalStateException("${m.name} failed verification") }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
            onProgress(1f)
        }

        /** Longer side of the detection input: 2400 px (measured), 1600 on low-memory devices. */
        fun detLimit(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return if (am == null || am.isLowRamDevice || am.memoryClass < 256) 1600 else 2400
        }
    }

    // created in open(): a device without the native library (x86 builds exclude it) just has no AI engine
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()
    @Volatile private var cancelled = false

    /** Opens both sessions; false when the models are missing or cannot be loaded. */
    fun open(): Boolean = synchronized(this) {
        if (det != null && rec != null) return true
        if (!isInstalled(context)) return false
        runCatching {
            val env = OrtEnvironment.getEnvironment().also { this.env = it }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            dict = ProOcrCore.parseDict(context.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
            det = env.createSession(file(context, DET).absolutePath, opts)
            rec = env.createSession(file(context, REC).absolutePath, opts)
        }.onFailure { closeLocked() }.isSuccess
    }

    fun cancel() { cancelled = true }

    /** Words of [bitmap] (reading order, logical text, confidence 0..100, line ids). Empty when not [open]. */
    fun recognize(bitmap: Bitmap): List<OcrRefine.Tok> = synchronized(this) {
        cancelled = false
        val d = det ?: return emptyList(); val r = rec ?: return emptyList(); val env = env ?: return emptyList()
        val w = bitmap.width; val h = bitmap.height
        val (nw, nh) = ProOcrCore.detSize(w, h, detLimit(context))
        val prob = runDet(env, d, bitmap, nw, nh)
        val boxes = ProOcrCore.dbBoxes(prob, nw, nh, w, h)
        val segs = ArrayList<ProOcrCore.Seg>()
        for (b in boxes) {
            if (cancelled) break
            val bw = b.x1 - b.x0; val bh = b.y1 - b.y0
            if (bw < 4 || bh < 4) continue
            val chars = runRec(env, r, bitmap, b)
            if (chars.isNotEmpty()) segs += ProOcrCore.Seg(b.x0, b.y0, b.x1, b.y1, chars)
        }
        ProOcrCore.words(segs)
    }

    /** DB probability map ([nw]×[nh]) of the page scaled to the detection size; BGR channels, ImageNet mean/std. */
    private fun runDet(env: OrtEnvironment, s: OrtSession, src: Bitmap, nw: Int, nh: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val px = IntArray(nw * nh); scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== src) scaled.recycle()
        val plane = nw * nh; val data = FloatArray(3 * plane)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until plane) {
            val p = px[i]
            val b = (p and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val rr = ((p shr 16) and 0xFF) / 255f
            data[i] = (b - mean[0]) / std[0]; data[plane + i] = (g - mean[1]) / std[1]; data[2 * plane + i] = (rr - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val out = (res.get(0) as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }
            }
        }
    }

    /** Characters of one detected box: the crop scaled to height 48, (x/255 - 0.5) / 0.5, BGR, greedy CTC. */
    private fun runRec(env: OrtEnvironment, s: OrtSession, src: Bitmap, b: ProOcrCore.Box): List<ProOcrCore.Ch> {
        val cw = b.x1 - b.x0; val ch = b.y1 - b.y0
        val rw = ProOcrCore.recWidth(cw, ch)
        val crop = Bitmap.createBitmap(src, b.x0, b.y0, cw, ch)
        val scaled = Bitmap.createScaledBitmap(crop, rw, 48, true)
        if (crop !== src && crop !== scaled) crop.recycle()
        val px = IntArray(rw * 48); scaled.getPixels(px, 0, rw, 0, 0, rw, 48)
        if (scaled !== src) scaled.recycle()
        val plane = rw * 48; val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
            data[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            data[2 * plane + i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 48, rw.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val o = res.get(0) as OnnxTensor
                val shape = o.info.shape                       // [1, T, C]
                val fb = o.floatBuffer; val arr = FloatArray(fb.remaining()); fb.get(arr)
                return ProOcrCore.ctcDecode(arr, shape[1].toInt(), shape[2].toInt(), dict, b.x0, cw)
            }
        }
    }

    override fun close() { synchronized(this) { closeLocked() } }

    private fun closeLocked() {
        runCatching { det?.close() }; runCatching { rec?.close() }
        det = null; rec = null
    }
}
