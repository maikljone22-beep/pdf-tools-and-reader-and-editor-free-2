package com.smartdocs.editor.core.ai5

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A citation shown under an answer: [n] as written by the model, the passage it points to and a short quote. */
data class Citation(val n: Int, val docKey: String, val filePath: String, val fileName: String, val page: Int, val quote: String)

/** Piece of an answer for rendering: plain text or a citation marker. */
sealed class AnswerPart {
    data class Text(val text: String) : AnswerPart()
    data class Cite(val n: Int) : AnswerPart()
}

data class CitedAnswer(val text: String, val citations: List<Citation>, val followUps: List<String>) {
    val parts: List<AnswerPart> get() = CitationFormat.parts(text)
    fun citation(n: Int): Citation? = citations.firstOrNull { it.n == n }
    /** Text without markers plus a numbered sources list (copy / share / PDF). */
    fun plainWithSources(pageWord: String = "صفحة"): String = buildString {
        append(CitationFormat.plain(text))
        if (citations.isNotEmpty()) {
            append("\n\n")
            citations.forEach { c ->
                append('[').append(c.n).append("] ")
                if (c.fileName.isNotBlank()) append(c.fileName).append(" — ")
                append(pageWord).append(' ').append(c.page + 1)
                if (c.quote.isNotBlank()) append(": «").append(c.quote).append('»')
                append('\n')
            }
        }
    }.trim()
}

/**
 * The citation contract with the model:
 * - passages are numbered [1]…[k] in the prompt ([Retriever.sourcesBlock]);
 * - the answer puts [n] or [n، m] after claims;
 * - then a line `@@@` and `{"quotes":{"1":"exact 5–15 words"},"followups":["…","…"]}`.
 */
object CitationFormat {
    const val TAIL = "@@@"

    const val INSTRUCTIONS =
        "اعتمد فقط على المقاطع المرقّمة. " + // اعتمد فقط على المقاطع المرقّمة.
            "ضع رقم المقطع بين قوسين مثل [2] أو [1، 3] بعد كل معلومة. " + // ضع رقم المقطع بين قوسين مثل [2] أو [1، 3] بعد كل معلومة.
            "إن لم تجد الجواب في المقاطع فقل ذلك بوضوح. " + // إن لم تجد الجواب في المقاطع فقل ذلك بوضوح.
            "بعد الجواب اكتب سطراً فيه @@@ ثم JSON بهذا الشكل فقط: " + // بعد الجواب اكتب سطراً فيه @@@ ثم JSON بهذا الشكل فقط:
            "{\"quotes\":{\"2\":\"اقتباس حرفي من 5 إلى 15 كلمة من المقطع\"},\"followups\":[\"سؤال متابعة 1\",\"سؤال 2\",\"سؤال 3\"]}"

    /** Marker: [3] / [1, 2] / [1، 2] / [1-3] / [١] / [p3]; captures the inner list. */
    private val MARKER = Regex("\\[\\s*((?:[0-9٠-٩۰-۹]+\\s*(?:[,،٫–—-]|و)?\\s*)+)\\]")

    /** What the user should see while streaming: everything before the tail marker (partial "@@" hidden). */
    fun visible(raw: String): String {
        val i = raw.indexOf(TAIL)
        if (i >= 0) return raw.substring(0, i).trimEnd()
        var s = raw
        while (s.endsWith("@")) s = s.dropLast(1)
        return s
    }

    /** Numbers referenced by one marker's inner text ("1، 3", "2-4"). */
    fun numbers(inner: String): List<Int> {
        val s = AiNorm.digits(inner)
        val out = ArrayList<Int>()
        Regex("(\\d+)\\s*[–—-]\\s*(\\d+)|(\\d+)").findAll(s).forEach { m ->
            if (m.groupValues[1].isNotEmpty()) {
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                if (b >= a && b - a <= 10) for (x in a..b) out += x else { out += a; out += b }
            } else out += m.groupValues[3].toInt()
        }
        return out.distinct()
    }

    /** Text split into plain parts and single citation markers (a list marker becomes several Cite parts). */
    fun parts(text: String): List<AnswerPart> {
        val out = ArrayList<AnswerPart>()
        var last = 0
        MARKER.findAll(text).forEach { m ->
            if (m.range.first > last) out += AnswerPart.Text(text.substring(last, m.range.first))
            numbers(m.groupValues[1]).forEach { out += AnswerPart.Cite(it) }
            last = m.range.last + 1
        }
        if (last < text.length) out += AnswerPart.Text(text.substring(last))
        return out
    }

    /** Answer without markers (double spaces left by removed markers are collapsed). */
    fun plain(text: String): String = MARKER.replace(text, "").replace(Regex("[ \t]{2,}"), " ").replace(Regex(" +([.,،؟!?:])"), "$1").trim()

    /** Parse a complete model answer against the [sources] that were in the prompt. */
    fun parse(raw: String, sources: List<Source>): CitedAnswer {
        val cut = raw.indexOf(TAIL)
        val body0 = (if (cut >= 0) raw.substring(0, cut) else raw).trim()
        val tail = if (cut >= 0) raw.substring(cut + TAIL.length) else ""
        val tailObj = AiJson.extract(tail, '{') as? JsonObject
        val quotes = HashMap<Int, String>()
        (tailObj?.get("quotes") as? JsonObject)?.forEach { (k, v) ->
            val n = AiNorm.digits(k).filter { it.isDigit() }.toIntOrNull() ?: return@forEach
            (v as? JsonPrimitive)?.contentOrNull?.let { quotes[n] = it.trim() }
        }
        val follow = AiJson.strings(tailObj?.get("followups") as? JsonArray ?: tailObj?.get("follow_ups") as? JsonArray).take(4)
        val byN = sources.associateBy { it.n }
        // drop markers that point nowhere; normalise the rest to [n] / [n][m]
        val body = MARKER.replace(body0) { m ->
            val ns = numbers(m.groupValues[1]).filter { it in byN }
            ns.joinToString("") { "[$it]" }
        }
        val used = parts(body).filterIsInstance<AnswerPart.Cite>().map { it.n }.distinct()
        val cites = used.mapNotNull { n ->
            val s = byN[n] ?: return@mapNotNull null
            Citation(n, s.docKey, s.filePath, s.fileName, s.page, bestQuote(quotes[n], s.text, sentenceCiting(body, n)))
        }.sortedBy { it.n }
        return CitedAnswer(body, cites, follow)
    }

    /** The sentence of [body] that carries marker [n] (used to pick a quote when the model gave none). */
    fun sentenceCiting(body: String, n: Int): String {
        val i = body.indexOf("[$n]"); if (i < 0) return ""
        val start = body.lastIndexOfAny(charArrayOf('.', '\n', '؟', '!', '?', '۔'), (i - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        return plain(body.substring(start, i))
    }

    /** A quote that really occurs in the passage: the model's (when found, normalised), else the best-overlapping sentence. */
    fun bestQuote(modelQuote: String?, passage: String, claim: String): String {
        val fp = AiNorm.fold(passage)
        if (!modelQuote.isNullOrBlank()) {
            val fq = AiNorm.fold(modelQuote.trim().trim('"', '«', '»', '“', '”'))
            if (fq.length >= 4 && fp.contains(fq)) return modelQuote.trim().trim('"', '«', '»', '“', '”')
        }
        val sentences = passage.split(Regex("(?<=[.!?؟۔])\\s+|\n")).map { it.trim() }.filter { it.length >= 12 }
        if (sentences.isEmpty()) return DocIndex.clip(passage.trim(), 140)
        val want = (AiTokens.tokens(claim) + AiTokens.tokens(modelQuote.orEmpty())).toSet()
        val best = sentences.maxByOrNull { s -> AiTokens.tokens(s).count { it in want } } ?: sentences.first()
        return DocIndex.clip(best, 160)
    }

    // ---- persistence (saved chats) ----
    fun toJson(c: Citation): JsonObject = buildJsonObject {
        put("n", c.n); put("k", c.docKey); put("f", c.filePath); put("name", c.fileName); put("p", c.page); put("q", c.quote)
    }

    fun fromJson(o: JsonObject): Citation? = runCatching {
        Citation(AiJson.int(o, "n") ?: return null, AiJson.str(o, "k").orEmpty(), AiJson.str(o, "f").orEmpty(), AiJson.str(o, "name").orEmpty(),
            AiJson.int(o, "p") ?: 0, AiJson.str(o, "q").orEmpty())
    }.getOrNull()

    fun listToJson(list: List<Citation>): JsonArray = buildJsonArray { list.forEach { add(toJson(it)) } }
    fun listFromJson(a: JsonArray?): List<Citation> = a?.mapNotNull { (it as? JsonObject)?.let(::fromJson) }.orEmpty()
}
