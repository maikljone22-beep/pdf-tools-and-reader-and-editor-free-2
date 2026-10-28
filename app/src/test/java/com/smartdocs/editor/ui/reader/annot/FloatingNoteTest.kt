package com.smartdocs.editor.ui.reader.annot

import com.smartdocs.editor.ui.reader.AnnotTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** fix/pdf39-forms-2: floating pop-up note window (7.10). */
class FloatingNoteTest {
    @Test fun windowStaysInsideTheViewport() {
        assertEquals(10f to 20f, FloatingNoteGeom.clamp(10f, 20f, 100f, 50f, 400f, 300f))
        assertEquals(300f to 250f, FloatingNoteGeom.clamp(390f, 290f, 100f, 50f, 400f, 300f))
        assertEquals(0f to 0f, FloatingNoteGeom.clamp(-5f, -9f, 100f, 50f, 400f, 300f))
        assertEquals(0f to 0f, FloatingNoteGeom.clamp(50f, 50f, 500f, 400f, 400f, 300f))   // larger than the viewport
    }

    @Test fun noteKeepsItsTarget() {
        val n = FloatingNote(AnnotTarget.Pending(7, 2), "Sara", "hello")
        assertEquals(n, n.copy())
        assertNotEquals(n, n.copy(target = AnnotTarget.Pending(8, 2)))
    }
}
