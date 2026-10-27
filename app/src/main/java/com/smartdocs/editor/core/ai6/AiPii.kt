package com.smartdocs.editor.core.ai6

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiJson
import com.smartdocs.editor.core.ai5.AiQuotaException
import com.smartdocs.editor.core.ai5.PiiHit
import com.smartdocs.editor.core.ai5.PiiKind
import com.smartdocs.editor.core.ai5.PiiMatch
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.serialization.json.JsonObject

/** One AI finding before it is located in the page text: 0-based page, kind and the exact text. */
data class AiPiiItem(val page: Int, val kind: PiiKind, val text: String)

/**
 * ai6: personal data the regexes of [com.smartdocs.editor.core.ai5.PiiDetector] cannot see — people's names,
 * postal addresses, IDs written in words or odd formats. The model gets page-tagged text and returns the exact
 * strings; they are located again in each page's text (every occurrence) so the hits carry real rectangles,
 * and merged with the regex hits without overlaps.
 */
object AiPii {
    const val BATCH_CHARS = 6000

    fun prompt(material: String): String =
        "استخرج من النص التالي كل البيانات الشخصية التي تعرّف أشخاصاً حقيقيين: أسماء الأشخاص (لا أسماء الشركات أو الأماكن العامة)، " +
            "العناوين البريدية والسكنية، أرقام الهويات والجوازات والحسابات والهواتف المكتوبة بصيغ غير معتادة أو بالكلمات، والبريد الإلكتروني. " +
            "انسخ النص كما ورد حرفياً دون تصحيح. kind واحد من: name, address, id, phone, email, other. " +
            "أعد JSON فقط: {\"items\":[{\"page\":1,\"kind\":\"name\",\"text\":\"...\"}]}\n\n" + material

    fun kindOf(s: String): PiiKind = when (s.lowercase().trim()) {
        "name", "person" -> PiiKind.NAME
        "address", "location" -> PiiKind.ADDRESS
        "phone", "mobile" -> PiiKind.PHONE
        "email", "e-mail" -> PiiKind.EMAIL
        "passport" -> PiiKind.PASSPORT
        else -> PiiKind.NATIONAL_ID   // id / account / other identifiers
    }

    /** Items from the answer; [page] is 1-based in the answer, 0-based here. */
    fun parse(answer: String): List<AiPiiItem> {
        val root = AiJson.extract(answer, '{') as? JsonObject ?: return emptyList()
        return AiJson.arr(root, "items", "pii", "entities").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val text = AiJson.str(o, "text", "value")?.trim()?.takeIf { it.length >= 3 } ?: return@mapNotNull null
            val page = (AiJson.int(o, "page") ?: 1) - 1
            AiPiiItem(page.coerceAtLeast(0), kindOf(AiJson.str(o, "kind", "type").orEmpty()), text)
        }
    }

    /** Every occurrence of the [items] strings in [text] (whitespace-tolerant), minus overlaps with [taken] and each other. */
    fun locate(text: String, items: List<AiPiiItem>, taken: List<IntRange> = emptyList()): List<PiiMatch> {
        val out = ArrayList<PiiMatch>()
        val used = ArrayList<IntRange>(taken)
        // longest first so "شارع الملك فهد، الرياض" wins over "الرياض"
        items.distinctBy { it.text }.sortedByDescending { it.text.length }.forEach { item ->
            val pattern = item.text.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            runCatching { Regex(pattern) }.getOrNull()?.findAll(text)?.forEach { m ->
                val r = m.range
                if (used.none { it.first <= r.last && r.first <= it.last }) {
                    used += r
                    out += PiiMatch(item.kind, r.first, r.last + 1, m.value)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    /** Page-tagged batches under [BATCH_CHARS]: list of (pages, material). */
    fun batches(pages: List<Pair<Int, String>>): List<Pair<List<Int>, String>> {
        val out = ArrayList<Pair<List<Int>, String>>()
        val sb = StringBuilder(); val ps = ArrayList<Int>()
        pages.forEach { (p, t) ->
            val part = "[صفحة ${p + 1}]\n" + t.take(BATCH_CHARS) + "\n\n"
            if (sb.isNotEmpty() && sb.length + part.length > BATCH_CHARS) { out += ArrayList(ps) to sb.toString(); sb.clear(); ps.clear() }
            sb.append(part); ps += p
        }
        if (sb.isNotEmpty()) out += ArrayList(ps) to sb.toString()
        return out
    }

    /**
     * AI pass over an open [session]; [existing] are the regex hits (not repeated). Kinds outside [kinds] are dropped.
     * Throws [AiQuotaException] when the batches do not fit today's budget.
     */
    suspend fun scan(ctx: Context, client: AiClient, session: PdfSession, existing: List<PiiHit>, kinds: Set<PiiKind>,
                     progress: (Int, Int) -> Unit = { _, _ -> }): List<PiiHit> {
        val texts = (0 until session.pageCount).map { it to runCatching { session.text(it) }.getOrDefault("") }.filter { it.second.isNotBlank() }
        val groups = batches(texts)
        if (!AiCalls.fits(ctx, client, groups.size)) throw AiQuotaException("need ${groups.size}")
        // the model's page numbers are not trusted: a batch's items are looked for on every page of that batch
        val perPage = HashMap<Int, ArrayList<AiPiiItem>>()
        groups.forEachIndexed { i, (pages, material) ->
            progress(i, groups.size)
            val answer = AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(material))))
            val items = parse(answer).filter { it.kind in kinds }
            pages.forEach { p -> perPage.getOrPut(p) { ArrayList() } += items }
        }
        progress(groups.size, groups.size)
        val out = ArrayList<PiiHit>()
        perPage.forEach { (p, list) ->
            if (p !in 0 until session.pageCount || list.isEmpty()) return@forEach
            val taken = existing.filter { it.hit.page == p }.map { it.match.start until it.match.end }
            var found: List<PiiMatch> = emptyList()
            val hits = runCatching { session.searchWith(p..p) { text -> found = locate(text, list, taken); found.map { it.start until it.end } } }.getOrDefault(emptyList())
            hits.forEach { h -> found.firstOrNull { it.start == h.charIndex }?.let { out += PiiHit(it, h) } }
        }
        return out
    }
}
