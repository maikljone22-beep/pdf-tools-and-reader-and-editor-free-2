package com.smartdocs.editor.core.share5

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-comments-forms: default e-mail subject / body templates. */
class MailDefaultsTest {
    @Test fun templatesFillTheFileName() {
        assertEquals("Contract", MailDefaults.subjectOf(MailDefaults.DEFAULT_SUBJECT, "Contract"))
        assertEquals("Review: Contract", MailDefaults.subjectOf("Review: {file}", "Contract"))
        assertEquals("Contract", MailDefaults.subjectOf("  ", "Contract"))
        assertEquals("Please see Contract attached.", MailDefaults.fill("Please see {file} attached.", "Contract"))
        assertEquals("", MailDefaults.fill("", "Contract"))
    }
}
