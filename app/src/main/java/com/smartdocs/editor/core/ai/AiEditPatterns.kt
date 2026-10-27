package com.smartdocs.editor.core.ai

import com.smartdocs.editor.core.ai5.PiiDetector
import com.smartdocs.editor.core.ai5.PiiKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.TextMatcher

/**
 * ai6 (B10 / AI-7): pure helpers for the "edit through chat" agent tools: which text ranges of a page a
 * highlight_text / redact_pattern call targets, colour names, and the rewrite prompt. No Android types, unit tested.
 */
object AiEditPatterns {

    /** redact_pattern kinds the model may send (Arabic and English aliases) → detector kinds. */
    fun piiKind(kind: String): PiiKind? = when (kind.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "email", "emails", "e_mail", "بريد", "البريد", "بريد_إلكتروني", "البريد_الإلكتروني" -> PiiKind.EMAIL
        "phone", "phones", "phone_number", "mobile", "هاتف", "الهاتف", "جوال", "أرقام_الهواتف", "ارقام_الهواتف" -> PiiKind.PHONE
        "card", "credit_card", "cards", "بطاقة", "بطاقات" -> PiiKind.CARD
        "iban", "bank", "آيبان", "ايبان" -> PiiKind.IBAN
        "ssn", "national_id", "id", "id_number", "هوية", "رقم_الهوية", "الهوية" -> PiiKind.NATIONAL_ID
        "passport", "جواز", "جواز_السفر" -> PiiKind.PASSPORT
        "date", "dates", "تاريخ", "التواريخ", "تواريخ" -> PiiKind.DATE
        "url", "urls", "link", "links", "رابط", "روابط" -> PiiKind.URL
        "ip", "ip_address" -> PiiKind.IP
        else -> null
    }

    /** "all"/"pii"/"كل البيانات الشخصية" → every detector kind. */
    fun isAllPii(kind: String): Boolean = kind.trim().lowercase().let { it == "all" || it == "pii" || it.contains("شخصي") || it == "الكل" }

    /**
     * Ranges to redact in [text] for [kind] (a detector kind, "all", or "custom" with [regex]). Throws
     * IllegalArgumentException on an unknown kind or a bad regex.
     */
    fun redactRanges(text: String, kind: String, regex: String?): List<IntRange> {
        if (!regex.isNullOrBlank() && (kind.isBlank() || kind.equals("custom", true) || kind.equals("regex", true))) return regexRanges(text, regex)
        if (isAllPii(kind)) return PiiDetector.detect(text).map { it.start until it.end }
        val k = piiKind(kind) ?: if (!regex.isNullOrBlank()) return regexRanges(text, regex) else throw IllegalArgumentException("unknown kind: $kind")
        return PiiDetector.detect(text, setOf(k)).map { it.start until it.end }
    }

    /** Ranges of a literal [query] (Arabic-normalised, like reader search) or, when [isRegex], of a regex. */
    fun highlightRanges(text: String, query: String, isRegex: Boolean, matchCase: Boolean = false): List<IntRange> =
        if (isRegex) regexRanges(text, query, matchCase)
        else TextMatcher.find(text, query, SearchOptions(norm = com.smartdocs.editor.core.search.NormOptions(matchCase = matchCase)))

    fun regexRanges(text: String, pattern: String, matchCase: Boolean = false): List<IntRange> {
        val re = runCatching { if (matchCase) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { throw IllegalArgumentException("bad regex") }
        return re.findAll(text).filter { it.value.isNotEmpty() }.map { it.range }.take(2000).toList()
    }

    /** Highlight colour names → RGB (0xRRGGBB). Unknown → yellow. */
    fun color(name: String?): Int {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.startsWith("#") && n.length == 7) n.substring(1).toIntOrNull(16)?.let { return it }
        return when (n) {
            "green", "أخضر", "اخضر" -> 0x7CFC00
            "blue", "أزرق", "ازرق" -> 0x64B5F6
            "pink", "وردي", "زهري" -> 0xF48FB1
            "red", "أحمر", "احمر" -> 0xEF5350
            "orange", "برتقالي" -> 0xFFB74D
            "purple", "بنفسجي" -> 0xBA68C8
            else -> 0xFFEB3B
        }
    }

    /** Prompt for rewrite_text: only the new text comes back. */
    fun rewritePrompt(original: String, instruction: String): String =
        "أعد صياغة النص التالي وفق هذا الطلب: \"${instruction.ifBlank { "صياغة أوضح" }}\". " +
            "حافظ على لغة النص الأصلية وعلى طوله تقريباً، وأعد النص الجديد وحده دون مقدمة أو علامات تنصيص.\n\n$original"

    /** Strip quotes / code fences the model may wrap around a rewrite. */
    fun cleanRewrite(s: String): String = s.trim().removePrefix("```").removeSuffix("```").trim().trim('"', '«', '»', '“', '”').trim()
}
