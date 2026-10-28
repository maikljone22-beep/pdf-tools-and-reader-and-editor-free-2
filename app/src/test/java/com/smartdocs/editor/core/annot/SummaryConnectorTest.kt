package com.smartdocs.editor.core.annot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** fix/pdf39-comments-forms: comment summary connector lines (Foxit 24.87). */
class SummaryConnectorTest {
    @Test fun connectorRunsFromEntryThroughGutterToMarker() {
        val p = CommentSummary.connector(200f, 700f, 320f, 400f, 650f)
        assertEquals(200f to 700f, p.first())
        assertEquals(320f to 700f, p[1])          // horizontal to the gutter
        assertEquals(400f to 650f, p.last())       // then to the marker
        assertFalse(SummaryOptions().connectors)   // off unless chosen
    }
}
