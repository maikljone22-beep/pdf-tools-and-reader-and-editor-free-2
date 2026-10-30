package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberSeparatorsTest {
    @Test fun pointConvention() {
        assertEquals(1234.56, BidiText.parseNumber("1,234.56")!!.value, 1e-9)
        assertEquals(2, BidiText.parseNumber("1,234.56")!!.format)
    }
    @Test fun commaConvention() {
        assertEquals(1234.56, BidiText.parseNumber("1.234,56", BidiText.SEP_COMMA)!!.value, 1e-9)
        assertEquals(0.5, BidiText.parseNumber("0,5", BidiText.SEP_COMMA)!!.value, 1e-9)
        assertEquals(1234567.0, BidiText.parseNumber("1.234.567", BidiText.SEP_COMMA)!!.value, 1e-9)
    }
    @Test fun spaceConvention() {
        assertEquals(1234.56, BidiText.parseNumber("1 234,56", BidiText.SEP_SPACE)!!.value, 1e-9)
        assertEquals(1234.56, BidiText.parseNumber("1 234.56", BidiText.SEP_SPACE)!!.value, 1e-9)
        assertEquals(1234.5, BidiText.parseNumber("١٢٣٤٫٥")!!.value, 1e-9)
    }
    @Test fun codesStayText() { assertNull(BidiText.parseNumber("007")); assertNull(BidiText.parseNumber("1.2.3", BidiText.SEP_COMMA)) }
}
