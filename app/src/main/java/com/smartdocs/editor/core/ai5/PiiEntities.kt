package com.smartdocs.editor.core.ai5


/**
 * Smart Redact entities offline (Foxit: person names, organisations, roles): patterns for Arabic and English titles
 * before names (السيد، الدكتور، Mr, Dr …), organisation words (شركة، وزارة، Inc, Ltd …) and a list of job titles.
 * The AI path is [com.smartdocs.editor.core.ai6.AiPii] (names, addresses, organisations, roles), run after this.
 */
object PiiEntities {
    val KINDS = setOf(PiiKind.NAME, PiiKind.ORG, PiiKind.ROLE)

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
        PERSON_AR.findAll(text).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.NAME, it) } }
        PERSON_EN.findAll(text).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.NAME, it) } }
        ORG_AR.findAll(text).forEach { add(PiiKind.ORG, it.range) }
        ORG_EN.findAll(text).forEach { add(PiiKind.ORG, it.range) }
        ROLE.findAll(text).forEach { add(PiiKind.ROLE, it.range) }
        return out
    }
}
