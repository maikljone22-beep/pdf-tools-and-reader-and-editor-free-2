package com.smartdocs.editor.core.sign

import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Root certificates of the Android system CA store ("AndroidCAStore": system roots plus CAs the user installed
 * in Android settings), used as signature trust anchors like Nitro/Acrobat use the Windows certificate store.
 * The Adobe AATL / EU trusted lists are not bundled (licensing); a root from those lists can be imported into
 * the app's trusted certificates or into Android settings.
 */
object SystemTrust {
    @Volatile private var cache: List<X509Certificate>? = null

    fun anchors(): List<X509Certificate> = cache ?: runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null, null)
        ks.aliases().toList().mapNotNull { ks.getCertificate(it) as? X509Certificate }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache = it }
}
