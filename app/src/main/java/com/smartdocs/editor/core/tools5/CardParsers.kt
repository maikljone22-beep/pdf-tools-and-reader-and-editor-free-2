package com.smartdocs.editor.core.tools5

/*
 * Business card → contact / vCard, receipt → merchant / date / total / currency, CSV rows.
 * Plain Kotlin (no Android), unit-tested in CardParsersTest.
 */

enum class PhoneType { CELL, WORK, FAX, OTHER }

data class Phone(val number: String, val type: PhoneType = PhoneType.OTHER)

data class Contact(
    val name: String = "",
    val title: String = "",
    val company: String = "",
    val phones: List<Phone> = emptyList(),
    val emails: List<String> = emptyList(),
    val websites: List<String> = emptyList(),
    val address: String = "",
    val note: String = "",
) {
    val isEmpty: Boolean get() = name.isBlank() && company.isBlank() && phones.isEmpty() && emails.isEmpty()
}

object BusinessCardParser {

    private val emailRe = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val webRe = Regex("""(?i)\b((?:https?://)?(?:www\.)?[a-z0-9\-]+(?:\.[a-z0-9\-]+)*\.(?:com|net|org|info|biz|io|co|me|sa|ae|eg|qa|kw|bh|om|jo|ye|iq|ma|dz|tn|lb|sy|sd|ly|uk|de|fr|tr|edu|gov)(?:\.[a-z]{2})?(?:/[^\s]*)?)\b""")
    private val phoneRe = Regex("""\+?\(?\d[\d\s().\-/]{5,}\d""")

    private val titleWords = listOf(
        "manager", "director", "engineer", "ceo", "cto", "cfo", "coo", "founder", "president", "officer", "head of", "sales",
        "consultant", "specialist", "developer", "designer", "accountant", "lawyer", "advocate", "doctor", "dr.", "supervisor",
        "executive", "assistant", "analyst", "architect", "representative", "marketing", "partner", "owner", "agent", "coordinator",
        "مدير", "مهندس", "رئيس", "مستشار", "أخصائي", "اخصائي", "مسؤول", "مسئول", "مندوب", "مؤسس", "محاسب", "محامي", "دكتور", "د.",
        "مشرف", "منسق", "مصمم", "مطور", "نائب", "شريك", "وكيل", "سكرتير", "طبيب", "استشاري", "المدير", "الرئيس",
    )
    private val companyWords = listOf(
        "company", " co.", " co ", "ltd", "llc", "inc", "group", "corp", "corporation", "est.", "establishment", "trading", "holding",
        "solutions", "services", "technologies", "technology", "agency", "studio", "bank", "university", "hospital", "clinic",
        "شركة", "مؤسسة", "مجموعة", "للتجارة", "مكتب", "مصنع", "مركز", "بنك", "مصرف", "جامعة", "مستشفى", "عيادة", "القابضة", "للمقاولات", "للخدمات",
    )
    private val addressWords = listOf(
        "street", "st.", "road", "rd.", "avenue", "ave", "building", "bldg", "floor", "p.o", "po box", "box", "district", "city",
        "suite", "zip", "شارع", "طريق", "حي", "مبنى", "عمارة", "الطابق", "ص.ب", "ص ب", "صندوق بريد", "المملكة", "مدينة", "برج", "الدور",
    )
    private val faxWords = listOf("fax", "فاكس")
    private val cellWords = listOf("mobile", "mob", "cell", "gsm", "جوال", "موبايل", "محمول", "الجوال", "whatsapp", "واتس")
    private val workWords = listOf("tel", "phone", "office", "هاتف", "تلفون", "ت:", "ت.", "الهاتف")
    private val labelRe = Regex("""(?i)^\s*(tel|phone|mobile|mob|cell|fax|e-?mail|email|web|website|site|address|addr|هاتف|جوال|فاكس|بريد|البريد|الموقع|العنوان|ت|ج|ف|m|t|f|e|w)\s*[:.\-]\s*""")

    /** Keyword test; short keywords (≤ 3 letters, e.g. "حي") must stand alone so they do not match inside names. */
    private fun has(line: String, words: List<String>): Boolean {
        val l = " " + line.lowercase() + " "
        return words.any { w ->
            val t = w.trim()
            if (t.length > 3 || t.any { !it.isLetter() }) l.contains(w)
            else Regex("""(?<!\p{L})""" + Regex.escape(t) + """(?!\p{L})""").containsMatchIn(l)
        }
    }

    fun normalizePhone(raw: String): String {
        val plus = raw.trim().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        return (if (plus) "+" else "") + digits
    }

    fun parse(text: String): Contact {
        val lines = TextNorm.asciiDigits(text).lines().map { it.filterNot(TextNorm::isInvisible).trim() }.filter { it.isNotEmpty() }
        val emails = LinkedHashSet<String>()
        val webs = LinkedHashSet<String>()
        val phones = ArrayList<Phone>()
        val rest = ArrayList<String>()
        for (line in lines) {
            var l = line
            var used = false
            emailRe.findAll(l).forEach { emails += it.value.lowercase(); used = true }
            l = emailRe.replace(l, " ")
            webRe.findAll(l).forEach { m -> val v = m.groupValues[1]; if (!v.contains('@')) { webs += v.lowercase(); used = true } }
            l = webRe.replace(l, " ")
            phoneRe.findAll(l).forEach { m ->
                val n = normalizePhone(m.value)
                val digits = n.count { it.isDigit() }
                if (digits in 7..15) {
                    val type = when { has(line, faxWords) -> PhoneType.FAX; has(line, cellWords) -> PhoneType.CELL; has(line, workWords) -> PhoneType.WORK; n.startsWith("+9665") || n.startsWith("05") -> PhoneType.CELL; else -> PhoneType.OTHER }
                    if (phones.none { it.number == n }) phones += Phone(n, type)
                    used = true
                }
            }
            if (used) {
                l = phoneRe.replace(l, " ")
                val left = labelRe.replace(l, "").trim().trim(',', '|', '-', ':', '/')
                if (left.count { it.isLetter() } >= 4 && !has(left, faxWords + cellWords + workWords)) rest += left
            } else rest += labelRe.replace(line, "").trim()
        }
        var title = ""; var company = ""; val address = ArrayList<String>(); val others = ArrayList<String>()
        for (l in rest) {
            when {
                l.isBlank() -> {}
                company.isEmpty() && has(l, companyWords) -> company = l
                title.isEmpty() && has(l, titleWords) && l.length <= 60 -> title = l
                has(l, addressWords) || (l.count { it.isDigit() } >= 2 && l.contains(',')) -> address += l
                else -> others += l
            }
        }
        val name = others.firstOrNull { looksLikeName(it) } ?: others.firstOrNull { it.count { c -> c.isLetter() } >= 3 && it.none { c -> c.isDigit() } }.orEmpty()
        others.remove(name)
        if (company.isEmpty()) {
            // an all-caps Latin line or the line right after the title often is the company
            company = others.firstOrNull { o -> o.length in 3..50 && o.any { it.isLetter() } && o.filter { it.isLetter() }.all { it.isUpperCase() } }.orEmpty()
            others.remove(company)
        }
        return Contact(
            name = name, title = title, company = company, phones = phones, emails = emails.toList(), websites = webs.toList(),
            address = address.joinToString(", "), note = others.filter { it.count { c -> c.isLetterOrDigit() } >= 3 }.joinToString("\n"),
        )
    }

    private fun looksLikeName(s: String): Boolean {
        if (s.any { it.isDigit() } || s.length > 40) return false
        if (has(s, titleWords) || has(s, companyWords) || has(s, addressWords)) return false
        val words = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return words.size in 2..5 && words.all { w -> w.count { it.isLetter() } >= 1 }
    }
}

/** vCard 3.0 writer (RFC 2426). UTF-8, CRLF line ends, folded at 75 octets. */
object VCard {

    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); ',' -> append("\\,"); ';' -> append("\\;"); '\n' -> append("\\n"); '\r' -> {}
            else -> append(c)
        }
    }

    /** Fold one content line: continuation lines start with a space; never splits a UTF-8 sequence or surrogate pair. */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val sb = StringBuilder(); var count = 0; var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i); val len = Character.charCount(cp)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (count + n > limit) { sb.append("\r\n "); count = 0; limit = 74 }
            sb.appendCodePoint(cp); count += n; i += len
        }
        return sb.toString()
    }

    fun write(c: Contact): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCARD"; lines += "VERSION:3.0"
        val name = c.name.ifBlank { c.company }.trim()
        val parts = name.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val family = if (parts.size > 1) parts.last() else ""
        val given = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts.firstOrNull().orEmpty()
        lines += "FN:" + escape(name)
        lines += "N:" + escape(family) + ";" + escape(given) + ";;;"
        if (c.company.isNotBlank()) lines += "ORG:" + escape(c.company)
        if (c.title.isNotBlank()) lines += "TITLE:" + escape(c.title)
        c.phones.forEach { p ->
            val t = when (p.type) { PhoneType.CELL -> "CELL"; PhoneType.WORK -> "WORK,VOICE"; PhoneType.FAX -> "WORK,FAX"; PhoneType.OTHER -> "VOICE" }
            lines += "TEL;TYPE=$t:" + escape(p.number)
        }
        c.emails.forEach { lines += "EMAIL;TYPE=INTERNET:" + escape(it) }
        c.websites.forEach { lines += "URL:" + escape(it) }
        if (c.address.isNotBlank()) lines += "ADR;TYPE=WORK:;;" + escape(c.address) + ";;;;"
        if (c.note.isNotBlank()) lines += "NOTE:" + escape(c.note)
        lines += "END:VCARD"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
}

data class Receipt(val merchant: String = "", val date: String? = null, val total: String? = null, val currency: String? = null)

object ReceiptParser {

    private val totalStrong = listOf("grand total", "total due", "amount due", "total amount", "net total", "balance due", "الإجمالي", "الاجمالي", "المبلغ الإجمالي", "المبلغ الاجمالي", "إجمالي المبلغ", "اجمالي المبلغ", "الصافي", "المجموع الكلي", "المستحق", "صافي المبلغ")
    private val totalWeak = listOf("total", "amount", "sum", "net", "إجمالي", "اجمالي", "المجموع", "المبلغ", "صافي", "الإجمالى", "الاجمالى")
    private val notTotal = listOf("subtotal", "sub total", "sub-total", "المجموع الفرعي", "قبل الضريبة", "vat", "tax", "ضريبة", "الضريبة", "خصم", "discount", "change", "الباقي", "cash", "نقدا", "نقدا\u064B", "paid", "المدفوع")
    private val skipMerchant = listOf("receipt", "invoice", "tax", "vat", "simplified", "فاتورة", "إيصال", "ايصال", "ضريبية", "مبسطة", "رقم", "date", "التاريخ", "tel:", "هاتف", "سجل")

    private val numRe = Regex("""\d{1,3}(?:[,\s]\d{3})+(?:[.,]\d{1,3})?|\d+(?:[.,]\d{1,3})?""")
    private val ymd = Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val dmy = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})\b""")

    private val currencies = listOf(
        "YER" to listOf("yer", "ريال يمني", "ر.ي"),
        "SAR" to listOf("sar", "sr ", "s.r", "ر.س", "ريال سعودي", "ريال", "رس"),
        "AED" to listOf("aed", "درهم", "د.إ", "د.ا"),
        "EGP" to listOf("egp", "le ", "l.e", "ج.م", "جنيه"),
        "KWD" to listOf("kwd", "د.ك", "دينار كويتي"),
        "QAR" to listOf("qar", "qr ", "ر.ق", "ريال قطري"),
        "BHD" to listOf("bhd", "bd ", "د.ب", "دينار بحريني"),
        "OMR" to listOf("omr", "ro ", "ر.ع", "ريال عماني"),
        "JOD" to listOf("jod", "jd ", "د.أ", "دينار أردني", "دينار اردني"),
        "IQD" to listOf("iqd", "د.ع", "دينار عراقي"),
        "MAD" to listOf("mad", "dh ", "د.م", "درهم مغربي"),
        "EUR" to listOf("eur", "€"),
        "GBP" to listOf("gbp", "£"),
        "TRY" to listOf("try", "₺", "tl "),
        "USD" to listOf("usd", "us$", "$", "دولار"),
    )

    /** "1,234.50" / "1.234,50" / "12,5" / "1 234" → "1234.50" / "1234.50" / "12.5" / "1234"; null when not a number. */
    fun normalizeAmount(raw0: String): String? {
        val raw = TextNorm.asciiDigits(raw0).trim().replace(" ", "")
        if (raw.isEmpty() || raw.none { it.isDigit() }) return null
        val lastDot = raw.lastIndexOf('.'); val lastComma = raw.lastIndexOf(',')
        val s = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) raw.replace(",", "") else raw.replace(".", "").replace(',', '.')
            lastComma >= 0 -> { val tail = raw.length - lastComma - 1; if (tail in 1..2 && raw.count { it == ',' } == 1) raw.replace(',', '.') else raw.replace(",", "") }
            lastDot >= 0 -> { val tail = raw.length - lastDot - 1; if (tail == 3 && raw.count { it == '.' } >= 1 && raw.length > 4 && raw.count { it == '.' } > 1) raw.replace(".", "") else raw }
            else -> raw
        }
        return s.takeIf { it.matches(Regex("""\d+(\.\d+)?""")) }?.trimStart('0')?.let { if (it.isEmpty() || it.startsWith(".")) "0$it" else it }
    }

    fun parseDate(text0: String): String? {
        val text = TextNorm.asciiDigits(text0)
        ymd.find(text)?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            if (valid(y, mo, d)) return iso(y, mo, d)
        }
        dmy.findAll(text).forEach { m ->
            var d = m.groupValues[1].toInt(); var mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            if (mo > 12 && d <= 12) { val t = d; d = mo; mo = t }   // US order m/d/y
            if (valid(y, mo, d)) return iso(y, mo, d)
        }
        return null
    }

    private fun valid(y: Int, m: Int, d: Int) = y in 1990..2100 && m in 1..12 && d in 1..31
    private fun iso(y: Int, m: Int, d: Int) = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d)

    private fun amountsIn(line: String): List<String> =
        numRe.findAll(line).map { it.value }.filterNot { v -> ymd.containsMatchIn(v) }.mapNotNull { normalizeAmount(it) }.toList()

    fun detectCurrency(text: String): String? {
        val t = " " + TextNorm.asciiDigits(text).lowercase() + " "
        return currencies.firstOrNull { (_, keys) -> keys.any { t.contains(it) } }?.first
    }

    fun parse(text: String): Receipt {
        val lines = TextNorm.asciiDigits(text).lines().map { it.filterNot(TextNorm::isInvisible).trim() }.filter { it.isNotEmpty() }
        val merchant = lines.take(6).firstOrNull { l ->
            val low = l.lowercase()
            l.count { it.isLetter() } >= 3 && skipMerchant.none { low.contains(it) } && l.count { it.isDigit() } <= l.length / 3
        }.orEmpty()
        val datesLine = lines.firstNotNullOfOrNull { parseDate(it) }
        val subOnly = listOf("subtotal", "sub total", "sub-total", "المجموع الفرعي", "قبل الضريبة")
        fun pick(words: List<String>, exclude: List<String>): String? {
            for (i in lines.indices.reversed()) {
                val low = lines[i].lowercase()
                val inclusive = low.contains("incl") || low.contains("شامل") || low.contains("with vat")
                if (words.none { low.contains(it) } || (!inclusive && exclude.any { low.contains(it) })) continue
                val here = amountsIn(lines[i].replace(Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}"""), " "))
                if (here.isNotEmpty()) return here.last()
                if (i + 1 < lines.size) amountsIn(lines[i + 1]).lastOrNull()?.let { return it }
            }
            return null
        }
        val total = pick(totalStrong, subOnly) ?: pick(totalWeak, notTotal) ?: lines.flatMap { l ->
            if (ymd.containsMatchIn(l) || dmy.containsMatchIn(l)) emptyList() else amountsIn(l).filter { it.contains('.') }
        }.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }
        return Receipt(merchant = merchant, date = datesLine, total = total, currency = detectCurrency(text))
    }
}

/** Minimal RFC 4180 CSV writer / reader with separator detection (`,` `;` tab). */
object Csv {

    fun field(s: String, sep: Char = ','): String =
        if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' } || s.startsWith(" ") || s.endsWith(" ")) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(fields: List<String>, sep: Char = ','): String = fields.joinToString(sep.toString()) { field(it, sep) }

    fun detectSeparator(text: String): Char {
        val head = text.lineSequence().take(5).toList()
        val cands = listOf(',', ';', '\t')
        return cands.maxByOrNull { c -> head.sumOf { l -> countOutsideQuotes(l, c) } * 10 + if (c == ',') 1 else 0 } ?: ','
    }

    private fun countOutsideQuotes(l: String, c: Char): Int { var q = false; var n = 0; for (ch in l) { if (ch == '"') q = !q else if (ch == c && !q) n++ }; return n }

    fun parse(text0: String, sep0: Char? = null): List<List<String>> {
        val text = text0.removePrefix("\uFEFF")
        val sep = sep0 ?: detectSeparator(text)
        val rows = ArrayList<List<String>>()
        var cur = ArrayList<String>(); val f = StringBuilder(); var quoted = false; var i = 0
        var any = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else quoted = false }
                else f.append(c)
            } else when (c) {
                '"' -> { quoted = true; any = true }
                sep -> { cur.add(f.toString()); f.clear(); any = true }
                '\r' -> {}
                '\n' -> { cur.add(f.toString()); f.clear(); if (cur.size > 1 || cur[0].isNotEmpty() || any) rows += cur; cur = ArrayList(); any = false }
                else -> { f.append(c); any = true }
            }
            i++
        }
        if (f.isNotEmpty() || cur.isNotEmpty() || any) { cur.add(f.toString()); rows += cur }
        return rows
    }
}
