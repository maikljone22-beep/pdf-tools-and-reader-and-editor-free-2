package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.forms.JsSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The "Execute JavaScript" batch command (Nitro / Acrobat): a user script runs once per file in the Rhino sandbox
 * ([JsSandbox]: no Java access, time limit). The script sees an Acrobat-like `this` / `doc`: `info` (Title, Author,
 * Subject, Keywords, Creator, Producer and custom keys), `title`/`author`/`subject`/`keywords`, `numPages`,
 * `documentFileName`, `numFields`, `getNthFieldName(i)` and `getField(name).value`; `console.println` and
 * `app.alert` write to the batch log. Changed info and field values are returned; the runner writes them back.
 * Plain Kotlin + Rhino (unit-tested).
 */
object JsBatch {

    data class DocData(val fileName: String, val numPages: Int, val info: Map<String, String>, val fields: Map<String, String>)

    data class Result(val info: Map<String, String>, val fields: Map<String, String>, val log: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    private const val PRELUDE = """
var __d = JSON.parse(__in);
var __log = [];
var console = { println: function (s) { __log.push(String(s)); }, show: function () {}, clear: function () {} };
var app = {
  alert: function (m) { __log.push(String(m !== null && typeof m === 'object' && m.cMsg !== undefined ? m.cMsg : m)); return 1; },
  beep: function () {}, viewerType: 'Reader', viewerVersion: 11, platform: 'ANDROID', language: 'ARA'
};
var __fields = {};
Object.keys(__d.fields).forEach(function (k) { __fields[k] = { name: k, value: __d.fields[k], get valueAsString() { return this.value == null ? '' : String(this.value); } }; });
var __names = Object.keys(__fields);
var doc = {
  info: __d.info,
  numPages: __d.numPages,
  documentFileName: __d.fileName,
  path: '/' + __d.fileName,
  get title() { return this.info.Title; }, set title(v) { this.info.Title = v; },
  get author() { return this.info.Author; }, set author(v) { this.info.Author = v; },
  get subject() { return this.info.Subject; }, set subject(v) { this.info.Subject = v; },
  get keywords() { return this.info.Keywords; }, set keywords(v) { this.info.Keywords = v; },
  get numFields() { return __names.length; },
  getNthFieldName: function (i) { return i >= 0 && i < __names.length ? __names[i] : null; },
  getField: function (n) { return Object.prototype.hasOwnProperty.call(__fields, n) ? __fields[n] : null; }
};
function __out() {
  var f = {}; __names.forEach(function (k) { var v = __fields[k].value; f[k] = v == null ? '' : String(v); });
  var info = {}; Object.keys(doc.info).forEach(function (k) { var v = doc.info[k]; if (v !== undefined && v !== null) info[k] = String(v); });
  return JSON.stringify({ info: info, fields: f, log: __log });
}
"""

    /** Run [script] against [d]. Script errors (syntax, runtime, time limit) are thrown as [IllegalArgumentException]. */
    fun run(script: String, d: DocData, millis: Long = 4000): Result {
        val input = buildJsonObject {
            put("fileName", d.fileName); put("numPages", d.numPages)
            put("info", buildJsonObject { d.info.forEach { (k, v) -> put(k, v) } })
            put("fields", buildJsonObject { d.fields.forEach { (k, v) -> put(k, v) } })
        }.toString()
        val out = try {
            JsSandbox().run(millis) { cx ->
                val scope = cx.initSafeStandardObjects()
                scope.put("__in", scope, input)
                cx.evaluateString(scope, PRELUDE, "prelude", 1, null)
                cx.evaluateString(scope, "(function () {\n$script\n}).call(doc);", "batch", 1, null)
                org.mozilla.javascript.Context.toString(cx.evaluateString(scope, "__out()", "out", 1, null))
            }
        } catch (e: Throwable) {
            if (!JsSandbox.scriptError(e)) throw e
            throw IllegalArgumentException("JavaScript: " + (e.message ?: e.javaClass.simpleName), e)
        }
        val o = json.parseToJsonElement(out).jsonObject
        fun map(k: String) = (o[k] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        return Result(map("info"), map("fields"), o["log"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
    }

    /** Info / field entries of [after] that differ from [before] (what has to be written back). */
    fun changed(before: Map<String, String>, after: Map<String, String>): Map<String, String> = after.filter { (k, v) -> before[k] != v }
}
