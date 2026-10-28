package com.smartdocs.editor.core.selmenu

import com.smartdocs.editor.core.edit5.LinkDetect

/** Kinds of "smart" entities found in selected text (Android TextClassifier style). */
enum class EntityKind { PHONE, EMAIL, URL, ADDRESS, DATE, NUMBER }

/**
 * An entity of the selection. [range] indexes the selected text; [value] is the normalised form the action uses:
 * PHONE → "+967771234567" / "0771234567" (ASCII digits, a leading 00 becomes +), EMAIL → the address, URL → an
 * https:// URI, ADDRESS → the one-line address, DATE → ISO "yyyy-MM-dd", NUMBER → ASCII number ("1234.5").
 */
data class SelEntity(val kind: EntityKind, val range: IntRange, val text: String, val value: String) {
    /** A phone number written with its country code (wa.me needs one). */
    val international: Boolean get() = kind == EntityKind.PHONE && value.startsWith("+")
}

/**
 * Pure entity detection for the selection menu. Works on Arabic text: Arabic-Indic / Persian digits and the Arabic
 * decimal separator are read as ASCII first (one character for one character, so ranges stay valid), Arabic
 * month names are understood, and Arabic address words (شارع، حي، طريق، ص.ب) are recognised.
 */
object SelEntities {
    /** Longer selections are not scanned (a whole page selected is not "an entity"). */
    const val MAX_SCAN = 4000

    private val phone = Regex("""(?<![\p{L}\p{N}+])(?:\+|00)?\d[\d \-()]{5,}\d(?![\p{L}\p{N}])""")
    private val bareDomain = Regex("""(?i)(?<![\p{L}\p{N}@./])([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|edu|gov|info|io|app|dev|me|co|uk|sa|ye|eg|ae|qa|kw|om|bh|jo|lb|sy|iq|ma|dz|tn|ly|sd)(?:/[^\s"'<>،]*)?)(?![\p{L}\p{N}])""")
    private val isoDate = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val dmyDate = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)""")
    private val number = Regex("""(?<![\p{L}\p{N}.,])[-+]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?%?(?![\p{L}\p{N}])""")

    private val months: Map<String, Int> = buildMap {
        listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
            .forEachIndexed { i, m -> put(m, i + 1); put(m.take(3), i + 1) }
        put("sept", 9)
        listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
            .forEachIndexed { i, m -> put(m, i + 1) }
        put("ابريل", 4); put("إبريل", 4); put("اغسطس", 8); put("اكتوبر", 10); put("يونية", 6); put("يولية", 7)
        listOf("كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول")
            .forEachIndexed { i, m -> put(m, i + 1) }
    }
    private val monthAlt = months.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val textDate = Regex("""(?i)(?<!\d)(\d{1,2})\s+($monthAlt)[,،]?\s+(\d{4})(?!\d)""")
    private val textDateUs = Regex("""(?i)\b($monthAlt)\s+(\d{1,2}),?\s+(\d{4})(?!\d)""")
    private val addressWords = Regex("""(?i)(شارع|حي |طريق|جولة|ص\.\s?ب|صندوق بريد|عمارة|\bstreet\b|\bst\.|\broad\b|\brd\.|\bavenue\b|\bave\.|\bboulevard\b|\bblvd\b|\blane\b|\bp\.?\s?o\.?\s?box\b|\bsuite\b)""")

    fun detect(selected: String): List<SelEntity> {
        if (selected.isBlank() || selected.length > MAX_SCAN) return emptyList()
        val t = SelText.latinDigits(selected)
        val out = ArrayList<SelEntity>()
        fun free(r: IntRange) = out.none { it.range.first <= r.last && r.first <= it.range.last }
        fun add(kind: EntityKind, r: IntRange, value: String) { if (free(r)) out += SelEntity(kind, r, selected.substring(r.first, r.last + 1), value) }

        // URLs and e-mails (shared with the plain-text link detection of the reader)
        LinkDetect.find(t, phones = false).forEach { l ->
            if (l.target.startsWith("mailto:")) add(EntityKind.EMAIL, l.range, l.target.removePrefix("mailto:"))
            else add(EntityKind.URL, l.range, l.target)
        }
        bareDomain.findAll(t).forEach { m -> val g = m.groups[1]!!; add(EntityKind.URL, g.range, "https://" + g.value) }
        // dates before phones and numbers: "12-05-2024" is a date, not a phone
        isoDate.findAll(t).forEach { m -> iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { add(EntityKind.DATE, m.range, it) } }
        dmyDate.findAll(t).forEach { m -> dmy(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])?.let { add(EntityKind.DATE, m.range, it) } }
        textDate.findAll(t).forEach { m -> months[m.groupValues[2].lowercase()]?.let { mo -> iso(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt())?.let { add(EntityKind.DATE, m.range, it) } } }
        textDateUs.findAll(t).forEach { m -> months[m.groupValues[1].lowercase()]?.let { mo -> iso(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())?.let { add(EntityKind.DATE, m.range, it) } } }
        phone.findAll(t).forEach { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits in 7..15) {
                val d = m.value.filter { it.isDigit() }
                val v = when { m.value.startsWith("+") -> "+$d"; d.startsWith("00") -> "+" + d.drop(2); else -> d }
                add(EntityKind.PHONE, m.range, v)
            }
        }
        // an address is the whole (short) selection when it carries a street / P.O. box word
        if (selected.length <= 240 && addressWords.containsMatchIn(selected) && out.none { it.kind == EntityKind.URL || it.kind == EntityKind.EMAIL }) {
            val one = selected.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            val first = selected.indexOfFirst { !it.isWhitespace() }; val last = selected.indexOfLast { !it.isWhitespace() }
            if (first >= 0) out += SelEntity(EntityKind.ADDRESS, first..last, selected.substring(first, last + 1), one)
        }
        // a number only when the selection is just that number (otherwise every page would offer it)
        val trimmed = t.trim()
        if (out.isEmpty() && number.matches(trimmed)) {
            val first = t.indexOf(trimmed)
            add(EntityKind.NUMBER, first until first + trimmed.length, trimmed.replace(",", ""))
        }
        return out.sortedBy { it.range.first }.take(6)
    }

    private fun iso(y: Int, m: Int, d: Int): String? {
        if (y !in 1900..2200 || m !in 1..12 || d !in 1..daysIn(y, m)) return null
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }

    /** Day-first (the Arabic and European order); month-first only when the first number cannot be a month day pair. */
    private fun dmy(a: Int, b: Int, yRaw: String): String? {
        val y = if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt()
        return iso(y, b, a) ?: iso(y, a, b)
    }

    private fun daysIn(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** wa.me link of an international phone number (digits only), or null. */
    fun whatsAppUri(e: SelEntity): String? = if (e.international) "https://wa.me/" + e.value.drop(1) else null
}
