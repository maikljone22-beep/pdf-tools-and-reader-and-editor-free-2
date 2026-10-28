package com.smartdocs.editor.core.sign

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

/** One member-state list named by the EU list of trusted lists (LOTL). */
data class TslPointer(val territory: String, val url: String, val mime: String)

/**
 * Parser of ETSI TS 119 612 trusted lists (pure: no Android API, unit-tested). Only the parts the app uses: the
 * LOTL's pointers to the member-state XML lists, and the CA certificates of qualified certificate services
 * (`Svctype/CA/QC`) whose current status is "granted" and that issue certificates for e-signatures.
 */
object TslParser {
    const val CA_QC = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC"
    const val GRANTED = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted"
    const val FOR_ESIG = "http://uri.etsi.org/TrstSvc/TrustedList/SvcInfoExt/ForeSignatures"
    private const val ADDITIONAL_INFO_URI = "http://uri.etsi.org/TrstSvc/TrustedList/SvcInfoExt/"

    private fun parse(input: InputStream): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // no DTDs, no external entities (XXE): a trusted list never needs them
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.isExpandEntityReferences = false }
        return input.use { f.newDocumentBuilder().parse(it) }
    }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>(); var n = firstChild
        while (n != null) { if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == name) out += n as Element; n = n.nextSibling }
        return out
    }

    private fun Element.descendants(name: String): List<Element> {
        val l = getElementsByTagNameNS("*", name)
        return (0 until l.length).map { l.item(it) as Element }
    }

    private fun Element.text(name: String): String? = descendants(name).firstOrNull()?.textContent?.trim()

    /** Pointers of the LOTL to the member-state lists in XML (https only; the LOTL's pointers to itself are skipped). */
    fun pointers(input: InputStream): List<TslPointer> {
        val root = parse(input).documentElement
        return root.descendants("OtherTSLPointer").mapNotNull { p ->
            val url = p.text("TSLLocation") ?: return@mapNotNull null
            val mime = p.text("MimeType").orEmpty()
            val territory = p.text("SchemeTerritory").orEmpty()
            TslPointer(territory, url, mime).takeIf {
                url.startsWith("https://", true) && territory.isNotEmpty() && !territory.equals("EU", true) && "xml" in mime.lowercase()
            }
        }.distinctBy { it.url }
    }

    /** DER bytes of the CA certificates of granted qualified-certificate services for e-signatures. */
    fun qualifiedCaCerts(input: InputStream): List<ByteArray> {
        val root = parse(input).documentElement
        val out = ArrayList<ByteArray>()
        for (svc in root.descendants("TSPService")) {
            // the current information only (ServiceHistory instances repeat the same element names)
            val info = svc.children("ServiceInformation").firstOrNull() ?: continue
            val type = info.children("ServiceTypeIdentifier").firstOrNull()?.textContent?.trim()
            val status = info.children("ServiceStatus").firstOrNull()?.textContent?.trim()
            if (type != CA_QC || status != GRANTED) continue
            val ext = info.children("ServiceInformationExtensions").flatMap { it.descendants("URI") }.map { it.textContent.trim() }
                .filter { it.startsWith(ADDITIONAL_INFO_URI) && it.contains("/For", ignoreCase = false) }
            if (ext.isNotEmpty() && FOR_ESIG !in ext) continue      // only seals or web sites
            for (sdi in info.children("ServiceDigitalIdentity")) for (x in sdi.descendants("X509Certificate")) {
                runCatching { java.util.Base64.getMimeDecoder().decode(x.textContent.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    /** "SchemeTerritory" of a list (e.g. "DE"); null when missing. */
    fun territory(input: InputStream): String? = parse(input).documentElement.descendants("SchemeInformation").firstOrNull()?.text("SchemeTerritory")
}

/**
 * EU trusted lists (EUTL) as a third set of signature trust anchors next to the user's trusted certificates and the
 * Android CA store ([CertInspector.inspect]). "Update now" or a monthly job downloads the LOTL from its pinned
 * address over HTTPS, follows it to every member-state list (https only) and keeps the CA certificates of granted
 * qualified services for e-signatures. The lists' XAdES signatures are not verified (no XML-DSig on Android); the
 * pinned LOTL URL and HTTPS are the protection. Adobe's AATL is not public (licensing) and is not supported.
 */
object TrustLists {
    const val LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"
    private const val MAX_BYTES = 40L shl 20
    private const val PREFS = "np2_trust_lists"
    private const val K_USE = "use_eutl"
    private const val K_AUTO = "auto_update"
    private const val K_TIME = "updated_at"
    private const val K_COUNT = "count"
    private const val K_COUNTRIES = "countries"
    private const val WORK = "np2_eutl_update"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context) = File(File(c.filesDir, "trust_lists").apply { mkdirs() }, "eutl.pem")
    @Volatile private var cache: Pair<Long, List<X509Certificate>>? = null

    /** Use the EU trusted list when validating signatures (SignPrefs-style switch). */
    fun useEutl(c: Context): Boolean = p(c).getBoolean(K_USE, false)
    fun setUseEutl(c: Context, v: Boolean) = p(c).edit { putBoolean(K_USE, v) }
    fun autoUpdate(c: Context): Boolean = p(c).getBoolean(K_AUTO, false)
    fun setAutoUpdate(c: Context, v: Boolean) { p(c).edit { putBoolean(K_AUTO, v) }; schedule(c) }
    fun lastUpdate(c: Context): Long = p(c).getLong(K_TIME, 0L)
    fun count(c: Context): Int = p(c).getInt(K_COUNT, 0)
    fun countries(c: Context): Int = p(c).getInt(K_COUNTRIES, 0)

    /** The stored EUTL anchors (empty before the first update). */
    fun anchors(c: Context): List<X509Certificate> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        cache?.let { (t, l) -> if (t == f.lastModified()) return l }
        val l = runCatching { TrustedCerts.parse(f.readBytes()) }.getOrDefault(emptyList())
        cache = f.lastModified() to l
        return l
    }

    data class Outcome(val countries: Int, val certificates: Int, val failed: List<String>)

    /** Downloads the LOTL and the member-state lists; replaces the stored anchors only when something was found. */
    suspend fun updateNow(c: Context): Outcome = withContext(Dispatchers.IO) {
        val pointers = TslParser.pointers(ByteArrayInputStream(fetch(LOTL_URL)))
        if (pointers.isEmpty()) throw IllegalStateException("the EU list of trusted lists names no member-state list")
        val cf = CertificateFactory.getInstance("X.509")
        val certs = LinkedHashMap<String, X509Certificate>()
        val failed = ArrayList<String>(); var ok = 0
        for (ptr in pointers) {
            runCatching {
                val der = TslParser.qualifiedCaCerts(ByteArrayInputStream(fetch(ptr.url)))
                der.forEach { b ->
                    runCatching { cf.generateCertificate(ByteArrayInputStream(b)) as X509Certificate }.getOrNull()?.let { x ->
                        certs.putIfAbsent(hex(MessageDigest.getInstance("SHA-256").digest(x.encoded)), x)
                    }
                }
                ok++
            }.onFailure { failed += ptr.territory }
        }
        if (certs.isEmpty()) throw IllegalStateException("no qualified CA certificate found")
        val f = file(c)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(certs.values.joinToString("") { pem(it) })
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        cache = null
        p(c).edit { putLong(K_TIME, System.currentTimeMillis()); putInt(K_COUNT, certs.size); putInt(K_COUNTRIES, ok) }
        Outcome(ok, certs.size, failed)
    }

    fun clear(c: Context) { file(c).delete(); cache = null; p(c).edit { remove(K_TIME); remove(K_COUNT); remove(K_COUNTRIES) } }

    /** HTTPS GET with a size limit; refuses anything that is not (or does not stay) HTTPS. */
    internal fun fetch(url: String): ByteArray {
        require(url.startsWith("https://", true)) { "not https: $url" }
        com.smartdocs.editor.core.data.NpfNet.check()   // nav-prefs: offline mode
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: throw IllegalStateException("not https: $url")
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SmartDocs trust-list update")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}: $url")
            if (!conn.url.protocol.equals("https", true)) throw IllegalStateException("redirected away from https: $url")
            if (conn.contentLengthLong > MAX_BYTES) throw IllegalStateException("too large: $url")
            conn.inputStream.use { i ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16); var total = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IllegalStateException("too large: $url"); out.write(buf, 0, n) }
                return out.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun pem(x: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(x.encoded) + "\n-----END CERTIFICATE-----\n"

    /** Monthly background update while "update automatically" is on (needs a connection). Idempotent. */
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!autoUpdate(c)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<TrustListWorker>(30, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class TrustListWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (runCatching { TrustLists.updateNow(applicationContext) }.isSuccess) Result.success() else Result.retry()
}
