package com.smartdocs.editor.core.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-forms-2: XFA picture clauses. */
class XfaPictureTest {
    private fun f(p: String, v: String, h: XfaPicture.Hint = XfaPicture.Hint.TEXT) = XfaPicture.format(p, v, h)

    @Test fun numbers() {
        assertEquals("1,234.50", f("num{z,zzz,zz9.99}", "1234.5"))
        assertEquals("5.00", f("num{z,zz9.99}", "5"))
        assertEquals("0.25", f("num{zz9.99}", ".25"))
        assertEquals("-1,234", f("num{z,zz9}", "-1234"))
        assertEquals("\$12.30", f("num{\$zz9.99}", "12.3"))
        assertEquals("007", f("num{999}", "7"))
        assertEquals("12.5", f("num{zz9.88}", "12.5"))
        assertEquals("12", f("num{zz9.88}", "12"))
        assertEquals("1.5 kg", f("num{z9.9' kg'}", "1.46"))
        assertNull(f("num{99}", "1234"))                 // does not fit
        assertNull(f("num{99}", "abc"))
        assertEquals("1,234.50", f("z,zzz,zz9.99", "1234.5", XfaPicture.Hint.NUMBER))   // bare pattern, numeric field
    }

    @Test fun datesTimesText() {
        assertEquals("05/03/2024", f("date{MM/DD/YYYY}", "2024-05-03"))
        assertEquals("3 May 2024", f("date{D MMMM YYYY}", "20240503"))
        assertEquals("Fri, 03 May 24", f("date{EEE, DD MMM YY}", "2024-05-03"))
        assertNull(f("date{MM/DD/YYYY}", "2024-02-30"))
        assertEquals("02:05 PM", f("time{hh:MM A}", "14:05:00"))
        assertEquals("14:05:09", f("time{HH:MM:SS}", "140509"))
        assertEquals("123-45-6789", f("text{999-99-9999}", "123456789"))
        assertNull(f("text{999-99-9999}", "12345678"))
        assertEquals("AB-12", f("text{AA'-'99}", "AB12"))
    }

    @Test fun alternativesZeroAndNull() {
        assertEquals("1,234", f("text{999}|num{z,zz9}", "1234"))
        assertEquals("none", f("null{'none'}|num{zz9}", ""))
        assertEquals("zero", f("zero{'zero'}|num{zz9}", "0"))
        assertEquals("7", f("zero{'zero'}|num{zz9}", "7"))
        assertNull(f("", "x"))
    }

    @Test fun percentArabicNamesAndLocale() {
        assertEquals("25%", f("num{zz9%}", "0.25"))
        assertEquals("12.5%", f("num{zz9.9%}", ".125"))
        assertEquals("3 مايو 2024", f("date(ar_SA){D MMMM YYYY}", "2024-05-03"))
        assertEquals("الجمعة", f("date(ar){EEEE}", "2024-05-03"))
        assertEquals("02:05 م", f("time(ar_AE){hh:MM A}", "14:05"))
        assertEquals("date(ar_SA){DD/MM/YYYY}", XfaPicture.withCategory("DD/MM/YYYY", XfaPicture.Hint.DATE, "ar_SA"))
        assertEquals("num(ar){9}|text(ar){X}", XfaPicture.withCategory("num{9}|text{X}", XfaPicture.Hint.TEXT, "ar"))
    }

    @Test fun parsingTypedText() {
        fun p(pic: String, s: String) = XfaPicture.parse(pic, s)
        assertEquals("1234.5", p("num{z,zzz,zz9.99}", "1,234.50"))
        assertEquals("-12", p("num{zz9}", "-12"))
        assertEquals("0.25", p("num{zz9%}", "25%"))
        assertEquals("1234.5", p("num{zzzz9.99}", "١٢٣٤٫٥٠"))           // Arabic-Indic digits and separator
        assertEquals("2024-05-03", p("date{MM/DD/YYYY}", "05/03/2024"))
        assertEquals("2024-05-03", p("date{D MMMM YYYY}", "3 May 2024"))
        assertEquals("2024-05-03", p("date(ar){D MMMM YYYY}", "3 مايو 2024"))
        assertEquals("2024-05-03", p("date{DD.MM.YY}", "03.05.24"))
        assertNull(p("date{MM/DD/YYYY}", "02/30/2024"))
        assertEquals("14:05:00", p("time{hh:MM A}", "02:05 PM"))
        assertEquals("123456789", p("text{999-99-9999}", "123-45-6789"))
        assertNull(p("text{999-99-9999}", "123456789"))
        // validation: canonical or typed values that the pattern reads conform
        assertTrue(XfaPicture.conforms("text{999-99-9999}", "123456789"))
        assertTrue(XfaPicture.conforms("date{MM/DD/YYYY}", "05/03/2024"))
        assertFalse(XfaPicture.conforms("text{999-99-9999}", "12-34"))
        assertTrue(XfaPicture.conforms("text{999}", ""))
    }
}
