package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.AfFormat
import com.smartdocs.editor.core.forms.AfNumbers
import com.smartdocs.editor.core.forms.FieldScripts
import com.smartdocs.editor.core.forms.FormatSpec
import com.smartdocs.editor.core.forms.KeystrokeKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date handling of form fields for the in-page date picker. Material's DatePicker works with UTC-midnight
 * milliseconds, so formatting and parsing here run in UTC (the older AfFormat.formatPickedDate uses the
 * device time zone, which shifts the day west of Greenwich).
 */
object FieldDates {

    /** Pattern used when a date field has no AFDate format (also used by the form builder's date fields). */
    const val DEFAULT_PATTERN = "dd/mm/yyyy"

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** True when the field should get a date picker: AFDate keystroke, a date format, or a date-like name. */
    fun isDateField(scripts: FieldScripts?, name: String = ""): Boolean {
        val dt = scripts?.format as? FormatSpec.DateTime
        if (scripts?.keystroke == KeystrokeKind.DATE) return true
        if (dt != null && !dt.timeOnly && scripts?.keystroke !=KeystrokeKind.TIME) return true
        if (scripts?.format != null || scripts?.keystroke?.let { it != KeystrokeKind.NONE } == true) return false
        val n = name.substringAfterLast('.').lowercase()
        return n.contains("date") || n.contains("dob") || n.contains("birth") || n.contains("تاريخ")  // "تاريخ"
    }

    /** Acrobat pattern of the field (AFDate_FormatEx argument) or [DEFAULT_PATTERN]. */
    fun patternOf(scripts: FieldScripts?): String =
        (scripts?.format as? FormatSpec.DateTime)?.takeIf { !it.timeOnly }?.pattern ?: DEFAULT_PATTERN

    /** UTC-midnight [millis] (DatePicker selection) formatted with the Acrobat [acroPattern]. */
    fun formatUtc(millis: Long, acroPattern: String = DEFAULT_PATTERN): String =
        runCatching { SimpleDateFormat(AfFormat.toJavaPattern(acroPattern), Locale.US).apply { timeZone = UTC }.format(Date(millis)) }
            .getOrElse { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }.format(Date(millis)) }   // invalid pattern from the PDF

    private val fallback = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "dd.MM.yyyy", "MM/dd/yyyy", "d MMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "yyyyMMdd")

    /** Parse a field value into UTC-midnight millis for the picker's initial selection; null when not a date. */
    fun parseUtc(value: String, acroPattern: String? = null): Long? {
        val s = AfNumbers.normalizeDigits(value).trim()
        if (s.isEmpty()) return null
        val patterns = buildList { if (acroPattern != null) add(AfFormat.toJavaPattern(acroPattern)); addAll(fallback) }
        for (p in patterns) {
            val d = runCatching {
                val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false; timeZone = UTC }
                val pos = java.text.ParsePosition(0)
                f.parse(s, pos)?.takeIf { pos.index == s.length }
            }.getOrNull()
            if (d != null) return d.time
        }
        return null
    }

    /** Today at UTC midnight (the picker's "today" button value) formatted for the field. */
    fun today(acroPattern: String = DEFAULT_PATTERN, nowMillis: Long = System.currentTimeMillis()): String {
        val local = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val utc = java.util.Calendar.getInstance(UTC).apply {
            clear(); set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH))
        }
        return formatUtc(utc.timeInMillis, acroPattern)
    }

    /** JavaScript for a date field created by the form builder: AFDate format + keystroke actions. */
    fun formatJs(acroPattern: String = DEFAULT_PATTERN) = "AFDate_FormatEx(\"$acroPattern\");"
    fun keystrokeJs(acroPattern: String = DEFAULT_PATTERN) = "AFDate_KeystrokeEx(\"$acroPattern\");"
}
