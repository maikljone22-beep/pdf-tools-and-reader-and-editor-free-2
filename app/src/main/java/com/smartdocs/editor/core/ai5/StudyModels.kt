package com.smartdocs.editor.core.ai5

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/** Spaced-repetition state of one card (SM-2). [due] is epoch millis; [interval] in days. */
data class CardSrs(val ease: Double = 2.5, val interval: Int = 0, val reps: Int = 0, val due: Long = 0L, val lapses: Int = 0)

data class Flashcard(val front: String, val back: String, val page: Int? = null, val srs: CardSrs = CardSrs())

enum class QuizKind { MCQ, TRUE_FALSE }

/** [answer] is the index into [options]; [page] 1-based when the model gave it. */
data class QuizQuestion(val kind: QuizKind, val question: String, val options: List<String>, val answer: Int, val explanation: String, val page: Int?)

data class GlossaryTerm(val term: String, val definition: String, val page: Int?)

data class MindNode(val title: String, val children: List<MindNode> = emptyList()) {
    val size: Int get() = 1 + children.sumOf { it.size }
}

/** Grades for the review buttons. */
enum class Grade(val q: Int) { AGAIN(1), HARD(3), GOOD(4), EASY(5) }

object Srs {
    const val DAY = 86_400_000L

    /** SM-2: below 3 resets the card (due again in 10 minutes), otherwise the interval grows by the ease factor. */
    fun review(s: CardSrs, g: Grade, now: Long): CardSrs {
        val q = g.q
        val ease = (s.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))).coerceAtLeast(1.3)
        if (q < 3) return s.copy(ease = ease, interval = 0, reps = 0, due = now + 10 * 60_000L, lapses = s.lapses + 1)
        val interval = when (s.reps) {
            0 -> if (g == Grade.EASY) 3 else 1
            1 -> if (g == Grade.HARD) 3 else 6
            else -> (s.interval * (if (g == Grade.HARD) 1.2 else ease) * (if (g == Grade.EASY) 1.3 else 1.0)).roundToLong().toInt().coerceAtLeast(s.interval + 1)
        }
        return CardSrs(ease, interval, s.reps + 1, now + interval * DAY, s.lapses)
    }

    fun isDue(s: CardSrs, now: Long) = s.due <= now

    /** Due cards first (oldest due first), then new ones. */
    fun queue(cards: List<Flashcard>, now: Long): List<Int> =
        cards.indices.filter { isDue(cards[it].srs, now) }.sortedBy { cards[it].srs.due }
}

/** Lenient parsers for the study JSON the model returns (keys in English or Arabic, malformed bits skipped). */
object StudyParse {
    private fun items(answer: String, vararg keys: String): List<JsonObject> {
        val el = AiJson.extract(answer) ?: return emptyList()
        val arr: JsonArray? = when (el) {
            is JsonArray -> el
            is JsonObject -> AiJson.arr(el, *keys) ?: el.values.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        }
        return arr?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    fun flashcards(answer: String): List<Flashcard> = items(answer, "cards", "flashcards", "بطاقات").mapNotNull { o ->
        val f = AiJson.str(o, "front", "q", "question", "term", "سؤال") ?: return@mapNotNull null
        val b = AiJson.str(o, "back", "a", "answer", "definition", "جواب") ?: return@mapNotNull null
        Flashcard(f, b, AiJson.int(o, "page", "p"))
    }

    fun quiz(answer: String): List<QuizQuestion> = items(answer, "questions", "quiz", "اسئلة").mapNotNull { o ->
        val q = AiJson.str(o, "question", "q", "text") ?: return@mapNotNull null
        val type = AiJson.str(o, "type", "kind").orEmpty().lowercase()
        var options = AiJson.strings(AiJson.arr(o, "options", "choices", "answers"))
        val tf = type.contains("true") || type.contains("tf") || type.contains("صح") || (options.isEmpty() && o["answer"] is JsonPrimitive && (o["answer"] as JsonPrimitive).booleanOrNull != null)
        if (tf) options = listOf("صح", "خطأ")
        if (options.size < 2) return@mapNotNull null
        val ans = answerIndex(o["answer"] ?: o["correct"] ?: o["correct_index"], options, tf) ?: return@mapNotNull null
        QuizQuestion(if (tf) QuizKind.TRUE_FALSE else QuizKind.MCQ, q, options.map { stripLetter(it) }, ans, AiJson.str(o, "explanation", "why", "شرح").orEmpty(), AiJson.int(o, "page", "p"))
    }

    private fun stripLetter(s: String) = s.replace(Regex("^\\s*(?:[A-Da-d]|[أابجد])\\s*[).:-]\\s+"), "").trim()

    /** Accepts an index (0- or 1-based), a letter (A/ب), the option text, or true/false. */
    fun answerIndex(e: JsonElement?, options: List<String>, tf: Boolean): Int? {
        val p = e as? JsonPrimitive ?: return null
        p.booleanOrNull?.let { return if (it) 0 else 1 }
        val raw = AiNorm.digits(p.contentOrNull.orEmpty()).trim()
        if (raw.isEmpty()) return null
        if (tf) {
            val f = AiNorm.fold(raw)
            if (f in setOf("true", "صح", "صحيح", "نعم")) return 0
            if (f in setOf("false", "خطا", "خاطئ", "لا")) return 1
        }
        raw.toIntOrNull()?.let { n -> return if (p.isString.not() && n in options.indices) n else if (n in 1..options.size) n - 1 else if (n == 0) 0 else null }
        val letters = listOf("a", "b", "c", "d", "e", "f")
        val arLetters = listOf("ا", "ب", "ج", "د", "ه", "و")
        val l = AiNorm.fold(raw.trim('(', ')', '.', ' '))
        letters.indexOf(l).takeIf { it in options.indices }?.let { return it }
        arLetters.indexOf(l).takeIf { it in options.indices }?.let { return it }
        val fr = AiNorm.fold(stripLetter(raw))
        options.indexOfFirst { AiNorm.fold(stripLetter(it)) == fr }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { AiNorm.fold(it).contains(fr) || fr.contains(AiNorm.fold(stripLetter(it))) }.takeIf { it >= 0 }
    }

    fun glossary(answer: String): List<GlossaryTerm> = items(answer, "terms", "glossary", "مصطلحات").mapNotNull { o ->
        val t = AiJson.str(o, "term", "word", "name") ?: return@mapNotNull null
        GlossaryTerm(t, AiJson.str(o, "definition", "meaning", "def").orEmpty(), AiJson.int(o, "page", "p"))
    }.sortedBy { AiNorm.fold(it.term) }

    /** Mind map from {"title","children":[…]} JSON, or from an indented / "-" outline when no JSON came back. */
    fun mindMap(answer: String): MindNode? {
        (AiJson.extract(answer, '{') as? JsonObject)?.let { node(it, 0) }?.let { return it }
        return outline(answer)
    }

    private fun node(o: JsonObject, depth: Int): MindNode? {
        val t = AiJson.str(o, "title", "name", "text", "label") ?: return null
        val kids = if (depth > 5) emptyList() else AiJson.arr(o, "children", "nodes", "items")?.mapNotNull { c ->
            (c as? JsonObject)?.let { node(it, depth + 1) } ?: (c as? JsonPrimitive)?.contentOrNull?.let { MindNode(it) }
        }.orEmpty()
        return MindNode(t, kids)
    }

    fun outline(text: String): MindNode? {
        val lines = text.lines().filter { it.isNotBlank() && !it.trim().startsWith("```") }
        if (lines.isEmpty()) return null
        data class L(val depth: Int, val text: String)
        var heading = 0   // depth of the last "#" heading; bullets nest under it
        val parsed = lines.map { raw ->
            val hashes = raw.trimStart().takeWhile { it == '#' }.length
            val indent = raw.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length
            val t = raw.trim().trimStart('#', '-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim()
            if (hashes > 0) { heading = hashes - 1; L(heading, t) } else L(heading + 1 + indent / 2, t)
        }.filter { it.text.isNotEmpty() }
        if (parsed.isEmpty()) return null
        fun build(from: Int, depth: Int, until: Int): List<MindNode> {
            val out = ArrayList<MindNode>()
            var i = from
            while (i < until) {
                val cur = parsed[i]
                var j = i + 1
                while (j < until && parsed[j].depth > cur.depth) j++
                out += MindNode(cur.text, build(i + 1, cur.depth + 1, j))
                i = j
            }
            return out
        }
        val root = parsed.first()
        val kids = build(1, root.depth + 1, parsed.size)
        return MindNode(root.text, kids)
    }

    // ---- persistence ----
    fun cardsToJson(cards: List<Flashcard>): JsonArray = buildJsonArray {
        cards.forEach { c -> add(buildJsonObject {
            put("front", c.front); put("back", c.back); c.page?.let { put("page", it) }
            put("e", c.srs.ease); put("i", c.srs.interval); put("r", c.srs.reps); put("d", c.srs.due); put("l", c.srs.lapses)
        }) }
    }

    fun cardsFromJson(a: JsonArray?): List<Flashcard> = a?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        Flashcard(AiJson.str(o, "front") ?: return@mapNotNull null, AiJson.str(o, "back").orEmpty(), AiJson.int(o, "page"),
            CardSrs((o["e"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 2.5, AiJson.int(o, "i") ?: 0, AiJson.int(o, "r") ?: 0,
                (o["d"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L, AiJson.int(o, "l") ?: 0))
    }.orEmpty()

    fun mindToJson(n: MindNode): JsonObject = buildJsonObject {
        put("title", n.title); put("children", buildJsonArray { n.children.forEach { add(mindToJson(it)) } })
    }
}
