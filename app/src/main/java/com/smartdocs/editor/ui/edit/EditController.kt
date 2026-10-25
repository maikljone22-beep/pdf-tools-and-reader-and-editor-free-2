package com.smartdocs.editor.ui.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.*
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.ImageItem
import com.smartdocs.editor.core.edit.PageModel
import com.smartdocs.editor.core.edit.PageTextModel
import com.smartdocs.editor.core.edit.StalePageException
import com.smartdocs.editor.core.edit.TextAlign
import com.smartdocs.editor.core.edit.TextBlock
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Apply a content edit to the document open in the reader, in place: pending annotations are saved first,
 * [block] writes the edited PDF from `src` into `out`, then the reader swaps the file and reloads.
 * Returns the previous version (pass it to [ReaderState.restoreFrom] to undo) or null when nothing was done.
 */
suspend fun applyEdit(state: ReaderState, block: suspend (src: File, out: File) -> Unit): File? =
    state.applyInPlace { src, out, _ -> block(src, out) }

enum class EditMode { TEXT, IMAGE }

/** A previous version kept for undo, with the password it opens with. */
private class Backup(val file: File, val password: String?)

/** State of the content-edit overlay: page model, selection, pending text / image changes, undo. */
class EditController(val state: ReaderState, private val scope: CoroutineScope, private val ctx: Context) {
    var page by mutableIntStateOf(state.currentPage.coerceAtLeast(0))
    var model by mutableStateOf<PageModel?>(null); private set
    var loading by mutableStateOf(false); private set
    var mode by mutableStateOf(EditMode.TEXT)
    var zoom by mutableFloatStateOf(1f)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    // ---- text ----
    var block by mutableStateOf<TextBlock?>(null); private set
    /** Top-left (LTR) / top-right (RTL) anchor of a new text box, display space. */
    var newAt by mutableStateOf<Pair<Float, Float>?>(null); private set
    var text by mutableStateOf("")
    var style by mutableStateOf(EditTextStyle())
    var alignTouched by mutableStateOf(false)
    val editingText: Boolean get() = block != null || newAt != null

    // ---- images ----
    var image by mutableStateOf<ImageItem?>(null); private set
    /** Pending move / resize / rotate of [image] in display space (6 affine values). */
    var affine by mutableStateOf(identity()); private set
    /** Tap position waiting for a picked image to insert. */
    var insertAt by mutableStateOf<Pair<Float, Float>?>(null)

    private val backups = ArrayDeque<Backup>()
    var canUndo by mutableStateOf(false); private set
    private var loadJob: Job? = null

    // ================= model =================

    fun reload() {
        val f = state.file ?: return
        val p = page
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            try {
                val m = PageTextModel.load(f, state.password, p)
                if (p == page) {
                    model = m
                    // keep selections that still exist after an edit
                    block = block?.let { b -> m.blocks.firstOrNull { it.ops.contentEquals(b.ops) } }
                    image = image?.let { i -> m.images.firstOrNull { it.op == i.op } }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { model = null; message = ctx.getString(R.string.ed_load_failed, e.message ?: "") }
            finally { loading = false }
        }
    }

    fun goToPage(p: Int) {
        val n = state.pageCount
        if (n <= 0) return
        val np = p.coerceIn(0, n - 1)
        if (np == page) return
        clearSelection(); page = np; model = null; reload()
    }

    fun clearSelection() { block = null; newAt = null; image = null; affine = identity(); text = ""; insertAt = null }

    // ================= taps =================

    /** A tap on the page at display coordinates ([x], [y]). */
    fun onTap(x: Float, y: Float) {
        val m = model ?: return
        if (busy) return
        when (mode) {
            EditMode.TEXT -> {
                if (editingText) { clearSelection(); return }       // tap outside closes the editor without saving
                val hit = m.blocks.filter { inflate(it.rect, 2f).let { r -> x in r.left..r.right && y in r.bottom..r.top } }
                    .minByOrNull { it.rect.width() * (it.rect.top - it.rect.bottom) }
                if (hit != null) selectBlock(hit) else startNew(x, y)
            }
            EditMode.IMAGE -> {
                val hit = m.images.lastOrNull { x in it.rect.left..it.rect.right && y in it.rect.bottom..it.rect.top }
                if (hit != null) { image = hit; affine = identity(); insertAt = null }
                else if (image != null) { image = null; affine = identity() }
                else insertAt = x to y
            }
        }
    }

    private fun selectBlock(b: TextBlock) {
        block = b; newAt = null; image = null
        text = b.text
        alignTouched = true
        style = EditTextStyle(size = b.size, color = b.color, bold = b.bold, italic = b.italic, align = b.align, family = b.family)
    }

    private fun startNew(x: Float, y: Float) {
        block = null; newAt = x to y; text = ""; alignTouched = false
        style = style.copy(bold = false, italic = false, lineHeight = null)
    }

    /** Box of the new text in display space for the current text direction. */
    fun newBox(rtlDefault: Boolean): RectF? {
        val (x, y) = newAt ?: return null
        val w = model?.width ?: return null
        val m = 18f
        var rtl = isRtl(text, rtlDefault)
        if (rtl && x - m < 60f) rtl = false
        if (!rtl && w - m - x < 60f) rtl = true
        return if (rtl) RectF(m, y, x, y - style.size * 1.3f) else RectF(x, y, w - m, y - style.size * 1.3f)
    }

    // ================= commits =================

    private fun run(done: Int, op: suspend (src: File, out: File) -> Unit) {
        if (busy) return
        busy = true
        val pw = state.password
        scope.launch {
            try {
                val b = applyEdit(state) { src, out -> op(src, out) }
                if (b != null) { backups.addLast(Backup(b, pw)); while (backups.size > 15) backups.removeFirst().file.delete(); canUndo = true }
                clearSelection()
                message = ctx.getString(done)
            } catch (e: StalePageException) {
                message = ctx.getString(R.string.ed_stale)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                message = ctx.getString(R.string.ed_failed, e.message ?: e.javaClass.simpleName)
            } finally { busy = false; reload() }
        }
    }

    fun commitText(rtlDefault: Boolean) {
        val p = page
        val t = text.trimEnd()
        val b = block
        if (b != null) {
            if (t == b.text && style == EditTextStyle(b.size, b.color, b.bold, b.italic, b.align, b.family)) { clearSelection(); return }
            val st = style
            run(if (t.isBlank()) R.string.ed_deleted else R.string.ed_saved) { src, out -> ContentEdit.replaceText(ctx, src, out, state.password, p, b.ops, t, st) }
            return
        }
        val box = newBox(rtlDefault) ?: return
        if (t.isBlank()) { clearSelection(); return }
        val rtl = isRtl(t, rtlDefault)
        val st = if (alignTouched) style else style.copy(align = if (rtl) TextAlign.RIGHT else TextAlign.LEFT)
        run(R.string.ed_added) { src, out -> ContentEdit.addText(ctx, src, out, state.password, p, t, st, box) }
    }

    fun deleteBlock() {
        val b = block ?: return
        val p = page
        run(R.string.ed_deleted) { src, out -> ContentEdit.deleteText(src, out, state.password, p, b.ops) }
    }

    fun deleteImage() {
        val i = image ?: return
        val p = page
        run(R.string.ed_deleted) { src, out -> ContentEdit.deleteImage(src, out, state.password, p, i.op) }
    }

    fun replaceImage(bmp: Bitmap) {
        val i = image ?: return
        val p = page
        run(R.string.ed_img_replaced) { src, out -> ContentEdit.replaceImage(src, out, state.password, p, i.op, bmp) }
    }

    fun applyTransform() {
        val i = image ?: return
        val a = affine
        if (isIdentity(a)) return
        val p = page
        run(R.string.ed_saved) { src, out -> ContentEdit.transformImage(src, out, state.password, p, i.op, a) }
    }

    fun insertImage(bmp: Bitmap) {
        val (x, y) = insertAt ?: return
        val m = model ?: return
        val p = page
        val maxW = m.width * 0.4f
        val w = maxW.coerceAtMost(bmp.width.toFloat()); val h = w * bmp.height / bmp.width.coerceAtLeast(1)
        val left = (x - w / 2).coerceIn(0f, (m.width - w).coerceAtLeast(0f))
        val bottom = (y - h / 2).coerceIn(0f, (m.height - h).coerceAtLeast(0f))
        run(R.string.ed_added) { src, out -> ContentEdit.addImage(src, out, state.password, p, bmp, RectF(left, bottom + h, left + w, bottom)) }
    }

    suspend fun imageBitmap(): Bitmap? {
        val i = image ?: return null
        val f = state.file ?: return null
        return ContentEdit.imageBitmap(f, state.password, page, i.op)
    }

    // ================= image gestures (display space) =================

    fun previewRect(): RectF? {
        val i = image ?: return null
        return Affine.box(Affine.fromValues(affine), i.rect.left, i.rect.bottom, i.rect.right, i.rect.top)
    }

    fun moveImage(dx: Float, dy: Float) { affine = Affine.values(Affine.mul(Affine.fromValues(affine), Affine.translate(dx, dy))) }

    /** Uniform resize keeping the top-left corner, the bottom-right corner follows the finger by [dx]/[dy]. */
    fun resizeImage(dx: Float, dy: Float) {
        val r = previewRect() ?: return
        val w = r.width(); val h = r.top - r.bottom
        if (w <= 0f || h <= 0f) return
        val s = (((w + dx) / w + (h - dy) / h) / 2f).coerceIn(0.2f, 5f)
        if (w * s < 8f || h * s < 8f) return
        val m = Affine.mul(Affine.mul(Affine.translate(-r.left, -r.top), Affine.scale(s, s)), Affine.translate(r.left, r.top))
        affine = Affine.values(Affine.mul(Affine.fromValues(affine), m))
    }

    fun rotateImage(clockwise: Boolean) {
        val r = previewRect() ?: return
        val m = Affine.rotateAround(if (clockwise) -90f else 90f, r.centerX(), (r.top + r.bottom) / 2f)
        affine = Affine.values(Affine.mul(Affine.fromValues(affine), m))
    }

    fun resetTransform() { affine = identity() }
    val transformPending: Boolean get() = !isIdentity(affine)

    // ================= undo =================

    fun undo() {
        if (busy) return
        val b = backups.removeLastOrNull() ?: return
        canUndo = backups.isNotEmpty()
        busy = true
        scope.launch {
            try { state.restoreFrom(b.file, b.password); clearSelection(); message = ctx.getString(R.string.ed_undone) }
            catch (e: Throwable) { message = ctx.getString(R.string.ed_failed, e.message ?: "") }
            finally { b.file.delete(); busy = false; reload() }
        }
    }

    fun dispose() {
        loadJob?.cancel()
        val list = backups.toList(); backups.clear()
        CoroutineScope(Dispatchers.IO).launch { list.forEach { it.file.delete() } }
    }

    companion object {
        fun identity() = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        fun isIdentity(a: FloatArray) = kotlin.math.abs(a[0] - 1f) < 1e-4f && kotlin.math.abs(a[1]) < 1e-4f && kotlin.math.abs(a[2]) < 1e-4f &&
            kotlin.math.abs(a[3] - 1f) < 1e-4f && kotlin.math.abs(a[4]) < 0.05f && kotlin.math.abs(a[5]) < 0.05f
        fun inflate(r: RectF, d: Float) = RectF(r.left - d, r.top + d, r.right + d, r.bottom - d)
        fun isRtl(t: String, default: Boolean): Boolean = t.firstOrNull { it.isLetter() }?.let { PageTextModel.isArabicChar(it) } ?: default
    }
}

@Composable
fun rememberEditController(state: ReaderState): EditController {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember(state) { EditController(state, scope, ctx.applicationContext) }
    DisposableEffect(c) { onDispose { c.dispose() } }
    return c
}
