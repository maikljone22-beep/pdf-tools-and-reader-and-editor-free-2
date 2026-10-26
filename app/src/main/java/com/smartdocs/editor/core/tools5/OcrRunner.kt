package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrPage
import com.smartdocs.editor.core.ocr.SearchablePdfWriter
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** OCR options of the batch screen. [langs] null = detect automatically from a quick first pass. */
data class OcrPlan(
    val langs: String? = null,
    val preClean: Boolean = true,
    val dpi: Int = 300,
    /** Words below this Tesseract confidence (0..100) are offered for review. */
    val minConfidence: Float = 65f,
)

/** A low-confidence word to review; [crop] is a small picture of the word (may be null). */
class Suspect(val page: Int, val index: Int, val text: String, val confidence: Float, val crop: Bitmap?)

/** Recognition of one input, kept in memory until the (reviewed) text layer is written. */
class OcrDoc(val src: File, val isPdf: Boolean, val password: String?, val langs: String, val pages: MutableMap<Int, OcrPage>, val suspects: List<Suspect>)

object OcrRunner {

    fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    /** Grey + background normalisation + level stretch: helps Tesseract on photos with shadows. Returns a new bitmap. */
    fun preClean(src: Bitmap): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        PixelOps.toGray(px); PixelOps.normalizeBackground(px, w, h); PixelOps.autoLevels(px, 0.005f, 0.995f)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** Quick pass with Arabic + English on a downscaled copy → detected Tesseract languages ("ara+eng"). */
    suspend fun detectLanguages(engine: OcrEngine, bmp: Bitmap): String {
        engine.ensureLanguages("ara+eng")
        if (!engine.init("ara+eng")) return "ara+eng"
        val k = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val text = runCatching { engine.recognize(small, wantWords = false).text }.getOrDefault("")
        if (small !== bmp) small.recycle()
        return LangDetect.detect(text).joinToString("+")
    }

    private fun crop(bmp: Bitmap, r: Rect): Bitmap? = runCatching {
        val pad = (r.height() / 3).coerceAtLeast(2)
        val l = (r.left - pad).coerceIn(0, bmp.width - 1); val t = (r.top - pad).coerceIn(0, bmp.height - 1)
        val rr = (r.right + pad).coerceIn(l + 1, bmp.width); val bb = (r.bottom + pad).coerceIn(t + 1, bmp.height)
        val c = Bitmap.createBitmap(bmp, l, t, rr - l, bb - t)
        val k = minOf(1f, 360f / c.width)
        if (k < 1f) Bitmap.createScaledBitmap(c, (c.width * k).toInt().coerceAtLeast(1), (c.height * k).toInt().coerceAtLeast(1), true).also { if (it !== c) c.recycle() } else c
    }.getOrNull()

    /** Page pictures of an input: PDF pages without text (rendered at the plan dpi) or the image itself. */
    private suspend fun forEachPage(ctx: Context, src: File, password: String?, plan: OcrPlan,
                                    block: suspend (Int, Int, Bitmap, suspend (Int, OcrPage) -> OcrPage) -> Unit) {
        if (isImage(src)) {
            val b = ScanImage.load(src, 3500) ?: throw IllegalArgumentException("image")
            block(0, 1, b) { _, p -> p }; b.recycle(); return
        }
        val s = PdfSession.open(ctx, src, password)
        try {
            // pages without text + pages mixing text with scanned pictures; on mixed pages only new words are kept
            val mixed = com.smartdocs.editor.core.ocr.MixedPageOcr.plan(s, src, password, (0 until s.pageCount).toList())
            val todo = mixed.pages
            val dedupe: suspend (Int, OcrPage) -> OcrPage = { pi, p -> com.smartdocs.editor.core.ocr.MixedPageOcr.dropExisting(s, pi, p, mixed) }
            todo.forEachIndexed { n, pi ->
                coroutineContext.ensureActive()
                val (w, h) = s.pageSize(pi)
                var scale = plan.dpi / 72f
                if (maxOf(w, h) * scale > 3500f) scale = 3500f / maxOf(w, h)
                val bmp = s.render(pi, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), annotations = false)
                s.invalidateCache()
                block(pi, todo.size, bmp, dedupe)
                bmp.recycle()
            }
        } finally { s.closeAsync() }
    }

    /** Recognise [src] (PDF or image). [onProgress] gets (done pages, total). Pure digital pages are skipped; mixed pages keep only new words. */
    suspend fun recognize(ctx: Context, engine: OcrEngine, src: File, password: String?, plan: OcrPlan, onProgress: (Int, Int) -> Unit = { _, _ -> }): OcrDoc {
        var langs = plan.langs
        val pages = LinkedHashMap<Int, OcrPage>()
        val suspects = ArrayList<Suspect>()
        var done = 0
        forEachPage(ctx, src, password, plan) { pi, total, raw, dedupe ->
            val bmp = if (plan.preClean) preClean(raw) else raw
            if (langs == null) langs = detectLanguages(engine, bmp)
            val l = langs!!
            engine.ensureLanguages(l)
            if (!engine.init(l)) throw IllegalStateException("ocr init")
            val page = dedupe(pi, engine.recognize(bmp, wantWords = true))
            pages[pi] = page
            page.words.forEachIndexed { wi, w ->
                if (w.confidence < plan.minConfidence && w.text.any { it.isLetterOrDigit() } && suspects.size < 300)
                    suspects += Suspect(pi, wi, w.text, w.confidence, crop(bmp, w.box))
            }
            if (bmp !== raw) bmp.recycle()
            onProgress(++done, total)
        }
        return OcrDoc(src, !isImage(src), password, langs ?: "ara+eng", pages, suspects)
    }

    /** Review decisions: (page, word index) → new text, or null to drop the word ("not text"). */
    fun applyReview(doc: OcrDoc, decisions: Map<Pair<Int, Int>, String?>) {
        if (decisions.isEmpty()) return
        val byPage = decisions.entries.groupBy { it.key.first }
        byPage.forEach { (pi, list) ->
            val p = doc.pages[pi] ?: return@forEach
            val words = p.words.toMutableList()
            val map = list.associate { it.key.second to it.value }
            val out = words.mapIndexedNotNull { i, w -> if (i in map) map[i]?.takeIf { it.isNotBlank() }?.let { w.copy(text = it.trim()) } else w }
            doc.pages[pi] = p.copy(text = out.joinToString(" ") { it.text }, words = out)
        }
    }

    /** Write the searchable result: PDFs get an invisible layer, an image becomes a one-page PDF with its layer. */
    suspend fun write(ctx: Context, doc: OcrDoc, out: File) = withContext(Dispatchers.IO) {
        if (doc.isPdf) {
            SearchablePdfWriter(ctx, doc.src, doc.password).use { w ->
                doc.pages.forEach { (pi, page) -> w.addLayer(pi, page) }
                w.save(out)
            }
        } else {
            val bmp = ScanImage.load(doc.src, 3500) ?: throw IllegalArgumentException("image")
            val page = doc.pages[0]
            PDDocument().use { pd ->
                val rect = PDRectangle(bmp.width * 72f / 200f, bmp.height * 72f / 200f)
                val p = PDPage(rect); pd.addPage(p)
                val img = JPEGFactory.createFromImage(pd, bmp, 0.85f)
                val arabic = runCatching { PdfOps.arabicFont(ctx, pd) }.getOrNull()?.takeIf { it !is PDType1Font }
                PDPageContentStream(pd, p).use { cs ->
                    cs.drawImage(img, 0f, 0f, rect.width, rect.height)
                    // the OCR ran on the same decode (3500 px), so word boxes map 1:1
                    if (page != null) TextLayer.write(cs, page.words, page.imageWidth, page.imageHeight, 0f, 0f, rect.width, rect.height, arabic)
                }
                pd.save(out)
            }
            bmp.recycle()
        }
        out
    }

    /** Recognise + write without review (batch). Returns null when nothing needed OCR. */
    suspend fun ocrFile(ctx: Context, engine: OcrEngine, src: File, password: String?, plan: OcrPlan, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): File? {
        val d = recognize(ctx, engine, src, password, plan, onProgress)
        if (d.pages.isEmpty()) return null
        return write(ctx, d, out)
    }

    /** Pages without a text layer / all pages. */
    suspend fun scannedShare(ctx: Context, src: File, password: String?): Pair<Int, Int> {
        val s = PdfSession.open(ctx, src, password)
        try {
            val n = s.pageCount
            val empty = (0 until n).count { runCatching { s.text(it) }.getOrDefault("").isBlank() }
            return empty to n
        } finally { s.closeAsync() }
    }
}

/**
 * Auto-OCR before converting a scanned PDF (PDF → Word/Excel/…): when at least half of the pages have no text,
 * a searchable copy is made in the temp folder and returned; otherwise null (convert the original).
 */
object AutoOcr {
    suspend fun ocrIfScanned(ctx: Context, src: File, password: String?, langs: String?, tempDir: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): File? {
        val (empty, n) = OcrRunner.scannedShare(ctx, src, password)
        if (n == 0 || empty * 2 < n) return null
        val out = File(tempDir, src.nameWithoutExtension + "_ocr_" + System.currentTimeMillis() + ".pdf")
        val engine = OcrEngine(ctx.applicationContext)
        return try { OcrRunner.ocrFile(ctx, engine, src, password, OcrPlan(langs = langs), out, onProgress) } finally { engine.close() }
    }
}
