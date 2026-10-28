package com.smartdocs.editor.ui.nve

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-settings (§39): the default chords never collide (with the Foxit layout on, every default is active). */
class NveKeyDefaultsTest {
    @Test fun defaultsAreUnique() {
        val clashes = NveKey.entries.filter { it.def != null }.groupBy { it.def }.filterValues { it.size > 1 }
        assertTrue("clashing defaults: $clashes", clashes.isEmpty())
    }

    @Test fun addedShortcuts() {
        assertEquals(Chord(KeyEvent.KEYCODE_8, ctrl = true), NveKey.HIDE_COMMENTS.def)
        assertEquals(Chord(KeyEvent.KEYCODE_D, ctrl = true, alt = true), NveKey.DUPLICATE_PAGE.def)
        assertEquals(Chord(KeyEvent.KEYCODE_K, ctrl = true, shift = true), NveKey.EMAIL_TABS.def)
        assertTrue(NveKey.CROSS_REF.foxitOnly)
    }

    @Test fun chordRoundTrip() {
        val c = Chord(KeyEvent.KEYCODE_ENTER, ctrl = true)
        assertEquals(c, Chord.decode(c.encode()))
    }
}
