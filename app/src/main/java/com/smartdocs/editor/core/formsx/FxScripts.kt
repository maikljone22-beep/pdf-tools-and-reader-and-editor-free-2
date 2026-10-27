package com.smartdocs.editor.core.formsx

import java.util.Locale

/**
 * Format / keystroke / validate / calculate JavaScript the designer writes into /AA /F /K /V /C (the Acrobat AF
 * helper calls the fill-time engine in core/forms5 runs), and the reverse: recognise those calls in an existing
 * field so its Properties dialog opens with the right choices.
 */
object FxScripts {

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun num(d: Double): String = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else String.format(Locale.US, "%s", d)

    /** (format JS, keystroke JS) of [f]; null for [FxFormat.None]. */
    fun format(f: FxFormat): Pair<String, String>? = when (f) {
        FxFormat.None -> null
        is FxFormat.Number -> {
            val args = "${f.decimals}, ${f.sep}, ${f.neg}, 0, ${q(f.currency)}, ${f.currencyPrepend}"
            "AFNumber_Format($args);" to "AFNumber_Keystroke($args);"
        }
        is FxFormat.Percent -> "AFPercent_Format(${f.decimals}, ${f.sep});" to "AFPercent_Keystroke(${f.decimals}, ${f.sep});"
        is FxFormat.Date -> "AFDate_FormatEx(${q(f.pattern)});" to "AFDate_KeystrokeEx(${q(f.pattern)});"
        is FxFormat.Time -> "AFTime_Format(${f.style});" to "AFTime_Keystroke(${f.style});"
        is FxFormat.Special -> "AFSpecial_Format(${f.psf});" to "AFSpecial_Keystroke(${f.psf});"
        is FxFormat.Mask -> "AFSpecial_KeystrokeEx(${q(f.mask)});" to "AFSpecial_KeystrokeEx(${q(f.mask)});"
        is FxFormat.Custom -> f.formatJs to f.keystrokeJs
    }

    fun validate(v: FxValidate): String? = when (v) {
        FxValidate.None -> null
        is FxValidate.Range -> "AFRange_Validate(${v.min != null}, ${num(v.min ?: 0.0)}, ${v.max != null}, ${num(v.max ?: 0.0)});"
        is FxValidate.Custom -> v.js.takeIf { it.isNotBlank() }
    }

    fun calc(c: FxCalc): String? = when (c) {
        FxCalc.None -> null
        is FxCalc.Simple -> "AFSimple_Calculate(${q(c.op.af)}, new Array(${c.fields.joinToString(", ") { q(it) }}));"
        is FxCalc.Sfn -> sfnToJs(c.expr)
        is FxCalc.Custom -> c.js.takeIf { it.isNotBlank() }
    }

    // ---------------- simplified field notation ----------------

    /** Tokens of an SFN expression: identifiers (dots allowed, `\` escapes the next char), numbers, operators. */
    internal fun sfnTokens(expr: String): List<Pair<Char, String>> {
        val out = ArrayList<Pair<Char, String>>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < expr.length && expr[i + 1].isDigit()) -> {
                    val s = i; while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    out += 'n' to expr.substring(s, i)
                }
                c in "+-*/()" -> { out += 'o' to c.toString(); i++ }
                else -> {
                    val sb = StringBuilder()
                    while (i < expr.length) {
                        val ch = expr[i]
                        if (ch == '\\' && i + 1 < expr.length) { sb.append(expr[i + 1]); i += 2; continue }
                        if (ch.isWhitespace() || ch in "+-*/()") break
                        sb.append(ch); i++
                    }
                    if (sb.isEmpty()) i++ else out += 'i' to sb.toString()
                }
            }
        }
        return out
    }

    /** Field names an SFN expression refers to. */
    fun sfnFields(expr: String): List<String> = sfnTokens(expr).filter { it.first == 'i' }.map { it.second }.distinct()

    /** Acrobat's SFN storage: the expression in a BVCALC / EVCALC comment, followed by equivalent JavaScript. */
    fun sfnToJs(expr: String): String {
        val body = sfnTokens(expr).joinToString(" ") { (k, v) -> if (k == 'i') "AFMakeNumber(getField(${q(v)}).value)" else v }
        return "/*** BVCALC ${expr.trim()} EVCALC ***/ event.value = ${body.ifBlank { "0" }};"
    }

    // ---------------- reading back ----------------

    private fun args(js: String, fn: String): List<String>? {
        val m = Regex("""\b${Regex.escape(fn)}\s*\(""").find(js) ?: return null
        var depth = 1; var i = m.range.last + 1; val sb = StringBuilder(); val out = ArrayList<String>(); var inStr: Char? = null
        while (i < js.length && depth > 0) {
            val c = js[i]
            if (inStr != null) { sb.append(c); if (c == '\\' && i + 1 < js.length) { sb.append(js[i + 1]); i += 2; continue }; if (c == inStr) inStr = null }
            else when (c) {
                '"', '\'' -> { inStr = c; sb.append(c) }
                '(', '[' -> { depth++; sb.append(c) }
                ')', ']' -> { depth--; if (depth > 0) sb.append(c) }
                ',' -> if (depth == 1) { out += sb.toString().trim(); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank() || out.isNotEmpty()) out += sb.toString().trim()
        return out
    }

    private fun unq(s: String?): String {
        val t = s?.trim() ?: return ""
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n").replace("\\\\", "\\")
        }
        return t
    }
    private fun int(a: List<String>, i: Int, d: Int) = a.getOrNull(i)?.trim()?.toIntOrNull() ?: d
    private fun bool(a: List<String>, i: Int) = a.getOrNull(i)?.trim()?.let { it == "true" || it == "1" } ?: false

    fun parseFormat(formatJs: String?, keystrokeJs: String?): FxFormat {
        val f = formatJs.orEmpty(); val k = keystrokeJs.orEmpty()
        if (f.isBlank() && k.isBlank()) return FxFormat.None
        args(f, "AFNumber_Format")?.let { a -> return FxFormat.Number(int(a, 0, 2), int(a, 1, 0), int(a, 2, 0), unq(a.getOrNull(4)), a.getOrNull(5)?.trim() != "false") }
        args(f, "AFPercent_Format")?.let { a -> return FxFormat.Percent(int(a, 0, 2), int(a, 1, 0)) }
        args(f, "AFDate_FormatEx")?.let { a -> return FxFormat.Date(unq(a.getOrNull(0)).ifBlank { "dd/mm/yyyy" }) }
        args(f, "AFTime_Format")?.let { a -> return FxFormat.Time(int(a, 0, 0)) }
        args(f, "AFSpecial_Format")?.let { a -> return FxFormat.Special(int(a, 0, 0)) }
        args(k, "AFSpecial_KeystrokeEx")?.let { a -> return FxFormat.Mask(unq(a.getOrNull(0))) }
        return FxFormat.Custom(f, k)
    }

    fun parseValidate(js: String?): FxValidate {
        val v = js.orEmpty(); if (v.isBlank()) return FxValidate.None
        args(v, "AFRange_Validate")?.let { a ->
            if (v.trim().removeSuffix(";").trim().startsWith("AFRange_Validate")) return FxValidate.Range(
                if (bool(a, 0)) a.getOrNull(1)?.trim()?.toDoubleOrNull() else null, if (bool(a, 2)) a.getOrNull(3)?.trim()?.toDoubleOrNull() else null)
        }
        return FxValidate.Custom(v)
    }

    fun parseCalc(js: String?): FxCalc {
        val c = js.orEmpty(); if (c.isBlank()) return FxCalc.None
        Regex("""BVCALC\s*([\s\S]*?)\s*EVCALC""").find(c)?.let { return FxCalc.Sfn(it.groupValues[1].trim()) }
        args(c, "AFSimple_Calculate")?.let { a ->
            val list = a.getOrNull(1).orEmpty()
            val fields = Regex("""(["'])((?:\\.|(?!\1).)*)\1""").findAll(list).map { unq(it.value) }.toList()
                .ifEmpty { list.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("new") } }
            return FxCalc.Simple(FxCalcOp.of(unq(a.getOrNull(0))), fields)
        }
        return FxCalc.Custom(c)
    }
}
