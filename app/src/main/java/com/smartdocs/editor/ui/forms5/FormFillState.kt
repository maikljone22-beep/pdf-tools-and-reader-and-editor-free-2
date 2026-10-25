package com.smartdocs.editor.ui.forms5

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.smartdocs.editor.core.forms.AfFormat
import com.smartdocs.editor.core.forms.Display
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.forms5.AutocompleteFile
import com.smartdocs.editor.core.forms5.ButtonAction
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.FieldNavigation
import com.smartdocs.editor.core.forms5.FieldRules
import com.smartdocs.editor.core.forms5.FieldValidation
import com.smartdocs.editor.core.forms5.FormModel
import com.smartdocs.editor.core.forms5.FormModelLoader
import com.smartdocs.editor.core.forms5.FormValueWriter
import com.smartdocs.editor.core.forms5.Issue
import com.smartdocs.editor.core.forms5.NavItem
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap

/** One form state per open reader; works before any hook is added (the hooks only wire dirty / save). */
object FormFill {
    private val states = WeakHashMap<ReaderState, FormFillState>()
    fun of(reader: ReaderState): FormFillState = synchronized(states) { states.getOrPut(reader) { FormFillState(reader) } }
}

/**
 * In-page AcroForm filling of one reader: the loaded widgets, pending values (kept in memory until the
 * reader saves), live calculations with the existing FormCalc engine, the active field and Next/Previous
 * order, its own undo/redo, validation results, autocomplete, and button actions (submit / reset / image).
 */
class FormFillState internal constructor(val reader: ReaderState) {

    /** Set by the ReaderState hook to `{ dirty = true }`; without it values still show but are not autosaved. */
    var markDirty: () -> Unit = {}

    var model by mutableStateOf(FormModel.EMPTY); private set
    private var loadedKey: String? = null
    private val loadMutex = Mutex()

    val edits = mutableStateMapOf<String, String>()
    val images = mutableStateMapOf<String, File>()
    val errors = mutableStateMapOf<String, Issue>()
    private val rtlPages = mutableStateMapOf<Int, Boolean>()

    var activeId by mutableStateOf<String?>(null); private set
    /** Combo / list widget whose dropdown is open. */
    var menuFor by mutableStateOf<String?>(null)
    /** Button action waiting for the user's confirmation. */
    var pendingAction by mutableStateOf<Pair<FWidget, ButtonAction>?>(null)
    /** Image field waiting for the picture picker (the layer launches it). */
    var imageTarget by mutableStateOf<String?>(null)
    /** Date picker request for the active field. */
    var datePickerFor by mutableStateOf<String?>(null)
    /** Short feedback for the field bar (submit result, required fields…), cleared by the UI. */
    var message by mutableStateOf<FormMessage?>(null)
    var busy by mutableStateOf(false)

    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private val undoStack = ArrayDeque<Pair<Map<String, String>, Map<String, File>>>()
    private val redoStack = ArrayDeque<Pair<Map<String, String>, Map<String, File>>>()
    private var typingField: String? = null

    private val acFile by lazy { AutocompleteFile(File(reader.context.filesDir, "forms5_autocomplete.json")) }

    // ------------------------------------------------------------------ derived

    /** Raw values of calculated fields that differ from what the file stores. */
    val computed: Map<String, String> by derivedStateOf {
        val m = model
        if (!m.calc.hasCalculations) emptyMap()
        else m.calc.recalculate(m.values.keys.associateWith { k -> edits[k] ?: m.values[k].orEmpty() })
    }

    /** Next/Previous order of widget ids. */
    val order: List<String> by derivedStateOf {
        val m = model
        val seenRadio = HashSet<String>()
        val items = m.widgets.filter { w -> isTabStop(w) && (w.kind != WKind.RADIO || seenRadio.add(w.name)) }
            .map { NavItem(it.id, it.page, it.box, it.annotIndex) }
        FieldNavigation.order(items, rtl = { rtlPages[it] == true }, tabs = { m.tabs[it] })
    }

    val active: FWidget? get() = activeId?.let { id -> model.widgets.firstOrNull { it.id == id } }

    /** The form layer only reacts while no annotation tool, placement or text selection is active. */
    val enabled: Boolean get() = reader.tool == null && reader.placement == null && reader.selection == null

    val hasPending: Boolean get() = edits.isNotEmpty() || images.isNotEmpty()

    fun isCalculated(name: String) = model.calc.isCalculated(name)

    fun isTabStop(w: FWidget) = !w.readOnly && !w.hidden && !isCalculated(w.name) &&
        w.kind != WKind.SIGNATURE && w.kind != WKind.BUTTON

    fun value(name: String): String = edits[name] ?: computed[name] ?: model.values[name].orEmpty()

    fun isPendingValue(name: String) = name in edits || name in computed || name in images

    /** Text shown for a text / choice widget (AF format applied, choice export value -> display text). */
    fun display(w: FWidget): Display {
        val raw = value(w.name)
        if (w.kind == WKind.COMBO || w.kind == WKind.LIST) {
            val parts = raw.split('\n').filter { it.isNotEmpty() }.map { v -> w.exports.indexOf(v).takeIf { it >= 0 }?.let { w.options.getOrNull(it) } ?: v }
            return Display(parts.joinToString(", "))
        }
        val spec = model.calc.scripts[w.name]?.format ?: return Display(raw)
        return AfFormat.format(spec, raw)
    }

    fun isOn(w: FWidget): Boolean = when (w.kind) {
        WKind.CHECK -> value(w.name).equals("true", true)
        WKind.RADIO -> w.onValue != null && value(w.name) == w.onValue
        else -> false
    }

    // ------------------------------------------------------------------ loading

    suspend fun ensureLoaded() {
        val f = reader.file ?: return
        val s = reader.session ?: return
        val key = f.path + "|" + System.identityHashCode(s) + "|" + reader.renderVersion
        if (key == loadedKey) return
        loadMutex.withLock {
            if (key == loadedKey) return
            val m = FormModelLoader.load(f, reader.password)
            withContext(Dispatchers.IO) { runCatching { acFile.store() } }
            Snapshot.withMutableSnapshot {
                model = m; loadedKey = key
                if (activeId != null && m.widgets.none { it.id == activeId }) activeId = null
                // pending edits of fields that no longer exist are dropped
                edits.keys.filter { it !in m.values }.forEach { edits.remove(it) }
            }
            m.widgets.map { it.page }.distinct().take(300).forEach { p ->
                if (p !in rtlPages) reader.scope.launch {
                    val t = runCatching { reader.pageText(p) }.getOrDefault("")
                    val ar = t.count { it.code in 0x0600..0x06FF }; val la = t.count { it in 'a'..'z' || it in 'A'..'Z' }
                    rtlPages[p] = ar > la
                }
            }
        }
    }

    // ------------------------------------------------------------------ editing

    private fun snapshot() {
        undoStack.addLast(edits.toMap() to images.toMap()); if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear(); canUndo = true; canRedo = false
    }

    /** Set a raw value. Successive keystrokes in one field ([typing]) form a single undo step. */
    fun setValue(name: String, v: String, typing: Boolean = false) {
        if (value(name) == v && name !in computed) return
        if (!typing || typingField != name) snapshot()
        typingField = if (typing) name else null
        if (v == model.values[name].orEmpty() && !isCalculated(name)) edits.remove(name) else edits[name] = v
        errors.remove(name)
        markDirty()
    }

    /** Several values as one undo step (reset form, date picker of a group). */
    fun setValues(map: Map<String, String>) {
        if (map.isEmpty()) return
        snapshot(); typingField = null
        map.forEach { (k, v) -> if (v == model.values[k].orEmpty()) edits.remove(k) else edits[k] = v; errors.remove(k) }
        images.keys.filter { it in map }.forEach { images.remove(it) }
        markDirty()
    }

    fun setImage(name: String, file: File) { snapshot(); typingField = null; images[name] = file; markDirty() }

    fun toggle(w: FWidget) {
        if (w.readOnly) return
        when (w.kind) {
            WKind.CHECK -> setValue(w.name, (!isOn(w)).toString())
            WKind.RADIO -> w.onValue?.let { if (value(w.name) != it) setValue(w.name, it) }
            else -> {}
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(edits.toMap() to images.toMap())
        restore(prev); canUndo = undoStack.isNotEmpty(); canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(edits.toMap() to images.toMap())
        restore(next); canUndo = true; canRedo = redoStack.isNotEmpty()
    }

    private fun restore(s: Pair<Map<String, String>, Map<String, File>>) {
        typingField = null
        edits.clear(); edits.putAll(s.first); images.clear(); images.putAll(s.second)
        markDirty()
    }

    // ------------------------------------------------------------------ focus / navigation

    /** Tap on a widget. */
    fun tap(w: FWidget) {
        if (!enabled || w.hidden) return
        when (w.kind) {
            WKind.CHECK, WKind.RADIO -> { commitActive(); toggle(w); activeId = w.id; typingField = null }
            WKind.COMBO, WKind.LIST -> { if (w.readOnly) return; commitActive(); activeId = w.id; menuFor = if (w.kind == WKind.COMBO && w.editable) null else w.id }
            WKind.TEXT -> { if (w.readOnly || isCalculated(w.name)) return; if (activeId != w.id) commitActive(); activeId = w.id }
            WKind.IMAGE -> if (!w.readOnly) { commitActive(); imageTarget = w.name }
            WKind.BUTTON -> w.action?.let { commitActive(); pendingAction = w to it }
            WKind.SIGNATURE -> {}
        }
    }

    fun focus(id: String?) {
        if (id == activeId) return
        commitActive(); activeId = id; menuFor = null
        val w = active ?: return
        if (w.page != reader.currentPage) reader.jumpTo(w.page)
    }

    fun step(delta: Int) = focus(FieldNavigation.step(order, activeId?.let { canonicalId(it) }, delta))

    /** Radio widgets share one stop: the id of the group's first widget. */
    private fun canonicalId(id: String): String {
        val w = model.widgets.firstOrNull { it.id == id } ?: return id
        return if (w.kind == WKind.RADIO) model.widgets.first { it.name == w.name }.id else id
    }

    fun done() { commitActive(); activeId = null; menuFor = null }

    /** Validate the field being left and remember its value for autocomplete. */
    fun commitActive() {
        val w = active ?: return
        typingField = null
        validate(w, requiredToo = false)
        if (w.kind == WKind.TEXT && !w.password && w.name in edits) runCatching { acFile.store().record(w.name, value(w.name)); acFile.save() }
    }

    fun validate(w: FWidget, requiredToo: Boolean): Issue? {
        val sc = model.calc.scripts[w.name]
        val rules = FieldRules(required = requiredToo && w.required, maxLen = w.maxLen, keystroke = sc?.keystroke ?: com.smartdocs.editor.core.forms.KeystrokeKind.NONE,
            format = sc?.format, validate = w.validate)
        val issue = if (w.kind == WKind.TEXT || (requiredToo && w.required)) FieldValidation.check(value(w.name), rules) else null
        if (issue == null) errors.remove(w.name) else errors[w.name] = issue
        return issue
    }

    fun suggestions(w: FWidget, typed: String): List<String> {
        if (w.password) return emptyList()
        if (w.kind == WKind.COMBO) return w.options.filter { typed.isBlank() || it.contains(typed, ignoreCase = true) }.take(6)
        return runCatching { acFile.store().suggestions(w.name, typed, 5) }.getOrDefault(emptyList())
    }

    fun forgetSuggestion(w: FWidget, v: String) { runCatching { acFile.store().remove(w.name, v); acFile.save() } }

    // ------------------------------------------------------------------ save / export

    /** Values to write (user edits + recalculated fields) and the formatted text of their appearances. */
    fun pending(): Pair<Map<String, String>, Map<String, String>> {
        val m = model
        val values = LinkedHashMap<String, String>(edits)
        computed.forEach { (k, v) -> values[k] = v }
        val all = m.values.keys.associateWith { values[it] ?: m.values[it].orEmpty() }
        val display = HashMap(m.calc.displayMap(all).filterKeys { it in values })
        computed.keys.forEach { k -> if (k !in display) display[k] = all[k].orEmpty() }
        return values to display
    }

    private suspend fun writeTo(src: File, password: String?, out: File) {
        val (values, display) = pending()
        FormValueWriter.write(reader.context, src, values, display, images.toMap(), out, password, dropXfa = model.xfa == XfaSupport.Kind.STATIC)
    }

    /**
     * Reader save hook: writes pending values into [file] (via a temp file and [swap], i.e. ReaderState.swapFile).
     * Returns true when the file changed (the caller then writes it back to its source). Throws on failure,
     * keeping the pending values.
     */
    suspend fun flushOnSave(file: File, password: String?, swap: suspend (File) -> Unit): Boolean {
        if (!hasPending) return false
        // save() may run on the autosave IO scope: touch Compose state on the main thread only
        withContext(Dispatchers.Main.immediate) { commitActive() }
        val tmp = File(file.parentFile, ".${file.nameWithoutExtension}.forms5.pdf")
        try { writeTo(file, password, tmp) } catch (e: Throwable) { tmp.delete(); throw e }
        withContext(Dispatchers.Main.immediate) {
            Snapshot.withMutableSnapshot {
                edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null
                undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
            }
        }
        swap(tmp)
        return true
    }

    /** Save as / share / print hook: [file] itself, or a temp copy carrying the pending values (caller deletes it). */
    suspend fun exportSource(file: File, password: String?): File {
        if (!hasPending && computed.isEmpty()) return file
        val out = reader.app.documents.newTemp("forms5")
        writeTo(file, password, out)
        return out
    }

    /** Leaving without saving. */
    fun discard() {
        edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null
        undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
    }
}

/** Feedback shown in the field bar. */
sealed class FormMessage {
    data class Submitted(val code: Int) : FormMessage()
    data class Failed(val reason: String) : FormMessage()
    data class MissingRequired(val count: Int) : FormMessage()
    data object PrintHint : FormMessage()
    data class Reset(val count: Int) : FormMessage()
}
