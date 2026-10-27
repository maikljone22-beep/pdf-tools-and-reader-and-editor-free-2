package com.smartdocs.editor.core.sign

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import com.smartdocs.editor.core.pdf.DigitalSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date

/**
 * "My Digital IDs" (Nitro §9): several signing identities instead of the single own-certificate slot.
 *  - file IDs: PKCS#12 files in `filesDir/digital_ids/<id>.p12`, each with its public certificate beside it
 *    (`<id>.cer`) so it can be listed, shown and shared without the password; the legacy
 *    `own_certificate.p12` is listed too;
 *  - Android KeyChain IDs (the Android counterpart of the Windows certificate store): the alias the user picked
 *    in the system chooser; the private key never leaves the system key store.
 */
object DigitalIds {

    enum class Usage { SIGNING, ENCRYPTION, BOTH }

    enum class KeyType(val algorithm: String, val size: Int, val curve: String? = null) {
        RSA_2048("RSA", 2048), RSA_3072("RSA", 3072), RSA_4096("RSA", 4096),
        EC_P256("EC", 256, "secp256r1"), EC_P384("EC", 384, "secp384r1"),
    }

    /** Self-signed ID creation options (Nitro "Create a self-signed digital ID"). */
    data class Spec(
        val commonName: String,
        val organization: String = "",
        val unit: String = "",
        val email: String = "",
        /** ISO 3166 two-letter code, or blank. */
        val country: String = "",
        val usage: Usage = Usage.BOTH,
        val key: KeyType = KeyType.RSA_2048,
        val validYears: Int = 5,
    )

    enum class Kind { FILE, KEYCHAIN }

    data class Entry(val id: String, val kind: Kind, val label: String, val file: File?, val cert: X509Certificate?, val alias: String? = null)

    private const val LEGACY_ID = "own"
    private const val PREFS = "digital_ids"
    private const val KEY_ALIASES = "keychain_aliases"

    private fun dir(c: Context) = File(c.filesDir, "digital_ids").apply { mkdirs() }

    fun list(context: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val legacy = DigitalSignature.ownKeystoreFile(context)
        if (legacy.exists()) out += Entry(LEGACY_ID, Kind.FILE, "", legacy, certBeside(legacy))
        (dir(context).listFiles { f -> f.extension == "p12" } ?: emptyArray()).sortedBy { it.lastModified() }.forEach { f ->
            val c = certBeside(f)
            out += Entry(f.nameWithoutExtension, Kind.FILE, c?.let { DigitalSignature.friendlyName(it.subjectX500Principal.name) } ?: f.nameWithoutExtension, f, c)
        }
        keychainAliases(context).forEach { a -> out += Entry("kc:$a", Kind.KEYCHAIN, a, null, null, a) }
        return out
    }

    private fun certBeside(p12: File): X509Certificate? =
        File(p12.parentFile, p12.nameWithoutExtension + ".cer").takeIf { it.exists() }?.let { runCatching { TrustedCerts.parse(it.readBytes()).firstOrNull() }.getOrNull() }

    private fun newId(): String = "id_" + System.currentTimeMillis().toString(36)

    /** Creates a self-signed ID from [spec] and stores it under [password]. */
    suspend fun create(context: Context, spec: Spec, password: CharArray): Pair<Entry, DigitalSignature.Identity> = withContext(Dispatchers.IO) {
        val (key, cert) = selfSigned(spec)
        val id = newId()
        val f = File(dir(context), "$id.p12")
        store(f, key, listOf(cert), password)
        File(dir(context), "$id.cer").writeBytes(cert.encoded)
        Entry(id, Kind.FILE, DigitalSignature.friendlyName(cert.subjectX500Principal.name), f, cert) to DigitalSignature.Identity(key, listOf(cert))
    }

    /** Copies an imported PKCS#12 into the ID list (it is checked with [password] first). */
    suspend fun importPkcs12(context: Context, input: InputStream, password: CharArray): Pair<Entry, DigitalSignature.Identity> = withContext(Dispatchers.IO) {
        val bytes = input.use { it.readBytes() }
        val identity = DigitalSignature.loadPkcs12(bytes.inputStream(), password)
        val id = newId()
        val f = File(dir(context), "$id.p12").apply { writeBytes(bytes) }
        File(dir(context), "$id.cer").writeBytes(identity.certificate.encoded)
        Entry(id, Kind.FILE, DigitalSignature.friendlyName(identity.subject), f, identity.certificate) to identity
    }

    /** Unlocks a file ID; also records the public certificate of the legacy ID once it is known. */
    suspend fun open(context: Context, entry: Entry, password: CharArray): DigitalSignature.Identity = withContext(Dispatchers.IO) {
        when (entry.kind) {
            Kind.FILE -> {
                val f = entry.file ?: error("no file")
                DigitalSignature.loadPkcs12(f, password).also { id ->
                    val cer = File(f.parentFile, f.nameWithoutExtension + ".cer")
                    if (!cer.exists()) runCatching { cer.writeBytes(id.certificate.encoded) }
                }
            }
            Kind.KEYCHAIN -> loadKeyChain(context, entry.alias ?: error("no alias"))
        }
    }

    fun delete(context: Context, entry: Entry) {
        when (entry.kind) {
            Kind.FILE -> entry.file?.let { f -> f.delete(); File(f.parentFile, f.nameWithoutExtension + ".cer").delete() }
            Kind.KEYCHAIN -> entry.alias?.let { a -> saveAliases(context, keychainAliases(context) - a) }
        }
    }

    // ---------------------------------------------------------------- KeyChain

    fun keychainAliases(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALIASES, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun saveAliases(context: Context, aliases: List<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ALIASES, aliases.distinct().joinToString("\n")).apply()

    /** Opens the system certificate chooser; [onPicked] runs on a binder thread with the alias (null = cancelled). */
    fun chooseKeyChain(activity: Activity, onPicked: (String?) -> Unit) {
        KeyChain.choosePrivateKeyAlias(activity, { alias ->
            if (alias != null) saveAliases(activity.applicationContext, keychainAliases(activity.applicationContext) + alias)
            onPicked(alias)
        }, arrayOf("RSA", "EC"), null, null, -1, null)
    }

    /** Private key handle + chain of a KeyChain alias (blocking: never on the main thread). */
    suspend fun loadKeyChain(context: Context, alias: String): DigitalSignature.Identity = withContext(Dispatchers.IO) {
        val key = KeyChain.getPrivateKey(context, alias) ?: throw IllegalStateException("no access to $alias")
        val chain = KeyChain.getCertificateChain(context, alias)?.toList().orEmpty()
        if (chain.isEmpty()) throw IllegalStateException("certificate chain missing")
        DigitalSignature.Identity(key, chain)
    }

    // ---------------------------------------------------------------- creation

    internal fun keyUsage(usage: Usage, ec: Boolean): Int {
        val sign = KeyUsage.digitalSignature or KeyUsage.nonRepudiation
        val enc = if (ec) KeyUsage.keyAgreement else KeyUsage.keyEncipherment or KeyUsage.dataEncipherment
        return when (usage) { Usage.SIGNING -> sign; Usage.ENCRYPTION -> enc; Usage.BOTH -> sign or enc }
    }

    /** Builds the key pair and self-signed certificate of [spec] (no Android dependencies). */
    fun selfSigned(spec: Spec, now: Date = Date()): Pair<PrivateKey, X509Certificate> {
        DigitalSignature.ensureProvider()
        val ec = spec.key.algorithm == "EC"
        val kpg = KeyPairGenerator.getInstance(spec.key.algorithm)
        if (ec) kpg.initialize(ECGenParameterSpec(spec.key.curve)) else kpg.initialize(spec.key.size)
        val kp = kpg.generateKeyPair()
        val nb = X500NameBuilder(BCStyle.INSTANCE)
        nb.addRDN(BCStyle.CN, spec.commonName.ifBlank { "SmartDocs User" })
        if (spec.unit.isNotBlank()) nb.addRDN(BCStyle.OU, spec.unit.trim())
        if (spec.organization.isNotBlank()) nb.addRDN(BCStyle.O, spec.organization.trim())
        if (spec.country.isNotBlank()) {
            val c = spec.country.trim().uppercase()
            require(c.length == 2 && c.all { it in 'A'..'Z' }) { "country must be a two-letter code" }
            nb.addRDN(BCStyle.C, c)
        }
        if (spec.email.isNotBlank()) nb.addRDN(BCStyle.EmailAddress, spec.email.trim())
        val name = nb.build()
        val until = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, spec.validYears.coerceIn(1, 50)) }.time
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time).shiftLeft(16).or(BigInteger.valueOf(java.security.SecureRandom().nextInt(65536).toLong())), now, until, name, kp.public)
        builder.addExtension(Extension.keyUsage, true, KeyUsage(keyUsage(spec.usage, ec)))
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder(if (ec) "SHA256withECDSA" else "SHA256withRSA").build(kp.private)))
        return kp.private to cert
    }

    internal fun store(out: File, key: PrivateKey, chain: List<X509Certificate>, password: CharArray) {
        val ks = DigitalSignature.pkcs12Store()
        ks.load(null, null)
        ks.setKeyEntry("smartdocs", key, password, chain.toTypedArray())
        out.outputStream().use { ks.store(it, password) }
    }
}
