package com.smartdocs.editor.core.sign

import android.content.Context
import com.smartdocs.editor.core.pdf.DigitalSignature
import java.io.File
import java.security.cert.X509Certificate

/**
 * Public certificate of the user's own self-signed ID, kept next to the password-protected .p12 once it was read
 * with the password (e.g. when adding yourself as a recipient). It lets "request a certificate by e-mail" attach
 * the user's certificate without asking for the ID password. The copy counts only while it is newer than the
 * .p12: creating a new ID overwrites the keystore and so invalidates it.
 */
object OwnPublicCert {
    private fun file(c: Context) = File(c.filesDir, "own_certificate_public.cer")

    fun remember(context: Context, cert: X509Certificate) {
        val ks = DigitalSignature.ownKeystoreFile(context)
        if (!ks.exists()) return
        // only the certificate that belongs to the current own ID
        file(context).writeBytes(cert.encoded)
    }

    /** The cached certificate, or null when there is none or the own ID changed since. */
    fun get(context: Context): X509Certificate? {
        val ks = DigitalSignature.ownKeystoreFile(context)
        val f = file(context)
        if (!ks.exists() || !f.exists() || !fresh(f.lastModified(), ks.lastModified())) return null
        return runCatching { TrustedCerts.parse(f.readBytes()).firstOrNull() }.getOrNull()
    }

    internal fun fresh(cacheModified: Long, keystoreModified: Long) = cacheModified >= keystoreModified
}
