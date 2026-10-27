package com.smartdocs.editor.core.pdf

import com.smartdocs.editor.core.pdf.AttachmentPolicy.Decision
import com.smartdocs.editor.core.pdf.AttachmentPolicy.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentPolicyTest {
    @Test fun decisions() {
        val rules = mapOf("docx" to Rule.ALLOW, "zip" to Rule.NEVER)
        assertEquals(Decision.OPEN_HERE, AttachmentPolicy.decide("a.PDF", rules))
        assertEquals(Decision.OPEN, AttachmentPolicy.decide("Report.DOCX", rules))
        assertEquals(Decision.BLOCK, AttachmentPolicy.decide("x.zip", rules))
        assertEquals(Decision.ASK, AttachmentPolicy.decide("photo.jpg", rules))
        assertEquals(Decision.ASK_RISKY, AttachmentPolicy.decide("setup.apk", rules))
        assertEquals(Decision.ASK_RISKY, AttachmentPolicy.decide("noext", rules))
        assertEquals(Decision.OPEN, AttachmentPolicy.decide("page.html", mapOf("html" to Rule.ALLOW)))
    }

    @Test fun normalizeAndCodec() {
        assertEquals("docx", AttachmentPolicy.normalize(" *.DOCX "))
        assertEquals("pdf", AttachmentPolicy.normalize(".pdf"))
        assertNull(AttachmentPolicy.normalize(""))
        assertNull(AttachmentPolicy.normalize("a b"))
        val m = mapOf("zip" to Rule.NEVER, "docx" to Rule.ALLOW)
        assertEquals("docx=ALLOW,zip=NEVER", AttachmentPolicy.encode(m))
        assertEquals(m, AttachmentPolicy.decode(AttachmentPolicy.encode(m)))
        assertEquals(mapOf("txt" to Rule.ALLOW), AttachmentPolicy.decode("txt=ALLOW,bad,x=MAYBE"))
    }
}
