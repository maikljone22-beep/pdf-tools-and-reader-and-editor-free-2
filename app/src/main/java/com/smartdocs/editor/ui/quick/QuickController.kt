package com.smartdocs.editor.ui.quick

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.ocr.OcrOptions
import com.smartdocs.editor.ui.ocr.runOcrToFile
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Every one-step document tool the reader offers. */
enum class QuickAction {
    COMPRESS, OCR, WATERMARK, PAGE_NUMBERS, PROTECT, UNLOCK, FLATTEN, ROTATE_ALL, ADD_BLANK, DELETE_PAGE, EXTRACT, PROPERTIES, PRINT, SHARE,
    FIX_TEXT,
}

/** A message meant for the user (not a failure): shown as is. */
class QuickInfo(message: String) : Exception(message)

/**
 * Result bar after a one-step action. [backup] = previous version kept by the reader's unified history
 * (undo / save as copy go through it; never deleted here);
 * [newFile] = a file the action created (extract, saved copy) to open or share.
 */
class QuickDone(val message: String, val backup: File? = null, val prevPassword: String? = null, val suffix: String = "edited", val newFile: File? = null)

/**
 * Drives the one-step tools of the reader: which sheet / confirmation is shown, the running operation
 * (progress + cancel) and the "done" bar with undo / save as copy. Operations replace the open document in
 * place through [ReaderState.applyInPlace].
 */
class QuickController(val state: ReaderState, private val scope: CoroutineScope, private val ctx: Context) {
    var sheet by mutableStateOf<QuickAction?>(null)
    var confirm by mutableStateOf<QuickAction?>(null)
    /** OCR sheet locked to one page (null = range chooser). */
    var ocrLockedPage by mutableStateOf<Int?>(null)

    var running by mutableStateOf(false); private set
    var progress by mutableStateOf<Float?>(null); private set
    var progressText by mutableStateOf(""); private set
    var cancellable by mutableStateOf(false); private set
    var done by mutableStateOf<QuickDone?>(null); private set
    /** Error / info text for the reader's snackbar. */
    var message by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var engine: OcrEngine? = null
    private val docs get() = state.app.documents

    // ---------------- dispatch ----------------

    /** Entry point of every tool button: one tap, then (at most) a compact sheet or a confirmation. */
    fun open(action: QuickAction, onShare: () -> Unit = {}, onPrint: () -> Unit = {}) {
        if (running || state.session == null) return
        when (action) {
            QuickAction.ROTATE_ALL -> rotateAll()
            QuickAction.ADD_BLANK -> addBlank()
            QuickAction.UNLOCK -> unlock()
            QuickAction.FLATTEN -> confirm = action
            QuickAction.DELETE_PAGE -> if (state.pageCount <= 1) message = ctx.getString(R.string.qk_del_last) else confirm = action
            QuickAction.SHARE -> onShare()
            QuickAction.PRINT -> onPrint()
            QuickAction.OCR -> { ocrLockedPage = null; sheet = action }
            QuickAction.FIX_TEXT -> fixText()
            else -> sheet = action
        }
    }

    fun openOcrForPage(page: Int) { if (!running) { ocrLockedPage = page; sheet = QuickAction.OCR } }

    // ---------------- core ----------------

    /**
     * Run [op] on the open document and replace it in place. [newPassword] maps the current password to the
     * one the result opens with. [after] runs once the document was reloaded (e.g. jump to a page).
     */
    fun apply(
        working: String, doneText: () -> String, suffix: String, cancellable: Boolean = false,
        newPassword: (String?) -> String? = { it }, after: () -> Unit = {},
        op: suspend (src: File, out: File, pw: String?) -> Unit,
    ) {
        if (running) return
        sheet = null; confirm = null
        dismissDone()
        running = true; progress = null; progressText = working; this.cancellable = cancellable
        val prevPw = state.password
        job = scope.launch {
            try {
                val backup = state.applyInPlace(newPassword(prevPw), label = doneText, op = op)
                if (backup != null) { done = QuickDone(doneText(), backup, prevPw, suffix); after() }
            } catch (e: CancellationException) {
                message = ctx.getString(R.string.qk_cancelled)
            } catch (e: QuickInfo) {
                message = e.message
            } catch (e: Throwable) {
                message = ctx.getString(R.string.qk_failed, e.message ?: e.javaClass.simpleName)
            } finally { running = false; progress = null }
        }
    }

    fun setProgress(text: String, value: Float?) { scope.launch { progressText = text; progress = value } }

    fun cancel() { engine?.cancel(); job?.cancel() }

    /** Why "undo through [b]" did nothing: the step is gone, or newer changes would be undone too (audit AN-08). */
    private fun undoRefusal(b: java.io.File): String {
        val n = state.history.newerStepsThan(b)
        if (n == 0 && state.fileUndoBlocked) return ctx.getString(R.string.fx_undo_save_first)
        return if (n > 0) ctx.getString(R.string.fx_undo_newer, n) else ctx.getString(R.string.hi_gone)
    }

    /** Put the previous version back: the unified undo back through this step (the reader shows «تم التراجع»). */
    fun undo() {
        val d = done ?: return
        val b = d.backup ?: return
        done = null
        if (!state.undoThrough(b)) message = undoRefusal(b)
    }

    /** Keep the result as a new file next to the documents and restore the open document to its previous version. */
    fun saveAsCopy() {
        val d = done ?: return
        val b = d.backup ?: return
        val f = state.file ?: return
        done = null
        scope.launch {
            try {
                val copy = docs.uniqueFile(docs.docsDir, f.nameWithoutExtension + "_" + d.suffix)
                withContext(Dispatchers.IO) { f.copyTo(copy, overwrite = true) }
                // the open document goes back through the unified history (the change stays available as redo)
                if (state.fileUndoBlocked || state.history.undoThrough(b) == null) message = undoRefusal(b)
                runCatching { docs.touchRecent(copy) }
                done = QuickDone(ctx.getString(R.string.qk_saved_copy, copy.name), newFile = copy)
            } catch (e: Throwable) {
                message = ctx.getString(R.string.qk_failed, e.message ?: "")
            }
        }
    }

    /** The previous version stays in the unified history (undo from the top bar); only the bar goes. */
    fun dismissDone() {
        done = null
    }

    fun dispose() {
        cancel()
        engine?.let { e -> CoroutineScope(Dispatchers.IO).launch { runCatching { e.close() } } }
    }

    // ---------------- one-tap actions ----------------

    private fun rotateAll() = apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_rot_done) }, "rotated") { src, out, pw ->
        val n = PdfOps.pageCount(src, pw)
        PdfOps.rotatePages(src, (0 until n).toList(), 90, out, pw)
    }

    private fun addBlank() {
        val at = state.currentPage + 1
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_blank_done, at) }, "blank", after = { state.jumpTo(at) }) { src, out, pw ->
            PdfOps.insertBlankPage(src, at, out, password = pw)
        }
    }

    fun deleteCurrent() {
        val p = state.currentPage
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_del_done, p + 1) }, "deleted", after = { state.jumpTo(p) }) { src, out, pw ->
            PdfOps.deletePages(src, setOf(p), out, pw)
        }
    }

    private fun unlock() = apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_unlock_done) }, "unlocked", newPassword = { null }) { src, out, pw ->
        if (!PdfOps.isEncrypted(src, pw)) throw QuickInfo(ctx.getString(R.string.qk_not_protected))
        PdfOps.removeSecurity(src, pw.orEmpty(), out)
    }

    fun flatten(raster: Boolean) = apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_fl_done) }, "flattened") { src, out, pw ->
        if (raster) PdfOps.flatten(ctx, src, out, password = pw) else PdfOps.flattenAnnotations(src, out, pw)
    }

    // ---------------- sheet actions ----------------

    fun compress(cached: CompressEstimate?, quality: CompressQuality) {
        var sizes = 0L to 0L
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_compress_done, DocumentRepository.formatSize(sizes.first), DocumentRepository.formatSize(sizes.second)) },
            "compressed", cancellable = true) { src, out, pw ->
            val before = src.length()
            try {
                if (cached != null && cached.matches(src)) withContext(Dispatchers.IO) { cached.file.copyTo(out, overwrite = true) }
                else PdfOps.compress(src, out, quality.jpeg, quality.maxDim, pw)
            } finally { cached?.file?.delete() }
            if (out.length() >= before) { out.delete(); throw QuickInfo(ctx.getString(R.string.qk_no_gain)) }
            sizes = before to out.length()
        }
    }

    fun watermark(text: String, size: Float, color: Int, opacity: Float, rotation: Float) =
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_wm_done) }, "watermark") { src, out, pw ->
            PdfOps.watermark(ctx, src, text, out, size, color, opacity, rotation, password = pw)
        }

    fun pageNumbers(position: PdfOps.NumberPosition, start: Int, arabicDigits: Boolean) =
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_pn_done) }, "numbered") { src, out, pw ->
            PdfOps.addPageNumbers(ctx, src, out, position, start, arabicDigits = arabicDigits, password = pw)
        }

    fun protect(open: String, owner: String, print: Boolean, copy: Boolean, modify: Boolean, annotate: Boolean, forms: Boolean) =
        apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_pr_done) }, "protected", newPassword = { open.ifEmpty { null } }) { src, out, pw ->
            PdfOps.protect(src, out, open, owner, print, copy, modify, annotate, forms, currentPassword = pw)
        }

    fun setProperties(info: PdfOps.Info) = apply(ctx.getString(R.string.qk_working), { ctx.getString(R.string.qk_props_done) }, "info") { src, out, pw ->
        PdfOps.setInfo(src, info, out, pw)
    }

    fun ocr(opt: OcrOptions) {
        val eng = engine ?: OcrEngine(ctx.applicationContext).also { engine = it }
        var count = 0
        var fixed = 0
        val done = {
            when {
                fixed > 0 && count == 0 -> ctx.getString(R.string.qk_fix_done, fixed)
                fixed > 0 -> ctx.getString(R.string.qk_ocr_done, count) + " · " + ctx.getString(R.string.qk_fix_done, fixed)
                else -> ctx.getString(R.string.qk_ocr_done, count)
            }
        }
        apply(ctx.getString(R.string.qk_working), done, "ocr", cancellable = true) { src, out, pw ->
            count = runOcrToFile(ctx, eng, src, out, pw, opt, onRepaired = { fixed = it.pages.size }) { text, f -> setProgress(text, f) }
        }
    }

    /**
     * «إصلاح نص الملف»: rewrites the wrong ToUnicode maps of old Word Arabic fonts ([com.smartdocs.editor.core.pdf.ToUnicodeRepair])
     * so copy, search, read-aloud, AI and reflow get the letters the page shows; the app's own earlier OCR words on those
     * pages are removed (they would duplicate the now readable text). The page looks exactly the same.
     */
    fun fixText() {
        var pages = 0
        var reocr = emptyList<Int>()
        val done = {
            val base = if (pages > 0) ctx.getString(R.string.qk_fix_done, pages) else ctx.getString(R.string.qk_fix_lig)
            if (reocr.isEmpty()) base else base + " · " + ctx.getString(R.string.qk_fix_reocr, reocr.take(8).joinToString("، ") { (it + 1).toString() })
        }
        apply(ctx.getString(R.string.qk_working), done, "textfix") { src, out, pw ->
            val r = com.smartdocs.editor.core.pdf.ToUnicodeRepair.repairFile(src, pw, out, removeOldOcr = true)
            if (!r.changed) { out.delete(); throw QuickInfo(ctx.getString(R.string.qk_fix_none)) }
            pages = r.pages.size
            reocr = r.ocrLayersKept.sorted()
        }
    }

    fun isLanguageInstalled(lang: String): Boolean = (engine ?: OcrEngine(ctx.applicationContext).also { engine = it }).isLanguageInstalled(lang)

    /** Pages → new file; the open document is not changed. */
    fun extract(pages: List<Int>) {
        if (running || pages.isEmpty()) return
        val f = state.file ?: return
        sheet = null; dismissDone()
        running = true; progress = null; progressText = ctx.getString(R.string.qk_working); cancellable = false
        job = scope.launch {
            try {
                if (state.dirty && !state.save()) return@launch
                val out = docs.uniqueFile(docs.docsDir, f.nameWithoutExtension + "_" + ctx.getString(R.string.qk_ex_suffix))
                PdfOps.keepPages(f, pages, out, state.password)
                runCatching { docs.touchRecent(out, pages.size) }
                done = QuickDone(ctx.getString(R.string.qk_ex_done, pages.size, out.name), newFile = out)
            } catch (e: CancellationException) { message = ctx.getString(R.string.qk_cancelled)
            } catch (e: Throwable) { message = ctx.getString(R.string.qk_failed, e.message ?: "") } finally { running = false }
        }
    }
}

@Composable
fun rememberQuickController(state: ReaderState): QuickController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember(state) { QuickController(state, scope, ctx.applicationContext) }
    DisposableEffect(c) { onDispose { c.dispose() } }
    return c
}
