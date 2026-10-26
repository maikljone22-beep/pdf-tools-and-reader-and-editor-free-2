package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Saved recipes: SharedPreferences "t5_recipes" (JSON list); built-ins are always offered first. */
object RecipeStore {
    private const val PREFS = "t5_recipes"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun user(ctx: Context): List<Recipe> = RecipeJson.listFromJson(p(ctx).getString("list", "[]") ?: "[]")
    fun all(ctx: Context): List<Recipe> = BuiltInRecipes.all + user(ctx)
    fun find(ctx: Context, id: String): Recipe? = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, r: Recipe) {
        val list = user(ctx).filter { it.id != r.id } + r.copy(builtIn = false)
        p(ctx).edit().putString("list", RecipeJson.listToJson(list)).apply()
    }

    fun delete(ctx: Context, id: String) { p(ctx).edit().putString("list", RecipeJson.listToJson(user(ctx).filter { it.id != id })).apply() }
}

/** One line of the per-file batch log. */
data class LogEntry(val input: String, val ok: Boolean, val output: String?, val message: String, val millis: Long, val skipped: Boolean = false)

/** Runs a recipe on one file: (converted to PDF first when needed) → each enabled step → output named by the pattern. */
class RecipeRunner(private val ctx: Context, private val tempDir: File) {

    private var engine: OcrEngine? = null
    fun close() { engine?.close(); engine = null }

    private fun tmp(tag: String) = File(tempDir, "t5r_${tag}_${System.nanoTime()}.pdf")

    /** Apply one step to [src] → new temp file (or [src] itself when the step changed nothing). */
    suspend fun step(s: Step, src: File, password: String?): File {
        val out = tmp(s.type.name.lowercase())
        when (s.type) {
            StepType.OCR -> {
                val e = engine ?: OcrEngine(ctx.applicationContext).also { engine = it }
                val langs = s.p("langs").trim().takeIf { it.isNotEmpty() && it != "auto" }
                return OcrRunner.ocrFile(ctx, e, src, password, OcrPlan(langs = langs, preClean = s.bool("clean")), out) ?: src
            }
            StepType.OPTIMIZE -> Optimizer.optimize(src, password, out, OptimizeOptions(imageDpi = s.int("dpi", 150), jpegQuality = s.int("quality", 70), stripMetadata = s.bool("strip"), stripThumbnails = true))
            StepType.PDFA -> PdfA.convert(ctx, src, password, out, PdfAOptions(rasterizeProblemPages = s.bool("rasterizeMissingFonts")))
            StepType.WATERMARK -> {
                val text = s.p("text").ifBlank { return src }
                PdfOps.watermark(ctx, src, text, out, fontSize = s.float("size", 48f), opacity = s.float("opacity", 0.3f).coerceIn(0.05f, 1f), password = password)
            }
            StepType.PAGE_NUMBERS -> PdfOps.addPageNumbers(ctx, src, out, start = s.int("start", 1), arabicDigits = s.bool("arabicDigits"), password = password)
            StepType.ROTATE -> {
                val n = PdfOps.pageCount(src, password)
                val pages = s.p("pages").trim().let { if (it.isEmpty()) (0 until n).toList() else PdfOps.parseRange(it, n) }
                PdfOps.rotatePages(src, pages, s.int("degrees", 90), out, password)
            }
            StepType.REMOVE_METADATA -> withContext(Dispatchers.IO) {
                PdfOps.load(src, password).use { d ->
                    d.documentCatalog.cosObject.removeItem(N.Metadata)
                    d.documentInformation = PDDocumentInformation()
                    d.save(out)
                }
            }
            StepType.SET_INFO -> PdfOps.setInfo(src, PdfOps.Info(title = s.p("title"), author = s.p("author"), subject = s.p("subject"), keywords = s.p("keywords")), out, password)
            StepType.FLATTEN -> if (s.bool("forms")) PdfOps.flattenForms(src, out, password) else PdfOps.flattenAnnotations(src, out, password)
            StepType.AUTO_TAG -> withContext(Dispatchers.IO) {
                PdfOps.load(src, password).use { d ->
                    AccessFix.autoTag(d, s.p("lang").ifBlank { "ar" })
                    if (d.documentInformation.title.isNullOrBlank()) AccessFix.setTitle(d, AccessFix.suggestTitle(d, src.nameWithoutExtension))
                    else AccessFix.setTitle(d, d.documentInformation.title)
                    AccessFix.tabOrder(d)
                    d.save(out)
                }
            }
            StepType.PROTECT -> {
                val pw = s.p("password").ifBlank { return src }
                PdfOps.protect(src, out, pw, pw, currentPassword = password)
            }
        }
        return out
    }

    /**
     * Run [recipe] on [input]. [outDir] receives the result unless the recipe writes next to the input.
     * [index] numbers outputs ({n}). Returns the log entry; never throws (except cancellation).
     */
    suspend fun run(input: File, password: String?, recipe: Recipe?, recipeLabel: String, outDir: File, index: Int, toPdfOnly: Boolean = false): LogEntry {
        val t0 = System.currentTimeMillis()
        val temps = ArrayList<File>()
        return try {
            coroutineContext.ensureActive()
            var cur = input
            var pw = password
            if (ToPdf.kindOf(input.name) != SourceKind.PDF) {
                val t = tmp("topdf"); temps += t
                cur = ToPdf.convert(ctx, input, t); pw = null
            }
            if (!toPdfOnly && recipe != null) for (s in recipe.steps) {
                if (!s.enabled) continue
                coroutineContext.ensureActive()
                val next = step(s, cur, pw)
                if (next !== cur && next.absolutePath != cur.absolutePath) { temps += next; cur = next; if (s.type != StepType.PROTECT) pw = null else pw = s.p("password") }
            }
            val now = Date()
            val base = OutputNaming.apply(
                recipe?.output?.pattern ?: "{name}", input.nameWithoutExtension, recipeLabel, index,
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now), SimpleDateFormat("HHmm", Locale.US).format(now),
            )
            val dir = if (recipe?.output?.sameFolder == true && input.parentFile?.canWrite() == true) input.parentFile!! else outDir
            dir.mkdirs()
            var target = File(dir, "$base.pdf")
            if (recipe?.output?.overwrite != true || target.absolutePath == input.absolutePath) {
                var n = 2
                while (target.exists()) target = File(dir, "${base}_${n++}.pdf")
            }
            if (cur.absolutePath == input.absolutePath) input.copyTo(target, overwrite = true)
            else { if (!cur.renameTo(target)) cur.copyTo(target, overwrite = true); temps.remove(cur) }
            LogEntry(input.name, true, target.absolutePath, "", System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogEntry(input.name, false, null, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
        } finally {
            temps.forEach { runCatching { if (it.absolutePath != input.absolutePath) it.delete() } }
        }
    }

    /** Smallest reasonable proof that a document opens (used by the batch UI to pre-check passwords). */
    suspend fun opens(f: File, password: String?): Boolean = withContext(Dispatchers.IO) {
        if (ToPdf.kindOf(f.name) != SourceKind.PDF) return@withContext true
        runCatching { (if (password.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, password)).close(); true }.getOrDefault(false)
    }
}
