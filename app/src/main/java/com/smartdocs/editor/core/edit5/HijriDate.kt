package com.smartdocs.editor.core.edit5

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** A Hijri (Islamic) calendar date. [month] is 1..12. */
data class Hijri(val year: Int, val month: Int, val day: Int)

/**
 * Gregorian → Hijri conversion. [ummAlQura] uses the platform's Umm al-Qura calendar (`java.time.chrono.HijrahDate`,
 * API 26+, valid 1300–1600 AH); outside that range or on failure the arithmetic (tabular, civil epoch) calendar is used.
 * Pure JVM code: no Android imports, unit-tested.
 */
object HijriDate {
    val MONTHS_AR = listOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")
    val MONTHS_EN = listOf("Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Ula", "Jumada al-Akhirah", "Rajab", "Shaban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah")

    fun of(date: LocalDate, ummAlQura: Boolean = true): Hijri {
        if (ummAlQura) runCatching {
            val h = HijrahDate.from(date)
            return Hijri(h.get(ChronoField.YEAR), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH))
        }
        return tabular(date)
    }

    // ---- arithmetic calendar (30-year cycle, leap years 2,5,7,10,13,16,18,21,24,26,29; civil epoch 16 July 622) ----
    private const val EPOCH = 1948440L   // JDN of 1 Muharram 1 AH (civil)

    fun tabular(date: LocalDate): Hijri {
        val jd = date.toEpochDay() + 2440588L   // epoch day 0 = JDN 2440588 (1970-01-01)
        val y = floor((30.0 * (jd - EPOCH) + 10646.0) / 10631.0).toInt()
        val m = min(12, ceil((jd - (29 + toJdn(y, 1, 1))) / 29.5).toInt() + 1).coerceAtLeast(1)
        val d = (jd - toJdn(y, m, 1) + 1).toInt()
        return Hijri(y, m, d)
    }

    fun toJdn(y: Int, m: Int, d: Int): Long =
        d + ceil(29.5 * (m - 1)).toLong() + (y - 1) * 354L + floor((3 + 11.0 * y) / 30.0).toLong() + EPOCH - 1

    fun tabularToGregorian(h: Hijri): LocalDate = LocalDate.ofEpochDay(toJdn(h.year, h.month, h.day) - 2440588L)

    /**
     * Format with a small pattern language: `d` / `dd` day, `M` / `MM` month number, `MMMM` month name,
     * `yyyy` year, `هـ`/`AH` are literal. [arabic] picks Arabic month names; digits are converted by the caller.
     */
    fun format(h: Hijri, pattern: String, arabic: Boolean): String {
        val names = if (arabic) MONTHS_AR else MONTHS_EN
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            when (c) {
                'd' -> sb.append(if (n >= 2) h.day.toString().padStart(2, '0') else h.day.toString())
                'M' -> sb.append(when { n >= 3 -> names[h.month - 1]; n == 2 -> h.month.toString().padStart(2, '0'); else -> h.month.toString() })
                'y' -> sb.append(if (n == 2) (h.year % 100).toString().padStart(2, '0') else h.year.toString())
                else -> repeat(n) { sb.append(c) }
            }
            i += n
        }
        return sb.toString()
    }
}
