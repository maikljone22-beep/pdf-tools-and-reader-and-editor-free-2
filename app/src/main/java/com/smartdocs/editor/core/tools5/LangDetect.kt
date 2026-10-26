package com.smartdocs.editor.core.tools5

/**
 * OCR language auto-detection from a quick first pass: script counts, then letters specific to Persian / Urdu
 * inside the Arabic script and stop words inside Latin. Returns Tesseract codes, dominant first. Plain Kotlin.
 */
object LangDetect {

    private val stop = mapOf(
        "eng" to setOf("the", "and", "of", "to", "is", "in", "for", "that", "with", "this", "on", "are"),
        "fra" to setOf("le", "la", "les", "et", "des", "est", "une", "du", "pour", "dans", "que", "pas"),
        "deu" to setOf("der", "die", "und", "das", "ist", "nicht", "mit", "den", "ein", "eine", "zu", "von"),
        "spa" to setOf("el", "los", "las", "y", "que", "por", "una", "con", "para", "del", "es", "como"),
        "ita" to setOf("il", "di", "che", "e", "per", "gli", "della", "una", "sono", "non", "con", "del"),
        "tur" to setOf("ve", "bir", "bu", "için", "ile", "da", "de", "olarak", "çok", "daha", "gibi", "ne"),
        "por" to setOf("o", "os", "que", "não", "uma", "com", "para", "do", "da", "em", "são", "mais"),
    )

    /** Persian-only letters: peh, tcheh, jeh, gaf, keheh, farsi yeh. */
    private val persian = setOf(0x067E, 0x0686, 0x0698, 0x06AF, 0x06A9, 0x06CC)
    /** Urdu-only letters: tteh, ddal, rreh, noon ghunna, heh goal, yeh barree. */
    private val urdu = setOf(0x0679, 0x0688, 0x0691, 0x06BA, 0x06C1, 0x06D2)

    fun detect(text: String, max: Int = 3): List<String> {
        var arabic = 0; var latin = 0; var cyr = 0; var greek = 0; var hebrew = 0; var deva = 0; var cjk = 0
        var fa = 0; var ur = 0
        for (c in text) {
            val k = c.code
            when {
                TextNorm.isArabicChar(c) && c.isLetter() -> { arabic++; if (k in persian) fa++; if (k in urdu) ur++ }
                k in 0x0041..0x005A || k in 0x0061..0x007A || k in 0x00C0..0x024F -> latin++
                k in 0x0400..0x04FF -> cyr++
                k in 0x0370..0x03FF -> greek++
                k in 0x0590..0x05FF -> hebrew++
                k in 0x0900..0x097F -> deva++
                k in 0x4E00..0x9FFF -> cjk++
            }
        }
        val total = arabic + latin + cyr + greek + hebrew + deva + cjk
        if (total == 0) return listOf("ara", "eng")
        val arabicLang = when {
            ur * 50 > arabic && ur >= 3 -> "urd"
            fa * 30 > arabic && fa >= 3 -> "fas"
            else -> "ara"
        }
        val scores = linkedMapOf(arabicLang to arabic, latinLang(text) to latin, "rus" to cyr, "ell" to greek, "heb" to hebrew, "hin" to deva, "chi_sim" to cjk)
        val res = scores.entries.filter { it.value > 0 && it.value * 10 >= total }.sortedByDescending { it.value }.map { it.key }.take(max)
        return res.ifEmpty { listOf(scores.maxByOrNull { it.value }!!.key) }
    }

    /**
     * Languages for OCR from a first-pass [text]: [detect], plus English when the dominant script is Arabic and the
     * text has any Latin words at all (at least [minLatin] letters). Arabic study material carries English terms and
     * formulas (CO2, Thermochemistry) far below [detect]'s 10 % share; without "eng" Tesseract turns them into
     * Arabic-looking garbage. Adding a second model costs little; missing one loses words.
     */
    fun forOcr(text: String, minLatin: Int = 8): List<String> {
        val langs = detect(text)
        val arabicScript = langs.firstOrNull() in setOf("ara", "fas", "urd")
        if (!arabicScript || langs.any { it in stop.keys }) return langs
        val latin = text.count { it.code in 0x0041..0x005A || it.code in 0x0061..0x007A }
        return if (latin >= minLatin) langs + latinLang(text) else langs
    }

    /** Latin language from stop-word hits; English when nothing matches. */
    fun latinLang(text: String): String {
        val words = text.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "eng"
        val hits = stop.mapValues { (_, set) -> words.count { it in set } }
        val best = hits.maxByOrNull { it.value } ?: return "eng"
        return if (best.value >= 2) best.key else "eng"
    }
}

/** Mail merge: automatic form field ↔ CSV column mapping and per-row file names. Plain Kotlin. */
object MergeMapping {

    fun key(s: String): String = TextNorm.arabicKey(s).filter { it.isLetterOrDigit() }

    /** For each field, the best column (exact key, then one key containing the other), or null. Each column is used once. */
    fun auto(fields: List<String>, columns: List<String>): Map<String, String?> {
        val used = HashSet<String>()
        val res = LinkedHashMap<String, String?>()
        val keys = columns.associateWith { key(it) }
        fun take(f: String, pred: (String, String) -> Boolean): String? {
            val fk = key(f.substringAfterLast('.'))
            if (fk.isEmpty()) return null
            return columns.firstOrNull { c -> c !in used && keys[c]!!.isNotEmpty() && pred(fk, keys[c]!!) }?.also { used += it }
        }
        fields.forEach { f -> res[f] = take(f) { a, b -> a == b } }
        fields.filter { res[it] == null }.forEach { f -> res[f] = take(f) { a, b -> (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b)) } }
        return res
    }

    /** Values for one row: field → cell text (missing columns give empty values). */
    fun values(mapping: Map<String, String?>, header: List<String>, row: List<String>): Map<String, String> =
        mapping.mapNotNull { (field, col) ->
            col ?: return@mapNotNull null
            val i = header.indexOf(col)
            field to (if (i in row.indices) row[i] else "")
        }.toMap()

    /** Output name for a row from [column] (or the row number), safe and unique within [taken]. */
    fun fileName(base: String, header: List<String>, row: List<String>, column: String?, index: Int, taken: MutableSet<String>): String {
        val i = column?.let { header.indexOf(it) } ?: -1
        val raw = if (i in row.indices && row[i].isNotBlank()) row[i] else base + "_" + (index + 1)
        var name = TextNorm.safeFileName(raw, 80).ifBlank { base + "_" + (index + 1) }
        var n = 2
        val stem = name
        while (!taken.add(name.lowercase())) name = stem + "_" + n++
        return name
    }
}
