package com.smartdocs.editor.core.pdf

import com.smartdocs.editor.core.pdf.SignedGuard.Change
import com.smartdocs.editor.core.pdf.SignedGuard.Decision
import com.smartdocs.editor.core.pdf.SignedGuard.Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** tab-view-core V-06 / E-01: decisions of the one signed-file gate. */
class SignedGuardTest {

    @Test fun unsignedFilesProceed() {
        for (c in Change.values()) for (i in listOf(true, false, null)) assertEquals(Decision.PROCEED, SignedGuard.decide(Info.NONE, c, i))
    }

    @Test fun signedContentChangesOfferACopy() {
        val signed = Info(true, null)
        assertEquals(Decision.OFFER_COPY, SignedGuard.decide(signed, Change.MODIFY, false))
        assertEquals(Decision.OFFER_COPY, SignedGuard.decide(signed, Change.MODIFY, true))
        assertEquals(Decision.OFFER_COPY, SignedGuard.decide(signed, Change.MODIFY, null))
    }

    @Test fun signedAnnotationsAreAppended() {
        val signed = Info(true, null)
        assertEquals(Decision.SAVE_INCREMENTAL, SignedGuard.decide(signed, Change.ANNOTATE, true))
        assertEquals(Decision.SAVE_INCREMENTAL, SignedGuard.decide(signed, Change.ANNOTATE, null))
        assertEquals(Decision.OFFER_COPY, SignedGuard.decide(signed, Change.ANNOTATE, false))
        assertEquals(Decision.SAVE_INCREMENTAL, SignedGuard.decide(signed, Change.FORM_FILL, true))
    }

    @Test fun certificationLevels() {
        val p1 = Info(true, 1); val p2 = Info(true, 2); val p3 = Info(true, 3)
        for (c in Change.values()) assertEquals(Decision.REFUSE, SignedGuard.decide(p1, c, true))
        assertEquals(Decision.SAVE_INCREMENTAL, SignedGuard.decide(p2, Change.FORM_FILL, true))
        assertEquals(Decision.REFUSE, SignedGuard.decide(p2, Change.ANNOTATE, true))
        assertEquals(Decision.SAVE_INCREMENTAL, SignedGuard.decide(p3, Change.ANNOTATE, true))
        assertEquals(Decision.REFUSE, SignedGuard.decide(p3, Change.MODIFY, true))
    }

    @Test fun reasons() {
        assertEquals(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, SignedGuard.reasonOf(Decision.REFUSE))
        assertEquals(SignedDocumentException.Reason.INCREMENTAL_FAILED, SignedGuard.reasonOf(Decision.OFFER_COPY))
        assertNull(SignedGuard.reasonOf(Decision.PROCEED)); assertNull(SignedGuard.reasonOf(Decision.SAVE_INCREMENTAL))
    }

    @Test fun incrementalPrefixCheck() {
        val dir = createTempDir("sg")
        try {
            val src = File(dir, "a.pdf").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            val appended = File(dir, "b.pdf").apply { writeBytes(src.readBytes() + "%update".toByteArray()) }
            val rewritten = File(dir, "c.pdf").apply { writeBytes(src.readBytes().also { it[150_000] = 7 } + "%x".toByteArray()) }
            val same = File(dir, "d.pdf").apply { writeBytes(src.readBytes()) }
            assertTrue(SignedGuard.isIncrementalOf(src, appended))
            assertFalse(SignedGuard.isIncrementalOf(src, rewritten))
            assertFalse(SignedGuard.isIncrementalOf(src, same))
        } finally { dir.deleteRecursively() }
    }
}
