package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.Date

/** Nitro §9 "My Digital IDs": creation options (OU, country, usage, key type/size, validity). */
class DigitalIdsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createsTheRequestedSubjectUsageKeyAndValidity() {
        val now = Date()
        val (key, cert) = DigitalIds.selfSigned(DigitalIds.Spec("Ali", "Smart Docs", "Legal", "ali@example.com", "ye", DigitalIds.Usage.SIGNING, DigitalIds.KeyType.RSA_3072, 3), now)
        val dn = cert.subjectX500Principal.name
        assertEquals("Ali", CertFormat.dnValue(dn, "CN")); assertEquals("Legal", CertFormat.dnValue(dn, "OU"))
        assertEquals("Smart Docs", CertFormat.dnValue(dn, "O")); assertEquals("YE", CertFormat.dnValue(dn, "C"))
        assertEquals(3072, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        val ku = CertFormat.keyUsage(cert.keyUsage)
        assertTrue(CertFormat.KeyUsageBit.DIGITAL_SIGNATURE in ku); assertFalse(CertFormat.KeyUsageBit.KEY_ENCIPHERMENT in ku)
        val years = Calendar.getInstance().apply { time = cert.notAfter }.get(Calendar.YEAR) - Calendar.getInstance().apply { time = now }.get(Calendar.YEAR)
        assertEquals(3, years)
        assertEquals("RSA", key.algorithm)
        cert.verify(cert.publicKey)
    }

    @Test fun ecIdsSignAndUseKeyAgreementForEncryption() {
        val (key, cert) = DigitalIds.selfSigned(DigitalIds.Spec("Ec User", key = DigitalIds.KeyType.EC_P256, usage = DigitalIds.Usage.BOTH))
        assertTrue(cert.publicKey is ECPublicKey)
        val ku = CertFormat.keyUsage(cert.keyUsage)
        assertTrue(CertFormat.KeyUsageBit.KEY_AGREEMENT in ku); assertTrue(CertFormat.KeyUsageBit.NON_REPUDIATION in ku)
        val s = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(byteArrayOf(1, 2, 3)) }.sign()
        assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(cert.publicKey); update(byteArrayOf(1, 2, 3)) }.verify(s))
    }

    @Test fun rejectsABadCountryCode() {
        try { DigitalIds.selfSigned(DigitalIds.Spec("X", country = "Yemen")); fail("two letters only") } catch (_: IllegalArgumentException) {}
    }

    @Test fun storedIdOpensWithItsPassword() {
        val (key, cert) = DigitalIds.selfSigned(DigitalIds.Spec("Stored", usage = DigitalIds.Usage.ENCRYPTION))
        val f = File(tmp.root, "id.p12")
        DigitalIds.store(f, key, listOf(cert), "secret".toCharArray())
        val id = runBlocking { DigitalSignature.loadPkcs12(f, "secret".toCharArray()) }
        assertEquals(cert, id.certificate)
        try { runBlocking { DigitalSignature.loadPkcs12(f, "wrong".toCharArray()) }; fail("wrong password") } catch (_: Exception) {}
    }
}
