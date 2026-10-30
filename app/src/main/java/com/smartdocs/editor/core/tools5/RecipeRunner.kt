package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import kotlinx.coroutines.flow.first
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Engine
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.edit5.BatesSpec
import com.smartdocs.editor.core.edit5.DecorSpec
import com.smartdocs.editor.core.edit5.E5Profiles
import com.smartdocs.editor.core.edit5.E5TextStyle
import com.smartdocs.editor.core.edit5.HFSpec
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.edit5.WatermarkOps
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SignedSave
import com.smartdocs.editor.core.redact.RedactApplier
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.smartdocs.editor.core.search.TextSearch
import com.smartdocs.editor.core.security.JsPolicy
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
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

    /** A recipe by id, else by (case-insensitive) name: the target of a "run recipe" step. */
    fun lookup(ctx: Context, key: String): Recipe? = all(ctx).let { l -> l.firstOrNull { it.id == key } ?: l.firstOrNull { it.name.equals(key, true) } }

    fun save(ctx: Context, r: Recipe) {
        val cur = user(ctx)
        val i = cur.indexOfFirst { it.id == r.id }
        // an edited recipe keeps its place in the (user-ordered) list
        val list = if (i >= 0) cur.toMutableList().also { it[i] = r.copy(builtIn = false) } else cur + r.copy(builtIn = false)
        p(ctx).edit().putString("list", RecipeJson.listToJson(list)).apply()
    }

    /** Move a user recipe up / down in the list. */
    fun move(ctx: Context, id: String, delta: Int) { p(ctx).edit().putString("list", RecipeJson.listToJson(RecipeOps.move(user(ctx), id, delta))).apply() }

    /** Recipe the "SmartDocs PDF" printer applies to every printed file (null = none). */
    fun printerRecipe(ctx: Context): Recipe? = p(ctx).getString("printer", null)?.let { find(ctx, it) }
    fun setPrinterRecipe(ctx: Context, id: String?) { p(ctx).edit().putString("printer", id).apply() }

    fun delete(ctx: Context, id: String) { p(ctx).edit().putString("list", RecipeJson.listToJson(user(ctx).filter { it.id != id })).apply() }
}

/** A step decided the file is left out of the batch (a preflight "skip"); the log marks it skipped, not failed. */
class StepSkip(message: String) : RuntimeException(message)

/** One line of the per-file batch log. */
data class LogEntry(val input: String, val ok: Boolean, val output: String?, val message: String, val millis: Long, val skipped: Boolean = false)

/**
 * Runs a recipe on one file: (converted to PDF first when needed) → each enabled step → output named by the pattern.
 * Every step writes a new temp file, so the input is never changed; a signed input keeps its signature valid only
 * when the steps are metadata-only (SET_INFO, JavaScript that changes document info), which are appended as an
 * incremental update ([SignedSave]). The log says when a signed input's copy lost its signature.
 */
class RecipeRunner(private val ctx: Context, private val tempDir: File) {

    private var engine: OcrEngine? = null
    fun close() { engine?.close(); engine = null }

    /** Next Bates number of each Bates step (by its index in the expanded step list), carried across the batch. */
    private val batesNext = HashMap<Int, Long>()
    /** Notes of the file being processed (shown in its log line). */
    private val notes = ArrayList<String>()
    /** Name of the input being processed ({file} tokens of header/footer and watermark). */
    private var inputName = ""
    /** Whether the last step saved incrementally (signature kept). */
    private var lastIncremental = false

    private fun tmp(tag: String, ext: String = "pdf") = File(tempDir, "t5r_${tag}_${System.nanoTime()}.$ext")
    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    private fun profile(name: String, kinds: List<String>): String? =
        kinds.firstNotNullOfOrNull { k -> E5Profiles.list(ctx, k).firstOrNull { it.name.equals(name, true) }?.json }

    /**
     * Load [src], run [block], save into [out]. With [metaOnly] a signed file is appended to incrementally (its
     * signatures stay valid); otherwise it is a full save into the new file.
     */
    private suspend fun edit(src: File, out: File, password: String?, metaOnly: Boolean, block: (PDDocument) -> Unit) = withContext(Dispatchers.IO) {
        if (metaOnly && SignedSave.isSigned(src, password)) {
            PdfOps.loadRaw(src, password).use { d ->
                block(d)
                d.documentInformation.cosObject.isNeedToBeUpdated = true
                d.documentCatalog.cosObject.isNeedToBeUpdated = true
                SignedSave.saveIncremental(d, emptyList(), out)
            }
            lastIncremental = true
        } else PdfOps.load(src, password).use { d -> block(d); d.save(out) }
    }

    /** Apply one step to [src] → new temp file (or [src] itself when the step changed nothing). [index]: place in the run. */
    suspend fun step(s: Step, src: File, password: String?, index: Int = 0): File {
        lastIncremental = false
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
                val prof = s.p("profile").trim()
                if (prof.isNotEmpty()) {
                    // a named watermark / background profile saved in the Watermark or Background tool
                    val spec = profile(prof, listOf("wm", "bg", "sec"))?.let { DecorSpec.fromJson(it) } ?: error(str(R.string.nbt_err_profile, prof))
                    WatermarkOps.apply(ctx, src, out, password, spec, replace = true, fileName = inputName)
                } else {
                    val text = s.p("text").ifBlank { return src }
                    PdfOps.watermark(ctx, src, text, out, fontSize = s.float("size", 48f), opacity = s.float("opacity", 0.3f).coerceIn(0.05f, 1f), password = password)
                }
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
            StepType.SET_INFO -> {
                // Nitro "Create/Edit metadata": blank fields keep their value; Base URL and custom keys as well
                val fields = mapOf("Title" to s.p("title"), "Author" to s.p("author"), "Subject" to s.p("subject"), "Keywords" to s.p("keywords"))
                    .filterValues { it.isNotBlank() } + RecipeOps.parsePairs(s.p("custom"))
                val base = s.p("baseUrl").trim()
                if (fields.isEmpty() && base.isEmpty()) return src
                edit(src, out, password, metaOnly = true) { d ->
                    val info = d.documentInformation
                    fields.forEach { (k, v) -> info.setCustomMetadataValue(k, v.ifEmpty { null }) }
                    d.documentInformation = info
                    if (base.isNotEmpty()) {
                        val uri = (d.documentCatalog.cosObject.getDictionaryObject(COSName.URI) as? COSDictionary) ?: COSDictionary().also { d.documentCatalog.cosObject.setItem(COSName.URI, it) }
                        uri.setItem(COSName.getPDFName("Base"), COSString(base))
                    }
                }
            }
            StepType.FLATTEN -> if (s.bool("forms")) PdfOps.flattenForms(src, out, password) else PdfOps.flattenAnnotations(src, out, password)
            StepType.AUTO_TAG -> withContext(Dispatchers.IO) {
                PdfOps.load(src, password).use { d ->
                    AccessFix.autoTag(d, s.p("lang").ifBlank { "ar" })
                    if (d.documentInformation.title.isNullOrBlank()) AccessFix.setTitle(d, AccessFix.suggestTitle(d, File(inputName).nameWithoutExtension.ifBlank { src.nameWithoutExtension }))
                    else AccessFix.setTitle(d, d.documentInformation.title)
                    AccessFix.tabOrder(d)
                    if (s.bool("pdfua")) {
                        // PDF/UA-1 identification + "display the title" (the checker's remaining failures are in the log)
                        val old = runCatching { d.documentCatalog.metadata?.toByteArray()?.toString(Charsets.UTF_8) }.getOrNull()
                        val md = PDMetadata(d)
                        md.importXMPMetadata(BatchSteps.withUaPart(old, d.documentInformation.title).toByteArray(Charsets.UTF_8))
                        d.documentCatalog.metadata = md
                        val vp = d.documentCatalog.viewerPreferences ?: PDViewerPreferences(COSDictionary())
                        vp.setDisplayDocTitle(true)
                        d.documentCatalog.viewerPreferences = vp
                    }
                    d.save(out)
                }
            }
            StepType.ALT_TEXT -> {
                // FINAL_CHECK_2 #21: figures without alt text get one (AI suggestion / fixed text) or become decorative
                val mode = s.p("mode").trim().lowercase()
                val fallback = s.p("fallback").trim()
                val app = ctx.applicationContext as SmartDocsApp
                val settings = app.settings.settings.first()
                val arabic = com.smartdocs.editor.core.data.AppLang.isArabic(settings)
                val useAi = mode == "ai" && AltTextAi.available(settings)
                val changed = withContext(Dispatchers.IO) {
                    PdfOps.load(src, password).use { d ->
                        val missing = AccessInspect.figures(d).filter { it.alt.isNullOrBlank() }
                        if (missing.isEmpty()) return@use false
                        val texts = HashMap<Int, String>()
                        fun pageText(p: Int): String = texts.getOrPut(p) {
                            if (p < 0) "" else runCatching { com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = p + 1; endPage = p + 1 }.getText(d) }.getOrDefault("")
                        }
                        for (fig in missing) {
                            if (mode == "decorative") { AccessFix.markDecorative(fig); continue }
                            var alt = if (mode == "text") fallback else ""
                            if (alt.isBlank() && useAi) alt = runCatching { AltTextAi.suggest(ctx, settings, null, pageText(fig.page), arabic) }.getOrDefault("")
                            if (alt.isBlank()) alt = fallback
                            if (alt.isBlank()) {
                                // no AI and no text: a context caption from the page's first line, so the figure is not left silent
                                val line = pageText(fig.page).lineSequence().map { it.trim() }.firstOrNull { it.length > 3 }.orEmpty().take(100)
                                alt = if (line.isBlank()) (if (arabic) "شكل" else "Figure") else (if (arabic) "شكل: $line" else "Figure: $line")
                            }
                            AccessFix.setAlt(fig, alt.take(250))
                        }
                        d.save(out)
                        true
                    }
                }
                if (!changed) return src
            }
            StepType.PROTECT -> {
                val pw = s.p("password").ifBlank { return src }
                PdfOps.protect(src, out, pw, pw, currentPassword = password)
            }
            StepType.JAVASCRIPT -> {
                if (!JsPolicy.enabled) error(str(R.string.nbt_err_js_off))
                val code = s.p("code").ifBlank { return src }
                val data = withContext(Dispatchers.IO) {
                    PdfOps.load(src, password).use { d ->
                        val info = d.documentInformation.let { i -> i.metadataKeys.associateWith { k -> i.getCustomMetadataValue(k).orEmpty() } }
                        val fields = d.documentCatalog.getAcroForm(null)?.fieldTree?.filterIsInstance<PDTerminalField>()?.filter { it !is PDSignatureField }
                            ?.associate { it.fullyQualifiedName to (runCatching { it.valueAsString }.getOrNull() ?: "") }.orEmpty()
                        JsBatch.DocData(inputName, d.numberOfPages, info, fields)
                    }
                }
                val r = JsBatch.run(code, data)
                r.log.take(20).forEach { notes += it }
                val info = JsBatch.changed(data.info, r.info)
                val fields = JsBatch.changed(data.fields, r.fields)
                if (info.isEmpty() && fields.isEmpty()) return src
                edit(src, out, password, metaOnly = fields.isEmpty()) { d ->
                    val i = d.documentInformation
                    info.forEach { (k, v) -> i.setCustomMetadataValue(k, v.ifEmpty { null }) }
                    d.documentInformation = i
                    val form = d.documentCatalog.getAcroForm(null)
                    fields.forEach { (k, v) -> runCatching { form?.getField(k)?.setValue(v) } }
                }
            }
            StepType.DELETE_COMMENTS -> {
                val n = Sanitizer.cleanFile(src, out, password, setOf(SanitizeItem.COMMENTS))[SanitizeItem.COMMENTS] ?: 0
                if (n == 0) { out.delete(); return src }
            }
            StepType.HEADER_FOOTER -> {
                val prof = s.p("profile").trim()
                val spec = if (prof.isNotEmpty()) profile(prof, listOf("hf"))?.let { HFSpec.fromJson(it) } ?: error(str(R.string.nbt_err_profile, prof))
                else {
                    val header = s.p("header"); val footer = s.p("footer")
                    if (header.isBlank() && footer.isBlank()) return src
                    HFSpec(topCenter = header, bottomCenter = footer, style = E5TextStyle(size = s.float("size", 10f).coerceIn(4f, 72f)))
                }
                HeaderFooterOps.apply(ctx, src, out, password, spec, replace = true, fileName = inputName)
            }
            StepType.BATES -> {
                val spec = BatesSpec(prefix = s.p("prefix"), suffix = s.p("suffix"), digits = s.int("digits", 6).coerceIn(1, 15),
                    start = batesNext[index] ?: s.p("start").trim().toLongOrNull() ?: 1L)
                val slot = HeaderFooterOps.Slot.entries.firstOrNull { it.name.equals(s.p("position").trim(), true) } ?: HeaderFooterOps.Slot.BOTTOM_RIGHT
                val res = HeaderFooterOps.bates(ctx, listOf(src), { _, _, _ -> out }, spec, HeaderFooterOps.BatesLayout(slot = slot), passwords = { password })
                // every page is stamped (default layout range), so the next file continues after this one's pages
                batesNext[index] = spec.start + PdfOps.pageCount(src, password)
                res.firstOrNull()?.let { (_, first, last) -> if (first.isNotEmpty()) notes += str(R.string.nbt_note_bates, first, last) }
            }
            StepType.REDACT -> {
                val req = BatchSteps.redactRequest(s)
                if (req.isEmpty) return src
                val session = PdfSession.open(ctx, src, password)
                val found = try { TextSearch.search(session, req) } finally { session.closeAsync() }
                val marks = found.filter { it.hit.rects.isNotEmpty() }
                    .mapIndexed { k, h -> RedactMark("nb$k", h.hit.page, h.hit.rects.map { RectF(it) }, label = h.hit.snippet.trim()) }
                if (marks.isEmpty()) {
                    notes += str(R.string.nbt_note_redact_none)
                    if (!s.bool("sanitize")) return src
                    Sanitizer.cleanFile(src, out, password, SanitizeItem.DEFAULTS)
                } else {
                    RedactApplier.apply(ctx, src, out, password, marks, RedactApplier.Options(sanitize = s.bool("sanitize")))
                    notes += str(R.string.nbt_note_redacted, marks.size)
                }
            }
            StepType.SANITIZE -> Sanitizer.cleanFile(src, out, password, BatchSteps.sanitizeItems(s.p("items")))
            StepType.ACCESS_CHECK -> {
                val attach = s.bool("report")
                val wrote = withContext(Dispatchers.IO) {
                    PdfOps.load(src, password).use { d ->
                        val results = AccessRules.evaluate(AccessInspect.facts(d, PdfInspect.inspect(d)))
                        val c = AccessRules.counts(results)
                        notes += str(R.string.nbt_note_access, c[AccStatus.PASS] ?: 0, c[AccStatus.FAIL] ?: 0, c[AccStatus.MANUAL] ?: 0)
                        // FINAL_CHECK_2 Low: the report travels with the file (an HTML attachment), as the checker's "attach report"
                        if (!attach) return@use false
                        val arabic = com.smartdocs.editor.core.data.AppLang.isArabic((ctx.applicationContext as SmartDocsApp).settings.settings.first())
                        val html = AccessRules.htmlReport(inputName, results, arabic, str(R.string.t5_acc_report_title), { it }, { it.name }, { it.name })
                        Embed.addFiles(d, listOf(Attachment(File(inputName).nameWithoutExtension + "_accessibility.html", "text/html", html.toByteArray())))
                        d.save(out); true
                    }
                }
                if (!wrote) return src
            }
            StepType.PREFLIGHT -> {
                // check only: the findings decide whether the file goes on, stops with an error, or is skipped
                val profile = PreflightProfile.entries.firstOrNull { it.code == s.p("profile").trim() } ?: PreflightProfile.PDFA_2B
                val report = withContext(Dispatchers.IO) { PdfOps.load(src, password).use { d -> Standards.preflight(d, profile, (0 until d.numberOfPages).toList()) } }
                val errors = report.errors
                val msg = str(R.string.nbt_note_preflight, profile.code.uppercase(), errors, report.findings.count { it.severity == Severity.WARNING })
                when {
                    errors == 0 -> notes += msg
                    s.p("onFail").trim().equals("skip", true) -> throw StepSkip(msg)
                    s.p("onFail").trim().equals("continue", true) -> notes += msg
                    else -> error(msg)
                }
                return src
            }
            StepType.CONVERT_OFFICE -> {
                val conv = BatchSteps.conversionId(s.p("format"))?.let { Cv6Catalog.byId(it) } ?: error(str(R.string.nbt_err_format, s.p("format")))
                val app = ctx.applicationContext as SmartDocsApp
                val res = Cv6Engine(ctx, app.documents).run(conv, listOf(src), com.smartdocs.editor.core.convert6.Cv6Catalog.defaultsFor(conv), password) { _, _, _ -> }   // same defaults as the hub (audit C47)
                val f = res.files.firstOrNull() ?: error(str(R.string.nbt_err_format, s.p("format")))
                val moved = tmp("conv", f.extension.ifBlank { "bin" })
                if (!f.renameTo(moved)) { f.copyTo(moved, overwrite = true); f.delete() }
                res.files.drop(1).forEach { runCatching { it.delete() } }
                out.delete()
                return moved
            }
            // instructions, the print request (handled by the batch screen) and nested recipes (expanded before) do nothing here
            StepType.PRINT, StepType.NOTE, StepType.RUN_RECIPE, StepType.DIVIDER -> return src
        }
        return out
    }

    /**
     * Run [recipe] on [input]. [outDir] receives the result unless the recipe writes next to the input. [index] numbers
     * outputs ({n}); [conflict] overrides the recipe's choice (an "ask" settled before the run); [displayName] is the
     * name of the original file. Returns the log entry; never throws (except cancellation).
     */
    suspend fun run(
        input: File, password: String?, recipe: Recipe?, recipeLabel: String, outDir: File, index: Int, toPdfOnly: Boolean = false,
        conflict: Conflict? = null, displayName: String = input.name,
    ): LogEntry {
        val t0 = System.currentTimeMillis()
        val temps = ArrayList<File>()
        notes.clear()
        inputName = displayName
        return try {
            coroutineContext.ensureActive()
            var cur = input
            var pw = password
            if (ToPdf.kindOf(input.name) != SourceKind.PDF) {
                val t = tmp("topdf"); temps += t
                cur = ToPdf.convert(ctx, input, t); pw = null
            }
            val signed = cur === input && withContext(Dispatchers.IO) { SignedSave.isSigned(input, password) }
            var lostSignature = false
            val steps = if (toPdfOnly || recipe == null) emptyList() else RecipeOps.expand(recipe) { RecipeStore.lookup(ctx, it) }
            for ((i, s) in steps.withIndex()) {
                coroutineContext.ensureActive()
                if (!cur.extension.equals("pdf", true)) { notes += str(R.string.nbt_note_after_convert); break }
                val next = step(s, cur, pw, i)
                if (next !== cur && next.absolutePath != cur.absolutePath) {
                    temps += next; cur = next
                    if (!lastIncremental) lostSignature = true
                    pw = if (s.type == StepType.PROTECT) s.p("password") else if (lastIncremental) pw else null
                }
            }
            if (signed && lostSignature) notes += str(R.string.nbt_note_signed_copy)
            val now = Date()
            val base = OutputNaming.apply(
                recipe?.output?.pattern ?: "{name}", File(displayName).nameWithoutExtension, recipeLabel, index,
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now), SimpleDateFormat("HHmm", Locale.US).format(now),
            )
            val dir = if (recipe?.output?.sameFolder == true && input.parentFile?.canWrite() == true) input.parentFile!! else outDir
            dir.mkdirs()
            val ext = cur.extension.lowercase().ifBlank { "pdf" }
            val policy = conflict ?: recipe?.output?.conflict ?: Conflict.RENAME
            // the input itself is never overwritten
            fun taken(n: String) = File(dir, n).exists()
            var name = RecipeOps.resolveName(base, ext, policy, ::taken)
                ?: return LogEntry(displayName, false, null, str(R.string.nbt_log_exists), System.currentTimeMillis() - t0, skipped = true)
            if (File(dir, name).absolutePath == input.absolutePath) name = RecipeOps.resolveName(base, ext, Conflict.RENAME, ::taken)!!
            val target = File(dir, name)
            if (cur.absolutePath == input.absolutePath) input.copyTo(target, overwrite = true)
            else { target.delete(); if (!cur.renameTo(target)) cur.copyTo(target, overwrite = true); temps.remove(cur) }
            LogEntry(displayName, true, target.absolutePath, notes.joinToString(" · "), System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StepSkip) {
            LogEntry(displayName, false, null, e.message ?: "", System.currentTimeMillis() - t0, skipped = true)
        } catch (e: Throwable) {
            LogEntry(displayName, false, null, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
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
