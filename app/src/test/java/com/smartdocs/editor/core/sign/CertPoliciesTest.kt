package com.smartdocs.editor.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foxit §28: certificate-protection policies keep the recipients' certificates, permissions and cipher. */
class CertPoliciesTest {
    private fun cert(n: String) = DigitalIds.selfSigned(DigitalIds.Spec(n)).second

    @Test fun roundTrip() {
        val a = cert("Alice"); val b = cert("Bob")
        val p = CertPolicies.Policy("Legal", listOf(
            CertPolicies.Member(a, CertEncryption.Perms()),
            CertPolicies.Member(b, CertEncryption.Perms(print = false, extract = false)),
        ), aes256 = false, encryptMetadata = false)
        val back = CertPolicies.fromJson(CertPolicies.toJson(listOf(p))).single()
        assertEquals("Legal", back.name); assertFalse(back.aes256); assertFalse(back.encryptMetadata)
        assertEquals(listOf(a, b), back.members.map { it.certificate })
        assertFalse(back.members[1].perms.print); assertFalse(back.members[1].perms.extract); assertTrue(back.members[0].perms.print)
    }

    @Test fun upsertDeleteAndBadInput() {
        var l = CertPolicies.upsert(emptyList(), CertPolicies.Policy("b", emptyList()))
        l = CertPolicies.upsert(l, CertPolicies.Policy("A", emptyList()))
        l = CertPolicies.upsert(l, CertPolicies.Policy("B", emptyList(), aes256 = false))
        assertEquals(listOf("A", "B"), l.map { it.name }); assertFalse(l[1].aes256)
        assertEquals(listOf("B"), CertPolicies.delete(l, "a").map { it.name })
        assertTrue(CertPolicies.fromJson("nope").isEmpty())
        assertTrue(CertPolicies.fromJson("""[{"name":"x","members":[{"cert":"!!"}]}]""").single().members.isEmpty())
    }
}
