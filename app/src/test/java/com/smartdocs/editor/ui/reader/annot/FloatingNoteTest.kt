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

    @Test fun resizeStaysBetweenMinimumAndViewport() {
        assertEquals(300f to 150f, FloatingNoteGeom.resize(260f, 100f, 40f, 50f, 180f, 48f, 400f, 300f))
        assertEquals(180f to 48f, FloatingNoteGeom.resize(260f, 100f, -500f, -500f, 180f, 48f, 400f, 300f))
        assertEquals(400f to 300f, FloatingNoteGeom.resize(260f, 100f, 900f, 900f, 180f, 48f, 400f, 300f))
        assertEquals(180f to 48f, FloatingNoteGeom.resize(260f, 100f, 0f, 0f, 180f, 48f, 100f, 20f))   // viewport smaller than the minimum
    }

    @Test fun noteKeepsItsTarget() {
        val n = FloatingNote(AnnotTarget.Pending(7, 2), "Sara", "hello")
        assertEquals(n, n.copy())
        assertNotEquals(n, n.copy(target = AnnotTarget.Pending(8, 2)))
    }
}
