package com.smartdocs.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LastScreenTest {
    private val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

    @Test fun routeIsRebuiltWithItsArguments() {
        assertEquals("tool/images_to_pdf?uri=", LastScreen.concrete("tool/{id}?uri={uri}", mapOf("id" to "images_to_pdf", "uri" to null), enc))
        assertEquals("office5/file%3A%2F%2F%2Fa%2Fb.docx", LastScreen.concrete("office5/{uri}", mapOf("uri" to "file:///a/b.docx"), enc))
    }
}
