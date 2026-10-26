package com.smartdocs.editor.core.sign

import java.math.BigInteger
import java.security.MessageDigest

/** Pure formatting helpers for certificate details (serial, fingerprints, key usage, DN parts). */
object CertFormat {

    /** Key usage bits in X.509 order (RFC 5280 §4.2.1.3). */
    enum class KeyUsageBit { DIGITAL_SIGNATURE, NON_REPUDIATION, KEY_ENCIPHERMENT, DATA_ENCIPHERMENT, KEY_AGREEMENT, KEY_CERT_SIGN, CRL_SIGN, ENCIPHER_ONLY, DECIPHER_ONLY }

    fun hex(bytes: ByteArray, sep: String = ":"): String = bytes.joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

    fun sha256(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(der))
    fun sha1(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(der))

    /** Serial number as colon-separated hex bytes, without the sign byte BigInteger adds. */
    fun serial(n: BigInteger): String {
        var b = n.toByteArray()
        if (b.size > 1 && b[0].toInt() == 0) b = b.copyOfRange(1, b.size)
        return hex(b)
    }

    /** X509Certificate.getKeyUsage() → set bits; null (no extension) → empty. */
    fun keyUsage(bits: BooleanArray?): List<KeyUsageBit> {
        if (bits == null) return emptyList()
        return KeyUsageBit.entries.filter { it.ordinal < bits.size && bits[it.ordinal] }
    }

    /** Well-known extended key usage OIDs → short English names (UI shows them as-is or maps them). */
    val EKU_NAMES = mapOf(
        "1.3.6.1.5.5.7.3.1" to "serverAuth",
        "1.3.6.1.5.5.7.3.2" to "clientAuth",
        "1.3.6.1.5.5.7.3.3" to "codeSigning",
        "1.3.6.1.5.5.7.3.4" to "emailProtection",
        "1.3.6.1.5.5.7.3.8" to "timeStamping",
        "1.3.6.1.5.5.7.3.9" to "OCSPSigning",
        "1.3.6.1.4.1.311.10.3.12" to "documentSigning (Microsoft)",
        "1.2.840.113583.1.1.5" to "Adobe Authentic Documents",
        "2.5.29.37.0" to "anyExtendedKeyUsage",
    )

    fun eku(oids: List<String>?): List<String> = oids.orEmpty().map { EKU_NAMES[it] ?: it }

    /**
     * RFC 2253 distinguished name → ordered (attribute, value) pairs; honours backslash escapes and quotes.
     * "CN=Ali\, Co,O=X" → [(CN, "Ali, Co"), (O, "X")].
     */
    fun dnParts(dn: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val sb = StringBuilder()
        var esc = false; var quoted = false
        fun flush() {
            val s = sb.toString().trim(); sb.setLength(0)
            val eq = s.indexOf('=')
            if (eq > 0) out += s.substring(0, eq).trim().uppercase() to s.substring(eq + 1).trim().removeSurrounding("\"")
        }
        for (c in dn) {
            when {
                esc -> { sb.append(c); esc = false }
                c == '\\' -> esc = true
                c == '"' -> { quoted = !quoted; sb.append(c) }
                (c == ',' || c == ';' || c == '+') && !quoted -> flush()
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }

    /** First value of attribute [attr] (e.g. "CN", "O") in [dn]. */
    fun dnValue(dn: String, attr: String): String? = dnParts(dn).firstOrNull { it.first.equals(attr, true) }?.second
}
