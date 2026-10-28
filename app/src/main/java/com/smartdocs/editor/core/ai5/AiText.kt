package com.smartdocs.editor.core.ai5

import com.smartdocs.editor.core.search.ArabicNormalizer
import com.smartdocs.editor.core.search.NormOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Normalisation for retrieval: diacritics off, letters unified, ta-marbuta folded, digits unified, lower case. */
object AiNorm {
    private val opts = NormOptions(ignoreDiacritics = true, unifyLetters = true, taMarbuta = true, unifyDigits = true, matchCase = false)
    fun fold(s: String): String = ArabicNormalizer.fold(s, opts).text

    /** Arabic-Indic / Persian digits to ASCII (keeps everything else). */
    fun digits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c.code) {
                in 0x0660..0x0669 -> '0' + (c.code - 0x0660)
                in 0x06F0..0x06F9 -> '0' + (c.code - 0x06F0)
                else -> c
            },
        )
    }
}

/** Tokeniser + stop words + a light Arabic stemmer (prefix و/ف/ب/ك/ل/ال, common suffixes). */
object AiTokens {
    private val STOP = setOf(
        // Arabic (already folded: ة→ه, ى→ي, أ→ا)
        "في", "من", "علي", "الي", "عن", "مع", "هذا", "هذه", "ذلك", "تلك", "التي", "الذي", "الذين", "او", "ام", "ثم", "قد", "لقد",
        "كان", "كانت", "يكون", "هو", "هي", "هم", "انا", "نحن", "انت", "ما", "ماذا", "لماذا", "كيف", "متي", "اين", "هل", "لا", "لم", "لن",
        "ان", "انه", "انها", "كل", "بعض", "غير", "بين", "عند", "حتي", "اذا", "لكن", "بل", "ايضا", "كما", "اي", "و", "ف", "ب", "ل", "ك",
        "يا", "اذ", "منذ", "خلال", "حول", "بعد", "قبل", "فوق", "تحت", "عليه", "عليها", "فيه", "فيها", "منه", "منها", "به", "بها", "له", "لها",
        "الصفحه", "صفحه", "المستند", "الملف", "اشرح", "وضح", "ما هو",
        // English
        "the", "a", "an", "of", "to", "in", "on", "and", "or", "is", "are", "was", "were", "be", "been", "for", "with", "as", "by", "at",
        "this", "that", "these", "those", "it", "its", "from", "what", "which", "who", "how", "why", "when", "where", "do", "does", "did",
        "not", "no", "can", "could", "should", "would", "will", "about", "into", "than", "then", "there", "their", "they", "you", "your",
        "page", "document", "explain", "tell", "me",
    )

    fun isArabicLetter(c: Char) = c.code in 0x0620..0x064A || c.code in 0x066E..0x06D3

    /** Folded word tokens (letters/digits), stop words removed, stemmed. */
    fun tokens(text: String): List<String> {
        val f = AiNorm.fold(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            val w = sb.toString(); sb.setLength(0)
            if (w in STOP) return
            val s = stem(w)
            if (s.length >= 2 || s.any { it.isDigit() }) out += s
        }
        for (c in f) if (c.isLetterOrDigit()) sb.append(c) else flush()
        flush()
        return out
    }

    /** Light stemmer: enough so "والمكتبات" and "مكتبه" share "مكتب". Latin: plural -s / -es / -ing / -ed. */
    fun stem(w0: String): String {
        var w = w0
        if (w.isEmpty()) return w
        if (isArabicLetter(w[0])) {
            for (p in listOf("وال", "فال", "بال", "كال", "لل", "ال")) if (w.length - p.length >= 3 && w.startsWith(p)) { w = w.substring(p.length); break }
            if (w.length >= 5 && (w[0] == 'و' || w[0] == 'ف')) w = w.substring(1)
            for (s in listOf("ات", "ون", "ين", "ان", "يه", "ها", "هم", "كم", "نا", "تي", "ه", "ي")) if (w.length - s.length >= 3 && w.endsWith(s)) { w = w.dropLast(s.length); break }
            return w
        }
        if (w.all { it in 'a'..'z' }) {
            when {
                w.length > 5 && w.endsWith("ing") -> w = w.dropLast(3)
                w.length > 4 && w.endsWith("ed") -> w = w.dropLast(2)
                w.length > 4 && w.endsWith("es") -> w = w.dropLast(2)
                w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w = w.dropLast(1)
            }
        }
        return w
    }
}

/** Document / question language helpers. */
object AiLang {
    /** "ar" when Arabic letters dominate Latin letters, "en" otherwise ("" for empty). */
    fun detect(text: String): String {
        var ar = 0; var la = 0
        for (c in text.take(6000)) { if (AiTokens.isArabicLetter(c)) ar++ else if (c in 'a'..'z' || c in 'A'..'Z') la++ }
        if (ar == 0 && la == 0) return ""
        return if (ar >= la) "ar" else "en"
    }

    /** Language names as the model should read them (Arabic prompts). */
    val NAMES = linkedMapOf(
        "ar" to "العربية", "en" to "الإنجليزية", "fr" to "الفرنسية", "de" to "الألمانية", "es" to "الإسبانية", "tr" to "التركية",
        "ur" to "الأردية", "fa" to "الفارسية", "id" to "الإندونيسية", "ms" to "الملايوية", "hi" to "الهندية", "zh" to "الصينية", "ru" to "الروسية",
        "da" to "الدنماركية",
    )

    fun name(code: String): String = NAMES[code] ?: code
}

/** Lenient JSON for model answers: code fences, prose around the JSON, Arabic digits, trailing commas. */
object AiJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** First balanced JSON object/array found in [text] (outermost of the given opener), parsed; null when none parses. */
    fun extract(text: String, opener: Char? = null): JsonElement? {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)
        val candidates = listOfNotNull(fence, text)
        for (c in candidates) {
            val openers = if (opener != null) listOf(opener) else listOf('{', '[')
            val starts = openers.map { c.indexOf(it) }.filter { it >= 0 }.sorted()
            for (start in starts) {
                val end = matchEnd(c, start) ?: continue
                val chunk = c.substring(start, end + 1)
                runCatching { return json.parseToJsonElement(chunk) }
                runCatching { return json.parseToJsonElement(chunk.replace(Regex(",\\s*([}\\]])"), "$1")) }
            }
        }
        return null
    }

    /** Index of the bracket closing the one at [start], aware of strings; null when unbalanced. */
    fun matchEnd(s: String, start: Int): Int? {
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    fun str(o: JsonObject?, vararg keys: String): String? {
        if (o == null) return null
        for (k in keys) (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return null
    }

    fun int(o: JsonObject?, vararg keys: String): Int? {
        if (o == null) return null
        for (k in keys) (o[k] as? JsonPrimitive)?.contentOrNull?.let { v -> AiNorm.digits(v).filter { it.isDigit() || it == '-' }.toIntOrNull()?.let { return it } }
        return null
    }

    fun arr(o: JsonObject?, vararg keys: String): JsonArray? {
        if (o == null) return null
        for (k in keys) (o[k] as? JsonArray)?.let { return it }
        return null
    }

    fun strings(a: JsonArray?): List<String> = a?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } ?: (it as? JsonObject)?.let { o -> str(o, "text", "q", "question", "title") } }.orEmpty()

    /** Page numbers from "12", "p. 12", "١٢", [12, 13] or "12-13" (1-based as written; first one). */
    fun pages(e: JsonElement?): List<Int> = when (e) {
        null -> emptyList()
        is JsonArray -> e.flatMap { pages(it) }
        is JsonPrimitive -> Regex("\\d+").findAll(AiNorm.digits(e.contentOrNull.orEmpty())).mapNotNull { it.value.toIntOrNull() }.toList()
        else -> emptyList()
    }
}
