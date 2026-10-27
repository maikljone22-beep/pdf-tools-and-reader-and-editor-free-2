package com.smartdocs.editor.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** nitro-fillsign2: name, palette order, default width and the temporary flag in the signature sidecar. */
class SignatureMetaFillSignTest {
    @Test fun newFieldsRoundTrip() {
        val map = mapOf(
            "/s/a.png" to SignatureMeta(name = "Work", order = 2, widthPt = 120.5f, rev = 3),
            "/s/b.png" to SignatureMeta(temporary = true, order = 0),
        )
        assertEquals(map, SignatureMetaStore.decode(SignatureMetaStore.encode(map)))
        // old files (no new keys) still decode with the defaults
        assertEquals(SignatureMeta(lastUsed = 5), SignatureMetaStore.decode("""{"/x.png":{"used":5}}""").getValue("/x.png"))
    }

    @Test fun orderedPutsOrderedFirst() {
        val map = mapOf("c" to SignatureMeta(order = 1), "a" to SignatureMeta(order = 0))
        assertEquals(listOf("a", "c", "new", "b"), SignatureMetaStore.ordered(map, listOf("new", "a", "b", "c")))
        assertEquals(listOf("x", "y"), SignatureMetaStore.ordered(emptyMap(), listOf("x", "y")))
    }

    @Test fun moveUpAndDown() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), SignatureMetaStore.move(l, 1, -1))
        assertEquals(listOf("a", "c", "b"), SignatureMetaStore.move(l, 1, 1))
        assertEquals(l, SignatureMetaStore.move(l, 0, -1))
        assertEquals(l, SignatureMetaStore.move(l, 2, 1))
        assertEquals(l, SignatureMetaStore.move(l, 5, 1))
    }
}
