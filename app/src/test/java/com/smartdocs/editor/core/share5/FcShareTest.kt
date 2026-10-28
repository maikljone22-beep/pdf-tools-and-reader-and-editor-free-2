package com.smartdocs.editor.core.share5

import com.smartdocs.editor.core.collab.CommentMerge
import com.smartdocs.editor.core.collab.ReviewReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-sync: recipient auto-complete, the FDF size rule, review reminders and take-over. */
class FcShareTest {
    @Test fun recipientBookRanksAndCompletes() {
        var list = RecipientBook.add(emptyList(), listOf("Ann@Example.com", "bad-address", "bob@work.org"), now = 100)
        list = RecipientBook.add(list, listOf("ann@example.com"), now = 200)
        assertEquals(listOf("ann@example.com", "bob@work.org"), list.map { it.email })
        assertEquals(2, list.first().uses)
        assertEquals(listOf("ann@example.com"), RecipientBook.rank(list, "an", emptySet(), 5))
        assertEquals(listOf("bob@work.org"), RecipientBook.rank(list, "work", emptySet(), 5))      // domain part
        assertEquals(emptyList<String>(), RecipientBook.rank(list, "an", setOf("ann@example.com"), 5))
        assertEquals(emptyList<String>(), RecipientBook.rank(list, "", emptySet(), 5))
        assertEquals("bo", RecipientBook.lastToken("ann@example.com, bo"))
        assertEquals("", RecipientBook.lastToken("ann@example.com, "))
        assertEquals("ann@example.com, bob@work.org, ", RecipientBook.complete("ann@example.com, bo", "bob@work.org"))
        assertEquals("x@y.z, ", RecipientBook.complete("", "x@y.z"))
        assertEquals(listOf("a@b.c", "d@e.f"), RecipientBook.tokens(" a@b.c ;D@e.f،"))
        assertTrue(RecipientBook.add(emptyList(), (1..300).map { "u$it@x.io" }, 1).size <= RecipientBook.MAX)
    }

    @Test fun fdfRule() {
        val mb = 1024L * 1024L
        assertTrue(FdfRule.useFdf(6 * mb, true, 5))
        assertFalse(FdfRule.useFdf(5 * mb, true, 5))
        assertFalse(FdfRule.useFdf(60 * mb, false, 5))
    }

    @Test fun reviewReminderTiming() {
        val day = 24 * 3600_000L; val now = 1_000 * day
        assertEquals(now + day, ReviewReminder.fireAt(now + 2 * day, false, now))
        assertEquals(now, ReviewReminder.fireAt(now + 3 * 3600_000L, false, now))      // inside the lead: right away
        assertNull(ReviewReminder.fireAt(now + 10 * 60_000L, false, now))              // too close
        assertNull(ReviewReminder.fireAt(now - day, false, now))
        assertNull(ReviewReminder.fireAt(0, false, now))
        assertNull(ReviewReminder.fireAt(now + 2 * day, true, now))
    }

    @Test fun takeOverPicksOthersCommentsByAuthor() {
        val comments = mapOf("n1" to "Sara", "n2" to "Omar", "n3" to "Sara", "mine" to "Me")
        val foreign = setOf("n1", "n2", "n3")
        assertEquals(setOf("n1", "n3"), CommentMerge.takeOver(comments, foreign, "Sara"))
        assertEquals(setOf("n1", "n2", "n3"), CommentMerge.takeOver(comments, foreign, null))
        assertEquals(emptySet<String>(), CommentMerge.takeOver(comments, foreign, "Me"))
    }

}
