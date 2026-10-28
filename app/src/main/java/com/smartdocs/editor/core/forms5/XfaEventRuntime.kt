package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.XInst
import com.smartdocs.editor.core.forms.XTemplate
import com.smartdocs.editor.core.forms.XfaJs
import com.smartdocs.editor.core.forms.XfaMerge
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.forms.XfaTemplateParser
import com.smartdocs.editor.core.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.File

/**
 * Interactive XFA events while filling a form generated from XFA (XfaGenerated): the generated AcroForm keeps the
 * source file and the field → data path map, so for each event the source template is merged again with the
 * current values, the field's exit / enter / change / click scripts run on the XFA object model (XfaJs.runEvent),
 * followed by the calculate scripts, and the values and presence they changed come back as AcroForm field names.
 */
class XfaEventRuntime(private val template: XTemplate, private val data: Element?, private val fields: Map<String, Entry>) {
    /** One generated field: its XFA data path, type ("TEXT", "CHECK", "RADIO", "CHOICE" …), display texts and saved values. */
    data class Entry(val path: String, val type: String, val options: List<String> = emptyList(), val exports: List<String> = emptyList())
    /**
     * Values and presence the scripts changed (AcroForm names). FINAL_CHECK_2: [invalid] fields whose validate test
     * failed (name → message, "" = the default text), [cancelled] a form event cancelled its action, [messages] texts
     * the scripts showed.
     */
    data class Result(val values: Map<String, String>, val presence: Map<String, String>,
                      val invalid: Map<String, String> = emptyMap(), val cancelled: Boolean = false, val messages: List<String> = emptyList()) {
        val isEmpty get() = values.isEmpty() && presence.isEmpty()
    }

    private val byPath: Map<String, List<String>> = fields.entries.groupBy({ it.value.path }, { it.key })
    private val withEvents: Set<String> by lazy {
        val m = XfaMerge.merge(template, data)
        XfaMerge.walk(m.root).filter { it.node.scripts.events.isNotEmpty() || it.node.scripts.validate != null }.flatMap { listOfNotNull(it.dataPath, it.som) }.toSet()
    }
    /** Activities of the form events present anywhere in the template. */
    private val formEvents: Set<String> by lazy {
        val m = XfaMerge.merge(template, data)
        XfaMerge.walk(m.root).flatMap { it.node.scripts.events.keys }.filter { it in FORM_EVENTS }.toSet() +
            (if (XfaMerge.walk(m.root).any { it.node.scripts.validate != null }) setOf("validate") else emptySet())
    }

    /** The instance a generated field stands for: by data path, or by SOM path for unbound buttons. */
    private fun XInst.isAt(path: String) = dataPath == path || som == path

    /** Whether the field [name] has a script for [activity] (cheap check before [fire]). */
    fun has(name: String, activity: String): Boolean {
        val p = fields[name]?.path ?: return false
        if (p !in withEvents) return false
        val m = XfaMerge.merge(template, data)
        return XfaMerge.walk(m.root).firstOrNull { it.isAt(p) }?.node?.scripts?.events?.get(activity)?.isNotEmpty() == true
    }

    /** Run [activity] of the field [name] with the form's [current] values (AcroForm names); null when nothing ran. */
    fun fire(name: String, activity: String, current: Map<String, String>, prevText: String = ""): Result? {
        val path = fields[name]?.path ?: return null
        if (path !in withEvents) return null
        val (m, insts) = merged(current)
        val target = insts.firstOrNull { it.isAt(path) } ?: return null
        return diff(insts) { XfaJs.runEvent(m, target, activity, newText = target.value, prevText = prevText) }
    }

    /**
     * FINAL_CHECK_2 XFA validate of the field [name] after it was left: its validate event scripts and its
     * `<validate>` scriptTest. Null when it has neither.
     */
    fun validate(name: String, current: Map<String, String>): Result? {
        val path = fields[name]?.path ?: return null
        if (path !in withEvents) return null
        val (m, insts) = merged(current)
        val target = insts.firstOrNull { it.isAt(path) } ?: return null
        if (target.node.scripts.validate == null && target.node.scripts.events["validate"].isNullOrEmpty()) return null
        return diff(insts) { XfaJs.runValidate(m, target) }
    }

    /** Whether the form has scripts for the form event [activity] (preSave, prePrint, preSubmit; "validate" = any test). */
    fun hasFormEvent(activity: String): Boolean = activity in formEvents || activity == "preSubmit" && "validate" in formEvents

    /** FINAL_CHECK_2 XFA form events: preSave / prePrint / preSubmit (preSubmit validates every field first). */
    fun formEvent(activity: String, current: Map<String, String>): Result? {
        if (!hasFormEvent(activity)) return null
        val (m, insts) = merged(current)
        return diff(insts) { XfaJs.runFormEvent(m, activity, validateAll = activity == "preSubmit") }
    }

    /** The source merged again, its instances holding the values the user sees now. */
    private fun merged(current: Map<String, String>): Pair<com.smartdocs.editor.core.forms.XMerged, List<XInst>> {
        val m = XfaMerge.merge(template, data)
        val insts = XfaMerge.walk(m.root).toList()
        insts.forEach { i -> i.dataPath?.let { p -> byPath[p]?.firstOrNull()?.let { n -> current[n]?.let { v -> i.value = toXfa(fields.getValue(n), v) } } } }
        return m to insts
    }

    /** Runs [script] and returns what it changed, as AcroForm names. */
    private fun diff(insts: List<XInst>, script: () -> XfaJs.Outcome): Result {
        val before = insts.associateWith { it.value to it.presence }
        val o = script()
        val values = LinkedHashMap<String, String>(); val presence = LinkedHashMap<String, String>()
        insts.forEach { i ->
            val (v0, p0) = before.getValue(i)
            val p = i.dataPath ?: return@forEach
            val names = byPath[p] ?: return@forEach
            if (i.value != v0) names.forEach { n -> values[n] = toAcro(fields.getValue(n), i.value) }
            if (i.presence != p0) names.forEach { n -> presence[n] = i.presence }
        }
        val invalid = LinkedHashMap<String, String>()
        o.invalid.forEach { (i, msg) -> (i.dataPath ?: i.som).let { p -> byPath[p]?.firstOrNull() }?.let { invalid[it] = msg } }
        return Result(values, presence, invalid, o.cancelled, o.messages)
    }

    companion object {
        /** Key of a generated push button that stands for an XFA button with click scripts (our key). */
        const val KEY_CLICK = "SDXfaClick"
        /** Form events run before saving, printing and submitting. */
        val FORM_EVENTS = setOf("preSave", "prePrint", "preSubmit")

        /** Data value of an AcroForm value (the conventions of XfaGenerated.writeBack). */
        fun toXfa(e: Entry, v: String): String = when (e.type) {
            "RADIO" -> if (v == "Off") "" else v
            "CHECK" -> if (v.equals("true", true)) e.exports.getOrElse(0) { "1" } else e.exports.getOrElse(1) { "0" }
            "CHOICE" -> e.options.indexOf(v).takeIf { it >= 0 }?.let { e.exports.getOrNull(it) } ?: v
            else -> v
        }

        /** AcroForm value of an XFA data value. */
        fun toAcro(e: Entry, v: String): String = when (e.type) {
            "RADIO" -> v.ifEmpty { "Off" }
            "CHECK" -> (v == e.exports.getOrElse(0) { "1" }).toString()
            "CHOICE" -> e.exports.indexOf(v).takeIf { it >= 0 }?.let { e.options.getOrNull(it) } ?: v
            else -> v
        }

        fun parseMap(json: String): Map<String, Entry> = runCatching {
            Json.parseToJsonElement(json).jsonObject.mapNotNull { (name, el) ->
                val o = el.jsonObject
                val p = o["p"]?.jsonPrimitive?.content ?: return@mapNotNull null
                name to Entry(p, o["t"]?.jsonPrimitive?.content.orEmpty(),
                    o["o"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o["e"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
            }.toMap()
        }.getOrDefault(emptyMap())

        /** The runtime of the generated form [generated] (its source opened with [password], then without); null when not generated / no events. */
        suspend fun load(generated: File, password: String?): XfaEventRuntime? = withContext(Dispatchers.IO) {
            runCatching {
                val (srcPath, mapJson) = PdfOps.load(generated, password).use { d ->
                    val info = d.documentInformation ?: return@runCatching null
                    (info.getCustomMetadataValue(XfaGenerated.KEY_SOURCE) ?: return@runCatching null) to (info.getCustomMetadataValue(XfaGenerated.KEY_MAP) ?: "{}")
                }
                val src = File(srcPath).takeIf { it.exists() } ?: return@runCatching null
                val bytes = (runCatching { PdfOps.load(src, password) }.getOrNull() ?: PdfOps.load(src, null)).use { it.documentCatalog.acroForm?.xfa?.bytes } ?: return@runCatching null
                val (xml, data) = XfaSupport.dataRoot(bytes)
                val t = XfaTemplateParser.parse(xml) ?: return@runCatching null
                XfaEventRuntime(t, data, parseMap(mapJson)).takeIf { it.withEvents.isNotEmpty() || it.formEvents.isNotEmpty() }
            }.getOrNull()
        }
    }
}
