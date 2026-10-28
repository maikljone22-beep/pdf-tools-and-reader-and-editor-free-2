package com.smartdocs.editor.ui.forms5

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.smartdocs.editor.core.forms5.FieldVisibility
import com.smartdocs.editor.core.forms5.FormPrefs
import com.smartdocs.editor.core.forms5.JsResult
import com.smartdocs.editor.core.forms5.PdfAction
import com.smartdocs.editor.core.forms5.Trigger
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
    fun forget(reader: ReaderState) {
        // document action "will close" (WC): scripts may log or alert; nothing can be saved any more
        synchronized(states) { states[reader] }?.let { st -> runCatching { st.runDocAction("WC") } }
        synchronized(states) { states.remove(reader) }
    }
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
    /** NVE: documents whose JavaScript the user allowed for this session. */
    private val nveJsAllowed = java.util.Collections.synchronizedSet(HashSet<String>())
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
    /** Signature field tapped: the reader shows its Sign / Validate / Clear sheet (ui/signature/SigFieldActions). */
    var sigTapped by mutableStateOf<FWidget?>(null)
    /** Validity of each signed signature field by full name (true = valid), for the status badges. */
    var sigStatus by mutableStateOf<Map<String, Boolean>>(emptyMap())
    /** Validation results and the untrusted signer of the open document, for the signed bar (tabs audit D21). */
    var sigResults by mutableStateOf<List<com.smartdocs.editor.core.pdf.DigitalSignature.VerifyResult>>(emptyList())
    var sigUntrusted by mutableStateOf<java.security.cert.X509Certificate?>(null)
    /** Path of the document whose signed bar the user closed (kept with the reader, so a rotation keeps it closed). */
    var sigBarClosedFor by mutableStateOf<String?>(null)
    var sigTrustRound by mutableIntStateOf(0)
    /** Date picker request for the active field. */
    var datePickerFor by mutableStateOf<String?>(null)
    /** Short feedback for the field bar (submit result, required fields\u2026), cleared by the UI. */
    var message by mutableStateOf<FormMessage?>(null)
    var busy by mutableStateOf(false)

    /** Visibility changed by Hide actions / removeField while filling (field name -> hidden); written on save. */
    val hiddenOverride = mutableStateMapOf<String, Boolean>()
    /** Action list waiting for the user's consent (it submits, opens a link, resets, imports or leaves the file). */
    var pendingChain by mutableStateOf<Triple<FWidget?, Trigger?, List<PdfAction>>?>(null)
    /** File-select text field waiting for the file picker (the layer launches it). */
    var filePickFor by mutableStateOf<String?>(null)
    /** ImportData / importAnFDF without a file next to the PDF: the bar asks for a data file. */
    var importPick by mutableStateOf(false)
    /** Another PDF to open in the reader (GoToR / Launch), with its zero-based page. */
    var openRequest by mutableStateOf<Pair<File, Int>?>(null)
    /** Body of a submit response to show (mime type, text). */
    var response by mutableStateOf<Pair<String, String>?>(null)
    /** JavaScript console output (console.println, results, errors). */
    val consoleLog = mutableStateListOf<String>()
    /** Values the calculation pass sees while automatic calculation is off (null = follow every edit). */
    private var frozenEdits by mutableStateOf<Map<String, String>?>(null)

    /** Field-bar undo / redo: the top step of the reader's unified history is a form step (core/history). */
    val canUndo: Boolean get() = reader.history.canUndo && reader.history.peekUndo()?.owner == OWNER_FORMS
    val canRedo: Boolean get() = reader.history.canRedo && reader.history.peekRedo()?.owner == OWNER_FORMS
    private var typingField: String? = null

    private val acFile by lazy { FormPrefs.load(reader.context); AutocompleteFile.shared(FormPrefs.autocompleteFile(reader.context)) }

    // ------------------------------------------------------------------ derived

    private class Live(val values: Map<String, String>, val props: Map<String, JsProps>)

    /**
     * Calculation pass: calculate scripts on the JS runtime in /CO order; fields whose script failed (and every
     * field when there is no engine) use the pattern-based FormCalcModel.
     */
    private val live: Live by derivedStateOf {
        val m = model; val sc = scripts
        @Suppress("UNUSED_VARIABLE") val v = propsVersion
        // automatic calculation off: the pass sees the values of the last "Calculate now" (the stored ones before that)
        val ed: Map<String, String> = if (FormPrefs.autoCalculate) edits else (frozenEdits ?: emptyMap())
        val base = m.values.keys.associateWith { k -> ed[k] ?: m.values[k].orEmpty() }
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

    fun isHidden(w: FWidget) = hiddenFor(w.name) ?: (w.hidden || props(w.name)?.hidden == true)

    private fun hiddenFor(name: String): Boolean? {
        if (hiddenOverride.isEmpty()) return null
        hiddenOverride[name]?.let { return it }
        // a parent name hides its descendants
        var n = name
        while (n.contains('.')) { n = n.substringBeforeLast('.'); hiddenOverride[n]?.let { return it } }
        return null
    }

    /** Hide / show fields (Hide action, removeField) as one undo step. */
    fun setHidden(names: Collection<String>, hide: Boolean) {
        val targets = names.filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        val before = hiddenOverride.toMap()
        targets.forEach { hiddenOverride[it] = hide }
        if (before == hiddenOverride.toMap()) return
        val after = hiddenOverride.toMap()
        reader.history.pushMemory(reader.context.getString(com.smartdocs.editor.R.string.f8_hi_visibility), pageOf(targets.first()), OWNER_FORMS,
            undo = { hiddenOverride.clear(); hiddenOverride.putAll(before); markDirty() },
            redo = { hiddenOverride.clear(); hiddenOverride.putAll(after); markDirty() })
        if (active?.let { isHidden(it) } == true) { activeId = null; menuFor = null }
        markDirty()
    }

    /** "Calculate now" while automatic calculation is off: the calculation pass takes the current values. */
    fun calculateNow() { frozenEdits = edits.toMap() }

    /** Automatic calculation was switched on or off. */
    fun onAutoCalculateChanged() { frozenEdits = if (FormPrefs.autoCalculate) null else edits.toMap() }

    /** Values of every field (pending edits, calculations, stored values). */
    fun currentValues(): Map<String, String> = allValues()

    /**
     * Result of an action script: values it set (one undo step), changed properties (redraw), the first alert.
     * Requests are handled by the caller (FormActions).
     */
    fun applyActionResult(r: JsResult) {
        val set = r.setValues.filter { (k, v) -> k in model.values && value(k) != v }
        if (set.isNotEmpty()) setValues(set)
        if (r.props.isNotEmpty()) propsVersion++
        r.alerts.firstOrNull { it.kind == null && it.text.isNotBlank() }?.let { alert = it.text; alertField = null }
        if (r.calculateNow) calculateNow()
    }

    /** Widget whose focus / blur triggers last ran (focus changes are watched in [init]). */
    private var focusedForTriggers: String? = null

    init {
        // page open / close actions (page /AA O, C) as the reader moves between pages
        // yield first: the collectors must not run before the rest of this object is initialised
        reader.scope.launch {
            kotlinx.coroutines.yield()
            var prev = -1
            snapshotFlow { reader.currentPage to model }.collect { (page, m) ->
                if (page == prev || m.pageActions.isEmpty()) { prev = page; return@collect }
                val sc = scripts
                if (sc != null) {
                    m.pageActions[prev]?.get("C")?.let { code -> runCatching { sc.action(null, "Page/Close", code, allValues(), prev) }.getOrNull()?.let { applyActionResult(it) } }
                    m.pageActions[page]?.get("O")?.let { code -> runCatching { sc.action(null, "Page/Open", code, allValues(), page) }.getOrNull()?.let { applyActionResult(it) } }
                }
                prev = page
            }
        }
        reader.scope.launch {
            kotlinx.coroutines.yield()
            snapshotFlow { activeId }.collect { id ->
                val old = focusedForTriggers?.let { o -> model.widgets.firstOrNull { it.id == o } }
                focusedForTriggers = id
                if (old != null) triggersOf(old)[Trigger.BLUR]?.let { FormActions.fire(this@FormFillState, old, Trigger.BLUR, it) }
                val now = id?.let { n -> model.widgets.firstOrNull { it.id == n } }
                if (now != null) triggersOf(now)[Trigger.FOCUS]?.let { FormActions.fire(this@FormFillState, now, Trigger.FOCUS, it) }
            }
        }
    }
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

    val hasPending: Boolean get() = edits.isNotEmpty() || images.isNotEmpty() || hiddenOverride.isNotEmpty() || !scriptChanges().isEmpty

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
        // XFA picture clause of a generated XFA form: typed text is read back to its canonical value first
        // (edit / display pattern), then shown in the display pattern; values that match neither show as typed
        w.picture?.let { pic -> com.smartdocs.editor.core.forms.XfaPicture.format(pic, canonical(w, raw))?.let { return Display(it) } }
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
        // nitro-protect2: the document's own decision (run level, Safe Reading Mode, trusted / "Keep blocked")
        com.smartdocs.editor.core.security.JsPolicy.enabled = reader.guard.jsAllowed(runCatching { reader.app.settings.settings.first().runJavaScript }.getOrDefault(com.smartdocs.editor.core.security.JsPolicy.enabled))
        val key = f.path + "|" + System.identityHashCode(s) + "|" + reader.renderVersion + "|" + com.smartdocs.editor.core.security.JsPolicy.enabled
        if (key == loadedKey) return
        loadMutex.withLock {
            if (key == loadedKey) return
            val m = FormModelLoader.load(f, reader.password)
            withContext(Dispatchers.IO) { runCatching { acFile.store() } }
            // the Rhino runtime starts here (document-level scripts run once), off the main thread
            // NVE: safe reading - document JavaScript runs only when trusted (once / always), see NveTrust
            val nveCtx = reader.context
            val nveDecision = if (!m.js.hasScripts || f.path in nveJsAllowed) com.smartdocs.editor.core.nve.shell.TrustPolicy.Decision.ALLOW
                else com.smartdocs.editor.core.nve.shell.TrustPolicy.script(f.path, com.smartdocs.editor.ui.nve.shell.NveTrust.docs(nveCtx), com.smartdocs.editor.ui.nve.shell.NveTrust.scriptMode(nveCtx))
            if (nveDecision == com.smartdocs.editor.core.nve.shell.TrustPolicy.Decision.ASK) withContext(Dispatchers.Main) {
                com.smartdocs.editor.ui.nve.shell.NveTrust.guardScript(nveCtx, f.path, f.name) { nveJsAllowed += f.path; loadedKey = null; reader.scope.launch { ensureLoaded() } }
            }
            val sc = if (m.js.hasScripts && nveDecision == com.smartdocs.editor.core.nve.shell.TrustPolicy.Decision.ALLOW) withContext(Dispatchers.Default) { FormScripts(m.js).takeIf { runCatching { it.available() }.getOrDefault(false) } } else null
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

    fun setImage(name: String, file: File) { snapshot(fieldLabel(name, image = true), pageOf(name)); typingField = null; images[name] = file; iconCopies.remove(name); markDirty() }

    fun toggle(w: FWidget) {
        if (isReadOnly(w)) return
        val before = value(w.name)
        when (w.kind) {
            WKind.CHECK -> setValue(w.name, (!isOn(w)).toString())
            WKind.RADIO -> w.onValue?.let { if (value(w.name) != it) setValue(w.name, it) }
            else -> {}
        }
        // XFA interactive events: click, then change when the state changed
        if (model.generatedFromXfa) { fireXfa(w, "click", before); if (value(w.name) != before) fireXfa(w, "change", before) }
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
        // nitro-protect2: the author's form-filling permission and Protected View (buttons still work)
        if (w.kind != WKind.BUTTON && !reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) return
        if (w.id != activeId && !commitActive()) return
        when (w.kind) {
            WKind.CHECK, WKind.RADIO -> { toggle(w); activeId = w.id; typingField = null }
            WKind.COMBO, WKind.LIST -> { if (isReadOnly(w)) return; activeId = w.id; menuFor = if (w.kind == WKind.COMBO && w.editable) null else w.id }
            WKind.TEXT -> {
                if (isReadOnly(w) || isCalculated(w.name)) return
                val entering = activeId != w.id
                activeId = w.id
                if (w.fileSelect) filePickFor = w.name
                if (entering && model.generatedFromXfa) fireXfa(w, "enter")      // XFA enter event
            }
            WKind.IMAGE -> if (!isReadOnly(w)) imageTarget = w.name
            WKind.BUTTON -> {
                if (w.xfaClick) { fireXfa(w, "click"); return }      // an XFA button: its click scripts
                // Enter, Mouse Down, Mouse Up (/A + /AA U), Exit: a tap enters and leaves the widget
                val t = triggersOf(w)
                val up = t[Trigger.MOUSE_UP].orEmpty()
                val useChain = up.isNotEmpty() && (scripts != null || up.any { it !is PdfAction.JavaScript })
                if (useChain || t.isNotEmpty()) {
                    val list = t[Trigger.ENTER].orEmpty() + t[Trigger.MOUSE_DOWN].orEmpty() + (if (useChain) up else emptyList()) + t[Trigger.EXIT].orEmpty()
                    if (list.isNotEmpty()) FormActions.fire(this, w, Trigger.MOUSE_UP, list)
                }
                // 27.10: a Mouse Up set by setAction replaces the button's own action
                if (!useChain && scriptActions[w.name]?.containsKey("U") != true) w.action?.let { pendingAction = w to it }
                return
            }
            // main (pdf-3c): a signature field opens the signature-field sheet (sign this field, lock, verify)
            WKind.SIGNATURE -> sigTapped = w
        }
        // other widgets: their mouse triggers run after the default behaviour
        val t = triggersOf(w)
        val list = t[Trigger.ENTER].orEmpty() + t[Trigger.MOUSE_DOWN].orEmpty() + t[Trigger.MOUSE_UP].orEmpty() + t[Trigger.EXIT].orEmpty()
        if (list.isNotEmpty()) FormActions.fire(this, w, Trigger.MOUSE_UP, list)
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
        // XFA interactive events of a generated form: change (when the value changed), then exit
        if (model.generatedFromXfa && textual) { if (v != previous) fireXfa(w, "change", previous); fireXfa(w, "exit", previous) }
        return true
    }

    // ------------------------------------------------------------------ XFA interactive events

    private var xfaEvents: com.smartdocs.editor.core.forms5.XfaEventRuntime? = null
    private var xfaEventsFor: String? = null

    /**
     * Run the XFA [activity] script of [w] (exit / change / click) on the source template with the current values;
     * the values and presence it changes are applied as one undo step / Hide-action change.
     */
    fun fireXfa(w: FWidget, activity: String, prev: String = "") {
        if (!model.generatedFromXfa) return
        val f = reader.file ?: return
        val values = allValues()
        reader.scope.launch {
            val rt = if (xfaEventsFor == f.path) xfaEvents else com.smartdocs.editor.core.forms5.XfaEventRuntime.load(f, reader.password).also { xfaEvents = it; xfaEventsFor = f.path }
            rt ?: return@launch
            val r = withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { rt.fire(w.name, activity, values, prev) }.getOrNull() } ?: return@launch
            if (r.values.isNotEmpty()) setValues(r.values.filterKeys { it != w.name || activity != "change" })
            r.presence.entries.groupBy({ it.value in setOf("hidden", "invisible", "inactive") }, { it.key }).forEach { (hide, names) -> setHidden(names, hide) }
        }
    }

    private fun remember(w: FWidget) {
        if (FormPrefs.autocomplete && w.kind == WKind.TEXT && !w.password && !w.fileSelect && w.name in edits) runCatching { acFile.store().record(w.name, value(w.name)); acFile.save() }
    }

    /** XFA: [v] as typed → its canonical value through the field's edit or display picture (unchanged when neither reads it). */
    fun canonical(w: FWidget, v: String): String {
        if (v.isEmpty()) return v
        for (p in listOfNotNull(w.editPicture, w.picture)) com.smartdocs.editor.core.forms.XfaPicture.parse(p, v)?.let { return it }
        return v
    }

    /** XFA: the text the editor starts with — the value in the edit picture when the field has one. */
    fun editText(w: FWidget): String {
        val raw = value(w.name)
        val p = w.editPicture ?: return raw
        return com.smartdocs.editor.core.forms.XfaPicture.format(p, canonical(w, raw)) ?: raw
    }

    fun validate(w: FWidget, requiredToo: Boolean): Issue? {
        val sc = model.calc.scripts[w.name]
        val rules = FieldRules(required = requiredToo && (w.required || props(w.name)?.required == true), maxLen = w.maxLen, keystroke = sc?.keystroke ?: com.smartdocs.editor.core.forms.KeystrokeKind.NONE,
            format = sc?.format, validate = w.validate)
        val issue = (if (w.kind == WKind.TEXT || (requiredToo && w.required)) FieldValidation.check(value(w.name), rules) else null)
            ?: w.validatePicture?.takeIf { !com.smartdocs.editor.core.forms.XfaPicture.conforms(it, canonical(w, value(w.name))) }?.let { Issue.Picture(it) }
        if (issue == null) errors.remove(w.name) else errors[w.name] = issue
        return issue
    }

    fun suggestions(w: FWidget, typed: String): List<String> {
        if (w.password || w.fileSelect) return emptyList()
        if (w.kind == WKind.COMBO) return w.options.filter { typed.isBlank() || it.contains(typed, ignoreCase = true) }.take(6)
        if (!FormPrefs.autocomplete) return emptyList()
        return runCatching { acFile.store().suggestions(w.name, typed, 5) }.getOrDefault(emptyList())
    }

    fun forgetSuggestion(w: FWidget, v: String) { runCatching { acFile.store().remove(w.name, v); acFile.save() } }

    // ------------------------------------------------------------------ save / export

    /** Values to write (user edits + recalculated fields) and the formatted text of their appearances. */
    fun pending(): Pair<Map<String, String>, Map<String, String>> {
        val m = model
        val values = LinkedHashMap<String, String>(edits)
        computed.forEach { (k, v) -> values[k] = v }
        // XFA picture fields store their canonical value (/V), whatever pattern it was typed in
        m.widgets.forEach { w -> if (w.picture != null || w.editPicture != null) values[w.name]?.let { values[w.name] = canonical(w, it) } }
        val all = m.values.keys.associateWith { values[it] ?: m.values[it].orEmpty() }
        val display = HashMap(m.calc.displayMap(all).filterKeys { it in values })
        // format (F) scripts on the JS runtime give the appearance text; the pattern formatter is the fallback
        scripts?.let { sc -> values.keys.forEach { k -> if (sc.hasFormat(k)) runCatching { sc.format(k, all[k].orEmpty()) }.getOrNull()?.let { display[k] = it.first } } }
        computed.keys.forEach { k -> if (k !in display) display[k] = all[k].orEmpty() }
        // XFA picture clauses: the saved appearance shows the formatted text, /V keeps the canonical value
        m.widgets.forEach { w -> val pic = w.picture; if (pic != null && w.name in values && w.name !in display)
            com.smartdocs.editor.core.forms.XfaPicture.format(pic, all[w.name].orEmpty())?.let { display[w.name] = it } }
        return values to display
    }

    /** 8.84: document changes asked for by the form's JavaScript (addScript, setLock, addField), written on save. */
    private val addedScripts = LinkedHashMap<String, String>()
    private val addedLocks = LinkedHashMap<String, com.smartdocs.editor.core.formsx.FxSigLock>()
    private val addedFields = ArrayList<com.smartdocs.editor.core.forms5.ScriptDocChanges.NewField>()
    /** 8.84: fields removed by `this.removeField` (hidden until the save deletes them). */
    private val removedFields = mutableStateListOf<String>()
    /** 27.10: `field.setAction` per field name: /AA key -> script ("" = removed); they run at once and are written on save. */
    val scriptActions = mutableStateMapOf<String, Map<String, String>>()
    /** 27.10: `buttonSetIcon` with an icon that holds no picture: copied on save. */
    private val iconCopies = LinkedHashMap<String, com.smartdocs.editor.core.forms5.ScriptDocChanges.IconSrc>()
    fun scriptChanges() = com.smartdocs.editor.core.forms5.ScriptDocChanges(addedScripts.toMap(), addedLocks.toMap(), addedFields.toList(),
        removedFields.toList(), scriptActions.toMap(), iconCopies.toMap())
    private fun clearScriptChanges() { addedScripts.clear(); addedLocks.clear(); addedFields.clear(); removedFields.clear(); scriptActions.clear(); iconCopies.clear() }
    fun addDocScript(name: String, code: String) { addedScripts[name] = code; markDirty() }
    fun setSigLock(name: String, lock: com.smartdocs.editor.core.formsx.FxSigLock) { addedLocks[name] = lock; markDirty() }
    fun addScriptField(f: com.smartdocs.editor.core.forms5.ScriptDocChanges.NewField) { if (addedFields.none { it.name == f.name }) { addedFields += f; markDirty() } }
    fun setIconCopy(target: String, src: com.smartdocs.editor.core.forms5.ScriptDocChanges.IconSrc) { images.remove(target); iconCopies[target] = src; markDirty() }
    fun isRemoved(name: String) = removedFields.any { name == it || name.startsWith("$it.") }

    /**
     * 27.10 `field.setAction(cTrigger, cScript)` on [name] (and the fields under it): mouse / focus triggers run from
     * the next event (see [triggersOf]); Keystroke / Format / Validate / Calculate go to the runtime. Written on save.
     * Returns false for an unknown trigger.
     */
    fun setScriptAction(name: String, trigger: String, code: String): Boolean {
        val key = com.smartdocs.editor.core.forms5.ScriptDocChanges.triggerKey(trigger) ?: return false
        val targets = model.byName.keys.filter { it == name || it.startsWith("$name.") }.ifEmpty { listOf(name) }
        targets.forEach { n ->
            scriptActions[n] = scriptActions[n].orEmpty() + (key to code)
            if (key !in com.smartdocs.editor.core.forms5.ScriptDocChanges.WIDGET_KEYS) scripts?.setFieldScript(n, key, code)
        }
        markDirty()
        return true
    }

    /** The widget's mouse / focus actions with the ones `setAction` replaced. */
    fun triggersOf(w: FWidget): Map<Trigger, List<PdfAction>> {
        val over = scriptActions[w.name] ?: return w.triggers
        val out = HashMap(w.triggers)
        Trigger.entries.forEach { t -> over[t.key]?.let { code -> if (code.isBlank()) out.remove(t) else out[t] = listOf(PdfAction.JavaScript(code)) } }
        return out
    }

    /** 8.28: fill-time style of rich-text fields (bold / italic / underline), written to /RV and /DS on save. */
    val richStyles = mutableStateMapOf<String, com.smartdocs.editor.core.annot.AnnotStyle.Rich>()
    fun richStyle(w: FWidget): com.smartdocs.editor.core.annot.AnnotStyle.Rich? = richStyles[w.name] ?: w.rich

    /** Toggle 'b'old, 'i'talic or 'u'nderline of a rich-text field; the field is then saved with its value. */
    fun toggleRich(w: FWidget, what: Char) {
        val base = richStyle(w) ?: com.smartdocs.editor.core.annot.AnnotStyle.Rich(if (w.fontSize > 0f) w.fontSize else 12f, w.textColor, false, false)
        richStyles[w.name] = when (what) { 'b' -> base.copy(bold = !base.bold); 'i' -> base.copy(italic = !base.italic); else -> base.copy(underline = !base.underline) }
        edits[w.name] = value(w.name)
        markDirty()
    }

    /**
     * 27.10: script document changes on a signed file go through the signed gate (SignedGuard): the allowed ones are
     * appended to [out] as an incremental update; the others never break the signatures. When saving ([offerCopy])
     * they are written into a copy that the reader offers to save ("signed - save a copy"); an export keeps only
     * the allowed ones and says so in the console.
     */
    private suspend fun writeScriptChangesSigned(src: File, out: File, password: String?, offerCopy: Boolean) {
        val all = scriptChanges()
        if (all.isEmpty) return
        val info = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedGuard.inspect(src, password) }
        val signedFields = withContext(Dispatchers.IO) { signedFieldNames(src, password) }
        val decisions = HashMap<com.smartdocs.editor.core.pdf.SignedGuard.Change, com.smartdocs.editor.core.pdf.SignedGuard.Decision>()
        fun decide(c: com.smartdocs.editor.core.pdf.SignedGuard.Change) = decisions.getOrPut(c) { com.smartdocs.editor.core.pdf.SignedGuard.decide(info, c, true) }
        val (ok, blocked) = all.split(signedFields) { c ->
            decide(c).let { it == com.smartdocs.editor.core.pdf.SignedGuard.Decision.PROCEED || it == com.smartdocs.editor.core.pdf.SignedGuard.Decision.SAVE_INCREMENTAL }
        }
        if (!ok.isEmpty) ok.appendTo(out, password)
        if (blocked.isEmpty) return
        if (!offerCopy) {
            withContext(Dispatchers.Main.immediate) { consoleLog += "! " + reader.context.getString(com.smartdocs.editor.R.string.jsa_signed_changes_not_exported) }
            return
        }
        // the copy: everything saved so far plus the blocked changes (a full save: its signatures no longer hold)
        val copy = reader.app.documents.newTemp("jsa_copy")
        val written = runCatching { withContext(Dispatchers.IO) { out.copyTo(copy, overwrite = true); blocked.applyTo(copy, password) } }.getOrDefault(0)
        val certified = decisions.values.any { it == com.smartdocs.editor.core.pdf.SignedGuard.Decision.REFUSE }
        withContext(Dispatchers.Main.immediate) {
            if (written > 0) reader.offerSignedCopy(copy, if (certified) com.smartdocs.editor.core.pdf.SignedDocumentException.Reason.CERTIFIED_NO_CHANGES
                else com.smartdocs.editor.core.pdf.SignedDocumentException.Reason.INCREMENTAL_FAILED)
            else copy.delete()
            consoleLog += "! " + reader.context.getString(com.smartdocs.editor.R.string.jsa_signed_changes_copy)
        }
    }

    /** Full names of the signature fields of [src] that hold a signature. */
    private fun signedFieldNames(src: File, password: String?): Set<String> = runCatching {
        com.smartdocs.editor.core.pdf.PdfOps.loadRaw(src, password).use { d ->
            d.documentCatalog.getAcroForm(null)?.fieldTree
                ?.filter { it is com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField && it.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.V) != null }
                ?.map { it.fullyQualifiedName }?.toSet().orEmpty()
        }
    }.getOrDefault(emptySet())

    private suspend fun writeTo(src: File, password: String?, out: File, offerCopy: Boolean = false) {
        val (values, display) = pending()
        // audit V-01: a full rewrite would invalidate the signatures of a signed form; it is appended instead
        if (com.smartdocs.editor.core.pdf.SignedSave.isSigned(src, password)) {
            FormValueWriter.writeSigned(reader.context, src, values, display, images.toMap(), hiddenOverride.toMap(), out, password, rich = richStyles.toMap())
            writeScriptChangesSigned(src, out, password, offerCopy)
            return
        }
        FormValueWriter.write(reader.context, src, values, display, images.toMap(), out, password, dropXfa = model.xfa == XfaSupport.Kind.STATIC, rich = richStyles.toMap())
        // field visibility changed by Hide actions + barcode fields re-encoded from the new values
        FieldVisibility.applyTo(out, password, hiddenOverride.toMap())
        // 8.84: addScript / setLock / addField of the form's scripts
        scriptChanges().takeIf { !it.isEmpty }?.applyTo(out, password)
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
        withContext(Dispatchers.Main.immediate) { runDocAction("WS") }        // document action: will save
        try { writeTo(file, password, tmp, offerCopy = true) } catch (e: Throwable) { tmp.delete(); throw e }
        // audit D37: the typed values are dropped only after the new file is in place (a failed swap keeps them)
        try { swap(tmp) } catch (e: Throwable) { tmp.delete(); throw e }
        withContext(Dispatchers.Main.immediate) {
            Snapshot.withMutableSnapshot {
                // the form steps of the unified history become the reader's save step (ReaderState.recordSaved)
                edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null; committed.clear(); hiddenOverride.clear()
                clearScriptChanges()      // written (or, on a signed file, handed to the "save a copy" offer)
            }
        }
        withContext(Dispatchers.Main.immediate) { runDocAction("DS") }        // did save
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
        if (!reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) return      // nitro-protect2
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

    /**
     * Document action [key] of the catalog /AA (WC, WS, DS, WP, DP) on the JavaScript runtime; values it sets
     * become pending edits. Its viewer requests are ignored except alerts (no navigation while saving / printing).
     */
    fun runDocAction(key: String) {
        val code = model.docActions[key] ?: return
        val sc = scripts ?: return
        val r = runCatching { sc.action(null, "Doc/" + key, code, allValues(), reader.currentPage) }.getOrNull() ?: return
        applyActionResult(r)
    }

    /**
     * 8.84 `this.removeField(name)`: the field is hidden at once and really deleted (with its widgets) on save;
     * its value is no longer submitted.
     */
    fun removeFieldByScript(name: String) {
        if (name.isBlank() || model.byName.keys.none { it == name || it.startsWith("$name.") }) return
        // no undo step: the deletion itself is not undoable before the save either
        hiddenOverride[name] = true
        if (active?.let { isHidden(it) } == true) { activeId = null; menuFor = null }
        if (name !in removedFields) removedFields += name
        edits.keys.filter { it == name || it.startsWith("$name.") }.forEach { edits.remove(it) }
        markDirty()
    }

    /** Generated XFA form: remove the last instance of the repeating subform [r] (one undoable file step). */
    fun removeRow(r: XfaRepeat) {
        val f = reader.file ?: return
        if (busy || r.count <= 1) return
        reader.scope.launch {
            busy = true
            try {
                if (!commitActive()) return@launch
                activeId = null
                if (reader.dirty && !reader.save()) throw IllegalStateException(reader.error ?: "save")
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.rows.pdf")
                val src = try { XfaGenerated.removeRow(reader.context, f, r, tmp) } catch (e: Throwable) { tmp.delete(); throw e }
                reader.swapFile(tmp, reader.context.getString(com.smartdocs.editor.R.string.f8_hi_row_removed))
                runCatching { reader.app.documents.writeBack(src) }
                message = FormMessage.RowRemoved
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = FormMessage.Failed(e.message ?: e.toString())
            } finally { busy = false }
        }
    }

    /** Signature field tapped: the bar offers the signing flow for this document. */
    var signRequest by mutableStateOf<String?>(null)

    /** Leaving without saving. */
    fun discard() {
        edits.clear(); images.clear(); errors.clear(); activeId = null; menuFor = null; committed.clear(); alert = null; hiddenOverride.clear()
        clearScriptChanges()
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
    data object RowRemoved : FormMessage()
    data class NotFound(val what: String) : FormMessage()
    data class Unsupported(val what: String) : FormMessage()
    data class Imported(val count: Int) : FormMessage()
    data object Calculated : FormMessage()
}
