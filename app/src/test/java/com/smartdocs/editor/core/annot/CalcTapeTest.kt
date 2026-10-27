package com.smartdocs.editor.core.annot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTapeTest {
    @Test fun runningTotalsEditDeleteUndo() {
        val t = CalcTape()
        t.add(CalcTape.Op.ADD, 100.0); t.add(CalcTape.Op.ADD, 50.0); t.add(CalcTape.Op.SUB, 10.0, percent = true)
        assertEquals(135.0, t.current(), 1e-9)                  // 150 - 10% of 150
        t.subtotal(); t.add(CalcTape.Op.MUL, 2.0); t.total()
        assertEquals(270.0, t.rows().last().result, 1e-9)
        assertEquals(0.0, t.current(), 1e-9)
        t.add(CalcTape.Op.ADD, 30.0); t.total()
        assertEquals(300.0, t.grandTotal(), 1e-9)
        t.edit(0, 200.0)
        assertEquals(450.0, t.rows()[5].result, 1e-9)
        t.delete(0)
        assertEquals(90.0, t.rows()[4].result, 1e-9)           // (50 - 5) * 2
        assertTrue(t.undo()); assertEquals(450.0, t.rows()[5].result, 1e-9)
        assertTrue(t.redo()); assertEquals(7, t.size)
        assertTrue(t.text(2, "Tape").startsWith("Tape\n"))
        assertTrue(t.text(2).contains("GT"))
        t.clear(); assertEquals(0, t.size); assertTrue(t.undo()); assertEquals(7, t.size)
    }

    @Test fun divisionByZeroAndFormat() {
        val t = CalcTape(); t.add(CalcTape.Op.ADD, 5.0); t.add(CalcTape.Op.DIV, 0.0)
        assertEquals("E", CalcTape.fmt(t.current(), 2))
        assertEquals("1,234.50", CalcTape.fmt(1234.5, 2))
        t.memoryAdd(3.0); t.memorySub(1.0); assertEquals(2.0, t.memory, 1e-9)
    }
}
