package com.smartdocs.editor.core.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecurityProfilesTest {
    private val a = SecurityProfile("Office", EncAlgo.AES_128, EncScope.ALL_BUT_METADATA, restrict = true,
        permissions = Permissions().withPrint(PrintLevel.LOW).withChanges(ChangeLevel.FORMS).copy(copy = false))
    private val b = SecurityProfile("Legacy", EncAlgo.RC4_40)

    @Test fun roundTripKeepsEveryField() {
        val back = SecurityProfiles.fromJson(SecurityProfiles.toJson(listOf(a, b)))
        assertEquals(listOf(a, b), back)
    }

    @Test fun jsonHasNoPasswordField() {
        assertFalse(SecurityProfiles.toJson(listOf(a)).contains("password", ignoreCase = true))
    }

    @Test fun tolerantParse() {
        assertTrue(SecurityProfiles.fromJson("not json").isEmpty())
        val list = SecurityProfiles.fromJson("""[{"name":" X ","algo":"NOPE","scope":"ATTACHMENTS_ONLY"},{"algo":"AES_256"},{"name":"x"},
            {"name":"R","algo":"RC4_128","scope":"ATTACHMENTS_ONLY","restrict":true,"perms":{"print":false}}]""")
        assertEquals(listOf("X", "R"), list.map { it.name })                 // unnamed skipped, duplicate (case) dropped
        assertEquals(EncAlgo.AES_256, list[0].algo)                          // unknown algorithm -> default
        assertEquals(EncScope.ATTACHMENTS_ONLY, list[0].scope)
        assertEquals(EncScope.ALL, list[1].scope)                            // RC4 cannot use a scoped handler
        assertFalse(list[1].permissions.print); assertTrue(list[1].permissions.copy)
    }

    @Test fun upsertRenameDelete() {
        var l = SecurityProfiles.upsert(emptyList(), a)
        l = SecurityProfiles.upsert(l, b)
        l = SecurityProfiles.upsert(l, a.copy(name = "office", algo = EncAlgo.AES_256))   // same name ignoring case: replaced in place
        assertEquals(listOf("office", "Legacy"), l.map { it.name })
        assertEquals(EncAlgo.AES_256, l[0].algo)
        l = SecurityProfiles.rename(l, "LEGACY", "Old readers")
        assertEquals("Old readers", l[1].name)
        try { SecurityProfiles.rename(l, "Old readers", "OFFICE"); fail() } catch (e: IllegalArgumentException) {}
        try { SecurityProfiles.upsert(l, a.copy(name = "  ")); fail() } catch (e: IllegalArgumentException) {}
        l = SecurityProfiles.rename(l, "office", "Office")                   // a case change of itself is fine
        assertEquals("Office", l[0].name)
        assertEquals(listOf("Old readers"), SecurityProfiles.delete(l, "office").map { it.name })
    }
}
