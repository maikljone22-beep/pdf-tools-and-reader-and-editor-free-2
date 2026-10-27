package com.smartdocs.editor.core.sign

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/** nitro-quicksign: "require trusted TSA certificate" chain check with a generated test CA. */
class TsaTrustTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun cert(subject: String, issuer: String, subjectKeys: KeyPair, issuerKeys: KeyPair, ca: Boolean): X509Certificate {
        val now = System.currentTimeMillis()
        val b = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(now + subject.hashCode()), Date(now - 60_000), Date(now + 86_400_000L),
            X500Name(subject), subjectKeys.public)
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)))
    }

    private val rootKeys = keys()
    private val interKeys = keys()
    private val tsaKeys = keys()
    private val otherKeys = keys()
    private val root = cert("CN=Test Root CA", "CN=Test Root CA", rootKeys, rootKeys, true)
    private val inter = cert("CN=Test TSA CA", "CN=Test Root CA", interKeys, rootKeys, true)
    private val tsa = cert("CN=Test TSA", "CN=Test TSA CA", tsaKeys, interKeys, false)
    private val other = cert("CN=Other Root", "CN=Other Root", otherKeys, otherKeys, true)
    // same name as the real root, different key: must not be accepted
    private val fakeRoot = cert("CN=Test Root CA", "CN=Test Root CA", otherKeys, otherKeys, true)

    @Test fun chainsToAnchorThroughEmbeddedIntermediate() {
        assertTrue(TsaTrust.isTrusted(tsa, listOf(tsa, inter), listOf(other, root)))
    }

    @Test fun directlyTrustedSigner() {
        assertTrue(TsaTrust.isTrusted(tsa, listOf(tsa), listOf(tsa)))
    }

    @Test fun missingIntermediateIsUntrusted() {
        assertFalse(TsaTrust.isTrusted(tsa, listOf(tsa), listOf(root)))
    }

    @Test fun embeddedRootIsNotAnAnchor() {
        assertFalse(TsaTrust.isTrusted(tsa, listOf(tsa, inter, root), listOf(other)))
        assertFalse(TsaTrust.isTrusted(tsa, listOf(tsa, inter, root), emptyList()))
    }

    @Test fun sameNameDifferentKeyRejected() {
        assertFalse(TsaTrust.isTrusted(tsa, listOf(tsa, inter), listOf(fakeRoot)))
    }

    @Test fun basicAuthHeader() {
        // RFC 7617 example
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", TsaClient.basicAuth("Aladdin", "open sesame"))
    }

    @Test fun policyToStringHidesPassword() {
        val p = TsaClient.Policy("user", "s3cret-pw", true, { emptyList() }, "x")
        assertFalse(p.toString().contains("s3cret-pw"))
    }
}
