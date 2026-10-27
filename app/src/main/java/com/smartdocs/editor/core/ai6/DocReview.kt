package com.smartdocs.editor.core.ai6

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiJson
import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.core.ai5.DocIndex
import com.smartdocs.editor.core.ai5.StudyGen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One finding of a review: a label ("الأطراف", "Risk: auto-renewal"), its value / explanation, a level and source pages (1-based). */
data class ReviewItem(val label: String, val value: String, val level: String = "", val pages: List<Int> = emptyList())

data class ReviewSection(val title: String, val items: List<ReviewItem>)

data class ReviewResult(val kind: DocReview.Kind, val summary: String, val sections: List<ReviewSection>)

/** A table extracted by the model: title, source page (1-based) and rows (first row = header). */
data class ExtractedTable(val title: String, val page: Int?, val rows: List<List<String>>)

/**
 * ai6 (B17): contract review (key terms with citations), document analysis, risk analysis, comparison of up to
 * 10 contracts, and table → CSV. Each is ONE request over a page-ordered spread of the document(s) with a strict
 * JSON schema; parsing is lenient ([AiJson]).
 */
object DocReview {
    enum class Kind { CONTRACT, ANALYSIS, RISK, COMPARE }

    const val MAX_COMPARE = 10

    private fun lang(material: String, language: String) =
        "اكتب بلغة: ${AiLang.name(language.ifBlank { AiLang.detect(material).ifBlank { "ar" } })}. "

    private const val SCHEMA = "أعد JSON فقط: {\"summary\":\"...\",\"sections\":[{\"title\":\"...\",\"items\":[{\"label\":\"...\",\"value\":\"...\",\"level\":\"high|medium|low|\",\"page\":3}]}]}. " +
        "اذكر رقم الصفحة من علامات [صفحة N] لكل بند، ولا تخترع ما ليس في النص؛ إن لم يوجد بند فاكتب \"غير مذكور\"."

    fun prompt(kind: Kind, language: String, material: String): String = lang(material, language) + when (kind) {
        Kind.CONTRACT -> "راجع هذا العقد. استخرج في أقسام: الأطراف، التواريخ (التوقيع، السريان، الانتهاء، المهل)، المبالغ وشروط الدفع، " +
            "الالتزامات على كل طرف، التجديد، الإنهاء وشروطه، الجزاءات والتعويضات، القانون الحاكم وتسوية النزاعات، السرية. " +
            "ثم قسم \"ملاحظات\" بالبنود الغامضة أو الناقصة مع مستوى أهميتها. "
        Kind.ANALYSIS -> "حلّل هذا المستند: الغرض والجمهور، البنية، الأفكار الرئيسية، الأرقام والحقائق المهمة، الاستنتاجات، " +
            "نقاط القوة والضعف، والأسئلة المفتوحة. "
        Kind.RISK -> "حلّل المخاطر في هذا المستند (قانونية، مالية، تشغيلية، امتثال، سمعة، مواعيد). لكل خطر: وصفه، الدليل من النص، " +
            "مستواه (high أو medium أو low)، واقتراح لتخفيفه داخل value. رتّب الأخطر أولاً. "
        Kind.COMPARE -> "قارن بين العقود التالية (كل عقد يبدأ بعنوان ## اسم الملف). لكل بند (الأطراف، المدة، المبالغ، الدفع، التجديد، الإنهاء، " +
            "الجزاءات، القانون الحاكم، السرية) أنشئ قسماً عنوانه البند، وفيه عنصر لكل عقد: label اسم الملف وvalue ما ينص عليه، " +
            "وضع level=high للعنصر المختلف جوهرياً عن بقية العقود. "
    } + SCHEMA + "\n\n" + material

    fun parse(kind: Kind, answer: String): ReviewResult? {
        val root = AiJson.extract(answer, '{') as? JsonObject ?: return null
        val sections = AiJson.arr(root, "sections", "Sections").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val items = AiJson.arr(o, "items", "points").orEmpty().mapNotNull { ie ->
                when (ie) {
                    is JsonObject -> {
                        val label = AiJson.str(ie, "label", "name", "term", "title").orEmpty()
                        val value = AiJson.str(ie, "value", "text", "description", "detail").orEmpty()
                        if (label.isBlank() && value.isBlank()) null
                        else ReviewItem(label, value, AiJson.str(ie, "level", "severity", "risk").orEmpty().lowercase(), AiJson.pages(ie["page"] ?: ie["pages"]))
                    }
                    is JsonPrimitive -> ie.contentOrNull?.takeIf { it.isNotBlank() }?.let { ReviewItem("", it) }
                    else -> null
                }
            }
            val title = AiJson.str(o, "title", "name").orEmpty()
            if (title.isBlank() && items.isEmpty()) null else ReviewSection(title, items)
        }
        val summary = AiJson.str(root, "summary", "overview").orEmpty()
        if (sections.isEmpty() && summary.isBlank()) return null
        return ReviewResult(kind, summary, sections)
    }

    suspend fun review(ctx: Context, client: AiClient, indexes: List<DocIndex>, kind: Kind, language: String = ""): ReviewResult {
        val docs = indexes.take(MAX_COMPARE)
        val material = StudyGen.material(docs)
        val answer = AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(kind, language, material))))
        return parse(kind, answer) ?: throw IllegalStateException("parse")
    }

    /** Plain text of a result (for the PDF export / share / copy). */
    fun text(r: ReviewResult): String = buildString {
        if (r.summary.isNotBlank()) append(r.summary.trim()).append("\n\n")
        r.sections.forEach { s ->
            if (s.title.isNotBlank()) append("■ ").append(s.title).append('\n')
            s.items.forEach { i ->
                append("• ")
                if (i.label.isNotBlank()) append(i.label).append(": ")
                append(i.value)
                if (i.level.isNotBlank()) append(" [").append(i.level).append(']')
                if (i.pages.isNotEmpty()) append(" (").append(i.pages.joinToString(", ") { "ص $it" }).append(')')
                append('\n')
            }
            append('\n')
        }
    }.trim()

    /** CSV of a result: section, label, value, level, pages. */
    fun csv(r: ReviewResult): String = CsvText.write(listOf(listOf("section", "label", "value", "level", "pages")) +
        r.sections.flatMap { s -> s.items.map { i -> listOf(s.title, i.label, i.value, i.level, i.pages.joinToString(" ")) } })

    // ---------------- table → CSV ----------------

    fun tablePrompt(material: String, focus: String): String =
        "استخرج كل الجداول الموجودة في النص التالي" + (if (focus.isNotBlank()) " (خصوصاً: $focus)" else "") +
            ". الجداول في النص المستخرج من PDF تظهر كأسطر بقيم متتالية؛ أعد بناء الصفوف والأعمدة بدقة دون تغيير القيم أو ترجمتها. " +
            "أول صف هو رؤوس الأعمدة. إن لم تجد جدولاً فأعد {\"tables\":[]}. " +
            "أعد JSON فقط: {\"tables\":[{\"title\":\"...\",\"page\":2,\"rows\":[[\"col1\",\"col2\"],[\"v1\",\"v2\"]]}]}\n\n" + material

    fun parseTables(answer: String): List<ExtractedTable> {
        val root = AiJson.extract(answer)
        val arr: JsonArray = when (root) {
            is JsonObject -> AiJson.arr(root, "tables", "Tables")
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val rows = AiJson.arr(o, "rows", "data").orEmpty().mapNotNull { r ->
                (r as? JsonArray)?.map { c -> (c as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
            }.filter { r -> r.any { it.isNotEmpty() } }
            if (rows.isEmpty()) null
            else {
                val width = rows.maxOf { it.size }
                ExtractedTable(AiJson.str(o, "title", "name").orEmpty(), AiJson.int(o, "page"), rows.map { r -> r + List(width - r.size) { "" } })
            }
        }
    }

    /** Material for tables: given pages in full (tables need every row), else a spread. */
    suspend fun tables(ctx: Context, client: AiClient, index: DocIndex, pages: List<Int>?, focus: String = ""): List<ExtractedTable> {
        val chunks = if (pages.isNullOrEmpty()) index.spread(StudyGen.MATERIAL_CHARS) else index.chunks.filter { it.page in pages }.let { c ->
            var used = 0; c.takeWhile { used += it.text.length; used <= StudyGen.MATERIAL_CHARS }
        }
        val answer = AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", tablePrompt(DocIndex.material(chunks), focus))))
        return parseTables(answer)
    }

    /** All tables in one CSV, separated by a blank line and a "# title (p. N)" comment row. */
    fun tablesCsv(tables: List<ExtractedTable>): String = tables.joinToString("\n") { t ->
        val head = if (tables.size > 1 || t.title.isNotBlank()) CsvText.write(listOf(listOf("# " + t.title + (t.page?.let { " (p. $it)" } ?: "")))) else ""
        head + CsvText.write(t.rows)
    }
}

/** RFC 4180 CSV writer (CRLF rows, quotes when needed) with a UTF-8 BOM option for Excel. */
object CsvText {
    fun cell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.startsWith(' ') || s.endsWith(' ')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun write(rows: List<List<String>>): String = buildString { rows.forEach { r -> append(r.joinToString(",") { cell(it) }).append("\r\n") } }
    const val BOM = "﻿"
}
