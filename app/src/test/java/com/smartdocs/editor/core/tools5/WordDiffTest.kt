package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {

    private fun ops(a: String, b: String) = WordDiff.diffText(a, b).map { it.type }

    @Test fun identical() {
        val c = WordDiff.diffText("one two three", "one two three")
        assertEquals(listOf(ChangeType.EQUAL), c.map { it.type })
        assertEquals(3, WordDiff.stats(c).equal)
    }

    @Test fun insertDeleteReplace() {
        assertEquals(listOf(ChangeType.EQUAL, ChangeType.INSERT, ChangeType.EQUAL), ops("a b d", "a b c d"))
        assertEquals(listOf(ChangeType.EQUAL, ChangeType.DELETE, ChangeType.EQUAL), ops("a b c d", "a b d"))
        val c = WordDiff.diffText("the quick brown fox", "the slow brown fox")
        assertEquals(listOf(ChangeType.EQUAL, ChangeType.REPLACE, ChangeType.EQUAL), c.map { it.type })
        assertEquals(1..1, c[1].a); assertEquals(1..1, c[1].b)
        val s = WordDiff.stats(c)
        assertEquals(1, s.replaced); assertEquals(3, s.equal)
    }

    @Test fun emptySides() {
        assertEquals(listOf(ChangeType.INSERT), ops("", "x y"))
        assertEquals(listOf(ChangeType.DELETE), ops("x y", ""))
        assertTrue(WordDiff.diffText("", "").isEmpty())
    }

    @Test fun arabicNormalisationIgnoresHarakatAlefAndDigits() {
        // "أحمد" vs "احمد", harakat, taa marbuta / haa, Arabic-Indic vs ASCII digits, trailing punctuation
        val old = "قال أحمد م\u064Fح\u064Eم\u0651\u064Eد مدرسة ١٢٣."
        val new = "قال احمد محمد مدرسه 123"
        assertEquals(listOf(ChangeType.EQUAL), WordDiff.diffText(old, new).map { it.type })
    }

    @Test fun arabicRealChange() {
        val c = WordDiff.diffText("ذهب الولد إلى المدرسة صباحا", "ذهب الولد إلى السوق صباحا")
        assertEquals(listOf(ChangeType.EQUAL, ChangeType.REPLACE, ChangeType.EQUAL), c.map { it.type })
        val d = WordDiff.describe(c, WordDiff.tokenize("ذهب الولد إلى المدرسة صباحا"), WordDiff.tokenize("ذهب الولد إلى السوق صباحا"))
        assertTrue(d.startsWith("~ المدرسة"))
    }

    @Test fun rangesCoverBothSides() {
        val a = WordDiff.tokenize("a b c d e f g h")
        val b = WordDiff.tokenize("a x c d y f h z")
        val c = WordDiff.diff(a, b)
        assertEquals(a.size, c.sumOf { it.a.count() })
        assertEquals(b.size, c.sumOf { it.b.count() })
        // equal runs really are equal
        c.filter { it.type == ChangeType.EQUAL }.forEach { e -> e.a.zip(e.b).forEach { (i, j) -> assertEquals(a[i], b[j]) } }
    }

    @Test fun largeInputStaysFast() {
        val a = (0 until 3000).map { "w${it % 97}_$it" }
        val b = a.toMutableList().apply { removeAt(100); add(2000, "new"); set(2500, "chg") }
        val c = WordDiff.diff(a, b)
        val s = WordDiff.stats(c)
        assertEquals(1, s.inserted); assertEquals(1, s.deleted); assertEquals(1, s.replaced)
    }
}
