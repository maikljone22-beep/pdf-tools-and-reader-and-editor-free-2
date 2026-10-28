package com.smartdocs.editor.core.fillsign2

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Fill & Sign "Date" tool formats (tab audit D26). Pure, unit-tested; the digits option is applied by the caller. */
object FsDate {
    const val DEFAULT = "yyyy/MM/dd"

    /** The patterns offered by the Date tool, in menu order. */
    val PATTERNS = listOf(DEFAULT, "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd.MM.yyyy", "d MMMM yyyy")

    /** [pattern] if offered, otherwise [DEFAULT]. */
    fun valid(pattern: String?): String = pattern?.takeIf { it in PATTERNS } ?: DEFAULT

    /**
     * [millis] in [pattern]. Numeric patterns always use Latin digits (Locale.US; the caller converts them with the
     * typing-digits option); a month name ("MMMM") is written in [locale].
     */
    fun format(pattern: String, millis: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val p = valid(pattern)
        val loc = if (p.contains("MMMM")) locale else Locale.US
        return SimpleDateFormat(p, loc).apply { timeZone = zone }.format(Date(millis))
    }
}
