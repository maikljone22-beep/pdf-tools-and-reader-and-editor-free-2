package com.smartdocs.editor.core.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectAdvancedTest {
    @Test fun printLevels() {
        val p = Permissions()
        assertEquals(PrintLevel.HIGH, p.printLevel)
        assertEquals(PrintLevel.LOW, p.withPrint(PrintLevel.LOW).printLevel)
        assertEquals(PrintLevel.NONE, p.withPrint(PrintLevel.NONE).printLevel)
        val low = ProtectAdvanced.accessPermission(p.withPrint(PrintLevel.LOW))
        assertTrue(low.canPrint()); assertFalse(low.canPrintFaithful())
    }

    @Test fun changePresetsRoundTrip() {
        for (l in ChangeLevel.values()) assertEquals(l, Permissions().withChanges(l).changeLevel)
        assertNull(Permissions().withChanges(ChangeLevel.NONE).copy(modify = true).changeLevel)
    }

    @Test fun bitsMapToAccessPermission() {
        val ap = ProtectAdvanced.accessPermission(Permissions(copy = false, accessibility = true).withChanges(ChangeLevel.FORMS))
        assertFalse(ap.canExtractContent()); assertTrue(ap.canExtractForAccessibility())
        assertTrue(ap.canFillInForm()); assertFalse(ap.canModify()); assertFalse(ap.canModifyAnnotations()); assertFalse(ap.canAssembleDocument())
    }

    @Test fun randomOwnerPassword() {
        val a = ProtectAdvanced.randomPassword(); val b = ProtectAdvanced.randomPassword()
        assertEquals(24, a.length); assertTrue(a != b)
    }
}
