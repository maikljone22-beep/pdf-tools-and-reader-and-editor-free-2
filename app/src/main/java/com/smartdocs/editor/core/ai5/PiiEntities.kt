package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import org.json.JSONArray

/**
 * Smart Redact entities (Foxit: person names, organisations, roles). [detectOffline] uses patterns — Arabic and
 * English titles before names (السيد، الدكتور، Mr, Dr …), organisation words (شركة، وزارة، Inc, Ltd …) and a list
 * of job titles; [detectAi] asks the app's AI client for the entities of a page and finds each returned string in
 * the text, so only text that is really on the page is marked. The AI path needs the network; the screen falls
 * back to [detectOffline] when it is not available.
 */
object PiiEntities {
    val KINDS = setOf(PiiKind.PERSON, PiiKind.ORG, PiiKind.ROLE)

    private const val AR_WORD = "[\\u0621-\\u064A][\\u0621-\\u064A\\u064B-\\u0652]+"
    private const val EN_NAME = "[A-Z][a-z'\\-]+"
    private val PERSON_AR = Regex("(?:السيد(?:ة)?|السيدة|الأستاذ(?:ة)?|الاستاذ(?:ة)?|الدكتور(?:ة)?|د\\.|المهندس(?:ة)?|م\\.|الشيخ|المحامي(?:ة)?|الأخ|الأخت|السادة)\\s+((?:$AR_WORD)(?:\\s+$AR_WORD){1,3})")
    private val PERSON_EN = Regex("\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Eng|Sir|Madam)\\.?\\s+((?:$EN_NAME)(?:\\s+(?:[A-Z]\\.|$EN_NAME)){0,3})")
    private val ORG_AR = Regex("(?:شركة|مؤسسة|وزارة|هيئة|جامعة|مستشفى|بنك|مصرف|جمعية|مجموعة|مكتب|منظمة|مجلس|إدارة|مدرسة|كلية)\\s+(?:$AR_WORD)(?:\\s+(?:$AR_WORD|و$AR_WORD)){0,4}")
    private val ORG_EN = Regex("\\b(?:$EN_NAME|[A-Z]{2,})(?:\\s+(?:$EN_NAME|[A-Z]{2,}|&|of|and)){0,5}\\s+(?:Inc|Ltd|LLC|PLC|GmbH|Corp|Corporation|Company|Co|Group|Bank|University|Ministry|Foundation|Association|Institute|Hospital)\\b\\.?")
    private val ROLES = listOf(
        "المدير العام", "مدير عام", "المدير التنفيذي", "الرئيس التنفيذي", "نائب الرئيس", "رئيس مجلس الإدارة", "رئيس القسم", "المدير", "مدير", "رئيس", "نائب", "مشرف",
        "المحاسب", "محاسب", "السكرتير", "سكرتير", "أمين الصندوق", "المستشار", "مستشار", "الوزير", "وزير", "الوكيل", "وكيل", "المحافظ", "محافظ", "عميد", "العميد",
        "Chief Executive Officer", "Chief Financial Officer", "Chief Operating Officer", "Managing Director", "General Manager", "Vice President",
        "CEO", "CFO", "COO", "CTO", "Director", "Manager", "President", "Chairman", "Secretary", "Treasurer", "Supervisor", "Accountant", "Consultant", "Minister", "Dean",
    )
    private val ROLE = Regex("(?<![\\p{L}])(?:" + ROLES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}])")

    fun detectOffline(text: String, kinds: Set<PiiKind>): List<PiiMatch> {
        val out = ArrayList<PiiMatch>()
        fun add(k: PiiKind, r: IntRange) { if (k in kinds && !r.isEmpty()) out += PiiMatch(k, r.first, r.last + 1, text.substring(r.first, r.last + 1)) }
        PERSON_AR.findAll(text).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.PERSON, it) } }
        PERSON_EN.findAll(text).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.PERSON, it) } }
        ORG_AR.findAll(text).forEach { add(PiiKind.ORG, it.range) }
        ORG_EN.findAll(text).forEach { add(PiiKind.ORG, it.range) }
        ROLE.findAll(text).forEach { add(PiiKind.ROLE, it.range) }
        return out
    }

    /** Every place each returned entity occurs in [text] (exact, then ignoring case for Latin text). */
    fun locate(text: String, entities: List<Pair<PiiKind, String>>, kinds: Set<PiiKind>): List<PiiMatch> {
        val out = ArrayList<PiiMatch>()
        for ((k, v) in entities) {
            if (k !in kinds) continue
            val s = v.trim(); if (s.length < 2) continue
            var i = text.indexOf(s)
            var ci = false
            if (i < 0) { i = text.indexOf(s, ignoreCase = true); ci = true }
            while (i >= 0) {
                out += PiiMatch(k, i, i + s.length, text.substring(i, i + s.length))
                i = text.indexOf(s, i + s.length, ignoreCase = ci)
            }
        }
        return out
    }

    /** Parses the AI reply: a JSON array of {"type": "PERSON|ORG|ROLE", "text": "..."} (code fences tolerated). */
    fun parseReply(reply: String): List<Pair<PiiKind, String>> {
        val start = reply.indexOf('['); val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val a = JSONArray(reply.substring(start, end + 1))
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val k = when (o.optString("type").uppercase()) { "PERSON", "NAME" -> PiiKind.PERSON; "ORG", "ORGANIZATION", "ORGANISATION" -> PiiKind.ORG; "ROLE", "TITLE" -> PiiKind.ROLE; else -> null }
                val t = o.optString("text")
                if (k == null || t.isBlank()) null else k to t
            }
        }.getOrDefault(emptyList())
    }

    /** [detectAi] as the per-page finder [PiiScanner.scan] takes. */
    fun finder(ctx: Context, client: AiClient, kinds: Set<PiiKind>): suspend (String) -> List<PiiMatch> = { text -> detectAi(ctx, client, text, kinds) }

    suspend fun detectAi(ctx: Context, client: AiClient, text: String, kinds: Set<PiiKind>): List<PiiMatch> {
        if (text.isBlank() || kinds.intersect(KINDS).isEmpty()) return emptyList()
        val system = "You find personal and organisational information for redaction. Reply ONLY with a JSON array of objects " +
            "{\"type\": \"PERSON\" | \"ORG\" | \"ROLE\", \"text\": \"exact text as it appears\"}. PERSON = names of people, ORG = names of companies, " +
            "ministries, institutions, ROLE = job titles and positions. Copy each text exactly from the input (Arabic or any language). No other words."
        val reply = AiCalls.ask(ctx, client, system, text.take(12_000))
        return locate(text, parseReply(reply), kinds)
    }
}
