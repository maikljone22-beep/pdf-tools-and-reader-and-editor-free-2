package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.FormatSpec
import com.smartdocs.editor.core.forms.KeystrokeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldValidationTest {

    @Test fun afRange() {
        val v = FieldValidation.parseValidate("AFRange_Validate(true, 0, true, 100);")!!
        assertEquals(0.0, v.min!!, 0.0); assertEquals(100.0, v.max!!, 0.0)
        val r = FieldRules(validate = v)
        assertNull(FieldValidation.check("50", r))
        assertEquals(Issue.OutOfRange(0.0, 100.0), FieldValidation.check("150", r))
        assertEquals(Issue.NotNumber, FieldValidation.check("abc", r))
        val onlyMax = FieldValidation.parseValidate("AFRange_Validate(false, 0, true, 10)")!!
        assertNull(onlyMax.min); assertEquals(10.0, onlyMax.max!!, 0.0)
    }

    @Test fun handWrittenComparison() {
        val js = "if (event.value < 18 || event.value > 99) { app.alert(\"Age 18-99\"); event.rc = false; }"
        val v = FieldValidation.parseValidate(js)!!
        assertEquals(18.0, v.min!!, 0.0); assertEquals(99.0, v.max!!, 0.0); assertEquals("Age 18-99", v.message)
        assertEquals(Issue.Script("Age 18-99"), FieldValidation.check("12", FieldRules(validate = v)))
        assertNull(FieldValidation.check("18", FieldRules(validate = v)))
        val flipped = FieldValidation.parseValidate("if (5 > event.value) event.rc = false;")!!
        assertEquals(5.0, flipped.min!!, 0.0)
    }

    @Test fun regexTest() {
        val v = FieldValidation.parseValidate("if (!/^\\d{5}$/.test(event.value)) { app.alert('Five digits'); event.rc = false; }")!!
        assertTrue(v.regex != null)
        assertNull(FieldValidation.check("12345", FieldRules(validate = v)))
        assertEquals(Issue.Script("Five digits"), FieldValidation.check("12a45", FieldRules(validate = v)))
        val rc = FieldValidation.parseValidate("event.rc = /^[A-Z]+$/i.test(event.value);")!!
        assertNull(FieldValidation.check("abc", FieldRules(validate = rc)))
    }

    @Test fun unsupportedScriptAccepts() {
        val v = FieldValidation.parseValidate("var x = util.scand('dd', event.value); if (!x) event.rc = false;")!!
        assertTrue(v.unsupported)
        assertNull(FieldValidation.check("anything", FieldRules(validate = v)))
    }

    @Test fun builtInChecks() {
        assertEquals(Issue.Required, FieldValidation.check("  ", FieldRules(required = true)))
        assertNull(FieldValidation.check("", FieldRules()))
        assertEquals(Issue.TooLong(3), FieldValidation.check("abcd", FieldRules(maxLen = 3)))
        assertEquals(Issue.NotNumber, FieldValidation.check("hello", FieldRules(keystroke = KeystrokeKind.NUMBER)))
        assertNull(FieldValidation.check("١٢٫٥", FieldRules(keystroke = KeystrokeKind.NUMBER)))   // 12.5 in Arabic-Indic digits
        assertEquals(Issue.NotDate("dd/mm/yyyy"), FieldValidation.check("2024/99/99", FieldRules(format = FormatSpec.DateTime("dd/mm/yyyy"))))
        assertNull(FieldValidation.check("05/03/2024", FieldRules(format = FormatSpec.DateTime("dd/mm/yyyy"))))
        assertEquals(Issue.BadFormat(0), FieldValidation.check("1234", FieldRules(format = FormatSpec.Special(0))))
        assertNull(FieldValidation.check("(555) 123-4567", FieldRules(format = FormatSpec.Special(2))))
    }
}
