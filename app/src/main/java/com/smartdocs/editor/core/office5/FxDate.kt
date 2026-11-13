package com.smartdocs.editor.core.office5

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.truncate

/** Serial of 9999-12-31, the last date Excel accepts. */
private const val MAX_SERIAL = 2958465.0

/** Date and time functions beyond the basic set in [FxFunctions]. Dates are 1900-system serials ([FxDates]). */
internal fun FxFunctions.registerDate() {
    reg("EDATE", "date", 2) { a -> serial(date(a, 0).plusMonths(months(a, 1))) }
    reg("EOMONTH", "date", 2) { a -> serial(date(a, 0).plusMonths(months(a, 1)).let { it.withDayOfMonth(it.lengthOfMonth()) }) }
    reg("DAYS", "date", 2) { a -> FV.Num((day(a, 0) - day(a, 1)).toDouble()) }
    reg("DATEDIF", "date", 3) { a ->
        val s = date(a, 0); val e = date(a, 1)
        if (s > e) FV.NUM else {
            val months = ChronoUnit.MONTHS.between(s, e)
            when (a.str(2).uppercase()) {
                "Y" -> FV.Num((months / 12).toDouble())
                "M" -> FV.Num(months.toDouble())
                "D" -> FV.Num((day(a, 1) - day(a, 0)).toDouble())
                "YM" -> FV.Num((months % 12).toDouble())
                // days past the start's day of month, counted from that day in the end's month (or the month before)
                "MD" -> FV.Num(ChronoUnit.DAYS.between(e.withDayOfMonth(1).minusMonths(if (e.dayOfMonth < s.dayOfMonth) 1 else 0).plusDays(s.dayOfMonth - 1L), e).toDouble())
                // days past the start's month and day, counted from them in the end's year (or the year before)
                "YD" -> FV.Num(ChronoUnit.DAYS.between(s.withYear(e.year).let { if (it > e) s.withYear(e.year - 1) else it }, e).toDouble())
                else -> FV.NUM
            }
        }
    }
    reg("DAYS360", "date", 2, 3) { a ->
        val s = date(a, 0); val e = date(a, 1); var d1 = s.dayOfMonth; var d2 = e.dayOfMonth
        if (a.bool(2, false)) { if (d1 == 31) d1 = 30; if (d2 == 31) d2 = 30 }   // European: every 31st is a 30th
        else {
            if (d1 == s.lengthOfMonth()) d1 = 30                               // US: a start on the last day of its month is a 30th
            if (d2 == 31 && d1 == 30) d2 = 30                                  // and an end on the 31st only after such a start
        }
        FV.Num(((e.year - s.year) * 360 + (e.monthValue - s.monthValue) * 30 + (d2 - d1)).toDouble())
    }
    reg("YEARFRAC", "date", 2, 3) { a ->
        val p = date(a, 0); val q = date(a, 1); val s = if (p > q) q else p; val e = if (p > q) p else q   // the order does not matter
        val days = ChronoUnit.DAYS.between(s, e).toDouble()
        when (a.int(2, 0)) {
            0 -> FV.of(days360(s, e, us = true) / 360.0)
            1 -> FV.of(days / actualYear(s, e))
            2 -> FV.of(days / 360.0)
            3 -> FV.of(days / 365.0)
            4 -> FV.of(days360(s, e, us = false) / 360.0)
            else -> FV.NUM
        }
    }
    // ---------------- weeks ----------------
    reg("WEEKDAY", "date", 1, 2) { a ->
        // Excel's own calendar: serial 1 is a Sunday (its 1900 has a 29 February, so real weekdays before March 1900 differ)
        val sun0 = ((day(a, 0) + 6) % 7).toInt()
        when (val t = a.int(1, 1)) {
            3 -> FV.Num(((sun0 + 6) % 7).toDouble())
            else -> weekStart(t)?.let { FV.Num(((sun0 - it + 7) % 7 + 1).toDouble()) } ?: FV.NUM
        }
    }
    reg("WEEKNUM", "date", 1, 2) { a ->
        val d = date(a, 0); val t = a.int(1, 1); val start = weekStart(t)
        if (t == 21) FV.Num(d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble())
        else if (start == null) FV.NUM
        else {
            // week 1 is the week holding 1 January
            val lead = (d.withDayOfYear(1).dayOfWeek.value % 7 - start + 7) % 7
            FV.Num(((d.dayOfYear - 1 + lead) / 7 + 1).toDouble())
        }
    }
    reg("ISOWEEKNUM", "date", 1) { a -> FV.Num(date(a, 0).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble()) }
    // ---------------- working days ----------------
    reg("NETWORKDAYS", "date", 2, 3) { a ->
        val s = day(a, 0); val e = day(a, 1); val lo = minOf(s, e); val hi = maxOf(s, e)
        val span = hi - lo + 1
        var n = span / 7 * 5
        for (d in lo until lo + span % 7) if (isWorkday(d)) n++
        n -= holidays(a, 2).count { it in lo..hi && isWorkday(it) }
        FV.Num((if (s <= e) n else -n).toDouble())
    }
    reg("WORKDAY", "date", 2, 3) { a ->
        var d = day(a, 0); var left = truncate(a.num(1)).toLong(); val off = holidays(a, 2); val step = if (left < 0) -1L else 1L
        while (left != 0L && d in 0L..MAX_SERIAL.toLong()) { d += step; if (isWorkday(d) && d !in off) left -= step }
        if (left != 0L) FV.NUM else FV.Num(d.toDouble())
    }
    // ---------------- time ----------------
    reg("HOUR", "date", 1) { a -> FV.Num(time(a).hour.toDouble()) }
    reg("MINUTE", "date", 1) { a -> FV.Num(time(a).minute.toDouble()) }
    reg("SECOND", "date", 1) { a -> FV.Num(time(a).second.toDouble()) }
    reg("TIME", "date", 3) { a ->
        val h = truncate(a.num(0)); val m = truncate(a.num(1)); val s = truncate(a.num(2))
        val t = (h * 3600 + m * 60 + s) / 86400.0
        if (maxOf(h, m, s) > 32767 || t < 0) FV.NUM else FV.of(t - Math.floor(t))
    }
    reg("DATEVALUE", "date", 1) { a ->
        when (val v = a.value(0)) { is FV.Err -> v; is FV.Str -> FxDates.parse(v.v)?.let { FV.Num(Math.floor(it)) } ?: FV.VALUE; else -> FV.VALUE }
    }
    reg("TIMEVALUE", "date", 1) { a ->
        when (val v = a.value(0)) { is FV.Err -> v; is FV.Str -> timeValue(v.v) ?: FV.VALUE; else -> FV.VALUE }
    }
}

/** Whole-day serial of argument [i]; outside Excel's date range is #NUM!. */
private fun day(a: FxArgs, i: Int): Long {
    val d = Math.floor(a.num(i))
    if (d < 0 || d > MAX_SERIAL) throw FxError(FV.NUM)
    return d.toLong()
}

private fun date(a: FxArgs, i: Int): LocalDate = FxDates.date(day(a, i).toDouble()).toLocalDate()

/** Whole months of argument [i], kept inside what a date can move by (the result is range-checked by [serial]). */
private fun months(a: FxArgs, i: Int): Long = truncate(a.num(i)).coerceIn(-120000.0, 120000.0).toLong()

private fun serial(d: LocalDate): FV = FxDates.serial(d).let { if (it < 0 || it > MAX_SERIAL) FV.NUM else FV.Num(it) }

/** Date-time of a serial for HOUR / MINUTE / SECOND (rounded to the second, like the display). */
private fun time(a: FxArgs): java.time.LocalDateTime {
    val x = a.num(0)
    if (x < 0 || x > MAX_SERIAL + 1) throw FxError(FV.NUM)
    return FxDates.date(x)
}

/** Monday to Friday, on Excel's calendar (serial 1 is a Sunday). */
private fun isWorkday(serial: Long): Boolean = (serial + 6) % 7 in 1L..5L

/** Whole-day serials of the optional holidays argument; cells that are not numbers are skipped. */
private fun holidays(a: FxArgs, i: Int): Set<Long> {
    if (a.missing(i)) return emptySet()
    val out = HashSet<Long>()
    a.each(i) { v, ref ->
        when (v) {
            is FV.Num -> out.add(Math.floor(v.v).toLong())
            is FV.Err -> throw FxError(v)
            is FV.Str -> if (!ref) out.add(Math.floor(FxCoerce.num(v)).toLong())
            else -> {}
        }
    }
    return out
}

/** First day of the week for a WEEKDAY / WEEKNUM return type, as 0 = Sunday … 6 = Saturday; null for an unknown type. */
private fun weekStart(type: Int): Int? = when (type) { 1, 17 -> 0; 2, 11 -> 1; in 12..16 -> type - 10; else -> null }

/** Days between two dates on the 30/360 calendar of YEARFRAC ([us] = basis 0, else the European basis 4). */
private fun days360(s: LocalDate, e: LocalDate, us: Boolean): Double {
    var d1 = s.dayOfMonth; var d2 = e.dayOfMonth
    if (!us) { if (d1 == 31) d1 = 30; if (d2 == 31) d2 = 30 }
    else if (d1 == 31) { d1 = 30; if (d2 == 31) d2 = 30 }
    else if (d1 == 30 && d2 == 31) d2 = 30
    else if (s.monthValue == 2 && d1 == s.lengthOfMonth()) {
        // a start on February's last day is a 30th, and then so is an end on February's last day
        d1 = 30
        if (e.monthValue == 2 && d2 == e.lengthOfMonth()) d2 = 30
    }
    return ((e.year - s.year) * 360 + (e.monthValue - s.monthValue) * 30 + (d2 - d1)).toDouble()
}

/** Year length for YEARFRAC basis 1 (actual/actual): the year(s) the period lies in, 366 when it holds a 29 February. */
private fun actualYear(s: LocalDate, e: LocalDate): Double {
    if (s.year == e.year) return s.lengthOfYear().toDouble()
    if (e > s.plusYears(1)) return (s.year..e.year).sumOf { LocalDate.of(it, 1, 1).lengthOfYear() }.toDouble() / (e.year - s.year + 1)
    val leapDay = (s.isLeapYear && s.monthValue <= 2) || (e.isLeapYear && (e.monthValue > 2 || (e.monthValue == 2 && e.dayOfMonth == 29)))
    return if (leapDay) 366.0 else 365.0
}

private val ampmRx = Regex("""^(.*\d)\s*([AaPp])\.?[Mm]\.?$""")

/** TIMEVALUE: the time of day of a time or date-time text, with an optional AM/PM. */
private fun timeValue(text: String): FV? {
    val m = ampmRx.matchEntire(text.trim())
    val t = FxDates.parse(m?.groupValues?.get(1) ?: text) ?: return null
    var f = t - Math.floor(t)
    if (m != null) {
        if (f >= 13 / 24.0) return null                                   // 13:00 PM is not a time
        val pm = m.groupValues[2].equals("p", ignoreCase = true)
        if (pm && f < 0.5) f += 0.5 else if (!pm && f >= 0.5) f -= 0.5    // 12:xx AM is 0:xx
    }
    return FV.of(f)
}
