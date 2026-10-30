package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL_CHECK_2 Low (Find Element): filters of the Content panel. */
class AccessFindFilterTest {
    private fun item(text: String, tag: String = "P", path: List<Int>? = null) = AccessContent.Item(0, 0, tag, "text", text, null, path)

    @Test fun suspectText() {
        assertTrue(AccessContent.suspectText("ab�cd"))
        assertTrue(AccessContent.suspectText("#$%&*@^~"))
        assertFalse(AccessContent.suspectText("نص عربي سليم، تماماً."))
        assertFalse(AccessContent.suspectText("Hello, world!"))
        assertFalse(AccessContent.suspectText("@#"))
    }

    @Test fun filters() {
        val untagged = item("x"); val tagged = item("x", path = listOf(0)); val art = item("x", tag = "Artifact")
        assertTrue(AccessContent.matches(untagged, AccessContent.Filter.UNTAGGED))
        assertFalse(AccessContent.matches(tagged, AccessContent.Filter.UNTAGGED))
        assertFalse(AccessContent.matches(art, AccessContent.Filter.UNTAGGED))
        assertTrue(AccessContent.matches(art, AccessContent.Filter.ARTIFACTS))
        assertTrue(AccessContent.matches(item("��"), AccessContent.Filter.SUSPECT))
    }
}
