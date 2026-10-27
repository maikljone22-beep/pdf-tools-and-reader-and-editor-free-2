package com.smartdocs.editor.core.annot

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeSnapTest {
    @Test fun line() {
        val pts = (0..20).map { i -> i * 10f to 100f + (if (i % 2 == 0) 1f else -1f) }
        assertEquals(ShapeSnap.Kind.LINE, ShapeSnap.classify(pts).kind)
    }

    @Test fun ellipse() {
        val pts = (0..40).map { i -> val a = i / 40.0 * 2 * Math.PI; (100 + 60 * cos(a)).toFloat() to (100 + 30 * sin(a)).toFloat() }
        val r = ShapeSnap.classify(pts)
        assertEquals(ShapeSnap.Kind.OVAL, r.kind)
        assertEquals(40f, r.box[0], 1f)
    }

    @Test fun rectangle() {
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0..10) pts += (i * 10f) to 0f
        for (i in 0..5) pts += 100f to (i * 10f)
        for (i in 10 downTo 0) pts += (i * 10f) to 50f
        for (i in 5 downTo 1) pts += 0f to (i * 10f)
        assertEquals(ShapeSnap.Kind.RECT, ShapeSnap.classify(pts).kind)
    }

    @Test fun scribble() {
        val pts = listOf(0f to 0f, 30f to 40f, 5f to 60f, 50f to 10f, 70f to 70f, 20f to 90f, 90f to 0f)
        assertEquals(ShapeSnap.Kind.NONE, ShapeSnap.classify(pts).kind)
    }
}
