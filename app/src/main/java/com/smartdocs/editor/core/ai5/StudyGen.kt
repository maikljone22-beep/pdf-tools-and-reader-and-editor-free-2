package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage

enum class StudyKind { FLASHCARDS, QUIZ, GUIDE, GLOSSARY, MINDMAP }

/**
 * Study tools. Each creation is ONE request over a page-ordered spread of the whole document (or several
 * documents of a Space), with a strict JSON schema; [StudyParse] accepts imperfect answers.
 */
object StudyGen {
    const val MATERIAL_CHARS = 14_000

    fun material(indexes: List<DocIndex>, focus: String = ""): String {
        val per = MATERIAL_CHARS / indexes.size.coerceAtLeast(1)
        return indexes.joinToString("\n\n") { d ->
            val chunks = if (focus.isNotBlank()) {
                // focus: best passages for the topic first, then a light spread for context
                val hits = d.search(focus, 12).map { it.first }
                val budget = per - hits.sumOf { it.text.length }
                (hits + if (budget > 1500) d.spread(budget) else emptyList()).distinctBy { it.id }
            } else d.spread(per)
            (if (indexes.size > 1) "## ${d.name}\n" else "") + DocIndex.material(chunks)
        }
    }

    private fun langRule(material: String, language: String) =
        "اكتب بلغة: ${AiLang.name(language.ifBlank { AiLang.detect(material).ifBlank { "ar" } })}."

    fun prompt(kind: StudyKind, count: Int, language: String, material: String): String = langRule(material, language) + " " + when (kind) {
        StudyKind.FLASHCARDS -> "أنشئ $count بطاقة مراجعة تغطي كل أجزاء المستند: الوجه سؤال أو مصطلح قصير، والظهر جواب دقيق مختصر. " +
            "أعد JSON فقط: {\"cards\":[{\"front\":\"...\",\"back\":\"...\",\"page\":3}]}"
        StudyKind.QUIZ -> "أنشئ اختباراً من $count سؤالاً يغطي كل أجزاء المستند: معظمها اختيار من متعدد بأربعة خيارات وبعضها صح/خطأ. " +
            "answer هو رقم الخيار الصحيح بدءاً من 0، وexplanation شرح قصير لسبب صحة الجواب. " +
            "أعد JSON فقط: {\"questions\":[{\"type\":\"mcq\",\"question\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explanation\":\"...\",\"page\":3}," +
            "{\"type\":\"tf\",\"question\":\"...\",\"options\":[\"صح\",\"خطأ\"],\"answer\":1,\"explanation\":\"...\",\"page\":5}]}"
        StudyKind.GUIDE -> "اكتب دليل مذاكرة منظماً بصيغة Markdown: عناوين ## لكل محور مع رقم الصفحة، تحت كل عنوان أهم الأفكار في نقاط، " +
            "ثم قسم \"## مفاهيم يجب حفظها\" ثم \"## أسئلة للمراجعة\" (5 أسئلة) ثم \"## خلاصة\"."
        StudyKind.GLOSSARY -> "استخرج حتى $count مصطلحاً مهماً من المستند مع تعريف واضح من سياق المستند ورقم الصفحة. " +
            "أعد JSON فقط: {\"terms\":[{\"term\":\"...\",\"definition\":\"...\",\"page\":2}]}"
        StudyKind.MINDMAP -> "أنشئ خريطة ذهنية للمستند: الجذر عنوان المستند، ثم 4 إلى 7 فروع رئيسية، ولكل فرع 2 إلى 5 فروع فرعية بعبارات قصيرة (2 إلى 6 كلمات). " +
            "أعد JSON فقط: {\"title\":\"...\",\"children\":[{\"title\":\"...\",\"children\":[{\"title\":\"...\"}]}]}"
    } + "\n\n" + material

    /** Raw model answer for [kind]; parse it with [StudyParse] (the caller stores the parsed creation). */
    suspend fun generate(ctx: Context, client: AiClient, indexes: List<DocIndex>, kind: StudyKind, count: Int = 12, language: String = "", focus: String = ""): String {
        val m = material(indexes, focus)
        return AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(kind, count, language, m))))
    }
}
