package com.smartdocs.editor.core.view5

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Hijri (Islamic) date. Umm al-Qura through java.time when available, else the tabular "Kuwaiti"
 * algorithm (30-year cycle, civil epoch 16 July 622). An observed calendar can differ by a day;
 * `adjustDays` lets the user correct that (setting).
 */
data class Hijri(val year: Int, val month: Int, val day: Int) {
    fun format(arabic: Boolean = true, arabicDigits: Boolean = arabic): String {
        val d = if (arabicDigits) Digits.toArabic(day.toString()) else day.toString()
        val y = if (arabicDigits) Digits.toArabic(year.toString()) else year.toString()
        return if (arabic) "$d ${HijriDate.MONTHS_AR[month - 1]} $y هـ" else "$d ${HijriDate.MONTHS_EN[month - 1]} $y AH"
    }
    /** Numeric form yyyy/mm/dd. */
    fun numeric(arabicDigits: Boolean = false): String {
        val s = String.format(Locale.US, "%04d/%02d/%02d", year, month, day)
        return if (arabicDigits) Digits.toArabic(s) else s
    }
}

object HijriDate {
    val MONTHS_AR = listOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")
    val MONTHS_EN = listOf("Muharram", "Safar", "Rabi I", "Rabi II", "Jumada I", "Jumada II", "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah")

    /** Julian day number of a Gregorian date (month 1..12). */
    fun julianDay(y: Int, m: Int, d: Int): Long {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365L * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    fun fromJulianDay(jd: Long): Hijri {
        var l = jd - 1948440 + 10632
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val m = (24 * l) / 709
        val d = l - (709 * m) / 24
        val y = 30 * n + j - 30
        return Hijri(y.toInt(), m.toInt(), d.toInt())
    }

    /** Inverse of [fromJulianDay]: ceil(29.5 × (m − 1)) days for the months before, 354 per year plus leap days. */
    fun toJulianDay(h: Hijri): Long =
        h.day + (59L * (h.month - 1) + 1) / 2 + (h.year - 1) * 354L + (3 + 11L * h.year) / 30 + 1948440 - 1

    /** Tabular (arithmetic) Hijri date of a Gregorian date. */
    fun tabular(y: Int, m: Int, d: Int, adjustDays: Int = 0): Hijri = fromJulianDay(julianDay(y, m, d) + adjustDays)

    /**
     * Umm al-Qura date (java.time HijrahDate, AH 1300–1600) shifted by [adjustDays]; the tabular calendar
     * outside that range or when java.time is unavailable.
     */
    fun fromGregorian(y: Int, m: Int, d: Int, adjustDays: Int = 0): Hijri = runCatching {
        val h = java.time.chrono.HijrahDate.from(java.time.LocalDate.of(y, m, d).plusDays(adjustDays.toLong()))
        Hijri(h.get(java.time.temporal.ChronoField.YEAR), h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR), h.get(java.time.temporal.ChronoField.DAY_OF_MONTH))
    }.getOrElse { tabular(y, m, d, adjustDays) }

    fun fromMillis(ms: Long, tz: TimeZone = TimeZone.getDefault(), adjustDays: Int = 0): Hijri {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), adjustDays)
    }
}

/**
 * Tokens of dynamic stamps: {name} {title} {org} {date} {time} {hijri} {hijri_num} {datetime}. Unknown
 * tokens stay as typed. Pure, so templates can be tested.
 */
object StampTokens {
    data class Identity(val name: String = "", val title: String = "", val org: String = "")

    fun fill(template: String, who: Identity, ms: Long, arabicDigits: Boolean = false, tz: TimeZone = TimeZone.getDefault(), hijriAdjust: Int = 0): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        fun two(v: Int) = String.format(Locale.US, "%02d", v)
        val date = "${c.get(Calendar.YEAR)}/${two(c.get(Calendar.MONTH) + 1)}/${two(c.get(Calendar.DAY_OF_MONTH))}"
        val time = "${two(c.get(Calendar.HOUR_OF_DAY))}:${two(c.get(Calendar.MINUTE))}"
        val h = HijriDate.fromMillis(ms, tz, hijriAdjust)
        fun dg(s: String) = if (arabicDigits) Digits.toArabic(s) else s
        val map = mapOf(
            "name" to who.name, "title" to who.title, "org" to who.org,
            "date" to dg(date), "time" to dg(time), "datetime" to dg("$date $time"),
            "hijri" to h.format(arabic = true, arabicDigits = arabicDigits), "hijri_num" to h.numeric(arabicDigits),
        )
        return Regex("""\{([a-z_]+)\}""").replace(template) { m -> map[m.groupValues[1]] ?: m.value }
    }
}
