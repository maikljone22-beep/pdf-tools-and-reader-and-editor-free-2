package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile review of the Office editors: formatting and formulas survive edits. */
class OfficeHostileTest {
    @Test fun arabicFormulaInput() {
        assertEquals("A1*2", SheetBook().arabicFormula("A1*٢"))
        assertEquals("SUM(A1,A2)", SheetBook().arabicFormula("SUM(A1،A2)"))
        assertEquals("IF(A1>1;\"نعم،٢\";0.5)", SheetBook().arabicFormula("IF(A1>1؛\"نعم،٢\"؛0٫5)"))
    }
}
