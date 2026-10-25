package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.serialization.json.JsonObject
import java.io.File

enum class SummaryLength { SHORT, MEDIUM, LONG }
enum class SummaryFormat { BULLETS, PARAGRAPHS }
enum class Audience { GENERAL, STUDENT, EXPERT, CHILD, EXECUTIVE }

data class SummaryOptions(
    val length: SummaryLength = SummaryLength.MEDIUM,
    val format: SummaryFormat = SummaryFormat.BULLETS,
    val language: String = "",          // "" = the document's language
    val audience: Audience = Audience.GENERAL,
    val focus: String = "",
) {
    fun cacheKey(): String = "${length.name}_${format.name}_${language}_${audience.name}_${focus.hashCode()}"
}

data class SummarySection(val title: String, val pages: List<Int>, val text: String)

/** Pages in [pages] / [SummarySection.pages] are 1-based as the model wrote them. */
data class StructuredSummary(val overview: String, val points: List<String>, val sections: List<SummarySection>, val questions: List<String>) {
    fun asText(pageWord: String = "ص"): String = buildString {
        if (overview.isNotBlank()) append(overview).append("\n\n")
        points.forEach { append("• ").append(it).append('\n') }
        if (points.isNotEmpty()) append('\n')
        sections.forEach { s ->
            append(s.title)
            if (s.pages.isNotEmpty()) append(" (").append(pageWord).append(' ').append(s.pages.joinToString("، ")).append(')')
            append('\n').append(s.text).append("\n\n")
        }
    }.trim()
}

/**
 * Customisable summary over the WHOLE document. Short documents: one request. Long ones: map-reduce over at most
 * [MAX_SEGMENTS] segments (each a page-ordered spread of its chunks) + 1 reduce request, so a 500-page book costs
 * at most 7 of the proxy's daily requests. Results are cached per document and options.
 */
object SummaryGen {
    const val SINGLE_LIMIT = 16_000
    const val SEGMENT_CHARS = 13_000
    const val MAX_SEGMENTS = 6

    fun requestsNeeded(idx: DocIndex): Int = if (idx.totalChars <= SINGLE_LIMIT) 1 else segments(idx).size + 1

    fun segments(idx: DocIndex): List<List<DocChunk>> {
        val n = ((idx.totalChars + SEGMENT_CHARS - 1) / SEGMENT_CHARS).coerceIn(1, MAX_SEGMENTS)
        val per = (idx.chunks.size + n - 1) / n
        return idx.chunks.chunked(per.coerceAtLeast(1))
    }

    private fun lengthRule(o: SummaryOptions) = when (o.length) {
        SummaryLength.SHORT -> "نظرة عامة في جملتين و3 إلى 5 نقاط، وبلا أقسام أو بقسمين على الأكثر"
        SummaryLength.MEDIUM -> "نظرة عامة في فقرة و5 إلى 8 نقاط و3 إلى 6 أقسام"
        SummaryLength.LONG -> "نظرة عامة مفصلة و8 إلى 12 نقطة وأقسام تغطي كل أجزاء المستند"
    }

    private fun audienceRule(a: Audience) = when (a) {
        Audience.GENERAL -> "لقارئ عام"
        Audience.STUDENT -> "لطالب يذاكر، مع توضيح المصطلحات"
        Audience.EXPERT -> "لخبير في المجال، بلغة تقنية دقيقة"
        Audience.CHILD -> "لطفل في العاشرة، بكلمات بسيطة جداً"
        Audience.EXECUTIVE -> "لمدير مشغول: القرارات والأرقام والمخاطر أولاً"
    }

    private fun langRule(o: SummaryOptions, docLang: String): String {
        val code = o.language.ifBlank { docLang.ifBlank { "ar" } }
        return "اكتب بلغة: ${AiLang.name(code)}"
    }

    private fun schema(o: SummaryOptions) =
        "أعد JSON فقط بهذا الشكل: {\"overview\":\"...\",\"points\":[\"...\"],\"sections\":[{\"title\":\"...\",\"pages\":[3,4],\"text\":\"...\"}],\"questions\":[\"سؤال مقترح\"]}. " +
            (if (o.format == SummaryFormat.BULLETS) "اجعل نص كل قسم نقاطاً قصيرة تبدأ بـ - في أسطر منفصلة." else "اجعل نص كل قسم فقرة متصلة.") +
            " استخدم أرقام الصفحات من علامات [صفحة N]. اقترح 3 أسئلة يمكن أن يسألها القارئ بعد قراءة الملخص."

    private fun instructions(o: SummaryOptions, docLang: String) =
        "لخّص المستند ${audienceRule(o.audience)}: ${lengthRule(o)}. ${langRule(o, docLang)}." +
            (if (o.focus.isNotBlank()) " ركّز على: ${o.focus}." else "") + " " + schema(o)

    private fun cacheFile(ctx: Context, idx: DocIndex, o: SummaryOptions) =
        File(File(ctx.filesDir, "ai5/summary").apply { mkdirs() }, "${idx.key}_${o.cacheKey()}.json")

    fun cached(ctx: Context, idx: DocIndex, o: SummaryOptions): StructuredSummary? =
        runCatching { cacheFile(ctx, idx, o).takeIf { it.exists() }?.readText()?.let(::parse) }.getOrNull()

    /** Any cached summary of this document (used for starter questions without a new request). */
    fun anyCached(ctx: Context, idx: DocIndex): StructuredSummary? = runCatching {
        File(ctx.filesDir, "ai5/summary").listFiles()?.filter { it.name.startsWith(idx.key + "_") }?.maxByOrNull { it.lastModified() }?.readText()?.let(::parse)
    }.getOrNull()

    suspend fun generate(ctx: Context, client: AiClient, idx: DocIndex, o: SummaryOptions, force: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): StructuredSummary {
        if (!force) cached(ctx, idx, o)?.let { return it }
        val need = requestsNeeded(idx)
        if (!AiCalls.fits(ctx, client, need)) throw AiQuotaException("need $need")
        val docLang = AiLang.detect(idx.chunks.take(20).joinToString(" ") { it.text })
        val answer = if (need == 1) {
            progress(0, 1)
            AiCalls.complete(ctx, client, listOf(
                ChatMessage("system", AiCalls.SYSTEM),
                ChatMessage("user", instructions(o, docLang) + "\n\nالمستند: ${idx.name}\n\n" + DocIndex.material(idx.chunks)),
            ))
        } else {
            val segs = segments(idx)
            val notes = segs.mapIndexed { i, seg ->
                progress(i, segs.size + 1)
                val material = DocIndex.material(DocIndex(idx.key, idx.name, idx.path, idx.pageCount, seg).spread(SEGMENT_CHARS))
                AiCalls.complete(ctx, client, listOf(
                    ChatMessage("system", AiCalls.SYSTEM),
                    ChatMessage("user", "هذا الجزء ${i + 1} من ${segs.size} من المستند \"${idx.name}\". اكتب ملاحظات مركزة عن كل أفكاره ومعلوماته المهمة، " +
                        "مع رقم الصفحة بين قوسين بعد كل ملاحظة مثل (ص 12). لا تكتب مقدمة.\n\n$material"),
                ))
            }
            progress(segs.size, segs.size + 1)
            AiCalls.complete(ctx, client, listOf(
                ChatMessage("system", AiCalls.SYSTEM),
                ChatMessage("user", instructions(o, docLang) + " الأرقام بين قوسين (ص N) هي أرقام الصفحات.\n\nملاحظات أجزاء المستند \"${idx.name}\" بالترتيب:\n\n" +
                    notes.mapIndexed { i, n -> "### الجزء ${i + 1}\n$n" }.joinToString("\n\n")),
            ))
        }
        val s = parse(answer)
        runCatching { cacheFile(ctx, idx, o).writeText(answer) }
        return s
    }

    /** Lenient parse; when no JSON came back the whole answer becomes the overview. */
    fun parse(answer: String): StructuredSummary {
        val o = AiJson.extract(answer, '{') as? JsonObject
            ?: return StructuredSummary(answer.trim(), emptyList(), emptyList(), emptyList())
        val sections = AiJson.arr(o, "sections")?.mapNotNull { e ->
            val so = e as? JsonObject ?: return@mapNotNull null
            val title = AiJson.str(so, "title", "heading") ?: return@mapNotNull null
            SummarySection(title, AiJson.pages(so["pages"] ?: so["page"]).distinct().sorted(), AiJson.str(so, "text", "summary", "content").orEmpty())
        }.orEmpty()
        return StructuredSummary(AiJson.str(o, "overview", "summary", "tldr").orEmpty(), AiJson.strings(AiJson.arr(o, "points", "key_points", "bullets")),
            sections, AiJson.strings(AiJson.arr(o, "questions", "suggested_questions")).take(5))
    }
}
