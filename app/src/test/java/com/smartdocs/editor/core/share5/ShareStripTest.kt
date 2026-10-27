package com.smartdocs.editor.core.share5

import com.smartdocs.editor.core.redact.SanitizeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareStripTest {
    @Test fun items() {
        assertTrue(ShareStrip.items(ShareStrip.Options()).isEmpty())
        assertEquals(setOf(SanitizeItem.METADATA, SanitizeItem.PRIVATE_DATA), ShareStrip.items(ShareStrip.Options(metadata = true)))
        assertEquals(setOf(SanitizeItem.JAVASCRIPT, SanitizeItem.ATTACHMENTS), ShareStrip.items(ShareStrip.Options(scripts = true, attachments = true)))
    }
}
