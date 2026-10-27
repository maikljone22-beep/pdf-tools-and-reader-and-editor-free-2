package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.forms.JsSandbox
import com.smartdocs.editor.core.forms5.AcroJsAf
import com.smartdocs.editor.core.forms5.AcroJsLib
import com.smartdocs.editor.core.forms5.FormScripts
import com.smartdocs.editor.core.forms5.JsField
import com.smartdocs.editor.core.security.JsPolicy
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File

/** Document triggers of the catalog /AA (Nitro "Document Actions"). */
enum class DocTrigger(val key: String) { WILL_CLOSE("WC"), WILL_SAVE("WS"), DID_SAVE("DS"), WILL_PRINT("WP"), DID_PRINT("DP") }

/** Document-level scripts (name → source, in /Names /JavaScript) and the document trigger scripts. */
data class DocScripts(val scripts: List<Pair<String, String>> = emptyList(), val triggers: Map<DocTrigger, String> = emptyMap())

/**
 * Editing the document JavaScript (Nitro "Document level JavaScript" + "Document Actions"). This only stores
 * scripts: whether they run is decided by the "Run JavaScript" setting ([JsPolicy]) at the places that run them.
 * Trigger scripts that are not plain JavaScript actions (or other action types) are kept unchanged.
 */
object DocJsStore {
    private val JAVASCRIPT = COSName.getPDFName("JavaScript")
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    fun read(doc: PDDocument): DocScripts {
        val cat = doc.documentCatalog.cosObject
        val out = ArrayList<Pair<String, String>>()
        val names = deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary
        fun walk(node: COSDictionary?, depth: Int) {
            if (node == null || depth > 12 || out.size > 500) return
            (deref(node.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { arr ->
                var i = 0
                while (i + 1 < arr.size()) {
                    val n = (deref(arr.getObject(i)) as? COSString)?.string ?: ""
                    val a = deref(arr.getObject(i + 1)) as? COSDictionary
                    NavActions.flatten(a).firstOrNull { it.getNameAsString(COSName.S) == "JavaScript" }?.let { d -> out += n to (NavActions.jsText(d.getDictionaryObject(COSName.JS)) ?: "") }
                    i += 2
                }
            }
            (deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) walk(deref(kids.getObject(k)) as? COSDictionary, depth + 1) }
        }
        walk(deref(names?.getDictionaryObject(JAVASCRIPT)) as? COSDictionary, 0)
        val aa = deref(cat.getDictionaryObject(COSName.AA)) as? COSDictionary
        val trig = HashMap<DocTrigger, String>()
        for (t in DocTrigger.values()) {
            val a = deref(aa?.getDictionaryObject(t.key)) as? COSDictionary ?: continue
            if (a.getNameAsString(COSName.S) == "JavaScript") NavActions.jsText(a.getDictionaryObject(COSName.JS))?.let { trig[t] = it }
        }
        return DocScripts(out, trig)
    }

    suspend fun load(src: File, password: String?): DocScripts = withContext(Dispatchers.IO) { E5Io.load(src, password).use { read(it) } }

    private fun jsAction(src: String) = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("Action")); setName(COSName.S, "JavaScript"); setString(COSName.JS, src) }

    /**
     * Write [s]: the /Names /JavaScript tree is rebuilt (sorted by name, as a name tree must be); each trigger in
     * [s] with a blank script is removed, other /AA entries (non-JavaScript actions) stay. New dictionaries are
     * set into the catalog, so an incremental save of a signed file writes them.
     */
    fun write(doc: PDDocument, s: DocScripts) {
        val cat = doc.documentCatalog.cosObject
        val names = (deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.let { COSDictionary(it) } ?: COSDictionary()
        val scripts = s.scripts.filter { it.first.isNotBlank() }.distinctBy { it.first }.sortedBy { it.first }
        if (scripts.isEmpty()) names.removeItem(JAVASCRIPT)
        else names.setItem(JAVASCRIPT, COSDictionary().apply {
            setItem(COSName.NAMES, COSArray().apply { scripts.forEach { (n, src) -> add(COSString(n)); add(jsAction(src)) } })
        })
        if (names.size() == 0) cat.removeItem(COSName.NAMES) else cat.setItem(COSName.NAMES, names)
        val aa = (deref(cat.getDictionaryObject(COSName.AA)) as? COSDictionary)?.let { COSDictionary(it) } ?: COSDictionary()
        for (t in DocTrigger.values()) {
            val src = s.triggers[t]
            val cur = deref(aa.getDictionaryObject(t.key)) as? COSDictionary
            val isJs = cur == null || cur.getNameAsString(COSName.S) == "JavaScript"
            if (src.isNullOrBlank()) { if (isJs) aa.removeItem(COSName.getPDFName(t.key)) }
            else aa.setItem(COSName.getPDFName(t.key), jsAction(src))
        }
        if (aa.size() == 0) cat.removeItem(COSName.AA) else cat.setItem(COSName.AA, aa)
    }

    suspend fun save(src: File, out: File, password: String?, s: DocScripts) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, _ -> write(doc, s) }
    }

    /** 1-based line → character offset in [text] (for "Go to line"). */
    fun lineOffset(text: String, line: Int): Int {
        if (line <= 1) return 0
        var n = 1
        for (i in text.indices) if (text[i] == '\n') { n++; if (n == line) return i + 1 }
        return text.length
    }

    /** A JavaScript syntax check (Rhino compile only; nothing runs). Null = fine, else the message with its line. */
    fun syntaxError(script: String): String? {
        val f = JsSandbox()
        val cx = f.enterContext()
        return try { cx.compileString(script, "doc.js", 1, null); null }
        catch (e: org.mozilla.javascript.EvaluatorException) { "${e.lineNumber()}: ${e.details()}" }
        catch (e: Throwable) { if (e is VirtualMachineError && e !is StackOverflowError) throw e; e.message ?: e.toString() }
        finally { Context.exit() }
    }
}

/** Output of one console run: `console.println` lines, `app.alert` texts, the value of the last expression, the error. */
data class ConsoleResult(val log: List<String>, val alerts: List<String>, val value: String?, val error: String?, val docErrors: List<String> = emptyList())

/**
 * JavaScript console (Nitro "JavaScript console"): runs a script in the same sandbox as the form scripts
 * ([JsSandbox] + the Acrobat object model of [AcroJsLib]) against the open document's fields and document-level
 * scripts. Nothing is written back to the file. Refuses when "Run JavaScript" is off ([JsPolicy]).
 */
object DocJsConsole {
    class Disabled : Exception("JavaScript is turned off")

    suspend fun run(src: File, password: String?, script: String, millis: Long = 2000): ConsoleResult = withContext(Dispatchers.IO) {
        if (!JsPolicy.enabled) throw Disabled()
        val (fields, docScripts, pages) = E5Io.load(src, password).use { doc -> Triple(fields(doc), read(doc), doc.numberOfPages) }
        run(fields, docScripts, pages, src.name, script, millis)
    }

    private fun read(doc: PDDocument) = DocJsStore.read(doc).scripts.map { it.second }

    private fun fields(doc: PDDocument): List<JsField> = runCatching {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        form.fieldTree.mapNotNull { f ->
            val type = when (f) {
                is PDTextField -> "text"; is PDCheckBox -> "checkbox"; is PDRadioButton -> "radiobutton"; is PDComboBox -> "combobox"
                is PDListBox -> "listbox"; is PDSignatureField -> "signature"
                is com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton -> "button"
                else -> return@mapNotNull null
            }
            JsField(f.fullyQualifiedName ?: return@mapNotNull null, type, value = runCatching { f.valueAsString }.getOrDefault(""), readOnly = f.isReadOnly, required = f.isRequired)
        }
    }.getOrDefault(emptyList())

    /** Run [script] with [fields] and [docScripts] (JVM-testable core). */
    fun run(fields: List<JsField>, docScripts: List<String>, numPages: Int, fileName: String, script: String, millis: Long = 2000): ConsoleResult {
        if (!JsPolicy.enabled) throw Disabled()
        val sandbox = JsSandbox()
        val cx = sandbox.enterContext()
        try {
            JsSandbox.deadline(cx, millis)
            val scope = cx.initSafeStandardObjects(null, false)
            cx.evaluateString(scope, AcroJsLib.CORE, "acro-core.js", 1, null)
            cx.evaluateString(scope, AcroJsAf.SOURCE, "acro-af.js", 1, null)
            val sd = ScriptableObject.getProperty(scope, "__sd") as Scriptable
            ScriptableObject.putProperty(sd, "numPages", numPages.toDouble())
            ScriptableObject.putProperty(sd, "fileName", fileName)
            ScriptableObject.callMethod(cx, sd, "init", arrayOf<Any>(FormScripts.fieldsJson(fields)))
            val docErrors = ArrayList<String>()
            for ((i, src) in docScripts.withIndex()) {
                try { JsSandbox.deadline(cx, millis); cx.evaluateString(scope, src, "doc-$i.js", 1, null) }
                catch (e: Throwable) { if (!JsSandbox.scriptError(e)) throw e; docErrors += e.message ?: e.toString() }
            }
            ScriptableObject.callMethod(cx, sd, "begin", arrayOf<Any>("Console", "", "{}"))
            JsSandbox.deadline(cx, millis)
            var value: String? = null
            var error: String? = null
            try {
                val r = cx.evaluateString(scope, script, "console.js", 1, null)
                value = if (r == null || r is Undefined) null else Context.toString(r)
            } catch (e: Throwable) {
                if (!JsSandbox.scriptError(e)) throw e
                error = when (e) {
                    is org.mozilla.javascript.RhinoException -> (e.details() ?: e.message ?: e.toString()) + (if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else "")
                    is com.smartdocs.editor.core.forms.JsTimeout -> "time limit"
                    else -> e.message ?: e.toString()
                }
            }
            JsSandbox.deadline(cx, millis)
            val log = runCatching {
                (Json.parseToJsonElement(Context.toString(cx.evaluateString(scope, "JSON.stringify(__sd.log || [])", "log.js", 1, null))) as JsonArray).map { (it as JsonPrimitive).content }
            }.getOrDefault(emptyList())
            val alerts = runCatching {
                val end = Json.parseToJsonElement(Context.toString(ScriptableObject.callMethod(cx, sd, "end", arrayOf<Any>("")))).jsonObject
                (end["a"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("t")?.let { t -> (t as JsonPrimitive).content } }.orEmpty()
            }.getOrDefault(emptyList())
            return ConsoleResult(log, alerts, value, error, docErrors)
        } finally { Context.exit() }
    }
}
