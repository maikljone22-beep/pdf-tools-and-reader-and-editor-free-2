package com.smartdocs.editor.core.forms

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Calculation of one field: what to compute and from which fields. */
sealed class CalcSpec {
    /** AFSimple_Calculate("SUM"|"PRD"|"AVG"|"MIN"|"MAX", [fields]) */
    data class Simple(val op: String, val fields: List<String>) : CalcSpec()
    /** Simple field notation: the expression between the BVCALC and EVCALC markers Acrobat writes in a JS comment. */
    data class Sfn(val expression: String) : CalcSpec()
    /** Custom script reduced to `var x = …; event.value = …` with getField() references. */
    data class Script(val statements: List<Pair<String?, String>>, val refs: Map<String, String>) : CalcSpec()
}

/** Scripts attached to a field, parsed. [calcSource] keeps the original JS for display. */
data class FieldScripts(
    val name: String,
    val calc: CalcSpec?,
    val calcSource: String?,
    val format: FormatSpec?,
    val keystroke: KeystrokeKind,
    val unsupportedCalc: Boolean = false,
)

/**
 * Acrobat-style form calculations implemented in Kotlin (no JavaScript engine). Covers the AF helpers
 * Acrobat generates from its field-properties UI plus the common hand-written
 * `event.value = this.getField("a").value * this.getField("b").value` pattern.
 */
class FormCalcModel(val scripts: Map<String, FieldScripts>, val order: List<String>, private val allFields: List<String>) {

    val hasCalculations: Boolean get() = order.isNotEmpty()
    fun isCalculated(name: String) = scripts[name]?.calc != null

    private fun expand(name: String): List<String> =
        if (name in allFields) listOf(name) else allFields.filter { it.startsWith("$name.") }

    private fun num(values: Map<String, String>, name: String): Double =
        expand(name).sumOf { AfNumbers.parse(values[it]) ?: 0.0 }

    fun compute(spec: CalcSpec, values: Map<String, String>): Double? = when (spec) {
        is CalcSpec.Simple -> {
            val nums = spec.fields.flatMap { expand(it) }.map { AfNumbers.parse(values[it]) ?: 0.0 }
            when (spec.op.uppercase()) {
                "SUM" -> nums.sum()
                "PRD" -> if (nums.isEmpty()) 0.0 else nums.fold(1.0) { a, b -> a * b }
                "AVG" -> if (nums.isEmpty()) 0.0 else nums.average()
                "MIN" -> nums.minOrNull() ?: 0.0
                "MAX" -> nums.maxOrNull() ?: 0.0
                else -> null
            }
        }
        is CalcSpec.Sfn -> AfExpr.eval(spec.expression, sfn = true) { id -> if (expand(id).isEmpty()) null else num(values, id) }
        is CalcSpec.Script -> {
            val env = HashMap<String, Double>()
            var result: Double? = null
            for ((target, expr) in spec.statements) {
                val v = AfExpr.eval(expr, sfn = false) { id -> env[id] ?: spec.refs[id]?.let { num(values, it) } } ?: return null
                if (target == null) result = v else env[target] = v
            }
            result
        }
    }

    /**
     * Run all calculations in calculation order (/CO) over [values] (raw values by fully-qualified name) and
     * return the new raw values of the calculated fields. Two passes so that calculated fields missing from
     * /CO still see up-to-date inputs.
     */
    fun recalculate(values: Map<String, String>): Map<String, String> {
        val work = HashMap(values)
        val changed = LinkedHashMap<String, String>()
        repeat(2) {
            for (name in order) {
                val spec = scripts[name]?.calc ?: continue
                val v = compute(spec, work) ?: continue
                val raw = AfNumbers.raw(v)
                if (work[name] != raw) { work[name] = raw; changed[name] = raw }
            }
        }
        return changed.filter { (k, v) -> values[k] != v }
    }

    fun display(name: String, raw: String): Display = AfFormat.format(scripts[name]?.format, raw)

    /** Formatted text for every field that has a format action (used to regenerate appearances). */
    fun displayMap(values: Map<String, String>): Map<String, String> =
        scripts.values.filter { it.format != null }.mapNotNull { s -> values[s.name]?.let { raw -> s.name to AfFormat.format(s.format, raw).text } }.toMap()

    companion object {
        val EMPTY = FormCalcModel(emptyMap(), emptyList(), emptyList())
    }
}

object FormCalc {

    private fun jsOf(action: COSBase?): String? {
        val d = action as? COSDictionary ?: return null
        if (d.getCOSName(COSName.S)?.name != "JavaScript") return null
        return when (val js = d.getDictionaryObject(COSName.JS)) {
            is COSString -> js.string
            is COSStream -> runCatching { js.toTextString() }.getOrNull()
            else -> null
        }
    }

    private val sfnRegex = Regex("""BVCALC\s*([\s\S]*?)\s*EVCALC""")
    private val fieldRef = Regex("""(?:this\s*\.\s*)?getField\s*\(\s*(["'])((?:\\.|(?!\1).)*)\1\s*\)\s*\.\s*(?:value|valueAsString|rawValue)""")

    private fun stripComments(js: String): String =
        js.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""(?m)//.*$"""), " ")

    /** Parse a calculate script. Returns null + unsupported flag when it cannot be reduced. */
    fun parseCalc(js: String): Pair<CalcSpec?, Boolean> {
        sfnRegex.find(js)?.let { return CalcSpec.Sfn(it.groupValues[1].trim()) to false }
        val code = stripComments(js)
        Regex("""AFSimple_Calculate\s*\(""").find(code)?.let { m ->
            var depth = 1; var i = m.range.last + 1; var quote: Char? = null
            while (i < code.length && depth > 0) {
                val c = code[i]
                if (quote != null) { if (c == '\\') i++ else if (c == quote) quote = null } else when (c) { '"', '\'' -> quote = c; '(' -> depth++; ')' -> depth-- }
                i++
            }
            val a = AfFormat.args(code.substring(m.range.last + 1, (i - 1).coerceAtLeast(m.range.last + 1)))
            val op = AfFormat.unquote(a.getOrElse(0) { "\"SUM\"" })
            val list = a.getOrNull(1)?.trim() ?: return null to true
            val names = when {
                list.startsWith("new Array") || list.startsWith("[") -> {
                    val inner = list.substringAfter('(', list.substringAfter('[')).removeSuffix(")").removeSuffix("]")
                    AfFormat.args(inner).map { AfFormat.unquote(it) }
                }
                else -> AfFormat.unquote(list).split(',').map { it.trim() }
            }.filter { it.isNotBlank() }
            return CalcSpec.Simple(op, names) to false
        }
        // custom: replace getField("x").value with placeholders, then accept only `var a = expr;` and `event.value = expr;`
        val refs = LinkedHashMap<String, String>()
        val replaced = fieldRef.replace(code) { mr ->
            val name = mr.groupValues[2].replace("\\\"", "\"").replace("\\'", "'")
            val key = refs.entries.firstOrNull { it.value == name }?.key ?: "__f${refs.size}".also { refs[it] = name }
            key
        }
        val statements = ArrayList<Pair<String?, String>>()
        for (raw in replaced.split(';', '\n')) {
            val st = raw.trim()
            if (st.isEmpty()) continue
            val ev = Regex("""^event\s*\.\s*value\s*=(?!=)\s*([\s\S]+)$""").find(st)
            if (ev != null) { statements += null to ev.groupValues[1]; continue }
            val v = Regex("""^(?:var|let|const)?\s*([A-Za-z_$][\w$]*)\s*=(?!=)\s*([\s\S]+)$""").find(st)
            if (v != null) { statements += v.groupValues[1] to v.groupValues[2]; continue }
            return null to true
        }
        if (statements.none { it.first == null }) return null to true
        return CalcSpec.Script(statements, refs) to false
    }

    /** Load scripts and calculation order of the AcroForm of [doc]. */
    fun load(doc: PDDocument): FormCalcModel {
        val acro = doc.documentCatalog.acroForm ?: return FormCalcModel.EMPTY
        val scripts = LinkedHashMap<String, FieldScripts>()
        val byDict = HashMap<COSDictionary, String>()
        val all = ArrayList<String>()
        for (f in acro.fieldTree) {
            val name = f.fullyQualifiedName ?: continue
            byDict[f.cosObject] = name
            if (f is PDTerminalField) all += name
            // "Run JavaScript" off: the AF helpers are document JavaScript too, so none is emulated
            if (!com.smartdocs.editor.core.security.JsPolicy.enabled) continue
            val aa = f.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary ?: continue
            val calcJs = jsOf(aa.getDictionaryObject(COSName.C))
            val fmtJs = jsOf(aa.getDictionaryObject(COSName.F))
            val keyJs = jsOf(aa.getDictionaryObject(COSName.K))
            val (calc, unsupported) = if (calcJs.isNullOrBlank()) null to false else parseCalc(calcJs)
            val fmt = AfFormat.parseFormat(fmtJs)
            val ks = AfFormat.parseKeystroke(keyJs)
            if (calcJs != null || fmt != null || ks != KeystrokeKind.NONE)
                scripts[name] = FieldScripts(name, calc, calcJs, fmt, ks, unsupported)
        }
        val order = ArrayList<String>()
        (acro.cosObject.getDictionaryObject(COSName.CO) as? COSArray)?.let { co ->
            for (i in 0 until co.size()) {
                val d = co.getObject(i) as? COSDictionary ?: continue
                byDict[d]?.let { n -> if (scripts[n]?.calc != null && n !in order) order += n }
            }
        }
        scripts.values.filter { it.calc != null && it.name !in order }.forEach { order += it.name }
        return FormCalcModel(scripts, order, all)
    }

    suspend fun load(file: File, password: String? = null): FormCalcModel = withContext(Dispatchers.IO) {
        runCatching { PdfOps.load(file, password).use { load(it) } }.getOrDefault(FormCalcModel.EMPTY)
    }
}
