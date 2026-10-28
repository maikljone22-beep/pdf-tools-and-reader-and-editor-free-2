package com.smartdocs.editor.ui.formsx

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.formsx.FieldRecognition
import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRect
import com.smartdocs.editor.core.formsx.FxRef
import com.smartdocs.editor.core.formsx.FxType
import com.smartdocs.editor.core.forms5.PageSpace
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Crop box and rotation of a page: maps PDF user space to the rendered (rotated, cropped) page and back. */
data class PageInfo(val cropLeft: Float, val cropBottom: Float, val cropW: Float, val cropH: Float, val rotate: Int) {
    private val rot get() = ((rotate % 360) + 360) % 360
    val dispW: Float get() = if (rot % 180 == 0) cropW else cropH
    val dispH: Float get() = if (rot % 180 == 0) cropH else cropW

    /** PDF point -> display point (origin top-left, y down, in points). */
    fun toView(x: Float, y: Float): Pair<Float, Float> {
        val (dx, dy) = PageSpace.toDisplay(x, y, cropLeft, cropBottom, cropW, cropH, rotate)
        return dx to (dispH - dy)
    }

    /** Display point (top-left origin, points) -> PDF user space. */
    fun toPdf(vx: Float, vy: Float): Pair<Float, Float> {
        val dx = vx; val dy = dispH - vy
        val (xx, yy) = when (rot) {
            90 -> (cropW - dy) to dx
            180 -> (cropW - dx) to (cropH - dy)
            270 -> dy to (cropH - dx)
            else -> dx to dy
        }
        return (xx + cropLeft) to (yy + cropBottom)
    }

    /** PDF rect -> display rect (l, t, r, b) in points. */
    fun rectToView(r: FxRect): FloatArray {
        val a = toView(r.left, r.bottom); val b = toView(r.right, r.top)
        return floatArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    fun rectToPdf(l: Float, t: Float, r: Float, b: Float): FxRect {
        val a = toPdf(l, t); val c = toPdf(r, b)
        return FxRect(minOf(a.first, c.first), minOf(a.second, c.second), maxOf(a.first, c.first), maxOf(a.second, c.second))
    }
}

enum class DesignMode { SELECT, CREATE, TAB_ORDER }

/**
 * State of the form designer: a working copy of the document, the fields read from it, the selection and the
 * designer's own multi-level undo / redo (byte snapshots of the working copy). Every change is one PdfBox pass
 * over the working copy ([edit]); the result goes back into the source through the tool result sheet, where the
 * reader records it as one step of its unified history.
 */
class DesignerState(private val context: Context, private val scope: CoroutineScope) {
    var source by mutableStateOf<File?>(null); private set
    var work by mutableStateOf<File?>(null); private set
    var session by mutableStateOf<PdfSession?>(null); private set
    var specs by mutableStateOf<List<FieldSpec>>(emptyList()); private set
    var pages by mutableStateOf<List<PageInfo>>(emptyList()); private set
    var tabs by mutableStateOf<Map<Int, String?>>(emptyMap()); private set
    var calcOrder by mutableStateOf<List<String>>(emptyList()); private set
    var layers by mutableStateOf<List<String>>(emptyList()); private set
    var version by mutableIntStateOf(0); private set
    var page by mutableIntStateOf(0)
    var mode by mutableStateOf(DesignMode.SELECT)
    var createType by mutableStateOf(FxType.TEXT)
    val selection = mutableStateListOf<FxRef>()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var showTabNumbers by mutableStateOf(false)
    var gridSnap by mutableStateOf(0f)
    /** Field recognition results per page (designer assistant and the review sheet). */
    var suggestions by mutableStateOf<Map<Int, List<FieldRecognition.Suggestion>>>(emptyMap())
    /** Manual tab order being built in TAB_ORDER mode (click-to-set). */
    val tabPicks = mutableStateListOf<FxRef>()
    var dirty by mutableStateOf(false); private set

    private val mutex = Mutex()
    private val undoStack = ArrayDeque<Pair<String, File>>()
    private val redoStack = ArrayDeque<Pair<String, File>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var undoLabel by mutableStateOf<String?>(null); private set
    var redoLabel by mutableStateOf<String?>(null); private set

    private val dir: File get() = File(context.cacheDir, "formsx").apply { mkdirs() }

    val pageCount: Int get() = pages.size

    fun specOf(ref: FxRef): FieldSpec? = specs.firstOrNull { it.name == ref.name }
    fun rectOf(ref: FxRef): FxRect? = specOf(ref)?.widgets?.getOrNull(ref.widget)?.rect
    fun selectedSpecs(): List<FieldSpec> = selection.mapNotNull { specOf(it) }.distinctBy { it.name }

    /** Widgets on [p]: (ref, spec, rect). */
    fun widgetsOn(p: Int): List<Triple<FxRef, FieldSpec, FxRect>> = specs.flatMap { s -> s.widgets.mapIndexedNotNull { i, w -> if (w.page == p) Triple(FxRef(s.name, i), s, w.rect) else null } }

    /**
     * tab-view-core E-03: the document password (from the reader, or typed in [needPassword]'s prompt). The working
     * copy is loaded with [PdfOps.load], so an encrypted file keeps its protection when saved.
     */
    var password: String? = null; private set
    /** The file waiting for its password (the screen shows the prompt while this is set). */
    var needPassword by mutableStateOf<File?>(null)
    var wrongPassword by mutableStateOf(false)

    fun open(file: File, pw: String? = com.smartdocs.editor.core.pdf.OpenPasswords.get(file)) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    // a wrong / missing password fails here, before anything is replaced
                    com.smartdocs.editor.core.pdf.PdfOps.load(file, pw).close()
                    val copy = File(dir, "work_${System.nanoTime()}.pdf")
                    file.copyTo(copy, overwrite = true)
                    clearStacks()
                    password = pw
                    source = file
                    swapTo(copy)
                }
                needPassword = null; wrongPassword = false
                page = 0; selection.clear(); suggestions = emptyMap(); dirty = false
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                wrongPassword = pw != null; needPassword = file
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    private fun clearStacks() {
        undoStack.forEach { it.second.delete() }; redoStack.forEach { it.second.delete() }
        undoStack.clear(); redoStack.clear(); updateStacks()
    }

    private fun updateStacks() {
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        undoLabel = undoStack.lastOrNull()?.first; redoLabel = redoStack.lastOrNull()?.first
    }

    /** Make [f] the working copy: reopen the renderer and re-read fields and page geometry. */
    private suspend fun swapTo(f: File) {
        val old = session
        val fresh = PdfSession.open(context, f, password)
        val (sp, pg, tb, co, ly) = com.smartdocs.editor.core.pdf.PdfOps.load(f, password).use { d ->
            val infos = (0 until d.numberOfPages).map { i ->
                val p = d.getPage(i); val c = p.cropBox
                PageInfo(c.lowerLeftX, c.lowerLeftY, c.width, c.height, p.rotation)
            }
            Quad(FxEditor.readAll(d), infos, (0 until d.numberOfPages).associateWith { FxEditor.tabsOf(d, it) }, FxEditor.calcOrder(d), com.smartdocs.editor.core.formsx.FxActions.ocgs(d).map { it.second })
        }
        withContext(Dispatchers.Main) {
            session = fresh; work = f; specs = sp; pages = pg; tabs = tb; calcOrder = co; layers = ly
            if (page >= pg.size) page = (pg.size - 1).coerceAtLeast(0)
            selection.removeAll { r -> sp.none { it.name == r.name && r.widget < it.widgets.size } }
            version++
        }
        runCatching { old?.closeAsync() }
    }

    private data class Quad(val a: List<FieldSpec>, val b: List<PageInfo>, val c: Map<Int, String?>, val d: List<String>, val e: List<String>)

    /**
     * One designer change: [block] edits the working document (returns false to cancel), the result becomes the
     * new working copy and the previous one an undo step labelled [label].
     */
    fun edit(label: String, after: (() -> Unit)? = null, block: (PDDocument) -> Boolean) {
        val cur = work ?: return
        scope.launch {
            mutex.withLock {
                busy = true
                try {
                    val out = File(dir, "work_${System.nanoTime()}.pdf")
                    val changed = withContext(Dispatchers.IO) {
                        com.smartdocs.editor.core.pdf.PdfOps.load(cur, password).use { d -> if (!block(d)) false else { d.save(out); true } }   // tab-view-core E-03: re-protected
                    }
                    if (changed) withContext(NonCancellable + Dispatchers.IO) {
                        undoStack.addLast(label to cur)
                        while (undoStack.size > MAX_STEPS) undoStack.removeFirst().second.delete()
                        redoStack.forEach { it.second.delete() }; redoStack.clear()
                        swapTo(out)
                    }
                    if (changed) { dirty = true; updateStacks(); after?.invoke() }
                } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
            }
        }
    }

    fun undo() = step(true)
    fun redo() = step(false)

    private fun step(isUndo: Boolean) {
        val cur = work ?: return
        scope.launch {
            mutex.withLock {
                val from = if (isUndo) undoStack else redoStack
                val to = if (isUndo) redoStack else undoStack
                val (label, f) = from.removeLastOrNull() ?: return@withLock
                busy = true
                try {
                    to.addLast(label to cur)
                    withContext(Dispatchers.IO) { swapTo(f) }
                    dirty = true
                } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false; updateStacks() }
            }
        }
    }

    /** Copy of the working document for the result sheet (save into the source or as a copy). */
    suspend fun export(outDir: File, uniqueName: (File, String) -> File): File? = withContext(Dispatchers.IO) {
        val w = work ?: return@withContext null
        val src = source ?: return@withContext null
        val out = uniqueName(outDir, src.nameWithoutExtension + "_form")
        w.copyTo(out, overwrite = true)
        out
    }

    /** Run field recognition on [pagesToScan] (null = all pages). */
    fun recognize(pagesToScan: List<Int>?, then: (Int) -> Unit = {}) {
        val cur = work ?: return
        scope.launch {
            busy = true
            try {
                val found = withContext(Dispatchers.IO) {
                    com.smartdocs.editor.core.pdf.PdfOps.load(cur, password).use { d ->
                        (pagesToScan ?: (0 until d.numberOfPages).toList()).filter { it in 0 until d.numberOfPages }
                            .associateWith { runCatching { FieldRecognition.scanPage(d, it) }.getOrDefault(emptyList()) }
                    }
                }
                suggestions = suggestions + found
                then(found.values.sumOf { it.size })
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    fun dispose() {
        runCatching { session?.close() }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    companion object { const val MAX_STEPS = 40 }
}
