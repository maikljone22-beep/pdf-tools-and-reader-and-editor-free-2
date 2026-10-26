package com.smartdocs.editor.core.search

/** Kinds of sensitive data the pattern library finds. */
enum class PatternKind {
    PHONE_YE, PHONE_SA, PHONE_GULF, PHONE_EG, PHONE_INTL,
    EMAIL, URL, CARD, IBAN, NATIONAL_ID, PASSPORT,
    DATE, DATE_HIJRI, DATE_MONTH_NAME, AMOUNT,
}

/** A pattern hit: [range] in the ORIGINAL text, [value] as found. */
data class PatternMatch(val kind: PatternKind, val range: IntRange, val value: String)

/**
 * Regex library with validators for data that is usually redacted: phone numbers of Yemen, Saudi Arabia,
 * the Gulf and Egypt (plus generic international numbers), e-mail, URL, payment cards (Luhn), IBAN
 * (ISO 7064 mod-97), national IDs (Saudi/UAE Luhn, Egypt with a birth-date check, Yemen 11 digits), passports,
 * dates (Gregorian, Hijri, Arabic/English month names) and money amounts with a currency.
 * Arabic-Indic digits are matched like Latin digits. Pure Kotlin (no Android), so it is unit-tested.
 */
object SearchPatterns {

    private const val NB = "(?<![\\d\\p{L}])"   // no digit/letter before
    private const val NA = "(?![\\d\\p{L}])"    // no digit/letter after
    private const val SEP = "[\\s\\-.]?"

    private val PHONE_YE = Regex("$NB(?:(?:(?:\\+|00)967$SEP)?7[01378]\\d$SEP\\d{3}$SEP\\d{3}|(?:(?:\\+|00)967$SEP|0)[1-7]$SEP\\d{3}$SEP\\d{3})$NA")
    private val PHONE_SA = Regex("$NB(?:(?:\\+|00)966$SEP|0)5\\d$SEP\\d{3}$SEP\\d{4}$NA")
    private val PHONE_GULF = Regex("$NB(?:\\+|00)(?:971$SEP(?:5\\d|[2-9])|965$SEP[569]\\d|974$SEP[3567]\\d|973$SEP[36]\\d|968$SEP[79]\\d)(?:$SEP\\d){6,7}$NA")
    private val PHONE_EG = Regex("$NB(?:(?:\\+|00)20$SEP|0)1[0125]$SEP\\d{4}$SEP\\d{4}$NA")
    private val PHONE_INTL = Regex("$NB(?:\\+|00)[1-9]\\d{0,2}[\\s\\-]?\\(?\\d{1,4}\\)?(?:[\\s\\-]?\\d{2,4}){2,4}$NA")
    private val EMAIL = Regex("(?<![\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![\\w-])")
    private val URL = Regex("(?:(?:https?|ftp)://|www\\.)[^\\s<>\"'«»]+", RegexOption.IGNORE_CASE)
    private val CARD = Regex("$NB(?:\\d[ \\-]?){12,18}\\d$NA")
    private val IBAN = Regex("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,32}")
    private val NUMBER = Regex("$NB\\d{10,15}$NA")
    private val UAE_ID = Regex("${NB}784[\\s\\-]?\\d{4}[\\s\\-]?\\d{7}[\\s\\-]?\\d$NA")
    private val PASSPORT = Regex("(?<![A-Za-z0-9])[A-Z]{1,2}\\d{6,8}(?![A-Za-z0-9])")
    private val DATE_NUM = Regex("$NB(?:\\d{1,2}[/\\-.]\\d{1,2}[/\\-.](?:\\d{4}|\\d{2})|\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2})$NA")
    private val HIJRI_SUFFIX = Regex("^\\s*(?:\u0647\u062C\u0631\u064A\u0629?|\u0647\u0640|\u0647(?![\\p{L}])|AH(?![A-Za-z]))")
    private val HIJRI_YEAR = Regex("${NB}1[34]\\d{2}\\s*(?:\u0647\u0640|\u0647(?![\\p{L}])|\u0647\u062C\u0631\u064A\u0629?)")

    /** Arabic (Gregorian, Levantine and Hijri) and English month names. */
    val MONTHS_GREGORIAN = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "ابريل", "إبريل", "مايو", "يونيو", "يونيه", "يوليو", "يوليه", "أغسطس", "اغسطس", "سبتمبر", "أكتوبر", "اكتوبر", "نوفمبر", "ديسمبر",
        "كانون الثاني", "شباط", "آذار", "اذار", "نيسان", "أيار", "ايار", "حزيران", "تموز", "آب", "اب", "أيلول", "ايلول", "تشرين الأول", "تشرين الاول", "تشرين الثاني", "كانون الأول", "كانون الاول",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
        "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec",
    )
    val MONTHS_HIJRI = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الاول", "ربيع الآخر", "ربيع الاخر", "ربيع الثاني", "جمادى الأولى", "جمادى الاولى", "جمادى الأول", "جمادى الآخرة", "جمادى الاخرة", "جمادى الثانية",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذي القعدة", "ذو الحجة", "ذي الحجة",
    )
    private fun alt(l: List<String>) = l.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val DATE_MONTH = Regex("$NB\\d{1,2}\\s*(?:من\\s+)?(?:${alt(MONTHS_GREGORIAN + MONTHS_HIJRI)}),?\\s*(?:\\d{4}|\\d{2})?(?:\\s*(?:م|هـ|ه)(?![\\p{L}]))?", RegexOption.IGNORE_CASE)
    private val DATE_MONTH_FIRST = Regex("(?<![\\p{L}])(?:${alt(MONTHS_GREGORIAN)})\\s+\\d{1,2},?\\s+\\d{4}$NA", RegexOption.IGNORE_CASE)

    private const val CUR_WORDS = "ريالاً|ريالا|ريال|دولاراً|دولارا|دولار|درهماً|درهم|جنيهاً|جنيه|دينار|ليرة|يورو|ر\\.\\s?ي|ر\\.\\s?س|د\\.\\s?إ|ج\\.\\s?م"
    private const val DOLLAR = "\\u0024"   // regex escape for '$'
    private const val CUR_CODES = "SAR|YER|USD|AED|EGP|KWD|QAR|BHD|OMR|JOD|EUR|GBP|SR|US" + DOLLAR
    private const val NUM = "\\d{1,3}(?:[,\u066C]\\d{3})+(?:[.\u066B]\\d{1,2})?|\\d+(?:[.\u066B]\\d{1,2})?"
    private const val CUR_SIGNS = "[" + DOLLAR + "\u20AC\u00A3]"
    private val AMOUNT = Regex("(?:(?:$CUR_SIGNS|(?<![A-Za-z])(?:$CUR_CODES))\\s?(?:$NUM)|$NB(?:$NUM)\\s?(?:(?:$CUR_CODES)(?![A-Za-z])|$CUR_WORDS|$CUR_SIGNS))")

    /** Lengths of IBANs per country (common ones; others are only checked by mod-97). */
    private val IBAN_LEN = mapOf(
        "SA" to 24, "AE" to 23, "KW" to 30, "QA" to 29, "BH" to 22, "OM" to 23, "EG" to 29, "JO" to 30, "LB" to 28, "IQ" to 23,
        "PS" to 29, "TR" to 26, "GB" to 22, "DE" to 22, "FR" to 27, "IT" to 27, "ES" to 24, "NL" to 18, "CH" to 21, "BE" to 16, "AT" to 20,
    )

    /** Finds every pattern of [kinds] in [text], plus matches of [custom] regexes (reported as kind null → see [findCustom]). */
    fun find(text: String, kinds: Set<PatternKind>): List<PatternMatch> {
        if (text.isEmpty() || kinds.isEmpty()) return emptyList()
        val t = ArabicNormalizer.latinDigits(text)
        val out = ArrayList<PatternMatch>()
        fun add(k: PatternKind, r: IntRange) { if (!r.isEmpty()) out += PatternMatch(k, r, text.substring(r.first, r.last + 1)) }
        fun each(re: Regex, k: PatternKind, ok: (String) -> Boolean = { true }) {
            if (k in kinds) re.findAll(t).forEach { m -> if (ok(m.value)) add(k, m.range) }
        }
        each(PHONE_YE, PatternKind.PHONE_YE)
        each(PHONE_SA, PatternKind.PHONE_SA)
        each(PHONE_GULF, PatternKind.PHONE_GULF)
        each(PHONE_EG, PatternKind.PHONE_EG)
        each(PHONE_INTL, PatternKind.PHONE_INTL) { digits(it).length in 8..15 }
        each(EMAIL, PatternKind.EMAIL)
        if (PatternKind.URL in kinds) URL.findAll(t).forEach { m ->
            val v = m.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?', '،', '؛', '؟')
            add(PatternKind.URL, m.range.first until m.range.first + v.length)
        }
        each(CARD, PatternKind.CARD) { val d = digits(it); d.length in 13..19 && luhn(d) && !d.all { c -> c == d[0] } }
        if (PatternKind.IBAN in kinds) IBAN.findAll(t).forEach { m -> ibanPrefix(m.value)?.let { len -> add(PatternKind.IBAN, m.range.first until m.range.first + len) } }
        if (PatternKind.NATIONAL_ID in kinds) {
            NUMBER.findAll(t).forEach { m -> if (isNationalId(m.value)) add(PatternKind.NATIONAL_ID, m.range) }
            UAE_ID.findAll(t).forEach { m -> if (luhn(digits(m.value))) add(PatternKind.NATIONAL_ID, m.range) }
        }
        each(PASSPORT, PatternKind.PASSPORT)
        if (PatternKind.DATE in kinds || PatternKind.DATE_HIJRI in kinds) DATE_NUM.findAll(t).forEach { m ->
            val hijriTail = HIJRI_SUFFIX.find(t.substring(m.range.last + 1))
            val hijri = hijriTail != null
            if (!validNumericDate(m.value, hijri)) return@forEach
            if (hijri && PatternKind.DATE_HIJRI in kinds) add(PatternKind.DATE_HIJRI, m.range.first..(m.range.last + hijriTail!!.value.length))
            else if (!hijri && PatternKind.DATE in kinds) add(PatternKind.DATE, m.range)
        }
        if (PatternKind.DATE_HIJRI in kinds) HIJRI_YEAR.findAll(t).forEach { add(PatternKind.DATE_HIJRI, it.range) }
        if (PatternKind.DATE_MONTH_NAME in kinds) {
            DATE_MONTH.findAll(t).forEach { m -> if (dayOk(m.value)) add(PatternKind.DATE_MONTH_NAME, trimEndSpace(t, m.range)) }
            DATE_MONTH_FIRST.findAll(t).forEach { add(PatternKind.DATE_MONTH_NAME, it.range) }
        }
        each(AMOUNT, PatternKind.AMOUNT)
        return dedupe(out)
    }

    /** Matches of a user regular expression (Arabic folding is not applied; digits are). Invalid → empty. */
    fun findCustom(text: String, pattern: String, ignoreCase: Boolean = true): List<IntRange> {
        val re = runCatching { if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern) }.getOrNull() ?: return emptyList()
        val t = ArabicNormalizer.latinDigits(text)
        return re.findAll(t).map { it.range }.filter { !it.isEmpty() }.take(100_000).toList()
    }

    /** Overlapping hits: the longer one wins (a card number is not also reported as a phone). */
    private fun dedupe(list: List<PatternMatch>): List<PatternMatch> {
        val sorted = list.sortedWith(compareByDescending<PatternMatch> { it.range.last - it.range.first }.thenBy { it.range.first })
        val kept = ArrayList<PatternMatch>()
        for (m in sorted) if (kept.none { it.range.first <= m.range.last && m.range.first <= it.range.last }) kept += m
        return kept.sortedBy { it.range.first }
    }

    private fun trimEndSpace(t: String, r: IntRange): IntRange { var e = r.last; while (e > r.first && t[e].isWhitespace()) e--; return r.first..e }

    fun digits(s: String) = ArabicNormalizer.latinDigits(s).filter { it in '0'..'9' }

    /** Luhn checksum (cards, Saudi national ID / Iqama, UAE ID). */
    fun luhn(digits: String): Boolean {
        if (digits.length < 2 || digits.any { it !in '0'..'9' }) return false
        var sum = 0
        var dbl = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (dbl) { d *= 2; if (d > 9) d -= 9 }
            sum += d; dbl = !dbl
        }
        return sum % 10 == 0
    }

    /** ISO 13616 IBAN check: letters → numbers, mod 97 == 1. Spaces are ignored. */
    fun ibanValid(raw: String): Boolean {
        val s = raw.filter { !it.isWhitespace() }.uppercase()
        if (s.length !in 15..34 || !s.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        if (s[0] !in 'A'..'Z' || s[1] !in 'A'..'Z' || s[2] !in '0'..'9' || s[3] !in '0'..'9') return false
        IBAN_LEN[s.substring(0, 2)]?.let { if (s.length != it) return false }
        val r = s.substring(4) + s.substring(0, 4)
        var mod = 0
        for (c in r) {
            val v = if (c in '0'..'9') c - '0' else c - 'A' + 10
            mod = if (v >= 10) (mod * 100 + v) % 97 else (mod * 10 + v) % 97
        }
        return mod == 1
    }

    /** Length (in source chars, spaces included) of the valid IBAN at the start of [cand], or null. */
    private fun ibanPrefix(cand: String): Int? {
        val want = IBAN_LEN[cand.substring(0, 2)]
        var count = 0
        var bestLen: Int? = null
        for (i in cand.indices) {
            if (cand[i] == ' ') continue
            count++
            if (count >= 15 && (want == null || count == want)) {
                if (ibanValid(cand.substring(0, i + 1))) bestLen = i + 1
                if (want != null) break
            }
        }
        return bestLen
    }

    /** Saudi ID / Iqama (10 digits, 1/2 first, Luhn), Egypt (14 digits with a valid birth date), Yemen (11 digits). */
    fun isNationalId(d: String): Boolean = when (d.length) {
        10 -> (d[0] == '1' || d[0] == '2') && luhn(d)
        11 -> d[0] in '0'..'3'
        14 -> (d[0] == '2' || d[0] == '3') && run {
            val mm = d.substring(3, 5).toInt(); val dd = d.substring(5, 7).toInt(); val gov = d.substring(7, 9).toInt()
            mm in 1..12 && dd in 1..31 && gov in 1..35 || gov == 88
        }
        else -> false
    }

    private fun dayOk(v: String): Boolean = Regex("\\d{1,2}").find(v)?.value?.toInt()?.let { it in 1..31 } ?: false

    /** d/m/y, m/d/y or y/m/d with a plausible day, month and year. Hijri years are 1300–1500. */
    fun validNumericDate(v: String, hijri: Boolean = false): Boolean {
        val p = v.split('/', '-', '.').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return false
        val yearFirst = v.takeWhile { it.isDigit() }.length == 4
        val (a, b, y) = if (yearFirst) Triple(p[1], p[2], p[0]) else Triple(p[0], p[1], p[2])
        val yearOk = when {
            y < 100 -> true
            hijri -> y in 1300..1500
            else -> y in 1800..2200 || y in 1300..1500
        }
        val dm = if (yearFirst) (a in 1..12 && b in 1..31) else ((a in 1..31 && b in 1..12) || (a in 1..12 && b in 1..31))
        return yearOk && dm
    }
}
