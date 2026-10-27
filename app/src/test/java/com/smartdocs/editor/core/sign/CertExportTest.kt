package com.smartdocs.editor.core.sign

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

class CertExportTest {
    private fun cert(cn: String, serial: Long): X509Certificate {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val name = X500Name("CN=$cn, O=Test")
        val now = Date()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(serial), now, Date(now.time + 86_400_000L), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private val one = cert("Alice Example", 1)
    private val two = cert("Bob", 2)
    private val cf = CertificateFactory.getInstance("X.509")

    @Test fun cerIsDerOfOneCertificate() {
        val bytes = CertExport.encode(listOf(one), CertExport.Kind.CER)
        assertArrayEquals(one.encoded, bytes)
        assertEquals(one, cf.generateCertificate(ByteArrayInputStream(bytes)))
        try { CertExport.encode(listOf(one, two), CertExport.Kind.CER); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun pemParsesBack() {
        val bytes = CertExport.encode(listOf(one, two), CertExport.Kind.PEM)
        val text = String(bytes, Charsets.US_ASCII)
        assertEquals(2, Regex("-----BEGIN CERTIFICATE-----").findAll(text).count())
        assertEquals(listOf(one, two), TrustedCerts.parse(bytes))
    }

    @Test fun p7bAndP7cAreCertsOnlySignedData() {
        for (k in listOf(CertExport.Kind.P7B, CertExport.Kind.P7C)) {
            val bytes = CertExport.encode(listOf(one, two), k)
            val cms = CMSSignedData(bytes)
            assertEquals(0, cms.signerInfos.size())
            assertEquals(2, cms.certificates.getMatches(null).size)
            // the JDK's PKCS#7 reader (what TrustedCerts.import uses for .p7b / .p7c) reads it back
            val back = cf.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>().toSet()
            assertEquals(setOf(one, two), back)
            assertEquals(setOf(one, two), TrustedCerts.parse(bytes).toSet())
        }
        assertArrayEquals(CertExport.encode(listOf(one), CertExport.Kind.P7B), CertExport.encode(listOf(one), CertExport.Kind.P7C))
    }

    @Test fun fileNames() {
        assertEquals("Alice_Example.cer", CertExport.fileName(listOf(one), CertExport.Kind.CER))
        assertEquals("certificates.p7b", CertExport.fileName(listOf(one, two), CertExport.Kind.P7B))
        assertEquals("a, b", CertExport.cn("CN=a\\, b,O=x"))
        assertEquals("", CertExport.cn("O=x"))
    }

    @Test fun ownCertificateCacheFreshness() {
        assertTrue(OwnPublicCert.fresh(2000, 1000))
        assertTrue(OwnPublicCert.fresh(1000, 1000))
        assertFalse(OwnPublicCert.fresh(999, 1000))
    }
}
