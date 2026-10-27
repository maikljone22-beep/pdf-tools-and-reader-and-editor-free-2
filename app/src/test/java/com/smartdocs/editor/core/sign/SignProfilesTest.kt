package com.smartdocs.editor.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Nitro §11 named signature profiles. */
class SignProfilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripsEveryField() {
        val p = SignProfiles.Profile("Approval", "Ali", "Sanaa", "I am approving this document", "ali@x.y", true, 0, false, true, true,
            SigFields.LockAction.INCLUDE, listOf("amount", "date"), true)
        val back = SignProfiles.fromJson(SignProfiles.toJson(listOf(p, SignProfiles.Profile("Certify", certify = 2))))
        assertEquals(listOf(p, SignProfiles.Profile("Certify", certify = 2)), back)
    }

    @Test fun upsertCopyAndBadInput() {
        var list = SignProfiles.upsert(emptyList(), SignProfiles.Profile("b"))
        list = SignProfiles.upsert(list, SignProfiles.Profile("A"))
        list = SignProfiles.upsert(list, SignProfiles.Profile("B", reason = "new"))
        assertEquals(listOf("A", "B"), list.map { it.name }); assertEquals("new", list[1].reason)
        assertEquals("A (2)", SignProfiles.copyName(list, "A"))
        assertEquals("A (3)", SignProfiles.copyName(list + SignProfiles.Profile("A (2)"), "A"))
        assertTrue(SignProfiles.fromJson("not json").isEmpty())
        assertTrue(SignProfiles.fromJson("""[{"name":""},{"x":1}]""").isEmpty())
    }

    @Test fun savesAndLoads() {
        val f = File(tmp.root, "p.json")
        assertTrue(SignProfiles.load(f).isEmpty())
        SignProfiles.save(f, listOf(SignProfiles.Profile("One")))
        SignProfiles.save(f, listOf(SignProfiles.Profile("One"), SignProfiles.Profile("Two")))
        assertEquals(listOf("One", "Two"), SignProfiles.load(f).map { it.name })
    }
}
