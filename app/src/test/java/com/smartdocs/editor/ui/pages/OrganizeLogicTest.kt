package com.smartdocs.editor.ui.pages

import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.SecMethod
import com.smartdocs.editor.core.security.SecurityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** audit 5 G-11 / G-12 / G-03 / G-02 / G-09: pure rules of the page organizer. */
class OrganizeLogicTest {

    private fun counter(start: Long = 100): () -> Long { var n = start; return { n++ } }

    @Test fun clipboardSurvivesDeleteOfAnEarlierPage() {
        // the audit scenario: copy page 3, delete page 1, paste -> the old page 3 (now index 1), not the old page 4
        val ids = listOf(1L, 2L, 3L, 4L)
        val clip = OrganizeLogic.idsOf(setOf(2), ids)
        val after = OrganizeLogic.idsAfterDelete(ids, setOf(0))
        assertEquals(listOf(1), OrganizeLogic.indexesOf(clip, after))
    }

    @Test fun clipboardDropsDeletedPages() {
        val ids = listOf(1L, 2L, 3L)
        val clip = OrganizeLogic.idsOf(setOf(0, 2), ids)
        val after = OrganizeLogic.idsAfterDelete(ids, setOf(2))
        assertEquals(listOf(0), OrganizeLogic.indexesOf(clip, after))
    }

    @Test fun clipboardFollowsReorderAndInsert() {
        val ids = listOf(1L, 2L, 3L)
        val clip = OrganizeLogic.idsOf(setOf(0), ids)                      // page id 1
        val reordered = OrganizeLogic.idsAfterReorder(ids, listOf(2, 1, 0), counter())
        assertEquals(listOf(2), OrganizeLogic.indexesOf(clip, reordered))
        val inserted = OrganizeLogic.idsAfterInsert(reordered, 0, 2, counter())
        assertEquals(5, inserted.size)
        assertEquals(listOf(4), OrganizeLogic.indexesOf(clip, inserted))
    }

    @Test fun repeatedPageInReorderIsACopy() {
        val ids = listOf(1L, 2L)
        val out = OrganizeLogic.idsAfterReorder(ids, listOf(0, 1, 0), counter(50))
        assertEquals(listOf(1L, 2L, 50L), out)
        val pasted = OrganizeLogic.idsAfterPaste(ids, 1, 1, counter(70))
        assertEquals(listOf(1L, 70L, 2L), pasted)
        // the clipboard still names the original page, not the copy
        assertEquals(listOf(0), OrganizeLogic.indexesOf(listOf(1L), pasted))
    }

    @Test fun selectAllTogglesOnVisiblePages() {
        val visible = listOf(1, 4)                     // filter on: only pages 2 and 5 are shown
        val first = OrganizeLogic.selectAllToggle(emptySet(), visible)
        assertEquals(setOf(1, 4), first)
        assertEquals(emptySet<Int>(), OrganizeLogic.selectAllToggle(first, visible))   // second tap clears (G-12)
        assertEquals(setOf(1, 4), OrganizeLogic.selectAllToggle(setOf(1), visible))
        assertEquals(emptySet<Int>(), OrganizeLogic.selectAllToggle(emptySet(), emptyList()))
    }

    private fun restricted(perms: Int, owner: Boolean = false) =
        SecurityInfo(method = SecMethod.PASSWORD, algorithm = "AES", keyLength = 128, version = 4, revision = 4, permissions = perms, ownerAccess = owner)

    @Test fun restrictionDecision() {
        val noPrintNoCopy = restricted(-1 xor (4 or 16))
        assertFalse(OrganizeLogic.allows(noPrintNoCopy, DocAction.PRINT))
        assertFalse(OrganizeLogic.allows(noPrintNoCopy, DocAction.COPY))
        assertTrue(OrganizeLogic.allows(noPrintNoCopy, DocAction.ASSEMBLE))
        // the owner password lifts the restrictions; unencrypted / unknown documents are not restricted
        assertTrue(OrganizeLogic.allows(restricted(-1 xor (4 or 16), owner = true), DocAction.PRINT))
        assertTrue(OrganizeLogic.allows(SecurityInfo.NONE, DocAction.COPY))
        assertTrue(OrganizeLogic.allows(null, DocAction.PRINT))
        assertTrue(OrganizeLogic.allows(restricted(-1), DocAction.PRINT))
    }

    @Test fun ownerPasswordWarning() {
        assertTrue(OrganizeLogic.ownerPasswordWillChange(restricted(-1)))
        assertFalse(OrganizeLogic.ownerPasswordWillChange(restricted(-1, owner = true)))
        assertFalse(OrganizeLogic.ownerPasswordWillChange(SecurityInfo.NONE))
        assertFalse(OrganizeLogic.ownerPasswordWillChange(null))
    }

    @Test fun printRangeSpec() {
        assertEquals("1-3,5,7-8", OrganizeLogic.rangeSpec(listOf(6, 0, 1, 2, 4, 7, 2)))
        assertEquals("4", OrganizeLogic.rangeSpec(listOf(3)))
        assertEquals("", OrganizeLogic.rangeSpec(emptyList()))
    }
}
