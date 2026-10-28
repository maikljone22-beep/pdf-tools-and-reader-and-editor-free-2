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
    data class Result(val values: Map<String, String>, val presence: Map<String, String>) { val isEmpty get() = values.isEmpty() && presence.isEmpty() }

    private val byPath: Map<String, List<String>> = fields.entries.groupBy({ it.value.path }, { it.key })
    private val withEvents: Set<String> by lazy {
        val m = XfaMerge.merge(template, data)
        XfaMerge.walk(m.root).filter { it.node.scripts.events.isNotEmpty() }.mapNotNull { it.dataPath }.toSet()
    }

    /** Whether the field [name] has a script for [activity] (cheap check before [fire]). */
    fun has(name: String, activity: String): Boolean {
        val p = fields[name]?.path ?: return false
        if (p !in withEvents) return false
        val m = XfaMerge.merge(template, data)
        return XfaMerge.walk(m.root).firstOrNull { it.dataPath == p }?.node?.scripts?.events?.get(activity)?.isNotEmpty() == true
    }

    /** Run [activity] of the field [name] with the form's [current] values (AcroForm names); null when nothing ran. */
    fun fire(name: String, activity: String, current: Map<String, String>, prevText: String = ""): Result? {
        val path = fields[name]?.path ?: return null
        if (path !in withEvents) return null
        val m = XfaMerge.merge(template, data)
        val insts = XfaMerge.walk(m.root).toList()
        // the instances get the values the user sees now
        insts.forEach { i -> i.dataPath?.let { p -> byPath[p]?.firstOrNull()?.let { n -> current[n]?.let { v -> i.value = toXfa(fields.getValue(n), v) } } } }
        val target = insts.firstOrNull { it.dataPath == path } ?: return null
        val before = insts.associateWith { it.value to it.presence }
        XfaJs.runEvent(m, target, activity, newText = target.value, prevText = prevText)
        val values = LinkedHashMap<String, String>(); val presence = LinkedHashMap<String, String>()
        insts.forEach { i ->
            val (v0, p0) = before.getValue(i)
            val p = i.dataPath ?: return@forEach
            val names = byPath[p] ?: return@forEach
            if (i.value != v0) names.forEach { n -> values[n] = toAcro(fields.getValue(n), i.value) }
            if (i.presence != p0) names.forEach { n -> presence[n] = i.presence }
        }
        return Result(values, presence)
    }

    companion object {
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
                XfaEventRuntime(t, data, parseMap(mapJson)).takeIf { it.withEvents.isNotEmpty() }
            }.getOrNull()
        }
    }
}
