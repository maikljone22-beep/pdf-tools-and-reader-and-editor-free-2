package com.smartdocs.editor.core.ai6

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * ai6 (AI-6, AI-11): chat skills. A skill is a named instruction that is put in front of the question
 * ("/latex ما حل المعادلة؟"). Presets ship with the app (math step by step, LaTeX, formula recognition…);
 * the user can add, edit, disable, export and import skills (JSON).
 */
data class Skill(
    val id: String,
    val name: String,
    val instruction: String,
    val enabled: Boolean = true,
    val preset: Boolean = false,
    /** True when the skill expects an image (formula / chart recognition through vision). */
    val wantsImage: Boolean = false,
)

object Skills {
    /** Built-in skills (ids are stable so a user's enable/disable choice survives updates). */
    val PRESETS: List<Skill> = listOf(
        Skill("steps", "خطوة بخطوة", "اشرح الحل خطوة بخطوة مع ترقيم الخطوات، وبيّن سبب كل خطوة، ثم اكتب النتيجة النهائية في سطر مستقل يبدأ بـ «النتيجة:».", preset = true),
        Skill("math", "حل مسألة رياضية", "حل المسألة الرياضية التالية خطوة بخطوة. اكتب المعادلات بصيغة LaTeX بين $ $ وتحقق من الناتج في النهاية.", preset = true),
        Skill("latex", "اكتب بصيغة LaTeX", "اكتب الجواب أو الصيغة المطلوبة بصيغة LaTeX صحيحة داخل كتلة ```latex``` قابلة للنسخ، ثم اشرحها باختصار.", preset = true),
        Skill("formula", "تعرّف على صيغة (LaTeX)", "حوّل الصيغة أو المعادلة الظاهرة في الصورة إلى LaTeX دقيق داخل كتلة ```latex```، ثم إلى MathML داخل كتلة ```xml```. لا تضف شرحاً طويلاً.", preset = true, wantsImage = true),
        Skill("chart", "اقرأ المخطط", "صف المخطط أو الجدول في الصورة: نوعه، المحاور، القيم الأساسية، والاتجاهات والاستنتاجات المهمة. إن كان جدولاً فأعد بياناته كجدول CSV داخل كتلة ```csv```.", preset = true, wantsImage = true),
        Skill("eli5", "اشرح ببساطة", "اشرح كأنك تخاطب طالباً مبتدئاً، بأمثلة بسيطة من الحياة اليومية وجمل قصيرة.", preset = true),
        Skill("bullets", "نقاط مختصرة", "أجب في نقاط قصيرة مرتبة، لا تزيد عن 7 نقاط.", preset = true),
        Skill("table", "أجب بجدول", "نظّم الجواب في جدول Markdown واضح الأعمدة.", preset = true),
    )

    /** Command token: "latex" for "/latex", the skill name with spaces as "_", or the id. */
    fun command(s: Skill): String = s.id

    /** Split "/id rest of question" into the skill and the question; null skill when there is no known command. */
    fun parseCommand(input: String, skills: List<Skill>): Pair<Skill?, String> {
        val t = input.trimStart()
        if (!t.startsWith("/")) return null to input.trim()
        val token = t.drop(1).takeWhile { !it.isWhitespace() }
        val rest = t.drop(1 + token.length).trim()
        val s = skills.firstOrNull { it.enabled && (it.id.equals(token, true) || it.name.replace(' ', '_').equals(token, true)) }
        return if (s == null) null to input.trim() else s to rest
    }

    /** The question as sent to the model: skill instruction first, then the user's text. */
    fun apply(skill: Skill?, question: String): String =
        if (skill == null) question else "تعليمات المهارة «${skill.name}»: ${skill.instruction}\n\n${question.ifBlank { "طبّق التعليمات على المرفق." }}"

    fun toJson(list: List<Skill>): String = buildJsonArray {
        list.forEach { s -> add(buildJsonObject {
            put("id", s.id); put("name", s.name); put("instruction", s.instruction); put("enabled", s.enabled)
            if (s.preset) put("preset", true)
            if (s.wantsImage) put("image", true)
        }) }
    }.toString()

    /** Parse a skills JSON array (export file or clipboard). Invalid items are skipped; never throws. */
    fun fromJson(text: String): List<Skill> = runCatching {
        val arr = com.smartdocs.editor.core.ai.AiClient.json.parseToJsonElement(text.trim()) as? JsonArray ?: return emptyList()
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun b(k: String) = s(k) == "true"
            val name = s("name")?.trim().orEmpty()
            val ins = s("instruction")?.trim().orEmpty()
            if (name.isEmpty() || ins.isEmpty()) return@mapNotNull null
            val id = s("id")?.trim()?.takeIf { it.isNotEmpty() } ?: slug(name)
            Skill(id, name.take(60), ins.take(2000), enabled = s("enabled") != "false", preset = b("preset"), wantsImage = b("image"))
        }
    }.getOrDefault(emptyList())

    /** Merge imported skills into [current]: same id replaces, new ids are added. Presets keep their text. */
    fun merge(current: List<Skill>, imported: List<Skill>): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        current.forEach { out[it.id] = it }
        imported.forEach { s ->
            val old = out[s.id]
            out[s.id] = if (old != null && old.preset) old.copy(enabled = s.enabled) else s.copy(preset = false)
        }
        return out.values.toList()
    }

    /** Presets (with the user's enabled flags) + custom skills, from the stored list. */
    fun withPresets(stored: List<Skill>): List<Skill> {
        val byId = stored.associateBy { it.id }
        val presets = PRESETS.map { p -> byId[p.id]?.let { p.copy(enabled = it.enabled) } ?: p }
        return presets + stored.filter { s -> PRESETS.none { it.id == s.id } }
    }

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(24)
        return s.ifEmpty { "skill" + (100..999).random() }
    }
}

/** Skills persisted in `filesDir/ai6/skills.json`. */
class SkillStore(ctx: Context) {
    private val file = File(File(ctx.applicationContext.filesDir, "ai6").apply { mkdirs() }, "skills.json")

    @Synchronized fun all(): List<Skill> = Skills.withPresets(runCatching { Skills.fromJson(file.readText()) }.getOrDefault(emptyList()))

    fun enabled(): List<Skill> = all().filter { it.enabled }

    @Synchronized fun save(list: List<Skill>) { file.writeText(Skills.toJson(list)) }

    fun upsert(s: Skill) { save(all().filter { it.id != s.id } + s) }

    fun delete(id: String) { save(all().filter { it.id != id || it.preset }) }

    fun importJson(text: String): Int { val imp = Skills.fromJson(text); if (imp.isNotEmpty()) save(Skills.merge(all(), imp)); return imp.size }

    fun exportJson(): String = Skills.toJson(all())
}
