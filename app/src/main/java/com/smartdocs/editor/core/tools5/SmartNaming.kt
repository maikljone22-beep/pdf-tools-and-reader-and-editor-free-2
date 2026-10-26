package com.smartdocs.editor.core.tools5

/** Kinds of the scan studio. Pure enum, shared by core and UI. */
enum class ScanKind { DOCUMENT, ID_CARD, BUSINESS_CARD, RECEIPT, WHITEBOARD, BOOK, BATCH }

/**
 * Smart file name after OCR: "<prefix>_<first prominent line>_<date>" — the date found in the text when there
 * is one, else [today] (yyyy-MM-dd). Plain Kotlin, unit-tested.
 */
object SmartNaming {

    /** Words that make a poor title (form boiler-plate, page markers). */
    private val weak = setOf("page", "صفحة", "date", "التاريخ", "tel", "fax", "www", "http", "https", "no", "رقم")

    /** The first line that looks like a title: enough letters, not mostly digits, not boiler-plate. */
    fun titleLine(text: String, maxWords: Int = 6): String? {
        for (raw in text.lines()) {
            val l = TextNorm.asciiDigits(raw).filterNot(TextNorm::isInvisible).trim()
            if (l.length < 3 || l.length > 90) continue
            val letters = l.count { it.isLetter() }
            if (letters < 3 || letters < l.length / 2) continue
            val words = l.split(Regex("""\s+""")).map { TextNorm.trimPunct(it) }.filter { it.isNotEmpty() }
            val lettered = words.filter { w -> w.any { it.isLetter() } }
            if (lettered.isEmpty() || lettered.all { it.lowercase() in weak }) continue
            if (l.contains('@') || l.contains("www.", true)) continue
            return words.take(maxWords).joinToString(" ")
        }
        return null
    }

    fun suggest(text: String, kind: ScanKind, today: String, prefix: Map<ScanKind, String> = emptyMap()): String {
        val date = ReceiptParser.parseDate(text) ?: today
        val core: String? = when (kind) {
            ScanKind.RECEIPT -> ReceiptParser.parse(text).merchant.ifBlank { null } ?: titleLine(text)
            ScanKind.BUSINESS_CARD -> BusinessCardParser.parse(text).let { c -> c.name.ifBlank { c.company }.ifBlank { null } }
            else -> titleLine(text)
        }
        val parts = listOfNotNull(prefix[kind]?.ifBlank { null }, core?.let { TextNorm.safeFileName(it, 50) }?.ifBlank { null }, date)
        return TextNorm.safeFileName(parts.joinToString("_"), 90).ifBlank { "Scan_$today" }
    }
}
