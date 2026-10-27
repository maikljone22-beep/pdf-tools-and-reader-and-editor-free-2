package com.smartdocs.editor.core.search

/**
 * Country / region patterns for Search & Redact (Foxit "Country/Region": US, UK, Canada …), with the checksums
 * where the format has one: US SSN (area / group / serial rules), US phone, UK National Insurance number, UK phone,
 * UK postcode, UK driving licence, Canada SIN (Luhn), India Aadhaar (Verhoeff) and PAN, Spain DNI / NIE (check
 * letter), Brazil CPF (two check digits), China resident ID (ISO 7064 MOD 11-2), France and Germany phones.
 * Works on text whose digits are already Latin. Pure Kotlin, unit-tested.
 */
object RegionPatterns {
    private const val NB = "(?<![\\d\\p{L}])"
    private const val NA = "(?![\\d\\p{L}])"

    private val US_SSN = Regex("$NB(\\d{3})[- ]?(\\d{2})[- ]?(\\d{4})$NA")
    private val US_PHONE = Regex("$NB(?:\\+?1[\\s.-]?)?\\(?[2-9]\\d{2}\\)?[\\s.-]?[2-9]\\d{2}[\\s.-]?\\d{4}$NA")
    private val UK_NINO = Regex("(?<![A-Za-z0-9])(?!BG|GB|KN|NK|NT|TN|ZZ)[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z] ?\\d{2} ?\\d{2} ?\\d{2} ?[A-D](?![A-Za-z0-9])")
    private val UK_PHONE = Regex("$NB(?:(?:\\+|00)44\\s?|0)(?:7\\d{3}|1\\d{2,3}|2\\d|3\\d{2})[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}$NA")
    private val UK_POSTCODE = Regex("(?<![A-Za-z0-9])(?:[A-PR-UWYZ][0-9][0-9A-HJKMNPR-Y]?|[A-PR-UWYZ][A-HK-Y][0-9][0-9ABEHMNPRV-Y]?) ?[0-9][ABD-HJLNP-UW-Z]{2}(?![A-Za-z0-9])")
    private val UK_DRIVING = Regex("(?<![A-Za-z0-9])[A-Z9]{5}\\d[0156]\\d(?:[0][1-9]|[12]\\d|3[01])\\d[A-Z9]{2}\\d[A-Z]{2}(?![A-Za-z0-9])")
    private val CA_SIN = Regex("$NB\\d{3}[- ]?\\d{3}[- ]?\\d{3}$NA")
    private val IN_AADHAAR = Regex("$NB[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}$NA")
    private val IN_PAN = Regex("(?<![A-Za-z0-9])[A-Z]{3}[ABCFGHLJPT][A-Z]\\d{4}[A-Z](?![A-Za-z0-9])")
    private val ES_DNI = Regex("(?<![A-Za-z0-9])(?:\\d{8}|[XYZ]\\d{7})-?[A-Z](?![A-Za-z0-9])")
    private val BR_CPF = Regex("$NB\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}$NA")
    private val CN_ID = Regex("$NB\\d{17}[\\dXx](?![\\dA-Za-z])")
    private val FR_PHONE = Regex("$NB(?:(?:\\+|00)33\\s?|0)[1-9](?:[\\s.-]?\\d{2}){4}$NA")
    private val DE_PHONE = Regex("$NB(?:(?:\\+|00)49\\s?|0)(?:1[5-7]\\d|[2-9]\\d{1,4})[\\s/-]?\\d{3,8}$NA")

    private fun digits(s: String) = s.filter { it in '0'..'9' }

    fun ssnValid(area: Int, group: Int, serial: Int) = area in 1..899 && area != 666 && group != 0 && serial != 0

    fun luhn(d: String): Boolean {
        var sum = 0; var alt = false
        for (i in d.indices.reversed()) { var n = d[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    private val VD = arrayOf(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5), intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7), intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1), intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3), intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0))
    private val VP = arrayOf(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4), intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7), intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1), intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8))

    /** Verhoeff check (Aadhaar). */
    fun verhoeff(d: String): Boolean {
        var c = 0
        d.reversed().forEachIndexed { i, ch -> c = VD[c][VP[i % 8][ch - '0']] }
        return c == 0
    }

    fun dniValid(v: String): Boolean {
        val s = v.replace("-", "").uppercase()
        val num = when (s[0]) { 'X' -> "0" + s.substring(1, 8); 'Y' -> "1" + s.substring(1, 8); 'Z' -> "2" + s.substring(1, 8); else -> s.substring(0, 8) }
        return "TRWAGMYFPDXBNJZSQVHLCKE"[num.toInt() % 23] == s.last()
    }

    fun cpfValid(v: String): Boolean {
        val d = digits(v)
        if (d.length != 11 || d.all { it == d[0] }) return false
        fun check(n: Int): Int { val s = (0 until n).sumOf { (d[it] - '0') * (n + 1 - it) }; val r = s * 10 % 11; return if (r == 10) 0 else r }
        return check(9) == d[9] - '0' && check(10) == d[10] - '0'
    }

    fun cnIdValid(v: String): Boolean {
        if (v.length != 18) return false
        val w = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        val sum = (0 until 17).sumOf { (v[it] - '0') * w[it] }
        return "10X98765432"[sum % 11] == v[17].uppercaseChar()
    }

    /** Matches of the regional [kinds] in [t] (Latin digits). */
    fun find(t: String, kinds: Set<PatternKind>, add: (PatternKind, IntRange) -> Unit) {
        fun each(re: Regex, k: PatternKind, ok: (MatchResult) -> Boolean = { true }) { if (k in kinds) re.findAll(t).forEach { m -> if (ok(m)) add(k, m.range) } }
        each(US_SSN, PatternKind.US_SSN) { m -> ssnValid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) && m.value.any { it == '-' || it == ' ' } }
        each(US_PHONE, PatternKind.US_PHONE)
        each(UK_NINO, PatternKind.UK_NINO)
        each(UK_PHONE, PatternKind.UK_PHONE) { digits(it.value).length in 10..13 }
        each(UK_POSTCODE, PatternKind.UK_POSTCODE)
        each(UK_DRIVING, PatternKind.UK_DRIVING)
        each(CA_SIN, PatternKind.CA_SIN) { m -> luhn(digits(m.value)) }
        each(IN_AADHAAR, PatternKind.IN_AADHAAR) { verhoeff(digits(it.value)) }
        each(IN_PAN, PatternKind.IN_PAN)
        each(ES_DNI, PatternKind.ES_DNI) { dniValid(it.value) }
        each(BR_CPF, PatternKind.BR_CPF) { cpfValid(it.value) }
        each(CN_ID, PatternKind.CN_ID) { cnIdValid(it.value) }
        each(FR_PHONE, PatternKind.FR_PHONE)
        each(DE_PHONE, PatternKind.DE_PHONE) { digits(it.value).length in 9..13 }
    }
}
