package com.smartdocs.editor.core.sign

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.tsp.TimeStampToken
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * RFC 3161 time-stamp client (BouncyCastle request/response + OkHttp transport).
 * Blocking: call from Dispatchers.IO.
 */
object TsaClient {

    const val DEFAULT_URL = "http://timestamp.digicert.com"

    /** Free public TSAs offered in the UI. */
    val presets = listOf(
        "http://timestamp.digicert.com",
        "http://timestamp.sectigo.com",
        "http://ts.ssl.com",
        "http://timestamp.globalsign.com/tsa/r6advanced1",
        "https://freetsa.org/tsr",
    )

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class TsaException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Per-server options of a saved time-stamp server (nitro-quicksign): HTTP Basic login and "require a trusted
     * TSA certificate". Not a data class so the password never appears in toString / logs.
     */
    class Policy(
        val username: String?,
        val password: String?,
        val requireTrusted: Boolean,
        val anchors: () -> List<X509Certificate>,
        val untrustedMessage: String,
    ) {
        override fun toString(): String = "Policy(user=${username != null}, requireTrusted=$requireTrusted)"
    }

    /** Looks up the [Policy] of a URL; installed by TsaServers.install. Null = plain anonymous request. */
    @Volatile var policyResolver: ((String) -> Policy?)? = null

    /** `Authorization` header value for HTTP Basic auth. */
    fun basicAuth(username: String, password: String): String = Credentials.basic(username, password, Charsets.UTF_8)

    /**
     * Request a time-stamp token over SHA-256([data]) from [url]. Returns the DER-encoded
     * TimeStampToken (a CMS SignedData), ready to be embedded as `id-aa-signatureTimeStampToken`
     * or as the /Contents of a document time-stamp.
     */
    fun timestamp(data: ByteArray, url: String = DEFAULT_URL): ByteArray =
        timestampToken(data, url).encoded

    fun timestampToken(data: ByteArray, url: String = DEFAULT_URL): TimeStampToken {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val gen = TimeStampRequestGenerator().apply { setCertReq(true) }
        val nonce = BigInteger(64, SecureRandom())
        val req = gen.generate(NISTObjectIdentifiers.id_sha256, digest, nonce)
        val policy = policyResolver?.invoke(url.trim())
        val headers = policy?.username?.let { u -> mapOf("Authorization" to basicAuth(u, policy.password.orEmpty())) } ?: emptyMap()
        val bytes = try {
            post(url.trim(), req.encoded, "application/timestamp-query", "application/timestamp-reply", headers)
        } catch (e: Exception) {
            throw TsaException("TSA: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val response = try { TimeStampResponse(bytes) } catch (e: Exception) { throw TsaException("TSA: invalid response", e) }
        try { response.validate(req) } catch (e: Exception) { throw TsaException("TSA: ${response.statusString ?: e.message}", e) }
        val token = response.timeStampToken ?: throw TsaException("TSA: no token (status ${response.status})")
        if (policy?.requireTrusted == true) requireTrusted(token, policy)
        return token
    }

    /** The token must be signed by its embedded signer certificate, and that certificate must chain to an anchor. */
    private fun requireTrusted(token: TimeStampToken, policy: Policy) {
        val conv = JcaX509CertificateConverter()
        val all = token.certificates.getMatches(null).filterIsInstance<X509CertificateHolder>().map { conv.getCertificate(it) }
        @Suppress("UNCHECKED_CAST")
        val signer = (token.certificates.getMatches(token.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).firstOrNull() as? X509CertificateHolder)
            ?.let { conv.getCertificate(it) } ?: throw TsaException(policy.untrustedMessage)
        try { token.validate(JcaSimpleSignerInfoVerifierBuilder().build(signer)) } catch (e: Exception) { throw TsaException(policy.untrustedMessage, e) }
        if (!TsaTrust.isTrusted(signer, all, policy.anchors())) throw TsaException(policy.untrustedMessage)
    }

    /**
     * POST (or GET when [body] is null) with an automatic https:// retry: Android blocks cleartext
     * HTTP unless the app's network security config allows it, and TSA/OCSP/CRL/AIA URLs are
     * usually plain http (their payloads are signed, so transport security is not required).
     */
    internal fun post(url: String, body: ByteArray?, contentType: String?, accept: String?, headers: Map<String, String> = emptyMap()): ByteArray {
        fun once(u: String): ByteArray {
            val b = Request.Builder().url(u)
            if (body != null) b.post(body.toRequestBody((contentType ?: "application/octet-stream").toMediaType())) else b.get()
            if (accept != null) b.header("Accept", accept)
            headers.forEach { (k, v) -> b.header(k, v) }
            return http.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("empty response")
            }
        }
        return try { once(url) } catch (e: Exception) {
            val cleartextBlocked = e is java.net.UnknownServiceException || (e.message ?: "").contains("CLEARTEXT", true)
            if (url.startsWith("http://", true) && cleartextBlocked) once("https://" + url.substring(7)) else throw e
        }
    }

    internal fun get(url: String): ByteArray = post(url, null, null, null)
}
