package com.smartdocs.editor.core.forms5

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** The raw JavaScript of one field's additional actions (/AA /K, /F, /V, /C). */
data class FieldJs(val keystroke: String? = null, val format: String? = null, val validate: String? = null, val calculate: String? = null) {
    val isEmpty: Boolean get() = keystroke.isNullOrBlank() && format.isNullOrBlank() && validate.isNullOrBlank() && calculate.isNullOrBlank()
}

/** What the JavaScript runtime knows about one field (type names as Acrobat's `field.type`). */
data class JsField(
    val name: String,
    val type: String,                       // text, checkbox, radiobutton, combobox, listbox, button, signature
    val value: String = "",                 // FormOps conventions ("true"/"false", export value, "\n"-joined list)
    val onValue: String? = null,            // check box on-state
    val exports: List<String> = emptyList(),
    val items: List<Pair<String, String>> = emptyList(),   // (display, export)
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val multiline: Boolean = false,
    val password: Boolean = false,
    val maxLen: Int = 0,
    val textColor: Int = 0xFF000000.toInt(),
    val fillColor: Int? = null,
    val page: Int = 0,
    val rect: List<Float> = emptyList(),
    val tooltip: String = "",
    val defaultValue: String = "",
    val calcIndex: Int = -1,
)

/** All scripts of a document: field actions, calculation order (/CO, completed) and document-level scripts. */
data class FormJs(
    val fields: List<JsField> = emptyList(),
    val scripts: Map<String, FieldJs> = emptyMap(),
    val calcOrder: List<String> = emptyList(),
    val docScripts: List<String> = emptyList(),
    val numPages: Int = 1,
    val fileName: String = "",
) {
    val hasScripts: Boolean get() = scripts.values.any { !it.isEmpty }
    companion object { val EMPTY = FormJs() }
}

/** Keystroke result: [value] is the field text after the (possibly modified) change; null [value] = rejected. */
data class KeyOutcome(val value: String?, val alerts: List<JsAlert>, val setValues: Map<String, String>, val props: Map<String, JsProps>)

/**
 * Commit (keystroke with willCommit, then validate). [ran] = false when no script ran successfully (no engine,
 * no script, an error or a time-out): the caller then uses the pattern-based checks.
 */
data class CommitOutcome(
    val ran: Boolean,
    val accepted: Boolean = true,
    val value: String = "",
    val issue: Issue? = null,
    val alertText: String? = null,
    val setValues: Map<String, String> = emptyMap(),
    val props: Map<String, JsProps> = emptyMap(),
)

/** Calculation pass in /CO order. [failed] fields need the pattern-based calculation (FormCalcModel). */
data class CalcOutcome(val changed: Map<String, String>, val failed: Set<String>, val props: Map<String, JsProps>, val alerts: List<JsAlert>)

/**
 * Field JavaScript of one document on the [AcroJs] runtime: keystroke (K) while typing, format (F) for
 * display, validate (V) on commit, calculate (C) in calculation order. Thread-safe. When the runtime cannot
 * start (no engine), every call returns null / `ran = false` so the callers keep their pattern-based logic.
 */
class FormScripts(val js: FormJs, private val limits: JsLimits = JsLimits()) {

    private var engine: AcroJs? = null
    private var engineTried = false
    /** Values the runtime already has (only differences are sent). */
    private val pushed = HashMap<String, String>()
    private val formatCache = LinkedHashMap<String, Pair<String, Boolean>?>()
    /** Field properties changed by scripts so far (display, readonly, colours...). */
    val props = HashMap<String, JsProps>()

    /** Why the runtime is unavailable (null while it works or before first use). */
    var unavailableReason: String? = null; private set

    fun script(name: String): FieldJs? = js.scripts[name]
    fun hasKeystroke(name: String) = !js.scripts[name]?.keystroke.isNullOrBlank()
    fun hasFormat(name: String) = !js.scripts[name]?.format.isNullOrBlank()
    fun hasCalculate(name: String) = !js.scripts[name]?.calculate.isNullOrBlank()
    val hasCalculations: Boolean get() = js.calcOrder.any { hasCalculate(it) }

    @Synchronized
    fun available(): Boolean = engine() != null

    private fun engine(): AcroJs? {
        if (engineTried) return engine
        engineTried = true
        if (!com.smartdocs.editor.core.security.JsPolicy.enabled) { unavailableReason = "JavaScript is turned off"; return null }
        if (!js.hasScripts && js.docScripts.isEmpty()) return null
        engine = try { AcroJs(fieldsJson(js.fields), js.docScripts, limits, js.numPages, js.fileName) }
        catch (e: Throwable) { if (e is VirtualMachineError && e !is StackOverflowError) throw e; unavailableReason = e.message ?: e.toString(); null }
        engine?.let { js.fields.forEach { f -> pushed[f.name] = f.value } }
        return engine
    }

    private fun delta(values: Map<String, String>): Map<String, String>? {
        val d = values.filter { (k, v) -> pushed[k] != v }
        if (d.isEmpty()) return null
        pushed.putAll(d)
        if (formatCache.isNotEmpty()) formatCache.clear()
        return d
    }

    private fun absorb(r: JsResult) {
        pushed.putAll(r.setValues)
        r.props.forEach { (k, p) -> props[k] = props[k]?.merge(p) ?: p }
    }

    /**
     * Keystroke while typing: [old] is the text before the edit, [change] the inserted text replacing
     * [selStart, selEnd). Null = no keystroke script or no engine (accept as typed).
     */
    @Synchronized
    fun keystroke(name: String, old: String, change: String, selStart: Int, selEnd: Int, values: Map<String, String>): KeyOutcome? {
        val src = js.scripts[name]?.keystroke?.takeIf { it.isNotBlank() } ?: return null
        val e = engine() ?: return null
        val r = e.run("Keystroke", name, src, JsEvent(old, change, selStart, selEnd, willCommit = false, keyDown = true), delta(values))
        if (!r.ok) return null
        absorb(r)
        if (!r.rc) return KeyOutcome(null, r.alerts, r.setValues, r.props)
        // the script may rewrite event.change (upper-casing, digit folding...)
        val s = selStart.coerceIn(0, old.length); val t = selEnd.coerceIn(s, old.length)
        return KeyOutcome(old.substring(0, s) + r.change + old.substring(t), r.alerts, r.setValues, r.props)
    }

    /** Leaving the field with [value]: keystroke with willCommit then validate. */
    @Synchronized
    fun commit(name: String, value: String, values: Map<String, String>): CommitOutcome {
        val sc = js.scripts[name] ?: return CommitOutcome(false, value = value)
        if (sc.keystroke.isNullOrBlank() && sc.validate.isNullOrBlank()) return CommitOutcome(false, value = value)
        val e = engine() ?: return CommitOutcome(false, value = value)
        var v = value
        var ran = false
        val set = LinkedHashMap<String, String>(); val pr = HashMap<String, JsProps>()
        var d = delta(values)
        sc.keystroke?.takeIf { it.isNotBlank() }?.let { src ->
            val r = e.run("Keystroke", name, src, JsEvent(v, "", -1, -1, willCommit = true), d); d = null
            if (r.ok) {
                ran = true; absorb(r); set.putAll(r.setValues); pr.putAll(r.props)
                if (!r.rc) return rejected(r, value, set, pr)
                v = r.value
            }
        }
        sc.validate?.takeIf { it.isNotBlank() }?.let { src ->
            val r = e.run("Validate", name, src, JsEvent(v, "", -1, -1, willCommit = true), d)
            if (!r.ok) return CommitOutcome(ran, value = v, setValues = set, props = pr).let { if (ran) it else it.copy(ran = false) }
            ran = true; absorb(r); set.putAll(r.setValues); pr.putAll(r.props)
            if (!r.rc) return rejected(r, v, set, pr)
            // a validate script may also show a message without rejecting
            r.alerts.firstOrNull { it.kind == null && it.text.isNotBlank() }?.let { return CommitOutcome(true, value = v, alertText = it.text, setValues = set, props = pr) }
        }
        return CommitOutcome(ran, value = v, setValues = set, props = pr)
    }

    private fun rejected(r: JsResult, v: String, set: Map<String, String>, pr: Map<String, JsProps>): CommitOutcome {
        val issue = issueOf(r.alerts)
        val text = r.alerts.firstOrNull { it.kind == null && it.text.isNotBlank() }?.text
        return CommitOutcome(true, accepted = false, value = v, issue = issue, alertText = text, setValues = set, props = pr)
    }

    /** Run every calculate script in calculation order over [values]; returns the values that changed. */
    @Synchronized
    fun calculate(values: Map<String, String>): CalcOutcome? {
        if (!hasCalculations) return null
        val e = engine() ?: return null
        val work = HashMap(values)
        val changed = LinkedHashMap<String, String>(); val failed = HashSet<String>()
        val pr = HashMap<String, JsProps>(); val alerts = ArrayList<JsAlert>()
        var d = delta(values)
        for (name in js.calcOrder) {
            val src = js.scripts[name]?.calculate?.takeIf { it.isNotBlank() } ?: continue
            val r = e.run("Calculate", name, src, JsEvent(work[name].orEmpty()), d); d = null
            if (!r.ok) { failed += name; continue }
            absorb(r); pr.putAll(r.props); alerts += r.alerts
            r.setValues.forEach { (k, v) -> work[k] = v; changed[k] = v }
            // the runtime already stored the result in the target (it is part of setValues)
            if (r.rc) { work[name] = r.value; changed[name] = r.value }
        }
        return CalcOutcome(changed.filter { (k, v) -> values[k] != v }, failed, pr, alerts)
    }

    /** Formatted display text of [raw] (Format event) and whether a script made it red; null = no script / error. */
    @Synchronized
    fun format(name: String, raw: String, values: Map<String, String>? = null): Pair<String, Boolean>? {
        val src = js.scripts[name]?.format?.takeIf { it.isNotBlank() } ?: return null
        val d = values?.let { delta(it) }
        val key = name + "\u0000" + raw
        if (d == null && formatCache.containsKey(key)) return formatCache[key]
        val e = engine() ?: return null
        val r = e.run("Format", name, src, JsEvent(raw), d)
        val out = if (r.ok) { absorb(r); r.value to isRed(props[name]?.textColor) } else null
        if (formatCache.size > 400) formatCache.remove(formatCache.keys.first())
        formatCache[key] = out
        return out
    }

    companion object {
        fun isRed(c: Int?): Boolean = c != null && c != 0 && (c shr 16 and 0xFF) > 180 && (c shr 8 and 0xFF) < 90 && (c and 0xFF) < 90

        /** Rejection reason from the alerts an AF helper raised (localised by the UI) or the script's own alert. */
        fun issueOf(alerts: List<JsAlert>): Issue {
            val af = alerts.firstOrNull { it.kind != null }
            fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull
            if (af != null) return when (af.kind) {
                "number" -> Issue.NotNumber
                "date", "time" -> Issue.NotDate((af.a as? JsonPrimitive)?.content ?: FieldDates.DEFAULT_PATTERN)
                "special" -> Issue.BadFormat((af.a as? JsonPrimitive)?.intOrNull ?: -1)
                "range" -> Issue.OutOfRange(num(af.a), num(af.b))
                else -> Issue.Script(af.text)
            }
            return Issue.Script(alerts.firstOrNull { it.text.isNotBlank() }?.text)
        }

        private fun colorJson(c: Int?): JsonElement? {
            if (c == null) return null
            if (c ushr 24 == 0) return JsonArray(listOf(JsonPrimitive("T")))
            fun ch(s: Int) = JsonPrimitive(((c shr s) and 0xFF) / 255.0)
            return JsonArray(listOf(JsonPrimitive("RGB"), ch(16), ch(8), ch(0)))
        }

        /** The field list in the runtime's JSON shape. */
        fun fieldsJson(fields: List<JsField>): String = JsonArray(fields.map { f ->
            val p = LinkedHashMap<String, JsonElement>()
            p["readonly"] = JsonPrimitive(f.readOnly); p["required"] = JsonPrimitive(f.required)
            p["display"] = JsonPrimitive(if (f.hidden) 1 else 0)
            p["multiline"] = JsonPrimitive(f.multiline); p["password"] = JsonPrimitive(f.password)
            p["charLimit"] = JsonPrimitive(f.maxLen); p["userName"] = JsonPrimitive(f.tooltip)
            colorJson(f.textColor)?.let { p["textColor"] = it }
            p["fillColor"] = colorJson(f.fillColor) ?: JsonArray(listOf(JsonPrimitive("T")))
            p["strokeColor"] = JsonArray(listOf(JsonPrimitive("T")))
            p["exportValues"] = JsonArray(f.exports.map { JsonPrimitive(it) })
            val o = LinkedHashMap<String, JsonElement>()
            o["n"] = JsonPrimitive(f.name); o["t"] = JsonPrimitive(f.type); o["v"] = JsonPrimitive(f.value)
            f.onValue?.let { o["on"] = JsonPrimitive(it) }
            o["ex"] = JsonArray(f.exports.map { JsonPrimitive(it) })
            o["it"] = JsonArray(f.items.map { (d, e) -> JsonArray(listOf(JsonPrimitive(d), JsonPrimitive(e))) })
            o["dv"] = JsonPrimitive(f.defaultValue); o["pg"] = JsonPrimitive(f.page); o["co"] = JsonPrimitive(f.calcIndex)
            o["r"] = JsonArray(f.rect.map { JsonPrimitive(it) })
            o["p"] = JsonObject(p)
            JsonObject(o)
        }).toString()
    }
}
