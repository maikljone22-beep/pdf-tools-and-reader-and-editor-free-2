package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** fix/pdf39-forms-2: text field overflow indicator box (§37). */
class OverflowIndicatorTest {
    @Test fun boxSitsInTheBottomRightCornerAndFitsTheField() {
        assertArrayEquals(floatArrayOf(90f, 30f, 100f, 40f), FormPrefs.overflowBox(0f, 20f, 100f, 40f, 10f), 0.001f)
        // a field lower than the box: the box shrinks to the field height
        assertArrayEquals(floatArrayOf(94f, 20f, 100f, 26f), FormPrefs.overflowBox(0f, 20f, 100f, 26f, 10f), 0.001f)
    }
}
