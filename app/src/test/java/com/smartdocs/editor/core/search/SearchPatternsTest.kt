package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPatternsTest {
    private fun vals(text: String, k: PatternKind) = SearchPatterns.find(text, setOf(k)).map { it.value }
    private fun kinds(text: String) = SearchPatterns.find(text, PatternKind.values().toSet()).map { it.kind }

    @Test fun yemeniPhones() {
        assertEquals(listOf("777123456"), vals("اتصل على 777123456 الآن", PatternKind.PHONE_YE))
        assertEquals(listOf("+967 771 234 567"), vals("Tel: +967 771 234 567.", PatternKind.PHONE_YE))
        assertEquals(listOf("٧٣٣١٢٣٤٥٦"), vals("الرقم ٧٣٣١٢٣٤٥٦", PatternKind.PHONE_YE))
        assertEquals(listOf("01-234567"), vals("هاتف المكتب 01-234567", PatternKind.PHONE_YE))
        assertTrue(vals("7771234567890", PatternKind.PHONE_YE).isEmpty())
        assertTrue(vals("رقم 761234567", PatternKind.PHONE_YE).isEmpty())
    }

    @Test fun saudiAndEgyptPhones() {
        assertEquals(listOf("0501234567"), vals("جوال 0501234567", PatternKind.PHONE_SA))
        assertEquals(listOf("+966 55 123 4567"), vals("+966 55 123 4567", PatternKind.PHONE_SA))
        assertEquals(listOf("01012345678"), vals("موبايل 01012345678", PatternKind.PHONE_EG))
        assertEquals(listOf("+20 112 345 6789".replace(" ", "")), vals("+201123456789", PatternKind.PHONE_EG))
        assertTrue(vals("0131234567", PatternKind.PHONE_EG).isEmpty())
    }

    @Test fun gulfAndInternationalPhones() {
        assertEquals(listOf("+971 50 123 4567"), vals("UAE +971 50 123 4567", PatternKind.PHONE_GULF))
        assertEquals(listOf("+965 9123 4567"), vals("Kuwait +965 9123 4567", PatternKind.PHONE_GULF))
        assertEquals(listOf("+44 20 7946 0958"), vals("London +44 20 7946 0958", PatternKind.PHONE_INTL))
    }

    @Test fun email() {
        assertEquals(listOf("ali.saleh@example.com"), vals("راسلني: ali.saleh@example.com،", PatternKind.EMAIL))
        assertTrue(vals("not an email@", PatternKind.EMAIL).isEmpty())
    }

    @Test fun luhnCards() {
        assertTrue(SearchPatterns.luhn("4111111111111111"))
        assertFalse(SearchPatterns.luhn("4111111111111112"))
        assertEquals(listOf("4111 1111 1111 1111"), vals("البطاقة 4111 1111 1111 1111 صالحة", PatternKind.CARD))
        assertTrue(vals("4111 1111 1111 1112", PatternKind.CARD).isEmpty())
        assertEquals(listOf("5500-0000-0000-0004"), vals("MC 5500-0000-0000-0004", PatternKind.CARD))
    }

    @Test fun iban() {
        assertTrue(SearchPatterns.ibanValid("SA03 8000 0000 6080 1016 7519"))
        assertTrue(SearchPatterns.ibanValid("GB82 WEST 1234 5698 7654 32"))
        assertFalse(SearchPatterns.ibanValid("SA03 8000 0000 6080 1016 7518"))
        assertEquals(listOf("SA03 8000 0000 6080 1016 7519"), vals("الآيبان SA03 8000 0000 6080 1016 7519 لدى البنك", PatternKind.IBAN))
        assertEquals(listOf("GB82WEST12345698765432"), vals("IBAN GB82WEST12345698765432 THANKS", PatternKind.IBAN))
    }

    @Test fun nationalIds() {
        // Saudi ID: 10 digits, starts with 1, Luhn valid
        assertTrue(SearchPatterns.isNationalId("1000000008"))
        assertFalse(SearchPatterns.isNationalId("1000000009"))
        // Egypt: century 2, born 1990-05-14, governorate 01
        assertTrue(SearchPatterns.isNationalId("29005140100123"))
        assertFalse(SearchPatterns.isNationalId("29013400100123"))
        assertEquals(listOf("29005140100123"), vals("الرقم القومي 29005140100123", PatternKind.NATIONAL_ID))
        assertEquals(listOf("01012345678"), vals("رقم الهوية 01012345678", PatternKind.NATIONAL_ID))
    }

    @Test fun passport() {
        assertEquals(listOf("A1234567"), vals("جواز سفر رقم A1234567", PatternKind.PASSPORT))
    }

    @Test fun dates() {
        assertEquals(listOf("15/03/2024"), vals("بتاريخ 15/03/2024 م", PatternKind.DATE))
        assertEquals(listOf("2024-03-15"), vals("on 2024-03-15.", PatternKind.DATE))
        assertTrue(vals("45/13/2024", PatternKind.DATE).isEmpty())
        assertEquals(listOf("12/09/1445 هـ"), vals("الموافق 12/09/1445 هـ", PatternKind.DATE_HIJRI))
        assertTrue(vals("الموافق 12/09/1445 هـ", PatternKind.DATE).isEmpty())
        assertEquals(listOf("١٤٤٥ هـ"), vals("عام ١٤٤٥ هـ", PatternKind.DATE_HIJRI))
        assertEquals(listOf("5 مارس 2024"), vals("صدر في 5 مارس 2024 بصنعاء", PatternKind.DATE_MONTH_NAME))
        assertEquals(listOf("1 رمضان 1445 هـ"), vals("في 1 رمضان 1445 هـ", PatternKind.DATE_MONTH_NAME))
        assertEquals(listOf("March 5, 2024"), vals("Dated March 5, 2024 in Aden", PatternKind.DATE_MONTH_NAME))
    }

    @Test fun urlAndAmount() {
        assertEquals(listOf("https://example.com/a?b=1"), vals("الرابط https://example.com/a?b=1.", PatternKind.URL))
        assertEquals(listOf("www.site.ye"), vals("زوروا www.site.ye", PatternKind.URL))
        assertEquals(listOf("1,500,000 ريال"), vals("المبلغ 1,500,000 ريال فقط", PatternKind.AMOUNT))
        assertEquals(listOf("\$250.75"), vals("Total \$250.75 due", PatternKind.AMOUNT))
        assertEquals(listOf("SAR 3,200"), vals("Paid SAR 3,200 today", PatternKind.AMOUNT))
        assertTrue(vals("1500 شخص", PatternKind.AMOUNT).isEmpty())
    }

    @Test fun overlapsKeepLongest() {
        val k = kinds("card 4111111111111111")
        assertEquals(listOf(PatternKind.CARD), k)
    }

    @Test fun customRegex() {
        assertEquals(listOf(4..8), SearchPatterns.findCustom("ref ABC12 end", "[A-Z]{3}\\d{2}", ignoreCase = false))
        assertTrue(SearchPatterns.findCustom("x", "([").isEmpty())
    }
}
