package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.FieldScripts
import com.smartdocs.editor.core.forms.FormatSpec
import com.smartdocs.editor.core.forms.KeystrokeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FieldDatesTest {
    private val day = 19_723L * 86_400_000L          // 2024-01-01T00:00Z
    private fun scripts(fmt: FormatSpec?, ks: KeystrokeKind = KeystrokeKind.NONE) = FieldScripts("d", null, null, fmt, ks)

    @Test fun formatsInUtcWhateverTheDeviceZone() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("01/01/2024", FieldDates.formatUtc(day, "dd/mm/yyyy"))
            assertEquals("2024-01-01", FieldDates.formatUtc(day, "yyyy-mm-dd"))
            assertEquals("Jan 1, 2024", FieldDates.formatUtc(day, "mmm d, yyyy"))
            assertEquals("1/1/24", FieldDates.formatUtc(day, "m/d/yy"))
            assertEquals(day, FieldDates.parseUtc("01/01/2024", "dd/mm/yyyy"))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun parseRoundTripAndArabicDigits() {
        assertEquals(day, FieldDates.parseUtc("01/01/2024", "dd/mm/yyyy"))
        assertEquals(day, FieldDates.parseUtc("٠١/٠١/٢٠٢٤", "dd/mm/yyyy"))
        assertEquals(day, FieldDates.parseUtc("2024-01-01"))
        assertNull(FieldDates.parseUtc("hello", "dd/mm/yyyy"))
        assertNull(FieldDates.parseUtc("31/02/2024", "dd/mm/yyyy"))
        assertNull(FieldDates.parseUtc(""))
    }

    @Test fun dateFieldDetection() {
        assertTrue(FieldDates.isDateField(scripts(null, KeystrokeKind.DATE)))
        assertTrue(FieldDates.isDateField(scripts(FormatSpec.DateTime("dd/mm/yyyy"))))
        assertFalse(FieldDates.isDateField(scripts(FormatSpec.DateTime("HH:MM", timeOnly = true), KeystrokeKind.TIME)))
        assertFalse(FieldDates.isDateField(scripts(FormatSpec.Number(2, 0, 0, "", true), KeystrokeKind.NUMBER), "date_total"))
        assertTrue(FieldDates.isDateField(null, "form.BirthDate"))
        assertTrue(FieldDates.isDateField(null, "تاريخ_الميلاد"))
        assertFalse(FieldDates.isDateField(null, "name"))
        assertEquals("yyyy-mm-dd", FieldDates.patternOf(scripts(FormatSpec.DateTime("yyyy-mm-dd"))))
        assertEquals(FieldDates.DEFAULT_PATTERN, FieldDates.patternOf(null))
    }

    @Test fun builderScripts() {
        assertEquals("AFDate_FormatEx(\"dd/mm/yyyy\");", FieldDates.formatJs())
        assertEquals("AFDate_KeystrokeEx(\"yyyy-mm-dd\");", FieldDates.keystrokeJs("yyyy-mm-dd"))
    }
}
