package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.AfFormat
import com.smartdocs.editor.core.forms.AfNumbers
import com.smartdocs.editor.core.forms.FormatSpec
import com.smartdocs.editor.core.forms.KeystrokeKind

/**
 * A parsed `/AA /V` validate script. No JavaScript engine: the Acrobat helper `AFRange_Validate` and the
 * common hand-written shapes are recognised —
 *  `if (event.value < 18 || event.value > 99) { app.alert("…"); event.rc = false; }`,
 *  `if (!/^\d{5}$/.test(event.value)) …` and `event.rc = /regex/.test(event.value)`.
 */
data class ValidateSpec(
    val min: Double? = null,
    val minInclusive: Boolean = true,
    val max: Double? = null,
    val maxInclusive: Boolean = true,
    val regex: Regex? = null,
    val message: String? = null,
    val unsupported: Boolean = false,
)

/** Why a value was rejected. The UI turns it into a localised message ([ValidateSpec.message] wins). */
sealed class Issue {
    data object Required : Issue()
    data object NotNumber : Issue()
    data class NotDate(val pattern: String) : Issue()
    data class OutOfRange(val min: Double?, val max: Double?) : Issue()
    data class TooLong(val max: Int) : Issue()
    data class BadFormat(val kind: Int) : Issue()     // AFSpecial: 0 zip, 1 zip+4, 2 phone, 3 SSN
    data class Script(val message: String?) : Issue()
}

/** Everything that constrains one field's value. */
data class FieldRules(
    val required: Boolean = false,
    val maxLen: Int = 0,
    val keystroke: KeystrokeKind = KeystrokeKind.NONE,
    val format: FormatSpec? = null,
    val validate: ValidateSpec? = null,
)

object FieldValidation {

    private val num = """(-?\d+(?:\.\d+)?)"""

    fun parseValidate(js: String?): ValidateSpec? {
        if (js.isNullOrBlank()) return null
        val code = js.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        Regex("""AFRange_Validate\s*\(([^)]*)\)""").find(code)?.let { m ->
            val a = AfFormat.args(m.groupValues[1])
            fun b(i: Int) = a.getOrNull(i)?.trim() == "true"
            fun n(i: Int) = a.getOrNull(i)?.trim()?.toDoubleOrNull()
            return ValidateSpec(min = if (b(0)) n(1) else null, max = if (b(2)) n(3) else null)
        }
        val message = Regex("""app\s*\.\s*alert\s*\(\s*(["'])((?:\\.|(?!\1).)*)\1""").find(code)?.groupValues?.get(2)?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\'", "'")
        // regular expression literal tested against the value
        Regex("""(!?)\s*/((?:\\/|[^/\n])+)/([gimsuy]*)\s*\.\s*test\s*\(\s*event\s*\.\s*value\s*\)""").find(code)?.let { m ->
            val negated = m.groupValues[1] == "!"
            val inIf = Regex("""if\s*\(\s*!?\s*/""").containsMatchIn(code)
            val opts = if ('i' in m.groupValues[3]) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val rx = runCatching { Regex(m.groupValues[2].replace("\\/", "/"), opts) }.getOrNull() ?: return ValidateSpec(message = message, unsupported = true)
            // `if (!re.test(v)) reject` and `event.rc = re.test(v)` both mean "must match"
            return if (!inIf || negated) ValidateSpec(regex = rx, message = message) else ValidateSpec(message = message, unsupported = true)
        }
        // comparisons inside the rejecting condition: `event.value < 18` -> min 18, `event.value > 99` -> max 99
        val cmp = Regex("""event\s*\.\s*value\s*(<=|>=|<|>)\s*$num|$num\s*(<=|>=|<|>)\s*event\s*\.\s*value""")
        val ms = cmp.findAll(code).toList()
        if (ms.isNotEmpty() && code.contains("rc") ) {
            var min: Double? = null; var minInc = true; var max: Double? = null; var maxInc = true
            for (m in ms) {
                var op = m.groupValues[1].ifEmpty { m.groupValues[4] }
                val v = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toDouble()
                if (m.groupValues[1].isEmpty()) op = when (op) { "<" -> ">"; ">" -> "<"; "<=" -> ">="; else -> "<=" }  // 5 < value == value > 5
                when (op) {
                    "<" -> { min = v; minInc = true }        // reject value < v  => value >= v
                    "<=" -> { min = v; minInc = false }
                    ">" -> { max = v; maxInc = true }
                    ">=" -> { max = v; maxInc = false }
                }
            }
            return ValidateSpec(min, minInc, max, maxInc, message = message)
        }
        return ValidateSpec(message = message, unsupported = true)
    }

    /** First problem of [value] under [rules], or null when it is acceptable. Empty values only fail "required". */
    fun check(value: String, rules: FieldRules): Issue? {
        val v = value.trim()
        if (v.isEmpty()) return if (rules.required) Issue.Required else null
        if (rules.maxLen > 0 && value.length > rules.maxLen) return Issue.TooLong(rules.maxLen)
        val numeric = rules.keystroke == KeystrokeKind.NUMBER || rules.keystroke == KeystrokeKind.PERCENT ||
            rules.format is FormatSpec.Number || rules.format is FormatSpec.Percent
        val number = AfNumbers.parse(v)
        if (numeric && (number == null || !looksNumeric(v))) return Issue.NotNumber
        val dt = rules.format as? FormatSpec.DateTime
        if ((rules.keystroke == KeystrokeKind.DATE || (dt != null && !dt.timeOnly)) && FieldDates.parseUtc(v, dt?.pattern) == null)
            return Issue.NotDate(dt?.pattern ?: FieldDates.DEFAULT_PATTERN)
        (rules.format as? FormatSpec.Special)?.let { sp ->
            val digits = AfNumbers.normalizeDigits(v).count { it.isDigit() }
            val ok = when (sp.kind) { 0 -> digits == 5; 1 -> digits == 9; 2 -> digits == 7 || digits == 10; 3 -> digits == 9; else -> true }
            if (!ok) return Issue.BadFormat(sp.kind)
        }
        val spec = rules.validate ?: return null
        if (spec.min != null || spec.max != null) {
            val n = number ?: return Issue.NotNumber
            val lowOk = spec.min == null || (if (spec.minInclusive) n >= spec.min else n > spec.min)
            val highOk = spec.max == null || (if (spec.maxInclusive) n <= spec.max else n < spec.max)
            if (!lowOk || !highOk) return if (spec.message != null) Issue.Script(spec.message) else Issue.OutOfRange(spec.min, spec.max)
        }
        spec.regex?.let { rx -> if (!rx.containsMatchIn(value)) return Issue.Script(spec.message) }
        return null
    }

    /** Numbers as Acrobat's AFNumber keystroke accepts them: digits, one separator style, sign, currency. */
    private fun looksNumeric(v: String): Boolean {
        val s = AfNumbers.normalizeDigits(v).trim()
        val letters = s.count { it.isLetter() }
        return letters <= 3 && s.any { it.isDigit() }   // allows "SAR 1,200" / "1.5e3" / "$12"
    }
}
