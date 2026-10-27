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
import com.smartdocs.editor.core.forms5.FormScripts
import com.smartdocs.editor.core.forms5.JsProps
import com.smartdocs.editor.core.forms5.FormValueWriter
import com.smartdocs.editor.core.forms5.Issue
import com.smartdocs.editor.core.forms5.NavItem
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.core.forms5.XfaGenerated
import com.smartdocs.editor.core.forms5.XfaRepeat
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    /** The reader was closed: drop its state (the value holds the reader, so the weak key alone never clears it). */
    fun forget(reader: ReaderState) { synchronized(states) { states.remove(reader) } }
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
    /** Field JavaScript on the Rhino runtime (null: no scripts, or the engine could not start -> pattern-based logic). */
    var scripts by mutableStateOf<FormScripts?>(null); private set
    /** Bumped whenever a keystroke / commit script changed field properties (hidden, readonly, colours). */
    private var propsVersion by mutableStateOf(0)
    /** Last accepted value per field: a value rejected by a script is replaced by it (as Acrobat does). */
    private val committed = HashMap<String, String>()
    /** `app.alert` text or a rejection to show in a dialog ("" = the generic message for [errors]). */
    var alert by mutableStateOf<String?>(null)
    /** Field whose rejected value the [alert] is about. */
    var alertField by mutableStateOf<String?>(null)
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

    /** Field-bar undo / redo: the top step of the reader's unified history is a form step (core/history). */
    val canUndo: Boolean get() = reader.history.canUndo && reader.history.peekUndo()?.owner == OWNER_FORMS
    val canRedo: Boolean get() = reader.history.canRedo && reader.history.peekRedo()?.owner == OWNER_FORMS
    private var typingField: String? = null

    private val acFile by lazy { AutocompleteFile(File(reader.context.filesDir, "forms5_autocomplete.json")) }

    // ------------------------------------------------------------------ derived

    private class Live(val values: Map<String, String>, val props: Map<String, JsProps>)

    /**
     * Calculation pass: calculate scripts on the JS runtime in /CO order; fields whose script failed (and every
     * field when there is no engine) use the pattern-based FormCalcModel.
     */
    private val live: Live by derivedStateOf {
        val m = model; val sc = scripts
        @Suppress("UNUSED_VARIABLE") val v = propsVersion
        val base = m.values.keys.associateWith { k -> edits[k] ?: m.values[k].orEmpty() }
        val js = if (sc != null && sc.hasCalculations) runCatching { sc.calculate(base) }.getOrNull() else null
        when {
            sc != null && js != null -> {
                val out = LinkedHashMap(js.changed)
                if (js.failed.isNotEmpty() && m.calc.hasCalculations)
                    m.calc.recalculate(base + out).forEach { (k, v2) -> if (k in js.failed || !sc.hasCalculate(k)) out[k] = v2 }
                Live(out, synchronized(sc) { sc.props.toMap() })
            }
            m.calc.hasCalculations -> Live(m.calc.recalculate(base), sc?.let { synchronized(it) { it.props.toMap() } }.orEmpty())
            else -> Live(emptyMap(), sc?.let { synchronized(it) { it.props.toMap() } }.orEmpty())
        }
    }

    /** Raw values of calculated fields that differ from what the file stores. */
    val computed: Map<String, String> get() = live.values

    /** Field properties changed by scripts (display, readonly, required, colours). */
    fun props(name: String): JsProps? = live.props[name]

    fun isHidden(w: FWidget) = w.hidden || props(w.name)?.hidden == true
    fun isReadOnly(w: FWidget) = w.readOnly || props(w.name)?.readOnly == true

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

    fun isCalculated(name: String) = model.calc.isCalculated(name) || scripts?.hasCalculate(name) == true

    fun isTabStop(w: FWidget) = !isReadOnly(w) && !isHidden(w) && !isCalculated(w.name) &&
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
        scripts?.takeIf { it.hasFormat(w.name) }?.let { sc -> runCatching { sc.format(w.name, raw) }.getOrNull()?.let { return Display(it.first, it.second) } }
        val spec = model.calc.scripts[w.name]?.format ?: return Display(raw)
        return AfFormat.format(spec, raw)
    }

    /**
     * Keystroke event while typing in [w]: the edit from [old] to [new] as Acrobat's change / selection.
     * Returns the text to keep (the script may rewrite the change) or null when the script rejected it.
     */
    fun keystroke(w: FWidget, old: String, new: String): String? {
        val sc = scripts ?: return new
        if (!sc.hasKeystroke(w.name)) return new
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val out = runCatching { sc.keystroke(w.name, old, new.substring(p, new.length - s), p, old.length - s, allValues()) }.getOrNull() ?: return new
        applyScriptSide(out.setValues - w.name, out.props.isNotEmpty())
        out.alerts.firstOrNull { it.kind == null && it.text.isNotBlank() }?.let { alert = it.text; alertField = null }
        return out.value
    }

    private fun allValues(): Map<String, String> { val m = model; return m.values.keys.associateWith { value(it) } }

    /** Values other fields got from `getField(x).value = ...` in a keystroke / validate script. */
    private fun applyScriptSide(set: Map<String, String>, propsChanged: Boolean) {
        set.forEach { (k, v) -> if (k in model.values && value(k) != v) { if (v == model.values[k].orEmpty()) edits.remove(k) else edits[k] = v } }
        if (propsChanged) propsVersion++
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
        // the JavaScript switch is read before the model: it decides which scripts the model keeps (and a change reloads)
        com.smartdocs.editor.core.security.JsPolicy.enabled = runCatching { reader.app.settings.settings.first().runJavaScript }.getOrDefault(com.smartdocs.editor.core.security.JsPolicy.enabled)
        val key = f.path + "|" + System.identityHashCode(s) + "|" + reader.renderVersion + "|" + com.smartdocs.editor.core.security.JsPolicy.enabled
        if (key == loadedKey) return
        loadMutex.withLock {
            if (key == loadedKey) return
            val m = FormModelLoader.load(f, reader.password)
            withContext(Dispatchers.IO) { runCatching { acFile.store() } }
            // the Rhino runtime starts here (document-level scripts run once), off the main thread
            val sc = if (m.js.hasScripts) withContext(Dispatchers.Default) { FormScripts(m.js).takeIf { runCatching { it.available() }.getOrDefault(false) } } else null
            Snapshot.withMutableSnapshot {
                model = m; scripts = sc; loadedKey = key; committed.clear()
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

    /**
     * One step of the reader's unified undo history. The state after the step is captured when it is undone
     * (successive keystrokes of one field keep growing the same step).
     */
    private fun snapshot(label: String, page: Int) {
        val before = edits.toMap() to images.toMap()
        var after: Pair<Map<String, String>, Map<String, File>>? = null
        reader.history.pushMemory(label, page, OWNER_FORMS,
            undo = { after = edits.toMap() to images.toMap(); restore(before) },
            redo = { after?.let { restore(it) } })
    }

    private fun fieldLabel(name: String, image: Boolean = false): String {
        val short = name.substringAfterLast('.').replace(Regex("\\[\\d+]"), "").ifBlank { name }.take(40)
        return reader.context.getString(if (image) com.smartdocs.editor.R.string.hi_form_image else com.smartdocs.editor.R.string.hi_form_value, short)
    }

    private fun pageOf(name: String): Int = model.widgets.firstOrNull { it.name == name }?.page ?: reader.currentPage

    /** Set a raw value. Successive keystrokes in one field ([typing]) form a single undo step. */
    fun setValue(name: String, v: String, typing: Boolean = false) {
        if (value(name) == v && name !in computed) return
        if (!typing || typingField != name) snapshot(fieldLabel(name), pageOf(name))
        typingField = if (typing) name else null
        if (v == model.values[name].orEmpty() && !isCalculated(name)) edits.remove(name) else edits[name] = v
        errors.remove(name)
        markDirty()
    }

    /** Several values as one undo step (reset form, date picker of a group). */
    fun setValues(map: Map<String, String>) {
        if (map.isEmpty()) return
        snapshot(if (map.size == 1) fieldLabel(map.keys.first()) else reader.context.getString(com.smartdocs.editor.R.string.hi_form_values), pageOf(map.keys.first())); typingField = null
        map.forEach { (k, v) -> if (v == model.values[k].orEmpty()) edits.remove(k) else edits[k] = v; errors.remove(k) }
        images.keys.filter { it in map }.forEach { images.remove(it) }
        markDirty()
    }

    fun setImage(name: String, file: File) { snapshot(fieldLabel(name, image = true), pageOf(name)); typingField = null; images[name] = file; markDirty() }

    fun toggle(w: FWidget) {
        if (isReadOnly(w)) return
        when (w.kind) {
            WKind.CHECK -> setValue(w.name, (!isOn(w)).toString())
            WKind.RADIO -> w.onValue?.let { if (value(w.name) != it) setValue(w.name, it) }
            else -> {}
        }
    }

    /** Field-bar undo: the reader's unified undo while its newest step is a form step. */
    fun undo() { if (canUndo) reader.undo() }

    fun redo() { if (canRedo) reader.redo() }

    private fun restore(s: Pair<Map<String, String>, Map<String, File>>) {
        typingField = null
        edits.clear(); edits.putAll(s.first); images.clear(); images.putAll(s.second)
        markDirty()
    }

    // ------------------------------------------------------------------ focus / navigation

    /** Tap on a widget. */
    fun tap(w: FWidget) {
        if (!enabled || isHidden(w)) return
        if (w.id != activeId && !commitActive()) return
        when (w.kind) {
            WKind.CHECK, WKind.RADIO -> { toggle(w); activeId = w.id; typingField = null }
            WKind.COMBO, WKind.LIST -> { if (isReadOnly(w)) return; activeId = w.id; menuFor = if (w.kind == WKind.COMBO && w.editable) null else w.id }
            WKind.TEXT -> { if (isReadOnly(w) || isCalculated(w.name)) return; activeId = w.id }
            WKind.IMAGE -> if (!isReadOnly(w)) imageTarget = w.name
            WKind.BUTTON -> w.action?.let { pendingAction = w to it }
            WKind.SIGNATURE -> {}
        }
    }

    fun focus(id: String?) {
        if (id == activeId) return
        if (!commitActive()) return            // a rejected value keeps the focus in its field (Acrobat)
        activeId = id; menuFor = null
        val w = active ?: return
        if (w.page != reader.currentPage) reader.jumpTo(w.page)
    }

    fun step(delta: Int) = focus(FieldNavigation.step(order, activeId?.let { canonicalId(it) }, delta))

    /** Radio widgets share one stop: the id of the group's first widget. */
    private fun canonicalId(id: String): String {
        val w = model.widgets.firstOrNull { it.id == id } ?: return id
        return if (w.kind == WKind.RADIO) model.widgets.first { it.name == w.name }.id else id
    }

    fun done() { if (!commitActive()) return; activeId = null; menuFor = null }

    /**
     * Leaving the active field: keystroke (willCommit) and validate scripts on the JS runtime; a rejected value
     * is replaced by the previous one and the script's alert is shown. Without a script (or when the engine
     * fails) the pattern-based checks run. Returns false when the value was rejected.
     */
    fun commitActive(): Boolean {
        val w = active ?: return true
        typingField = null
        val v = value(w.name)
        val previous = committed[w.name] ?: model.values[w.name].orEmpty()
        val sc = scripts
        val textual = w.kind == WKind.TEXT || (w.kind == WKind.COMBO && w.editable)
        if (textual && sc != null && v != previous) {
            val out = runCatching { sc.commit(w.name, v, allValues()) }.getOrNull()
            if (out != null && out.ran) {
                applyScriptSide(out.setValues - w.name, out.props.isNotEmpty())
                if (!out.accepted) {
                    setValue(w.name, previous)
                    out.issue?.let { errors[w.name] = it }
                    alert = out.alertText ?: ""; alertField = w.name
                    return false
                }
                if (out.value != v) setValue(w.name, out.value, typing = true)
                errors.remove(w.name); committed[w.name] = out.value
                out.alertText?.let { alert = it; alertField = null }
                remember(w)
                return true
            }
        }
        val issue = validate(w, requiredToo = false)
        if (issue == null) committed[w.name] = v
        remember(w)
        return true
    }

    private fun remember(w: FWidget) {
        if (w.kind == WKind.TEXT && !w.password && w.name in edits) runCatching { acFile.store().record(w.name, value(w.name)); acFile.save() }
    }

    fun validate(w: FWidget, requiredToo: Boolean): Issue? {
        val sc = model.calc.scripts[w.name]
        val rules = FieldRules(required = requiredToo && (w.required || props(w.name)?.required == true), maxLen = w.maxLen, keystroke = sc?.keystroke ?: com.smartdocs.editor.core.forms.KeystrokeKind.NONE,
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
        // format (F) scripts on the JS runtime give the appearance text; the pattern formatter is the fallback
        scripts?.let { sc -> values.keys.forEach { k -> if (sc.hasFormat(k)) runCatching { sc.format(k, all[k].orEmpty()) }.getOrNull()?.let { display[k] = it.first } } }
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
                // the form steps of the unified history become the reader's save step (ReaderState.recordSaved)
                edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null; committed.clear()
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

    /**
     * Generated XFA form: add an instance of the repeating subform [r]. The reader saves first, the values go
     * into the original's datasets with the new (empty) instance, and the form is generated again in place.
     */
    fun addRow(r: XfaRepeat) {
        val f = reader.file ?: return
        if (busy || !r.canAdd) return
        reader.scope.launch {
            busy = true
            try {
                if (!commitActive()) return@launch
                activeId = null
                if (reader.dirty && !reader.save()) throw IllegalStateException(reader.error ?: "save")
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.rows.pdf")
                val src = try { XfaGenerated.addRow(reader.context, f, r, tmp) } catch (e: Throwable) { tmp.delete(); throw e }
                reader.swapFile(tmp, reader.context.getString(com.smartdocs.editor.R.string.hi_form_row))
                runCatching { reader.app.documents.writeBack(src) }
                message = FormMessage.RowAdded
            } catch (e: Throwable) {   // XFA layout can overflow the stack / run out of memory on huge forms
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = FormMessage.Failed(e.message ?: e.toString())
            } finally { busy = false }
        }
    }

    /** Leaving without saving. */
    fun discard() {
        edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null; committed.clear(); alert = null
        reader.history.removeOwner(OWNER_FORMS)
    }
}

/** Owner tag of form-value steps in the reader's unified history. */
const val OWNER_FORMS = "forms"

/** Feedback shown in the field bar. */
sealed class FormMessage {
    data class Submitted(val code: Int) : FormMessage()
    data class Failed(val reason: String) : FormMessage()
    data class MissingRequired(val count: Int) : FormMessage()
    data object PrintHint : FormMessage()
    data class Reset(val count: Int) : FormMessage()
    data object RowAdded : FormMessage()
}
