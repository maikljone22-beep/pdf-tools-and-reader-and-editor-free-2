package com.smartdocs.editor.core.share5

/**
 * Pure rules shared by the share screens and mirrored by `ai-proxy/share/worker.js` (kept free of android.* so
 * they are unit tested on the JVM).
 */
object LinkRules {
    const val MAX_TRIES = 10
    const val LOCK_WINDOW_MS = 15L * 60_000
    private const val DAY = 24L * 3600_000

    enum class Status { ACTIVE, EXPIRED, DISABLED }

    /** [expires] 0 = never. The link stops working at [expires] exactly. */
    fun status(expires: Long, disabled: Boolean, now: Long): Status = when {
        disabled -> Status.DISABLED
        expires > 0 && now >= expires -> Status.EXPIRED
        else -> Status.ACTIVE
    }

    /** Expiry presets offered in the UI (days from now; 0 = never). End of the chosen day is not used: exact +N×24h. */
    val PRESET_DAYS = listOf(1, 7, 30, 0)

    fun expiryFromPreset(days: Int, now: Long): Long = if (days <= 0) 0L else now + days * DAY

    /** Whole days left (rounded up), 0 when expired, -1 when the link never expires. */
    fun daysLeft(expires: Long, now: Long): Int = when {
        expires <= 0 -> -1
        now >= expires -> 0
        else -> ((expires - now + DAY - 1) / DAY).toInt()
    }

    /**
     * Password lockout: failed attempts inside a sliding window of [LOCK_WINDOW_MS]. Returns the attempts kept
     * (older ones dropped) and whether a new attempt is refused.
     */
    data class Tries(val times: List<Long>) {
        fun prune(now: Long) = Tries(times.filter { now - it < LOCK_WINDOW_MS })
        fun locked(now: Long): Boolean = prune(now).times.size >= MAX_TRIES
        fun fail(now: Long) = Tries(prune(now).times + now)
        /** Milliseconds until the oldest attempt in the window expires (0 when not locked). */
        fun retryInMs(now: Long): Long {
            val p = prune(now).times.sorted()
            if (p.size < MAX_TRIES) return 0
            return (p[p.size - MAX_TRIES] + LOCK_WINDOW_MS - now).coerceAtLeast(0)
        }
    }

    /** Password policy for view links: empty = no password, otherwise 4..64 characters. */
    fun passwordOk(pw: String): Boolean = pw.isEmpty() || pw.length in 4..64
}

/** Matching a returned e-mail review (annotated PDF, XFDF or FDF) to the copy this device sent. */
object EmailMatch {
    /** One copy this device sent for e-mail review. [idHex] = trailer /ID[0] of the sent copy (upper-case hex). */
    data class Sent(val reviewId: String, val idHex: String, val fileName: String, val sentAt: Long)

    /** What could be read from an incoming file. */
    data class Incoming(val infoReviewId: String?, val idHex: String?, val href: String?)

    enum class How { REVIEW_ID, DOCUMENT_ID, FILE_NAME }
    data class Match(val sent: Sent, val how: How)

    fun normHex(s: String?): String? = s?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotEmpty() }

    /** Base name without folders, extension and the "(2)" / "-commented" style suffixes mail clients and editors add. */
    fun baseName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var n = name.replace('\\', '/').substringAfterLast('/')
        n = n.substringBeforeLast('.', n)
        repeat(2) {
            n = n.replace(Regex("""\s*\(\d+\)$"""), "")
            n = n.replace(Regex("""[\s_-]*(commented|reviewed|annotated|comments|معلق|تعليقات)$""", RegexOption.IGNORE_CASE), "")
        }
        return n.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * Best match: the review id written into /Info (survives most editors), else the trailer document id, else the
     * file name (only when exactly one sent copy has that name; the newest wins otherwise is too risky → null).
     */
    fun match(sent: List<Sent>, inc: Incoming): Match? {
        inc.infoReviewId?.trim()?.takeIf { it.isNotEmpty() }?.let { rid -> sent.firstOrNull { it.reviewId == rid }?.let { return Match(it, How.REVIEW_ID) } }
        normHex(inc.idHex)?.let { id -> sent.firstOrNull { normHex(it.idHex) == id }?.let { return Match(it, How.DOCUMENT_ID) } }
        val b = baseName(inc.href) ?: return null
        val byName = sent.filter { baseName(it.fileName) == b }
        return if (byName.size == 1) Match(byName[0], How.FILE_NAME) else null
    }

    private val IDS = Regex("""<ids\s+original\s*=\s*"([0-9A-Fa-f]+)"""")
    private val HREF = Regex("""<f\s+href\s*=\s*"([^"]*)"""")
    private val INFO_RID = Regex("""SDReviewId\s*=\s*"([^"]+)"""")
    private val FDF_ID = Regex("""/ID\s*\[\s*<([0-9A-Fa-f]+)>""")
    private val FDF_F = Regex("""/F\s*\(([^)]*)\)""")

    /** Reads ids from XFDF text (`<ids original=…>`, `<f href=…>`). */
    fun fromXfdf(xml: String): Incoming = Incoming(INFO_RID.find(xml)?.groupValues?.get(1), IDS.find(xml)?.groupValues?.get(1), HREF.find(xml)?.groupValues?.get(1))

    /** Reads ids from FDF bytes decoded as ISO-8859-1. */
    fun fromFdf(text: String): Incoming = Incoming(null, FDF_ID.find(text)?.groupValues?.get(1), FDF_F.find(text)?.groupValues?.get(1))

    /** Inserts `<ids original modified>` right after the opening `<xfdf …>` tag (export of "comments only"). */
    fun withIds(xml: String, idHex: String, reviewId: String?): String {
        val i = xml.indexOf("<xfdf"); if (i < 0) return xml
        val end = xml.indexOf('>', i); if (end < 0) return xml
        val extra = buildString {
            append("\n<ids original=\"").append(idHex).append("\" modified=\"").append(idHex).append("\"/>")
            if (reviewId != null) append("\n<!-- SDReviewId=\"").append(reviewId).append("\" -->")
        }
        return xml.substring(0, end + 1) + extra + xml.substring(end + 1)
    }
}

/** Share-sheet choices (pure). */
object ShareChoice {
    enum class Pages { ALL, CURRENT, RANGE }
    enum class Format { AS_IS, COMPRESSED, NO_COMMENTS, FLATTENED, IMAGES, COMMENTS_XFDF }

    const val MAX_IMAGE_PAGES = 60

    /** Zero-based page indices for the choice; null when the range text is invalid. */
    fun pages(choice: Pages, current: Int, range: String, count: Int): List<Int>? = when (choice) {
        Pages.ALL -> (0 until count).toList()
        Pages.CURRENT -> listOf(current.coerceIn(0, (count - 1).coerceAtLeast(0)))
        Pages.RANGE -> parseRange(range, count).takeIf { it.isNotEmpty() }
    }

    /** "1-3, 5, 8-" style ranges, 1-based in the text, returned 0-based, sorted, unique; out-of-range parts ignored. */
    fun parseRange(spec: String, count: Int): List<Int> {
        val out = sortedSetOf<Int>()
        for (raw in spec.split(',', '،', ';')) {
            val part = raw.trim().map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
            if (part.isEmpty()) continue
            val m = Regex("""^(\d*)\s*-\s*(\d*)$""").find(part)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: count
                for (p in minOf(a, b)..maxOf(a, b)) if (p in 1..count) out += p - 1
            } else part.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }
        }
        return out.toList()
    }

    /** Output file name: "<base> (p.3).pdf", "<base> (p.2-5).pdf", "<base> - comments.xfdf". */
    fun outName(base: String, pages: List<Int>, count: Int, format: Format): String {
        val suffix = when {
            pages.size == count || pages.isEmpty() -> ""
            pages.size == 1 -> " (p.${pages[0] + 1})"
            pages.zipWithNext().all { (a, b) -> b == a + 1 } -> " (p.${pages.first() + 1}-${pages.last() + 1})"
            else -> " (${pages.size}p)"
        }
        return when (format) {
            Format.COMMENTS_XFDF -> "$base - comments.xfdf"
            Format.IMAGES -> if (pages.size == 1) "$base$suffix.jpg" else "$base - p%d.jpg"
            else -> "$base$suffix.pdf"
        }
    }
}
