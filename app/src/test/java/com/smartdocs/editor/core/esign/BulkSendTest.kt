package com.smartdocs.editor.core.esign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BulkSendTest {
    // ---------------------------------------------------------------- CSV / pasted lists

    @Test fun headerRowWithCustomColumns() {
        val r = CsvRecipients.parse("name,email,company\nAhmed Ali,ahmed@x.com,ACME\nSara,sara@y.org,Globex\n")
        assertEquals(listOf("name", "email", "company"), r.headers)
        assertEquals(2, r.rows.size)
        assertEquals("Ahmed Ali", r.rows[0].name)
        assertEquals("ahmed@x.com", r.rows[0].email)
        assertEquals(mapOf("company" to "ACME"), r.rows[0].extra)
        assertEquals(3, r.rows[1].line)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun quotedFieldsWithCommasQuotesAndLineBreaks() {
        val csv = "name,email,note\r\n\"Smith, John\",john@x.com,\"He said \"\"hi\"\"\"\r\n\"Multi\nLine\",m@x.com,ok\r\n"
        val r = CsvRecipients.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals("Smith, John", r.rows[0].name)
        assertEquals("He said \"hi\"", r.rows[0].extra["note"])
        assertEquals("Multi\nLine", r.rows[1].name)
        assertEquals("m@x.com", r.rows[1].email)
    }

    @Test fun utf8BomAndArabicNamesAndHeaders() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "الاسم,البريد الإلكتروني,المدينة\nمحمد عبد الله,mohd@x.sa,الرياض\n".toByteArray()
        val text = CsvRecipients.decode(bytes)
        assertTrue(!text.startsWith("﻿"))
        val r = CsvRecipients.parse(text)
        assertEquals(1, r.rows.size)
        assertEquals("محمد عبد الله", r.rows[0].name)
        assertEquals("mohd@x.sa", r.rows[0].email)
        assertEquals("الرياض", r.rows[0].extra["المدينة"])
    }

    @Test fun bomLeftInTextIsIgnored() {
        val r = CsvRecipients.parse("﻿name,email\nA,a@b.co")
        assertEquals("A", r.rows.single().name)
    }

    @Test fun utf16AndWindows1256Decoding() {
        val s = "اسم\tبريد\nعلي\tali@x.com\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        assertEquals(s, CsvRecipients.decode(le))
        val cp = java.nio.charset.Charset.forName("windows-1256")
        assertEquals("علي,ali@x.com", CsvRecipients.decode("علي,ali@x.com".toByteArray(cp)))
    }

    @Test fun delimiterDetectionSemicolonTabArabicComma() {
        assertEquals(';', CsvRecipients.delimiter("name;email\nA;a@b.co"))
        assertEquals('\t', CsvRecipients.delimiter("A\ta@b.co"))
        assertEquals('،', CsvRecipients.delimiter("علي، ali@x.com"))
        assertEquals("ali@x.com", CsvRecipients.parse("علي، ali@x.com").rows.single().email)
        assertEquals("b@c.de", CsvRecipients.parse("B;b@c.de").rows.single().email)
    }

    @Test fun pastedListWithoutHeaderAngleFormAndBareEmails() {
        val r = CsvRecipients.parse("Ahmed <ahmed@x.com>\nlaila@y.com\nb@z.com, Basma\nOnly Name\n\n")
        assertEquals(listOf("Ahmed", "laila", "Basma", "Only Name"), r.rows.map { it.name })
        assertEquals(listOf("ahmed@x.com", "laila@y.com", "b@z.com", ""), r.rows.map { it.email })
    }

    @Test fun issuesBadEmailDuplicateMissingName() {
        val r = CsvRecipients.parse("name,email\nA,a@x.com\nB,not-an-email\nC,A@X.com\n,\n,nobody")
        assertEquals(listOf("A"), r.rows.map { it.name })
        assertEquals(listOf(CsvRecipients.Issue.BAD_EMAIL, CsvRecipients.Issue.DUPLICATE, CsvRecipients.Issue.MISSING_NAME), r.issues.map { it.issue })
        assertEquals(listOf(3, 4, 6), r.issues.map { it.line })
    }

    @Test fun bidiMarksAreStripped() {
        val r = CsvRecipients.parse("‏سارة‏,‎sara@x.com‎")
        assertEquals("سارة", r.rows.single().name)
        assertEquals("sara@x.com", r.rows.single().email)
    }

    @Test fun rowLimit() {
        val text = (1..CsvRecipients.MAX_ROWS + 3).joinToString("\n") { "P$it,p$it@x.com" }
        val r = CsvRecipients.parse(text)
        assertEquals(CsvRecipients.MAX_ROWS, r.rows.size)
        assertEquals(3, r.issues.count { it.issue == CsvRecipients.Issue.TOO_MANY })
    }

    @Test fun emptyInput() {
        val r = CsvRecipients.parse("  \n\n")
        assertTrue(r.rows.isEmpty() && r.issues.isEmpty())
    }

    // ---------------------------------------------------------------- placeholders

    @Test fun templatePlaceholders() {
        val row = BulkRecipient("ليلى", "l@x.com", mapOf("Company" to "ACME", "3" to "X"))
        assertEquals("عقد ليلى - ACME", BulkTemplate.fill("عقد {الاسم} - {company}", row))
        assertEquals("Hi ليلى <l@x.com> X {unknown}", BulkTemplate.fill("Hi {Name} <{email}> {3} {unknown}", row))
        assertEquals("no placeholders", BulkTemplate.fill("no placeholders", row))
    }

    // ---------------------------------------------------------------- send loop

    private fun items(n: Int) = (1..n).map { BulkItem(BulkRecipient("P$it", "p$it@x.com")) }

    @Test fun partialFailureThenRetryOnlyFailed() {
        val calls = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val first = BulkSender.run(items(4), send = { r ->
            calls += r.name
            if (r.name == "P2" || r.name == "P4") throw IllegalStateException("HTTP 400: bad")
            BulkSender.Sent("id_" + r.name, "link_" + r.name)
        }, progress = { d, t -> progress += d to t })
        assertEquals(listOf("P1", "P2", "P3", "P4"), calls)
        assertEquals(2, BulkSender.sent(first)); assertEquals(2, BulkSender.failed(first))
        assertEquals("HTTP 400: bad", first[1].error)
        assertEquals("link_P1", first[0].link)
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)

        calls.clear()
        val second = BulkSender.run(first, send = { r -> calls += r.name; BulkSender.Sent("id_" + r.name, "link_" + r.name) })
        assertEquals(listOf("P2", "P4"), calls)
        assertEquals(4, BulkSender.sent(second))
        assertEquals("id_P1", second[0].requestId)
        assertEquals("", second[1].error)
    }

    @Test fun transientErrorsRetriedOnceOthersNot() {
        var n = 0
        val ok = BulkSender.run(items(1), send = { n++; if (n == 1) throw IOException("timeout"); BulkSender.Sent("a", "b") }, retryable = { it is IOException })
        assertEquals(2, n); assertEquals(BulkState.SENT, ok[0].state)
        n = 0
        val bad = BulkSender.run(items(1), send = { n++; throw IllegalArgumentException("400") }, retryable = { it is IOException })
        assertEquals(1, n); assertEquals(BulkState.FAILED, bad[0].state)
    }

    @Test fun cancelKeepsSentMarksRestCancelledAndRetryResendsThem() {
        var stop = false
        val calls = mutableListOf<String>()
        val first = BulkSender.run(items(5), send = { r ->
            calls += r.name
            if (r.name == "P2") stop = true          // user presses cancel while P2 is in flight
            if (r.name == "P1") throw IllegalStateException("HTTP 400")
            BulkSender.Sent("id_" + r.name, "l")
        }, cancelled = { stop })
        assertEquals(listOf("P1", "P2"), calls)
        assertEquals(listOf(BulkState.FAILED, BulkState.SENT, BulkState.CANCELLED, BulkState.CANCELLED, BulkState.CANCELLED), first.map { it.state })
        assertEquals(1, BulkSender.sent(first)); assertEquals(1, BulkSender.failed(first))
        assertEquals(3, BulkSender.cancelled(first)); assertEquals(4, BulkSender.retryable(first))

        calls.clear()
        val second = BulkSender.run(first, send = { r -> calls += r.name; BulkSender.Sent("id_" + r.name, "l") })
        assertEquals(listOf("P1", "P3", "P4", "P5"), calls)
        assertEquals(5, BulkSender.sent(second)); assertEquals(0, BulkSender.retryable(second))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationPropagates() {
        BulkSender.run(items(2), send = { throw java.util.concurrent.CancellationException("stop") })
    }
}
