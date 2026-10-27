package com.smartdocs.editor.core.ai5

enum class PiiKind { EMAIL, PHONE, CARD, IBAN, NATIONAL_ID, PASSPORT, DATE, URL, IP, PERSON, ORG, ROLE }

/** One finding in the text given to [PiiDetector.detect]: [start] inclusive, [end] exclusive (char indices). */
data class PiiMatch(val kind: PiiKind, val start: Int, val end: Int, val value: String)

/**
 * On-device personal-data detector (no network): e-mail, phone numbers (international, Gulf / Yemen mobile
 * formats), payment cards (Luhn-checked), IBAN (mod-97 checked), national IDs and passport numbers (by label or
 * Saudi 10-digit pattern), dates, URLs and IPv4. Arabic-Indic digits are recognised. Matches never overlap; the
 * first kind in [PiiKind] order wins. Used by redaction (see [PiiScanner] for page rectangles).
 */
object PiiDetector {
    private const val D = "[0-9]"
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val URL = Regex("(?:https?://|www\\.)[^\\s<>\"'،]+", RegexOption.IGNORE_CASE)
    private val IBAN = Regex("\\b[A-Z]{2}[0-9]{2}(?:[ ]?[A-Z0-9]){11,30}\\b")
    private val CARD = Regex("(?<![0-9])(?:$D[ -]?){12,18}$D(?![0-9])")
    private val PHONE = Regex("(?<![0-9A-Za-z])(?:\\+|00)?[0-9](?:[ ().-]{0,2}[0-9]){7,14}(?![0-9])")
    private val DATE = Regex("(?<![0-9])(?:[0-3]?[0-9][/.-][01]?[0-9][/.-](?:19|20|14)?[0-9]{2}|(?:19|20|14)[0-9]{2}[/.-][01]?[0-9][/.-][0-3]?[0-9])(?![0-9])")
    private val IP = Regex("(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9.])")
    private val ID_LABEL = Regex("(?:رقم\\s+(?:ال)?هوي[ةه]|(?:ال)?رقم\\s+(?:ال)?وطني|رقم\\s+(?:ال)?سجل\\s+(?:ال)?مدني|رقم\\s+(?:ال)?إقام[ةه]|رقم\\s+(?:ال)?بطاق[ةه]|national\\s+id|id\\s*(?:no|number)|ssn)\\s*[:#.]?\\s*([A-Z]?[0-9][0-9 -]{4,18}[0-9])", RegexOption.IGNORE_CASE)
    private val PASSPORT_LABEL = Regex("(?:رقم\\s+(?:ال)?جواز(?:\\s+(?:ال)?سفر)?|جواز\\s+(?:ال)?سفر|passport(?:\\s*(?:no|number))?)\\s*[:#.]?\\s*([A-Z]{0,2}[0-9]{6,9})", RegexOption.IGNORE_CASE)
    private val SAUDI_ID = Regex("(?<![0-9])[12][0-9]{9}(?![0-9])")

    fun detect(text: String, kinds: Set<PiiKind> = PiiKind.entries.toSet() - PiiEntities.KINDS): List<PiiMatch> {
        if (text.isEmpty()) return emptyList()
        val t = AiNorm.digits(text)   // same length: one char per digit
        val found = ArrayList<PiiMatch>()
        fun add(kind: PiiKind, start: Int, end: Int) {
            if (kind !in kinds || end <= start) return
            if (found.any { start < it.end && it.start < end }) return
            found += PiiMatch(kind, start, end, text.substring(start, end))
        }
        EMAIL.findAll(t).forEach { add(PiiKind.EMAIL, it.range.first, it.range.last + 1) }
        URL.findAll(t).forEach { m -> val e = trimEnd(t, m.range.first, m.range.last + 1); add(PiiKind.URL, m.range.first, e) }
        IBAN.findAll(t).forEach { if (ibanValid(it.value)) add(PiiKind.IBAN, it.range.first, it.range.last + 1) }
        ID_LABEL.findAll(t).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.NATIONAL_ID, it.first, it.last + 1) } }
        PASSPORT_LABEL.findAll(t).forEach { m -> m.groups[1]?.range?.let { add(PiiKind.PASSPORT, it.first, it.last + 1) } }
        CARD.findAll(t).forEach { m -> val digits = m.value.filter { it.isDigit() }; if (digits.length in 13..19 && luhn(digits)) add(PiiKind.CARD, m.range.first, m.range.last + 1) }
        IP.findAll(t).forEach { add(PiiKind.IP, it.range.first, it.range.last + 1) }
        DATE.findAll(t).forEach { if (dateValid(it.value)) add(PiiKind.DATE, it.range.first, it.range.last + 1) }
        SAUDI_ID.findAll(t).forEach { add(PiiKind.NATIONAL_ID, it.range.first, it.range.last + 1) }
        PHONE.findAll(t).forEach { m -> if (phoneLike(m.value)) add(PiiKind.PHONE, m.range.first, m.range.last + 1) }
        if (kinds.any { it in PiiEntities.KINDS }) PiiEntities.detectOffline(text, kinds).forEach { add(it.kind, it.start, it.end) }
        return found.sortedBy { it.start }
    }

    private fun trimEnd(t: String, s: Int, e0: Int): Int { var e = e0; while (e > s && t[e - 1] in ".,;:)!?]") e--; return e }

    fun luhn(digits: String): Boolean {
        var sum = 0; var alt = false
        for (i in digits.indices.reversed()) {
            var n = digits[i] - '0'
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n; alt = !alt
        }
        return sum % 10 == 0
    }

    fun ibanValid(raw: String): Boolean {
        val s = raw.replace(" ", "").uppercase()
        if (s.length !in 15..34) return false
        val r = s.substring(4) + s.substring(0, 4)
        var mod = 0
        for (c in r) {
            val v = when (c) { in '0'..'9' -> (c - '0').toString(); in 'A'..'Z' -> (c - 'A' + 10).toString(); else -> return false }
            for (d in v) mod = (mod * 10 + (d - '0')) % 97
        }
        return mod == 1
    }

    private fun dateValid(v: String): Boolean {
        val parts = v.split('/', '.', '-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return false
        val (a, b, c) = parts
        return if (v.indexOfFirst { !it.isDigit() } >= 4) b in 1..12 && c in 1..31 else a in 1..31 && b in 1..12
    }

    /** 9–15 digits; a leading +/00 or a typical mobile prefix; not a plain number like 1,000,000. */
    private fun phoneLike(v: String): Boolean {
        val digits = v.filter { it.isDigit() }
        if (digits.length !in 9..15) return false
        if (v.contains(',')) return false
        val intl = v.startsWith("+") || v.startsWith("00")
        val mobile = Regex("^(?:05|07|7[01378]|9[0-9]|01)").containsMatchIn(digits)
        val grouped = v.count { it == ' ' || it == '-' || it == '(' } >= 1
        return intl || mobile || grouped
    }

    /** Masked preview for the UI: keeps the last 4 characters. */
    fun mask(v: String): String = if (v.length <= 4) "••••" else "•".repeat((v.length - 4).coerceAtMost(8)) + v.takeLast(4)
}
