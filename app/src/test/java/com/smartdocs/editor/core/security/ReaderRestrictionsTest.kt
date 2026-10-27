package com.smartdocs.editor.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRestrictionsTest {
    /** /P with the given 1-based bits set (and the reserved high bits as PDF writers set them). */
    private fun p(vararg bits: Int): Int = bits.fold(0xFFFFF000.toInt()) { acc, b -> acc or (1 shl (b - 1)) }

    private fun info(perms: Int, owner: Boolean = false, revision: Int = 4, method: SecMethod = SecMethod.PASSWORD) =
        SecurityInfo(method, "Standard", algorithm = "AES", keyLength = 128, version = 4, revision = revision, permissions = perms, ownerAccess = owner)

    @Test fun revision3PlusBits() {
        val a = PermissionBits.allowed(p(3, 5), 4)
        assertTrue(a.getValue(Perm.PRINT)); assertFalse(a.getValue(Perm.PRINT_HIGH))          // bit 12 missing: low resolution only
        assertTrue(a.getValue(Perm.COPY)); assertTrue(a.getValue(Perm.ACCESSIBILITY))
        assertFalse(a.getValue(Perm.MODIFY)); assertFalse(a.getValue(Perm.ANNOTATE)); assertFalse(a.getValue(Perm.FILL_FORMS)); assertFalse(a.getValue(Perm.ASSEMBLE))
        val b = PermissionBits.allowed(p(3, 12, 9, 10, 11), 3)
        assertTrue(b.getValue(Perm.PRINT_HIGH)); assertTrue(b.getValue(Perm.FILL_FORMS)); assertTrue(b.getValue(Perm.ASSEMBLE))
        assertTrue(b.getValue(Perm.ACCESSIBILITY)); assertFalse(b.getValue(Perm.COPY)); assertFalse(b.getValue(Perm.ANNOTATE))
        assertEquals(Perm.values().toList(), b.keys.toList())
    }

    @Test fun revision2FollowsTheFourOldBits() {
        val a = PermissionBits.allowed(p(3, 6), 2)
        assertTrue(a.getValue(Perm.PRINT_HIGH)); assertTrue(a.getValue(Perm.FILL_FORMS)); assertTrue(a.getValue(Perm.ANNOTATE))
        assertFalse(a.getValue(Perm.ASSEMBLE)); assertFalse(a.getValue(Perm.ACCESSIBILITY))
        // bits 9-12 mean nothing in revision 2
        val b = PermissionBits.allowed(p(9, 10, 11, 12), 2)
        assertFalse(b.getValue(Perm.FILL_FORMS)); assertFalse(b.getValue(Perm.ASSEMBLE)); assertFalse(b.getValue(Perm.ACCESSIBILITY))
    }

    @Test fun userPasswordHonoursEveryBit() {
        val i = info(p(3, 9))
        assertNull(ReaderRestrictions.check(i, false, DocAction.PRINT))
        assertNull(ReaderRestrictions.check(i, false, DocAction.FILL_FORMS))
        for (a in listOf(DocAction.COPY, DocAction.ANNOTATE, DocAction.MODIFY, DocAction.ASSEMBLE, DocAction.SAVE_UNPROTECTED, DocAction.CHANGE_SECURITY))
            assertEquals(a.name, Block.AUTHOR, ReaderRestrictions.check(i, false, a))
        assertTrue(ReaderRestrictions.restricted(i))
    }

    @Test fun ownerPasswordLiftsRestrictions() {
        val i = info(p(), owner = true)
        DocAction.values().forEach { assertNull(it.name, ReaderRestrictions.check(i, false, it)) }
        assertFalse(ReaderRestrictions.restricted(i))
    }

    @Test fun unencryptedAndUnknownAreFree() {
        DocAction.values().forEach {
            assertNull(ReaderRestrictions.check(SecurityInfo.NONE, false, it))
            assertNull(ReaderRestrictions.check(null, false, it))
        }
    }

    @Test fun certificatePermissionsAreHonouredToo() {
        val i = info(p(3, 4, 5, 6, 9, 10, 12), method = SecMethod.CERTIFICATE, revision = 0)
        assertNull(ReaderRestrictions.check(i, false, DocAction.MODIFY))
        assertNull(ReaderRestrictions.check(i, false, DocAction.ASSEMBLE))      // bit 4 implies assembling
        assertEquals(Block.AUTHOR, ReaderRestrictions.check(i, false, DocAction.SAVE_UNPROTECTED))
    }

    @Test fun protectedViewBlocksChangesOnly() {
        for (a in listOf(DocAction.ANNOTATE, DocAction.MODIFY, DocAction.FILL_FORMS, DocAction.ASSEMBLE, DocAction.CHANGE_SECURITY))
            assertEquals(Block.PROTECTED_VIEW, ReaderRestrictions.check(null, true, a))
        assertNull(ReaderRestrictions.check(null, true, DocAction.PRINT))
        assertNull(ReaderRestrictions.check(null, true, DocAction.COPY))
        // the author's refusal of printing still applies in Protected View
        assertEquals(Block.AUTHOR, ReaderRestrictions.check(info(p(5)), true, DocAction.PRINT))
    }

    @Test fun toolsAndQuickActions() {
        assertEquals(DocAction.ASSEMBLE, ReaderRestrictions.actionForTool("organize"))
        assertEquals(DocAction.FILL_FORMS, ReaderRestrictions.actionForTool("forms"))
        assertEquals(DocAction.COPY, ReaderRestrictions.actionForTool("pdf_to_text"))
        assertEquals(DocAction.CHANGE_SECURITY, ReaderRestrictions.actionForTool("protect_advanced"))
        assertEquals(DocAction.MODIFY, ReaderRestrictions.actionForTool("redact"))
        assertNull(ReaderRestrictions.actionForTool("signatures"))
        assertEquals(DocAction.ASSEMBLE, ReaderRestrictions.actionForQuick("DELETE_PAGE"))
        assertEquals(DocAction.SAVE_UNPROTECTED, ReaderRestrictions.actionForQuick("UNLOCK"))
        assertEquals(DocAction.PRINT, ReaderRestrictions.actionForQuick("PRINT"))
        assertNull(ReaderRestrictions.actionForQuick("PROPERTIES"))
        assertEquals(DocAction.MODIFY, ReaderRestrictions.actionForQuick("COMPRESS"))
    }
}
