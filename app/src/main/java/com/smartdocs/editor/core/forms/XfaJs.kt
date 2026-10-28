package com.smartdocs.editor.core.forms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Runs the JavaScript `initialize`, `ready` / `docReady` and `calculate` scripts of an XFA form on the sandboxed Rhino runtime,
 * over a small XFA object model: every instance is an object with `rawValue`, `presence`, `name`, `index`,
 * `parent`, `className`, `isNull`, `access`, `resolveNode(som)`, `instanceManager.count` and its named children
 * as properties; `xfa.resolveNode`, `xfa.host.messageBox`, `xfa.layout.pageCount()`, `app.alert`. Names are
 * resolved like XFA does (the field's container, then its ancestors). FormCalc scripts are not run (the parser
 * keeps JavaScript only), their stored values stay. Errors and time-outs leave the values unchanged.
 */
object XfaJs {

    /**
     * [invalid]: instances whose validate test failed, with the message to show; [cancelled]: a form event script set
     * `xfa.event.cancelAction`; [messages]: texts the scripts showed with `xfa.host.messageBox` / `app.alert`.
     */
    data class Outcome(val changedValues: Int, val changedPresence: Int, val errors: Int,
                       val invalid: List<Pair<XInst, String>> = emptyList(), val cancelled: Boolean = false, val messages: List<String> = emptyList())

    /** What a plan can do: run a script on instance i (calc: its result becomes the value), set xfa.event, test a validate script. */
    private interface Runner {
        fun exec(i: Int, src: String, calc: Boolean)
        fun setEvent(newText: String, prevText: String)
        /** Runs [src] on instance i; false when it returned false (a failed scriptTest). Errors pass. */
        fun test(i: Int, src: String): Boolean
    }

    fun run(m: XMerged, eventMillis: Long = 200, totalMillis: Long = 3000): Outcome {
        if (!com.smartdocs.editor.core.security.JsPolicy.enabled) return Outcome(0, 0, 0)   // "Run JavaScript" off: stored values stay
        val all = XfaMerge.walk(m.root).toList()
        if (all.none { it.node.scripts.initialize != null || it.node.scripts.calculate != null || it.node.scripts.ready.isNotEmpty() }) return Outcome(0, 0, 0)
        return execute(all, eventMillis, totalMillis) { r ->
            all.forEachIndexed { i, n -> n.node.scripts.initialize?.let { r.exec(i, it, false) } }
            // form / layout ready and docReady: once, after initialize (the form is open and laid out)
            all.forEachIndexed { i, n -> n.node.scripts.ready.forEach { r.exec(i, it, false) } }
            repeat(2) { all.forEachIndexed { i, n -> n.node.scripts.calculate?.let { r.exec(i, it, true) } } }
        }
    }

    /**
     * An interactive event while filling: the [activity] scripts (exit, enter, change, click) of [target], with
     * `xfa.event.newText` / `prevText`, then the calculate scripts (twice, for dependent sums). Values and
     * presence changed by the scripts are written into the instances of [m] (compare before / after).
     */
    fun runEvent(m: XMerged, target: XInst, activity: String, newText: String = target.value, prevText: String = "",
                 eventMillis: Long = 200, totalMillis: Long = 2000): Outcome {
        if (!com.smartdocs.editor.core.security.JsPolicy.enabled) return Outcome(0, 0, 0)
        val scripts = target.node.scripts.events[activity].orEmpty()
        if (scripts.isEmpty()) return Outcome(0, 0, 0)
        val all = XfaMerge.walk(m.root).toList()
        val ti = all.indexOf(target).takeIf { it >= 0 } ?: return Outcome(0, 0, 0)
        return execute(all, eventMillis, totalMillis) { r ->
            r.setEvent(newText, prevText)
            scripts.forEach { r.exec(ti, it, false) }
            repeat(2) { all.forEachIndexed { i, n -> n.node.scripts.calculate?.let { r.exec(i, it, true) } } }
        }
    }

    /**
     * FINAL_CHECK_2 XFA validate: the `validate` event scripts of [target], then its `<validate><script>` test.
     * [Outcome.invalid] holds the target with its message ("" = the default text) when the test returned false.
     */
    fun runValidate(m: XMerged, target: XInst, eventMillis: Long = 200, totalMillis: Long = 1000): Outcome {
        if (!com.smartdocs.editor.core.security.JsPolicy.enabled) return Outcome(0, 0, 0)
        val sc = target.node.scripts
        val events = sc.events["validate"].orEmpty()
        if (events.isEmpty() && sc.validate == null) return Outcome(0, 0, 0)
        val all = XfaMerge.walk(m.root).toList()
        val ti = all.indexOf(target).takeIf { it >= 0 } ?: return Outcome(0, 0, 0)
        return execute(all, eventMillis, totalMillis) { r ->
            r.setEvent(target.value, target.value)
            events.forEach { r.exec(ti, it, false) }
            sc.validate?.let { if (!r.test(ti, it)) r.fail(ti, sc.validateMessage) }
        }
    }

    /**
     * FINAL_CHECK_2 XFA form events: the [activity] scripts (preSave, prePrint, preSubmit) of every node in document
     * order, then the calculate scripts. With [validateAll] (preSubmit, as before a submission in Acrobat) every
     * field's validate test runs first. A script may cancel the action with `xfa.event.cancelAction = true`.
     */
    fun runFormEvent(m: XMerged, activity: String, validateAll: Boolean = false, eventMillis: Long = 200, totalMillis: Long = 3000): Outcome {
        if (!com.smartdocs.editor.core.security.JsPolicy.enabled) return Outcome(0, 0, 0)
        val all = XfaMerge.walk(m.root).toList()
        val any = all.any { it.node.scripts.events[activity]?.isNotEmpty() == true || validateAll && it.node.scripts.validate != null }
        if (!any) return Outcome(0, 0, 0)
        return execute(all, eventMillis, totalMillis) { r ->
            if (validateAll) all.forEachIndexed { i, n -> val sc = n.node.scripts; sc.validate?.let { if (!r.test(i, it)) r.fail(i, sc.validateMessage) } }
            all.forEachIndexed { i, n -> n.node.scripts.events[activity]?.forEach { r.exec(i, it, false) } }
            repeat(2) { all.forEachIndexed { i, n -> n.node.scripts.calculate?.let { r.exec(i, it, true) } } }
        }
    }

    /** A Runner that also records validate failures. */
    private interface Plan : Runner { fun fail(i: Int, message: String) }

    private fun execute(all: List<XInst>, eventMillis: Long, totalMillis: Long, plan: (Plan) -> Unit): Outcome {
        val ids = HashMap<XInst, Int>(); all.forEachIndexed { i, n -> ids[n] = i }
        val tree = JsonArray(all.map { n ->
            JsonObject(mapOf(
                "n" to JsonPrimitive(n.name), "v" to JsonPrimitive(n.value), "p" to JsonPrimitive(n.presence),
                "k" to JsonPrimitive(kind(n)), "par" to JsonPrimitive(n.parent?.let { ids[it] } ?: -1),
                "c" to JsonArray(n.children.map { JsonPrimitive(ids[it]!!) }),
                "i" to JsonPrimitive(Regex("""\[(\d+)]$""").find(n.som)?.groupValues?.get(1)?.toIntOrNull() ?: 0),
            ))
        }).toString()
        val sandbox = JsSandbox()
        var errors = 0
        val invalid = ArrayList<Pair<XInst, String>>()
        val started = System.nanoTime()
        val out = sandbox.run(totalMillis) { cx ->
            val scope = cx.initSafeStandardObjects(null, false)
            cx.evaluateString(scope, LIB, "xfa-om.js", 1, null)
            val x = ScriptableObject.getProperty(scope, "__xfa") as Scriptable
            ScriptableObject.callMethod(cx, x, "init", arrayOf<Any>(tree))
            fun runScript(i: Int, src: String, calc: Boolean): Any? {
                if ((System.nanoTime() - started) / 1_000_000 > totalMillis) return null
                JsSandbox.deadline(cx, eventMillis)
                return try { ScriptableObject.callMethod(cx, x, "exec", arrayOf<Any>(i, src, calc)) }
                catch (e: Throwable) { if (!JsSandbox.scriptError(e)) throw e; errors++; null }
            }
            plan(object : Plan {
                override fun exec(i: Int, src: String, calc: Boolean) { runScript(i, src, calc) }
                override fun setEvent(newText: String, prevText: String) {
                    val ev = ScriptableObject.getProperty(ScriptableObject.getProperty(scope, "xfa") as Scriptable, "event") as Scriptable
                    ScriptableObject.putProperty(ev, "newText", newText); ScriptableObject.putProperty(ev, "prevText", prevText)
                    ScriptableObject.putProperty(ev, "change", newText.removePrefix(prevText))
                    ScriptableObject.putProperty(ev, "fullText", newText)
                }
                override fun test(i: Int, src: String): Boolean = when (val v = runScript(i, src, false)) {
                    is Boolean -> v
                    is Number -> v.toDouble() != 0.0
                    else -> true          // undefined / error: the value passes, as in Acrobat
                }
                override fun fail(i: Int, message: String) { invalid += all[i] to message }
            })
            JsSandbox.deadline(cx, eventMillis)
            Context.toString(ScriptableObject.callMethod(cx, x, "dump", emptyArray()))
        }
        var cv = 0; var cp = 0
        val dump = Json.parseToJsonElement(out).jsonObject
        dump["c"]?.jsonObject?.forEach { (k, v) ->
            val n = all.getOrNull(k.toIntOrNull() ?: -1) ?: return@forEach
            val o = v.jsonObject
            o["v"]?.jsonPrimitive?.content?.let { if (it != n.value) { n.value = it; cv++ } }
            o["p"]?.jsonPrimitive?.content?.let { if (it != n.presence) { n.presence = it; cp++ } }
        }
        val cancelled = dump["x"]?.jsonPrimitive?.content == "true"
        val messages = (dump["m"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        return Outcome(cv, cp, errors, invalid, cancelled, messages)
    }

    private fun kind(n: XInst) = when (val t = n.node) {
        is XFieldT -> if (t.ui == XUi.NUMERIC) "num" else if (t.ui == XUi.CHECK) "check" else "field"
        is XDraw -> "draw"
        is XSub -> if (t.isExclGroup) "exclGroup" else "subform"
    }

    private const val LIB = $$"""
(function (G) {
var N = [], O = [], changed = {};
function nstr(n) { if (isNaN(n) || !isFinite(n)) return ""; if (n === Math.floor(n) && Math.abs(n) < 1e15) return String(n); return String(parseFloat(n.toPrecision(12))); }
function mark(i, k, v) { (changed[i] = changed[i] || {})[k] = v; }
function Node(i) { this.__i = i; }
function def(k, get, set) { Object.defineProperty(Node.prototype, k, { get: get, set: set || function () {}, configurable: true }); }
def("rawValue", function () {
  var n = N[this.__i]; if (n.k === "subform" || n.k === "draw" && n.v === "") return null;
  if (n.v === "") return null;
  if (n.k === "num") { var x = Number(n.v); return isNaN(x) ? n.v : x; }
  return n.v;
}, function (x) {
  var n = N[this.__i]; var s = x === null || x === undefined ? "" : (typeof x === "number" ? nstr(x) : (typeof x === "boolean" ? (x ? "1" : "0") : String(x)));
  if (n.v !== s) { n.v = s; mark(this.__i, "v", s); }
});
def("formattedValue", function () { var v = this.rawValue; return v === null ? "" : String(v); }, function (x) { this.rawValue = x; });
def("isNull", function () { return N[this.__i].v === ""; });
def("presence", function () { return N[this.__i].p; }, function (x) { var n = N[this.__i]; x = String(x); if (n.p !== x) { n.p = x; mark(this.__i, "p", x); } });
def("name", function () { return N[this.__i].n; });
def("index", function () { return N[this.__i].i; });
def("className", function () { return N[this.__i].k === "num" || N[this.__i].k === "check" ? "field" : N[this.__i].k; });
def("parent", function () { var p = N[this.__i].par; return p >= 0 ? O[p] : null; });
def("access", function () { return "open"; }, function () {});
def("mandatory", function () { return "disabled"; }, function () {});
def("somExpression", function () { var a = [], i = this.__i; while (i >= 0) { a.unshift(N[i].n + "[" + N[i].i + "]"); i = N[i].par; } return "xfa[0].form[0]." + a.join("."); });
def("instanceManager", function () {
  var me = N[this.__i], p = me.par, cnt = 0;
  if (p >= 0) for (var k = 0; k < N[p].c.length; k++) if (N[N[p].c[k]].n === me.n) cnt++;
  return { count: cnt, min: 0, max: -1, addInstance: function () { return null; }, removeInstance: function () {}, setInstances: function () {} };
});
def("nodes", function () { var me = N[this.__i], a = []; for (var k = 0; k < me.c.length; k++) a.push(O[me.c[k]]); a.item = function (j) { return a[j]; }; return a; });
Node.prototype.resolveNode = function (som) { return resolve(String(som), this.__i); };
Node.prototype.resolveNodes = function (som) { var r = resolve(String(som), this.__i); var a = r ? [r] : []; a.item = function (j) { return a[j]; }; return a; };
Node.prototype.execEvent = function () {};
Node.prototype.execCalculate = function () {};
Node.prototype.execInitialize = function () {};
Node.prototype.clearItems = function () {};
Node.prototype.addItem = function () {};
Node.prototype.boundItem = function (v) { return v; };
Node.prototype.getAttribute = function () { return ""; };
Node.prototype.setAttribute = function () {};

function child(i, name, idx) {
  var c = N[i].c, seen = 0;
  for (var k = 0; k < c.length; k++) if (N[c[k]].n === name) { if (seen === idx) return c[k]; seen++; }
  // unnamed (transparent) containers do not hide their children
  for (var k2 = 0; k2 < c.length; k2++) if (N[c[k2]].n === "" ) { var r = child(c[k2], name, idx); if (r >= 0) return r; }
  return -1;
}
function resolve(som, from) {
  som = som.replace(/^xfa\[0\]\.|^xfa\./, "").replace(/^form\[0\]\.|^form\.|^\$form\./, "");
  var parts = som.split("."), cur = -1;
  for (var p = 0; p < parts.length; p++) {
    var m = /^(.*?)(\[(\d+|\*)\])?$/.exec(parts[p]), nm = m[1], ix = m[3] && m[3] !== "*" ? parseInt(m[3], 10) : 0;
    if (p === 0) {
      if (nm === "$") { cur = from; continue; }
      if (nm === "this") { cur = from; continue; }
      if (N[0].n === nm) { cur = 0; continue; }
      // search from the current node upwards (XFA scoping)
      var s = from; cur = -1;
      while (s >= 0 && cur < 0) { cur = child(s, nm, ix); s = N[s].par; }
      if (cur < 0) return null;
      continue;
    }
    if (nm === "parent") { cur = N[cur].par; if (cur < 0) return null; continue; }
    cur = child(cur, nm, ix); if (cur < 0) return null;
  }
  return cur >= 0 ? O[cur] : null;
}
G.__xfa = {
  init: function (json) {
    N = JSON.parse(json); O = [];
    for (var i = 0; i < N.length; i++) O.push(new Node(i));
    var f = {}; if (N.length) f[N[0].n] = O[0]; G.xfa.form = f;
    for (var j = 0; j < N.length; j++) {
      var seen = {};
      for (var k = 0; k < N[j].c.length; k++) {
        var cn = N[N[j].c[k]].n;
        if (cn && !seen[cn] && !(cn in Node.prototype)) { seen[cn] = true; O[j][cn] = O[N[j].c[k]]; }
      }
    }
  },
  exec: function (i, __src, calc) {
    var __c = [], p = N[i].par;
    while (p >= 0) { __c.unshift(O[p]); p = N[p].par; }
    if (N[i].k === "subform") __c.push(O[i]);   // a subform's script names its own children (form events on the root)
    var code = "(function(){";
    for (var k = 0; k < __c.length; k++) code += "with(__c[" + k + "]){";
    code += "return eval(__src);";
    for (var k2 = 0; k2 < __c.length; k2++) code += "}";
    code += "})";
    var r = eval(code).call(O[i]);
    if (calc && r !== undefined && N[i].k !== "subform") O[i].rawValue = r;
    return typeof r === "boolean" || typeof r === "number" ? r : undefined;
  },
  dump: function () { return JSON.stringify({ c: changed, x: G.xfa.event.cancelAction === true, m: msgs }); },
};
var msgs = [];
function say(t) { if (t !== undefined && t !== null && msgs.length < 20) msgs.push(String(t)); return 1; }
var host = { messageBox: say, setFocus: function () {}, resetData: function () {}, beep: function () {},
  numPages: 1, currentPage: 0, name: "Reader", platform: "Android", version: "11", appType: "Reader", language: "ar", title: "",
  response: function () { return null; }, gotoURL: function () {}, print: function () {}, exportData: function () {}, importData: function () {} };
G.xfa = { host: host, form: null, layout: { pageCount: function () { return 1; }, page: function () { return 1; }, absPageCount: function () { return 1; },
  relayout: function () {}, sheetCount: function () { return 1; } }, event: { newText: "", prevText: "", change: "", cancelAction: false },
  resolveNode: function (s) { return resolve(String(s), 0); }, resolveNodes: function (s) { var r = resolve(String(s), 0); return r ? [r] : []; },
  record: null, datasets: null, data: null, util: {}, signature: {}, connectionSet: {} };
G.app = { alert: say, beep: function () {}, viewerType: "Reader", viewerVersion: 11 };
G.console = { println: function () {} };
G.event = {};
})(this);
"""
}
