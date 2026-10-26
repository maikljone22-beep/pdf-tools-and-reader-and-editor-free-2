package com.smartdocs.editor.core.forms5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.smartdocs.editor.core.forms.JsSandbox
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Limits of one script run (and of the document-level scripts at start-up). */
data class JsLimits(
    val eventMillis: Long = 250,
    val initMillis: Long = 1500,
    val maxStackDepth: Int = 400,
)

/** An `app.alert` text, or a rejection raised by an AF helper ([kind] "number", "date", "time", "special", "range"). */
data class JsAlert(val text: String, val kind: String? = null, val a: JsonElement? = null, val b: JsonElement? = null)

/** Field properties a script changed (null = unchanged). Colours are 0xAARRGGBB, transparent = 0. */
data class JsProps(
    val display: Int? = null,
    val readOnly: Boolean? = null,
    val required: Boolean? = null,
    val textColor: Int? = null,
    val fillColor: Int? = null,
    val strokeColor: Int? = null,
    val items: List<Pair<String, String>>? = null,
) {
    val hidden: Boolean? get() = display?.let { it == 1 || it == 3 }
    fun merge(o: JsProps) = JsProps(o.display ?: display, o.readOnly ?: readOnly, o.required ?: required, o.textColor ?: textColor,
        o.fillColor ?: fillColor, o.strokeColor ?: strokeColor, o.items ?: items)
}

/** Result of one event. [ok] = false when the script threw or ran out of time ([error]); callers then accept the value. */
data class JsResult(
    val ok: Boolean,
    val rc: Boolean = true,
    val value: String = "",
    val change: String = "",
    val selStart: Int = -1,
    val selEnd: Int = -1,
    val alerts: List<JsAlert> = emptyList(),
    val setValues: Map<String, String> = emptyMap(),
    val props: Map<String, JsProps> = emptyMap(),
    val calculateNow: Boolean = false,
    val error: String? = null,
) {
    companion object { fun failed(e: Throwable) = JsResult(ok = false, error = e.message ?: e.toString()) }
}

/** Input of one event. */
data class JsEvent(
    val value: String = "",
    val change: String = "",
    val selStart: Int = -1,
    val selEnd: Int = -1,
    val willCommit: Boolean = false,
    val keyDown: Boolean = false,
)

/**
 * Sandboxed Acrobat-JavaScript runtime on Mozilla Rhino (interpreted mode, so it runs on Android without
 * bytecode generation). The global object is the Doc; [AcroJsLib] provides event / Field / app / util /
 * color / display and the AForm helpers. No Java access: safe standard objects only and a ClassShutter that
 * denies every class. Each run has a time limit enforced by the instruction observer.
 *
 * [fieldsJson] is the field list (see [FormScripts]); [docScripts] are the document-level scripts
 * (/Names /JavaScript), run once at start-up. Not thread-safe by itself: callers synchronise (FormScripts).
 */
class AcroJs(fieldsJson: String, docScripts: List<String>, private val limits: JsLimits = JsLimits(), numPages: Int = 1, fileName: String = "") {

    private val factory = JsSandbox(limits.maxStackDepth)
    private val scope: ScriptableObject
    private val sd: Scriptable
    private val compiled = HashMap<String, Function?>()
    /** Document-level scripts that failed (reported, never fatal). */
    val docErrors = ArrayList<String>()

    init {
        val cx = factory.enterContext()
        try {
            deadline(cx, limits.initMillis)
            scope = cx.initSafeStandardObjects(null, false)
            cx.evaluateString(scope, AcroJsLib.CORE, "acro-core.js", 1, null)
            cx.evaluateString(scope, AcroJsAf.SOURCE, "acro-af.js", 1, null)
            sd = ScriptableObject.getProperty(scope, "__sd") as Scriptable
            ScriptableObject.putProperty(sd, "numPages", numPages.toDouble())
            ScriptableObject.putProperty(sd, "fileName", fileName)
            ScriptableObject.callMethod(cx, sd, "init", arrayOf<Any>(fieldsJson))
            for ((i, src) in docScripts.withIndex()) {
                try { deadline(cx, limits.initMillis); cx.evaluateString(scope, src, "doc-$i.js", 1, null) }
                catch (e: Throwable) { if (!JsSandbox.scriptError(e)) throw e; docErrors += e.message ?: e.toString() }
            }
        } finally { Context.exit() }
    }

    private fun deadline(cx: Context, millis: Long) = JsSandbox.deadline(cx, millis)

    private fun compile(cx: Context, script: String): Function? = compiled.getOrPut(script) {
        try { cx.compileFunction(scope, "function(){\n$script\n}", "field.js", 0, null) } catch (e: Throwable) { null }
    }

    /** True when [script] compiles (a syntax error makes every event accept, like a viewer that cannot run it). */
    fun compiles(script: String): Boolean {
        val cx = factory.enterContext()
        try { return compile(cx, script) != null } finally { Context.exit() }
    }

    /**
     * Run [script] as the [kind] event ("Keystroke", "Format", "Validate", "Calculate") of field [target], with the
     * current raw [values] (FormOps conventions). Errors and time-outs give `ok = false`.
     */
    fun run(kind: String, target: String?, script: String, ev: JsEvent, values: Map<String, String>?): JsResult {
        val cx = factory.enterContext()
        try {
            deadline(cx, limits.eventMillis)
            if (values != null) ScriptableObject.callMethod(cx, sd, "values", arrayOf<Any>(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()))
            ScriptableObject.callMethod(cx, sd, "begin", arrayOf<Any>(kind, target ?: "", eventJson(ev)))
            val fn = compile(cx, script) ?: return JsResult(ok = false, error = "syntax error")
            var err: Throwable? = null
            try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Throwable) { if (!JsSandbox.scriptError(e)) throw e; err = e }
            // collect even after an error (state changed before it stays, as in a viewer)
            deadline(cx, limits.eventMillis)
            val out = Context.toString(ScriptableObject.callMethod(cx, sd, "end", arrayOf<Any>(err?.toString() ?: "")))
            return parse(out, err)
        } catch (e: Throwable) {
            if (!JsSandbox.scriptError(e)) throw e
            return JsResult.failed(e)
        } finally { Context.exit() }
    }

    /** Values set through `getField(x).value = ...` are kept by the runtime; this pushes Kotlin-side values only. */
    fun setValues(values: Map<String, String>) {
        val cx = factory.enterContext()
        try { deadline(cx, limits.eventMillis); ScriptableObject.callMethod(cx, sd, "values", arrayOf<Any>(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())) }
        finally { Context.exit() }
    }

    private fun eventJson(e: JsEvent) = JsonObject(mapOf(
        "value" to JsonPrimitive(e.value), "change" to JsonPrimitive(e.change), "selStart" to JsonPrimitive(e.selStart),
        "selEnd" to JsonPrimitive(e.selEnd), "willCommit" to JsonPrimitive(e.willCommit), "keyDown" to JsonPrimitive(e.keyDown),
    )).toString()

    private fun parse(json: String, err: Throwable?): JsResult {
        val o = Json.parseToJsonElement(json).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val alerts = (o["a"] as? JsonArray)?.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            JsAlert((a["t"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "", (a["k"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, a["a"], a["b"])
        }.orEmpty()
        val setv = (o["s"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
        val props = (o["p"] as? JsonObject)?.mapValues { (_, v) -> props(v.jsonObject) }.orEmpty()
        return JsResult(
            ok = err == null, rc = (o["rc"] as? JsonPrimitive)?.booleanOrNull ?: true, value = s("v"), change = s("c"),
            selStart = (o["ss"] as? JsonPrimitive)?.intOrNull ?: -1, selEnd = (o["se"] as? JsonPrimitive)?.intOrNull ?: -1,
            alerts = alerts, setValues = setv, props = props, calculateNow = (o["cn"] as? JsonPrimitive)?.booleanOrNull ?: false,
            error = err?.let { it.message ?: it.toString() },
        )
    }

    private fun props(p: JsonObject): JsProps {
        fun b(k: String) = (p[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.doubleOrNull?.let { d -> d != 0.0 } }
        return JsProps(
            display = (p["display"] as? JsonPrimitive)?.doubleOrNull?.toInt(), readOnly = b("readonly"), required = b("required"),
            textColor = p["textColor"]?.let { color(it) }, fillColor = p["fillColor"]?.let { color(it) }, strokeColor = p["strokeColor"]?.let { color(it) },
            items = (p["items"] as? JsonArray)?.map { el -> el.jsonArray.let { a -> a[0].jsonPrimitive.content to a.getOrElse(1) { a[0] }.jsonPrimitive.content } },
        )
    }

    companion object {

        /** Acrobat colour array (["RGB",r,g,b], ["G",g], ["CMYK",c,m,y,k], ["T"]) -> 0xAARRGGBB; null when malformed. */
        fun color(el: JsonElement): Int? {
            val a = el as? JsonArray ?: return null
            val space = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return null
            val n = a.drop(1).map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
            fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            val rgb = when (space) {
                "T" -> return 0
                "G" -> n.getOrElse(0) { 0.0 }.let { Triple(it, it, it) }
                "RGB" -> Triple(n.getOrElse(0) { 0.0 }, n.getOrElse(1) { 0.0 }, n.getOrElse(2) { 0.0 })
                "CMYK" -> { val k = 1 - n.getOrElse(3) { 0.0 }; Triple((1 - n.getOrElse(0) { 0.0 }) * k, (1 - n.getOrElse(1) { 0.0 }) * k, (1 - n.getOrElse(2) { 0.0 }) * k) }
                else -> return null
            }
            return (0xFF shl 24) or (c(rgb.first) shl 16) or (c(rgb.second) shl 8) or c(rgb.third)
        }
    }
}
