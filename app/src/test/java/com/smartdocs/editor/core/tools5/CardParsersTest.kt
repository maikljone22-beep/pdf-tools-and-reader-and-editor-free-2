package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardParsersTest {

    @Test fun latinCard() {
        val c = BusinessCardParser.parse(
            """
            John Smith
            Sales Manager
            ACME Trading LLC
            Mobile: +966 55 123 4567
            Tel: 011-456-7890
            Fax: 011 456 7891
            john.smith@acme.com
            www.acme.com
            King Fahd Road, Riyadh
            """.trimIndent(),
        )
        assertEquals("John Smith", c.name)
        assertEquals("Sales Manager", c.title)
        assertEquals("ACME Trading LLC", c.company)
        assertEquals(listOf("john.smith@acme.com"), c.emails)
        assertEquals(listOf("www.acme.com"), c.websites)
        assertEquals(3, c.phones.size)
        assertEquals(Phone("+966551234567", PhoneType.CELL), c.phones[0])
        assertEquals(PhoneType.WORK, c.phones[1].type)
        assertEquals(PhoneType.FAX, c.phones[2].type)
        assertTrue(c.address.contains("King Fahd Road"))
    }

    @Test fun arabicCardWithArabicDigits() {
        val c = BusinessCardParser.parse("محمد أحمد العتيبي\nمدير المبيعات\nشركة النور للتجارة\nجوال: ٠٥٥١٢٣٤٥٦٧\ninfo@alnoor.sa")
        assertEquals("محمد أحمد العتيبي", c.name)
        assertEquals("مدير المبيعات", c.title)
        assertEquals("شركة النور للتجارة", c.company)
        assertEquals("0551234567", c.phones.single().number)
        assertEquals(PhoneType.CELL, c.phones.single().type)
        assertEquals(listOf("info@alnoor.sa"), c.emails)
    }

    @Test fun vcardEscapesAndFolds() {
        val v = VCard.write(Contact(name = "Ali Hasan", company = "A, B; C", phones = listOf(Phone("+1555", PhoneType.WORK)), note = "x".repeat(100)))
        assertTrue(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(v.contains("FN:Ali Hasan\r\n"))
        assertTrue(v.contains("N:Hasan;Ali;;;\r\n"))
        assertTrue(v.contains("ORG:A\\, B\\; C\r\n"))
        assertTrue(v.contains("TEL;TYPE=WORK,VOICE:+1555"))
        assertTrue(v.lines().all { it.trimEnd('\r').toByteArray().size <= 75 })
        assertTrue(v.contains("\r\n x"))
        assertTrue(v.endsWith("END:VCARD\r\n"))
    }

    @Test fun receiptLatin() {
        val r = ReceiptParser.parse(
            """
            Panda Hypermarket
            Simplified Tax Invoice
            Date: 14/03/2024 18:22
            Milk 2 x 6.50     13.00
            Bread             4.00
            Subtotal          17.00
            VAT 15%           2.55
            Total (incl. VAT) SAR 19.55
            Cash              50.00
            Change            30.45
            """.trimIndent(),
        )
        assertEquals("Panda Hypermarket", r.merchant)
        assertEquals("2024-03-14", r.date)
        assertEquals("19.55", r.total)
        assertEquals("SAR", r.currency)
    }

    @Test fun receiptArabic() {
        val r = ReceiptParser.parse("مطعم البيك\nفاتورة ضريبية مبسطة\nالتاريخ 2024-01-05\nوجبة   ٢٥٫٠٠\nالإجمالي ٢٨٫٧٥ ر.س")
        assertEquals("مطعم البيك", r.merchant)
        assertEquals("2024-01-05", r.date)
        assertEquals("28.75", r.total)
        assertEquals("SAR", r.currency)
    }

    @Test fun amounts() {
        assertEquals("1234.50", ReceiptParser.normalizeAmount("1,234.50"))
        assertEquals("1234.50", ReceiptParser.normalizeAmount("1.234,50"))
        assertEquals("12.5", ReceiptParser.normalizeAmount("12,5"))
        assertEquals("1234", ReceiptParser.normalizeAmount("1,234"))
        assertNull(ReceiptParser.normalizeAmount("abc"))
    }

    @Test fun csvRoundTrip() {
        val row = Csv.row(listOf("a", "b,c", "d\"e", "x y"))
        assertEquals("a,\"b,c\",\"d\"\"e\",x y", row)
        val parsed = Csv.parse("h1;h2\n1;\"x;y\"\n")
        assertEquals(listOf(listOf("h1", "h2"), listOf("1", "x;y")), parsed)
        assertEquals('\t', Csv.detectSeparator("a\tb\tc\n1\t2\t3"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "line\nbreak")), Csv.parse("\uFEFFa,b\r\n1,\"line\nbreak\""))
    }
}
