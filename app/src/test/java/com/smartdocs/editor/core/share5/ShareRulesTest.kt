package com.smartdocs.editor.core.share5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRulesTest {
    private val day = 24L * 3600_000
    private val now = 1_800_000_000_000L

    @Test fun linkStatus() {
        assertEquals(LinkRules.Status.ACTIVE, LinkRules.status(0, false, now))
        assertEquals(LinkRules.Status.ACTIVE, LinkRules.status(now + 1, false, now))
        assertEquals(LinkRules.Status.EXPIRED, LinkRules.status(now, false, now))
        assertEquals(LinkRules.Status.EXPIRED, LinkRules.status(now - 5, false, now))
        assertEquals(LinkRules.Status.DISABLED, LinkRules.status(now + day, true, now))
        assertEquals(LinkRules.Status.DISABLED, LinkRules.status(now - day, true, now))
    }

    @Test fun presetsAndDaysLeft() {
        assertEquals(0L, LinkRules.expiryFromPreset(0, now))
        assertEquals(now + 7 * day, LinkRules.expiryFromPreset(7, now))
        assertEquals(-1, LinkRules.daysLeft(0, now))
        assertEquals(0, LinkRules.daysLeft(now - 1, now))
        assertEquals(1, LinkRules.daysLeft(now + 1, now))
        assertEquals(7, LinkRules.daysLeft(now + 7 * day, now))
        assertEquals(8, LinkRules.daysLeft(now + 7 * day + 1, now))
    }

    @Test fun lockoutSlidingWindow() {
        var t = LinkRules.Tries(emptyList())
        repeat(9) { t = t.fail(now + it * 1000L) }
        assertFalse(t.locked(now + 9000))
        t = t.fail(now + 9000)
        assertTrue(t.locked(now + 9001))
        assertEquals(LinkRules.LOCK_WINDOW_MS - 9001, t.retryInMs(now + 9001))
        // first attempt leaves the window → unlocked again
        assertFalse(t.locked(now + LinkRules.LOCK_WINDOW_MS))
        assertEquals(0L, t.retryInMs(now + LinkRules.LOCK_WINDOW_MS))
        assertEquals(9, t.prune(now + LinkRules.LOCK_WINDOW_MS).times.size)
    }

    @Test fun passwordPolicy() {
        assertTrue(LinkRules.passwordOk(""))
        assertFalse(LinkRules.passwordOk("abc"))
        assertTrue(LinkRules.passwordOk("abcd"))
        assertFalse(LinkRules.passwordOk("x".repeat(65)))
    }

    private val sent = listOf(
        EmailMatch.Sent("r1", "AB01CD", "Contract.pdf", 1),
        EmailMatch.Sent("r2", "FF00EE", "Report.pdf", 2),
        EmailMatch.Sent("r3", "1234", "Report.pdf", 3),
    )

    @Test fun matchByReviewIdFirst() {
        val m = EmailMatch.match(sent, EmailMatch.Incoming("r2", "AB01CD", "Contract.pdf"))!!
        assertEquals("r2", m.sent.reviewId); assertEquals(EmailMatch.How.REVIEW_ID, m.how)
    }

    @Test fun matchByDocumentIdCaseInsensitive() {
        val m = EmailMatch.match(sent, EmailMatch.Incoming(null, "ab01cd", null))!!
        assertEquals("r1", m.sent.reviewId); assertEquals(EmailMatch.How.DOCUMENT_ID, m.how)
        assertEquals("r1", EmailMatch.match(sent, EmailMatch.Incoming("unknown", "<AB 01 CD>", null))!!.sent.reviewId)
    }

    @Test fun matchByFileNameOnlyWhenUnique() {
        val m = EmailMatch.match(sent, EmailMatch.Incoming(null, "999", "C:\\mail\\Contract (2)-commented.pdf"))!!
        assertEquals("r1", m.sent.reviewId); assertEquals(EmailMatch.How.FILE_NAME, m.how)
        assertNull(EmailMatch.match(sent, EmailMatch.Incoming(null, null, "Report.pdf"))) // two candidates
        assertNull(EmailMatch.match(sent, EmailMatch.Incoming(null, null, null)))
        assertNull(EmailMatch.match(emptyList(), EmailMatch.Incoming("r1", "AB01CD", "Contract.pdf")))
    }

    @Test fun parseXfdfAndFdf() {
        val xml = EmailMatch.withIds("<?xml version=\"1.0\"?>\n<xfdf xmlns=\"http://ns.adobe.com/xfdf/\">\n<f href=\"Contract.pdf\"/>\n</xfdf>", "AB01CD", "r1")
        val inc = EmailMatch.fromXfdf(xml)
        assertEquals("AB01CD", inc.idHex); assertEquals("Contract.pdf", inc.href); assertEquals("r1", inc.infoReviewId)
        assertEquals("r1", EmailMatch.match(sent, inc)!!.sent.reviewId)
        val fdf = EmailMatch.fromFdf("%FDF-1.2\n1 0 obj << /FDF << /F (Report.pdf) /ID [<FF00EE> <FF00EE>] /Annots [] >> >> endobj")
        assertEquals("FF00EE", fdf.idHex); assertEquals("Report.pdf", fdf.href)
        assertEquals("r2", EmailMatch.match(sent, fdf)!!.sent.reviewId)
    }

    @Test fun baseNames() {
        assertEquals("contract", EmailMatch.baseName("Contract (3).pdf"))
        assertEquals("contract", EmailMatch.baseName("/x/y/Contract_reviewed.PDF"))
        assertEquals("\u0639\u0642\u062f", EmailMatch.baseName("\u0639\u0642\u062f - \u0645\u0639\u0644\u0642.pdf"))
        assertNull(EmailMatch.baseName(" "))
    }

    @Test fun ranges() {
        assertEquals(listOf(0, 1, 2, 4), ShareChoice.parseRange("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), ShareChoice.parseRange("8-", 10))
        assertEquals(listOf(0, 1), ShareChoice.parseRange("-2", 10))
        assertEquals(listOf(1, 2), ShareChoice.parseRange("\u0662\u060C\u0663", 10)) // Arabic digits and comma
        assertEquals(emptyList<Int>(), ShareChoice.parseRange("40", 10))
        assertNull(ShareChoice.pages(ShareChoice.Pages.RANGE, 0, "x", 10))
        assertEquals(listOf(3), ShareChoice.pages(ShareChoice.Pages.CURRENT, 3, "", 10))
        assertEquals(listOf(9), ShareChoice.pages(ShareChoice.Pages.CURRENT, 40, "", 10))
    }

    @Test fun outNames() {
        assertEquals("a.pdf", ShareChoice.outName("a", (0 until 5).toList(), 5, ShareChoice.Format.AS_IS))
        assertEquals("a (p.3).pdf", ShareChoice.outName("a", listOf(2), 5, ShareChoice.Format.COMPRESSED))
        assertEquals("a (p.2-4).pdf", ShareChoice.outName("a", listOf(1, 2, 3), 5, ShareChoice.Format.FLATTENED))
        assertEquals("a (2p).pdf", ShareChoice.outName("a", listOf(0, 3), 5, ShareChoice.Format.NO_COMMENTS))
        assertEquals("a - comments.xfdf", ShareChoice.outName("a", listOf(0), 5, ShareChoice.Format.COMMENTS_XFDF))
        assertEquals("a (p.1).jpg", ShareChoice.outName("a", listOf(0), 5, ShareChoice.Format.IMAGES))
    }
}
