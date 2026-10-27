package com.smartdocs.editor.core.sign

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * "Acquire timestamp from attestable server": a time-stamp is accepted only when the TSA signer certificate chains
 * (through the certificates embedded in the token) to an anchor: the user's trusted certificates or the Android
 * system CA store. Pure: anchors are passed in, so the check is unit-tested with a generated CA.
 */
object TsaTrust {
    val ops = TrustPath.Ops<X509Certificate>(
        subject = { it.subjectX500Principal.name },
        issuer = { it.issuerX500Principal.name },
        fingerprint = { MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString("") { b -> "%02x".format(b) } },
        signedBy = { child, parent -> runCatching { child.verify(parent.publicKey) }.isSuccess },
    )

    fun isTrusted(signer: X509Certificate, embedded: List<X509Certificate>, anchors: List<X509Certificate>): Boolean =
        anchors.isNotEmpty() && TrustPath.evaluate(signer, embedded.filter { it != signer }, anchors, ops).status == TrustPath.Status.TRUSTED
}
