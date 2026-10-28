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

/** aor (§35 / §41 Workspace crafts): what a craft becomes. */
enum class CraftKind { INFOGRAPHIC, WEB_PAGE }

data class CraftStat(val value: String, val label: String)

data class CraftSection(val heading: String, val text: String, val bullets: List<String> = emptyList(), val pages: List<Int> = emptyList())

/** One generated craft: a title block, key figures, sections and a closing takeaway. */
data class Craft(
    val kind: CraftKind, val title: String, val subtitle: String, val stats: List<CraftStat>,
    val sections: List<CraftSection>, val takeaway: String = "",
) {
    val rtl: Boolean get() = SlidesGen.isArabic(title + subtitle + sections.joinToString(" ") { it.heading })
}

/** Headings written into the outputs (from string resources, so the app language decides). */
data class CraftLabels(val keyFigures: String, val takeaway: String, val contents: String)

/**
 * aor: infographics and web pages made from a document or a Space. One AI request over the same page-ordered
 * material as the study tools, strict JSON, then [html] (pure, unit tested) and [CraftRender] (PNG / PDF).
 */
object Crafts {
    fun prompt(kind: CraftKind, language: String, focus: String, material: String): String {
        val lang = AiLang.name(language.ifBlank { AiLang.detect(material).ifBlank { "ar" } })
        val what = when (kind) {
            CraftKind.INFOGRAPHIC -> "أنشئ محتوى إنفوجرافيك يلخص المستند بصرياً: عنوان قصير، وعنوان فرعي في جملة واحدة، ومن 2 إلى 4 أرقام بارزة " +
                "مأخوذة من المستند حرفياً (القيمة ووصف قصير لها)، ومن 4 إلى 6 أقسام لكل منها عنوان قصير وجملة واحدة وحتى 3 نقاط قصيرة (أقل من 10 كلمات)، " +
                "وخلاصة في جملة. لا تخترع أرقاماً غير موجودة في المستند؛ إن لم توجد أرقام فاترك stats فارغة. "
            CraftKind.WEB_PAGE -> "أنشئ صفحة ويب تعريفية تشرح المستند لقارئ عام: عنوان، ومقدمة في فقرة (subtitle)، ومن 4 إلى 8 أقسام لكل منها عنوان " +
                "وفقرة أو فقرتان (text) ونقاط اختيارية، وأرقام بارزة من المستند إن وجدت، وخلاصة. "
        }
        return "اكتب بلغة: $lang. $what" + (if (focus.isNotBlank()) "ركّز على: $focus. " else "") +
            "أضف رقم الصفحة المصدر لكل قسم. أعد JSON فقط: {\"title\":\"...\",\"subtitle\":\"...\",\"stats\":[{\"value\":\"...\",\"label\":\"...\"}]," +
            "\"sections\":[{\"heading\":\"...\",\"text\":\"...\",\"bullets\":[\"...\"],\"page\":3}],\"takeaway\":\"...\"}\n\n" + material
    }

    /** Lenient parse; null when the answer has no usable section. */
    fun parse(kind: CraftKind, answer: String, fallbackTitle: String = ""): Craft? {
        val el = AiJson.extract(answer) ?: return null
        val root = el as? JsonObject
        val arr: JsonArray = (root?.let { AiJson.arr(it, "sections", "Sections", "أقسام", "items") } ?: (el as? JsonArray)) ?: return null
        val sections = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val h = AiJson.str(o, "heading", "title", "عنوان").orEmpty()
            val t = AiJson.str(o, "text", "body", "paragraph", "content").orEmpty()
            val b = AiJson.strings(AiJson.arr(o, "bullets", "points", "items"))
            if (h.isBlank() && t.isBlank() && b.isEmpty()) return@mapNotNull null
            CraftSection(h, t, b, AiJson.pages(o["page"] ?: o["pages"]))
        }
        if (sections.isEmpty()) return null
        val stats = AiJson.arr(root, "stats", "figures", "numbers").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val v = AiJson.str(o, "value", "number", "stat").orEmpty()
            if (v.isBlank()) null else CraftStat(v, AiJson.str(o, "label", "description", "text").orEmpty())
        }.take(6)
        val title = AiJson.str(root, "title").orEmpty().ifBlank { fallbackTitle.ifBlank { sections.first().heading } }
        return Craft(kind, title, AiJson.str(root, "subtitle", "intro", "summary").orEmpty(), stats, sections, AiJson.str(root, "takeaway", "conclusion").orEmpty())
    }

    suspend fun generate(ctx: Context, client: AiClient, kind: CraftKind, indexes: List<DocIndex>, language: String = "", focus: String = ""): Craft {
        val m = StudyGen.material(indexes, focus)
        val answer = AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(kind, language, focus, m))))
        return parse(kind, answer, indexes.firstOrNull()?.name?.substringBeforeLast('.').orEmpty()) ?: throw IllegalStateException("parse")
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c) }
    }

    private fun pagesNote(p: List<Int>) = if (p.isEmpty()) "" else " <span class=\"pg\">(" + p.joinToString(", ") { "p. $it" } + ")</span>"

    /** A self-contained HTML page (inline CSS, no scripts, no external files); [footer] e.g. "Made from: report.pdf". */
    fun html(c: Craft, labels: CraftLabels, footer: String = ""): String {
        val dir = if (c.rtl) "rtl" else "ltr"
        val lang = if (c.rtl) "ar" else "en"
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"").append(lang).append("\" dir=\"").append(dir).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>").append(esc(c.title)).append("</title>\n<style>\n")
            .append(CSS).append(if (c.kind == CraftKind.INFOGRAPHIC) CSS_INFO else CSS_WEB).append("</style>\n</head>\n<body class=\"")
            .append(if (c.kind == CraftKind.INFOGRAPHIC) "info" else "web").append("\">\n")
        sb.append("<header><h1>").append(esc(c.title)).append("</h1>")
        if (c.subtitle.isNotBlank()) sb.append("<p class=\"sub\">").append(esc(c.subtitle)).append("</p>")
        sb.append("</header>\n<main>\n")
        if (c.kind == CraftKind.WEB_PAGE && c.sections.size > 2) {
            sb.append("<nav><h2>").append(esc(labels.contents)).append("</h2><ol>")
            c.sections.forEachIndexed { i, s -> sb.append("<li><a href=\"#s").append(i + 1).append("\">").append(esc(s.heading.ifBlank { "${i + 1}" })).append("</a></li>") }
            sb.append("</ol></nav>\n")
        }
        if (c.stats.isNotEmpty()) {
            sb.append("<section class=\"stats\" aria-label=\"").append(esc(labels.keyFigures)).append("\">")
            if (c.kind == CraftKind.WEB_PAGE) sb.append("<h2>").append(esc(labels.keyFigures)).append("</h2>")
            c.stats.forEach { st -> sb.append("<div class=\"stat\"><b>").append(esc(st.value)).append("</b><span>").append(esc(st.label)).append("</span></div>") }
            sb.append("</section>\n")
        }
        c.sections.forEachIndexed { i, s ->
            sb.append("<section class=\"card\" id=\"s").append(i + 1).append("\">")
            if (c.kind == CraftKind.INFOGRAPHIC) sb.append("<div class=\"n\">").append(i + 1).append("</div>")
            sb.append("<div class=\"body\">")
            if (s.heading.isNotBlank()) sb.append("<h2>").append(esc(s.heading)).append(pagesNote(s.pages)).append("</h2>")
            s.text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { p -> sb.append("<p>").append(esc(p)).append("</p>") }
            if (s.bullets.isNotEmpty()) { sb.append("<ul>"); s.bullets.forEach { b -> sb.append("<li>").append(esc(b)).append("</li>") }; sb.append("</ul>") }
            sb.append("</div></section>\n")
        }
        if (c.takeaway.isNotBlank()) sb.append("<section class=\"take\"><h2>").append(esc(labels.takeaway)).append("</h2><p>").append(esc(c.takeaway)).append("</p></section>\n")
        sb.append("</main>\n")
        if (footer.isNotBlank()) sb.append("<footer>").append(esc(footer)).append("</footer>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private const val CSS = ":root{--a:#1F4E79;--b:#2E75B6;--l:#EAF2FA;--t:#222}\n" +
        "*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Tahoma,Arial,sans-serif;color:var(--t);background:#f6f8fb;line-height:1.6}\n" +
        "header{background:var(--a);color:#fff;padding:40px 24px;text-align:center}header h1{margin:0 0 8px;font-size:2em}.sub{margin:0;opacity:.9;font-size:1.1em}\n" +
        "main{max-width:960px;margin:0 auto;padding:24px 16px}h2{color:var(--a);margin:0 0 8px;font-size:1.25em}.pg{color:#777;font-size:.7em;font-weight:normal}\n" +
        ".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-bottom:20px}\n" +
        ".stat{background:var(--l);border-radius:12px;padding:16px;text-align:center}.stat b{display:block;font-size:2em;color:var(--a)}.stat span{color:#444}\n" +
        ".card{background:#fff;border-radius:12px;padding:18px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}\n" +
        ".take{background:var(--a);color:#fff;border-radius:12px;padding:18px}.take h2{color:#fff}\n" +
        "footer{text-align:center;color:#777;font-size:.85em;padding:16px}ul{margin:6px 0;padding-inline-start:22px}\n" +
        "@media print{body{background:#fff}.card{box-shadow:none;border:1px solid #ddd}}\n"
    private const val CSS_INFO = ".card{display:flex;gap:14px;align-items:flex-start;border-inline-start:6px solid var(--b)}\n" +
        ".n{flex:0 0 40px;height:40px;border-radius:50%;background:var(--b);color:#fff;font-weight:bold;display:flex;align-items:center;justify-content:center}\n" +
        ".card:nth-of-type(even){border-color:#C55A11}.card:nth-of-type(even) .n{background:#C55A11}.body{flex:1}\n"
    private const val CSS_WEB = "nav{background:#fff;border-radius:12px;padding:14px 18px;margin-bottom:16px}nav ol{margin:0;padding-inline-start:22px}\n" +
        "nav a{color:var(--b);text-decoration:none}.card p{margin:0 0 10px}\n"
}
