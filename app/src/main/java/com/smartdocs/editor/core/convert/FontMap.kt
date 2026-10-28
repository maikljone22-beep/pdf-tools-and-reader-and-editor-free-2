package com.smartdocs.editor.core.convert

/**
 * Maps PDF font names ("ABCDEF+Arial-BoldMT", "TimesNewRomanPSMT", "TraditionalArabic,Bold") to the common family
 * names Office and browsers know. Pure Kotlin.
 */
object FontMap {

    const val ARIAL = "Arial"
    const val TIMES = "Times New Roman"
    const val COURIER = "Courier New"

    /** Normalised key (lower case, letters and digits only) → family. Longest key wins. */
    private val KNOWN: List<Pair<String, String>> = listOf(
        "arialunicodems" to "Arial Unicode MS", "arialnarrow" to "Arial Narrow", "arialblack" to "Arial Black", "arial" to ARIAL,
        "helveticaneue" to "Helvetica Neue", "helvetica" to ARIAL, "liberationsans" to ARIAL, "nimbussans" to ARIAL, "dejavusans" to "DejaVu Sans",
        "timesnewroman" to TIMES, "times" to TIMES, "liberationserif" to TIMES, "nimbusroman" to TIMES, "tinos" to TIMES,
        "couriernew" to COURIER, "courier" to COURIER, "liberationmono" to COURIER, "nimbusmono" to COURIER, "cousine" to COURIER,
        "calibri" to "Calibri", "cambriamath" to "Cambria Math", "cambria" to "Cambria", "candara" to "Candara", "consolas" to "Consolas",
        "constantia" to "Constantia", "corbel" to "Corbel", "georgia" to "Georgia", "verdana" to "Verdana", "tahoma" to "Tahoma",
        "segoeui" to "Segoe UI", "trebuchetms" to "Trebuchet MS", "trebuchet" to "Trebuchet MS", "garamond" to "Garamond",
        "bookantiqua" to "Book Antiqua", "palatino" to "Palatino Linotype", "centurygothic" to "Century Gothic", "century" to "Century",
        "gillsans" to "Gill Sans MT", "franklingothic" to "Franklin Gothic Medium", "lucidaconsole" to "Lucida Console",
        "lucidasans" to "Lucida Sans", "impact" to "Impact", "comicsans" to "Comic Sans MS", "symbol" to "Symbol",
        "wingdings" to "Wingdings", "roboto" to "Roboto", "opensans" to "Open Sans", "lato" to "Lato", "sourcesans" to "Source Sans Pro",
        "minionpro" to "Minion Pro", "myriadpro" to "Myriad Pro",
        // Arabic families
        "traditionalarabic" to "Traditional Arabic", "simplifiedarabicfixed" to "Simplified Arabic Fixed", "simplifiedarabic" to "Simplified Arabic",
        "sakkalmajalla" to "Sakkal Majalla", "arabictypesetting" to "Arabic Typesetting", "notonaskharabic" to "Noto Naskh Arabic",
        "notosansarabic" to "Noto Sans Arabic", "notokufiarabic" to "Noto Kufi Arabic", "notonastaliqurdu" to "Noto Nastaliq Urdu",
        "amiri" to "Amiri", "scheherazade" to "Scheherazade New", "lateef" to "Lateef", "cairo" to "Cairo", "tajawal" to "Tajawal",
        "almarai" to "Almarai", "harmattan" to "Harmattan", "dubai" to "Dubai", "aldhabi" to "Aldhabi", "andalus" to "Andalus",
        "urdutypesetting" to "Urdu Typesetting", "microsoftuighur" to "Microsoft Uighur", "geezapro" to "Geeza Pro", "kfgqpc" to "KFGQPC Uthmanic Script HAFS",
        "uthmanic" to "KFGQPC Uthmanic Script HAFS", "droidarabicnaskh" to "Droid Arabic Naskh", "droidarabickufi" to "Droid Arabic Kufi",
        "mohammadboldart" to "Mohammad Bold Art 1", "aparajita" to "Aparajita", "diwani" to "DecoType Naskh", "decotype" to "DecoType Naskh",
        "hacen" to "Hacen Liner Print-out", "gess" to "GE SS Two", "frutigerarabic" to "Frutiger LT Arabic",
    ).sortedByDescending { it.first.length }

    /** Families that contain Arabic glyphs (safe for complex-script runs). */
    private val ARABIC_CAPABLE = setOf(
        ARIAL, TIMES, COURIER, "Tahoma", "Segoe UI", "Arial Unicode MS", "Microsoft Sans Serif", "DejaVu Sans",
        "Traditional Arabic", "Simplified Arabic", "Simplified Arabic Fixed", "Sakkal Majalla", "Arabic Typesetting", "Noto Naskh Arabic",
        "Noto Sans Arabic", "Noto Kufi Arabic", "Noto Nastaliq Urdu", "Amiri", "Scheherazade New", "Lateef", "Cairo", "Tajawal", "Almarai",
        "Harmattan", "Dubai", "Aldhabi", "Andalus", "Urdu Typesetting", "Microsoft Uighur", "Geeza Pro", "KFGQPC Uthmanic Script HAFS",
        "Droid Arabic Naskh", "Droid Arabic Kufi", "Mohammad Bold Art 1", "DecoType Naskh", "Hacen Liner Print-out", "GE SS Two", "Frutiger LT Arabic",
    )

    private val STYLE_WORDS = listOf("bolditalic", "boldoblique", "semibold", "demibold", "extrabold", "ultrabold", "bold", "italic", "oblique",
        "regular", "medium", "light", "black", "heavy", "condensed", "narrow", "book", "normal", "psmt", "mt", "ps")

    /** Base name without subset prefix and style suffixes, e.g. "ABCDEF+Arial-BoldMT" → "Arial". */
    fun baseName(raw: String): String {
        var s = raw.substringAfter('+')
        s = s.substringBefore(',').substringBefore('-')
        // "TimesNewRomanPSMT" / "ArialMT": drop trailing style tokens glued to the name
        var changed = true
        while (changed) {
            changed = false
            val low = s.lowercase()
            for (w in STYLE_WORDS) if (low.endsWith(w) && s.length > w.length + 2) { s = s.substring(0, s.length - w.length); changed = true; break }
        }
        return s
    }

    private fun key(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * Family for a PDF font. [serif] / [monospace] come from the font descriptor flags and are used when the name
     * is unknown (serif → Times New Roman, monospace → Courier New, else Arial).
     */
    fun family(raw: String?, serif: Boolean = false, monospace: Boolean = false): String {
        val name = raw?.trim().orEmpty()
        if (name.isNotEmpty()) {
            // nav-prefs: the user's font mapping (Settings → more preferences → font mapping) wins
            com.smartdocs.editor.core.data.NpfText.userFontMap().let { m -> m[com.smartdocs.editor.core.data.NpfText.fontKey(name)] ?: m[com.smartdocs.editor.core.data.NpfText.fontKey(baseName(name))] }?.let { return it }
            val k = key(name.substringAfter('+'))
            KNOWN.firstOrNull { k.startsWith(it.first) }?.let { return it.second }
            KNOWN.firstOrNull { it.first.length >= 5 && k.contains(it.first) }?.let { return it.second }
        }
        return when { monospace -> COURIER; serif -> TIMES; else -> ARIAL }
    }

    fun hasArabic(family: String?): Boolean = family != null && family in ARABIC_CAPABLE

    /** Font for complex-script (Arabic) runs: the family itself when it has Arabic glyphs, else Arial. */
    fun complexScript(family: String?): String = if (hasArabic(family)) family!! else ARIAL

    private val SYMBOL = mapOf(
        0xB7 to "•", 0xDC to "⇐", 0xDE to "⇒", 0xDB to "⇔", 0xAE to "→", 0xAC to "←", 0xAB to "↔", 0x5C to "∴", 0xB4 to "×", 0xB8 to "÷",
        0xB1 to "±", 0xB0 to "°", 0xBB to "≈", 0xA3 to "≤", 0xB3 to "≥", 0xB9 to "≠", 0xBA to "≡", 0xA5 to "∞", 0xD6 to "√", 0xE5 to "∑",
        0x44 to "Δ", 0x53 to "Σ", 0x57 to "Ω", 0x50 to "Π", 0x46 to "Φ", 0x61 to "α", 0x62 to "β", 0x67 to "γ", 0x64 to "δ", 0x65 to "ε",
        0x6C to "λ", 0x6D to "μ", 0x70 to "π", 0x72 to "ρ", 0x73 to "σ", 0x74 to "τ", 0x71 to "θ", 0x77 to "ω", 0x6E to "ν", 0x2D to "−",
        0x2B to "+", 0x3D to "=", 0x28 to "(", 0x29 to ")", 0x2E to ".", 0x2C to ",",
    )
    private val WINGDINGS = mapOf(
        0x3F to "✍", 0xFC to "✓", 0xFB to "✗", 0xFE to "☑", 0xFD to "☒", 0x76 to "❖", 0xA7 to "▪", 0x6C to "●", 0x6E to "■", 0x71 to "❑",
        0x77 to "◆", 0xD8 to "➢", 0xA8 to "◻", 0x4A to "☺", 0xE0 to "→", 0xDF to "←", 0xE8 to "➔", 0x9F to "•", 0xA1 to "○",
    )

    /**
     * Unicode for a glyph of a Symbol / Wingdings font whose PDF text is a private-use code (U+F020–U+F0FF, the font's
     * own character code + F000): Word's bullets («•»), arrows («⇐», «→»), «∴», «✓», «✍». Such codes were dropped as
     * private-use text, so list bullets and the arrows of worked examples were lost. Unknown Wingdings codes become «•»;
     * null for any other font or code.
     */
    fun symbolText(rawFont: String?, text: String): String? {
        if (text.length != 1) return null
        val c = text[0].code
        if (c !in 0xF020..0xF0FF) return null
        val k = key(rawFont.orEmpty().substringAfter('+'))
        return when {
            k.startsWith("symbol") -> SYMBOL[c - 0xF000]
            k.startsWith("wingdings") -> WINGDINGS[c - 0xF000] ?: "•"
            else -> null
        }
    }
}
