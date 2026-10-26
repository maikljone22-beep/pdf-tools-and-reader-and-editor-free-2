package com.smartdocs.editor.core.esign

import org.json.JSONArray
import org.json.JSONObject

/*
 * Signature-request model shared by the sender, the in-app signer and the worker (ai-proxy/esign).
 * Pure Kotlin + org.json: no android.* so the rules run in plain JVM unit tests.
 *
 * Field geometry: fractions (0..1) of the displayed page, origin top-left, so the same numbers work for the
 * page preview, the web signing page (pdf.js viewport) and the composer (which maps them to PDF space).
 */

enum class FieldType { SIGNATURE, INITIALS, DATE, TEXT;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: SIGNATURE }
}

enum class SignOrder { SEQUENTIAL, PARALLEL;
    companion object { fun of(s: String?) = if (s.equals("parallel", true)) PARALLEL else SEQUENTIAL }
}

enum class SignerStatus { WAITING, PENDING, VIEWED, SIGNED, DECLINED;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: WAITING }
}

enum class RequestStatus { DRAFT, OUT, COMPLETED, DECLINED, CANCELLED, FINALIZED;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: OUT }
}

data class EsignField(
    val id: String, val signer: Int, val page: Int,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val type: FieldType = FieldType.SIGNATURE, val required: Boolean = true, val label: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("signer", signer).put("page", page)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("type", type.name.lowercase()).put("required", required).put("label", label)
    companion object {
        fun from(o: JSONObject) = EsignField(o.optString("id"), o.optInt("signer"), o.optInt("page"),
            o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("w").toFloat(), o.optDouble("h").toFloat(),
            FieldType.of(o.optString("type")), o.optBoolean("required", true), o.optString("label"))
    }
}

/** Evidence recorded by the server when a signer finishes or declines. */
data class SignerEvent(val time: Long, val ip: String, val userAgent: String, val device: String, val channel: String)

data class Signer(
    val name: String, val email: String = "", val status: SignerStatus = SignerStatus.WAITING,
    val viewedAt: Long = 0L, val signedAt: Long = 0L, val declineReason: String = "",
    val event: SignerEvent? = null, val link: String = "",
) {
    val done get() = status == SignerStatus.SIGNED || status == SignerStatus.DECLINED
    fun toJson(): JSONObject = JSONObject().put("name", name).put("email", email).put("status", status.name.lowercase())
        .put("viewedAt", viewedAt).put("signedAt", signedAt).put("declineReason", declineReason).put("link", link)
        .apply { event?.let { put("event", JSONObject().put("time", it.time).put("ip", it.ip).put("ua", it.userAgent).put("device", it.device).put("channel", it.channel)) } }
    companion object {
        fun from(o: JSONObject) = Signer(o.optString("name"), o.optString("email"), SignerStatus.of(o.optString("status")),
            o.optLong("viewedAt"), o.optLong("signedAt"), o.optString("declineReason"),
            o.optJSONObject("event")?.let { SignerEvent(it.optLong("time"), it.optString("ip"), it.optString("ua"), it.optString("device"), it.optString("channel")) },
            o.optString("link"))
    }
}

/** One audit-trail line, as kept by the server (`kind`: created, sent, viewed, signed, declined, reminded, cancelled, finalized). */
data class AuditEntry(val time: Long, val kind: String, val who: String, val ip: String = "", val device: String = "", val detail: String = "") {
    companion object {
        fun from(o: JSONObject) = AuditEntry(o.optLong("time"), o.optString("kind"), o.optString("who"), o.optString("ip"), o.optString("device"), o.optString("detail"))
    }
}

data class EsignRequest(
    val id: String, val title: String, val sender: String, val message: String,
    val order: SignOrder, val signers: List<Signer>, val fields: List<EsignField>,
    val status: RequestStatus, val created: Long, val originalSha: String, val finalSha: String = "",
    val audit: List<AuditEntry> = emptyList(), val pageCount: Int = 0, val expires: Long = 0L,
) {
    companion object {
        fun from(o: JSONObject): EsignRequest {
            val s = o.optJSONArray("signers") ?: JSONArray()
            val f = o.optJSONArray("fields") ?: JSONArray()
            val a = o.optJSONArray("audit") ?: JSONArray()
            return EsignRequest(o.optString("id"), o.optString("title"), o.optString("sender"), o.optString("message"),
                SignOrder.of(o.optString("order")), (0 until s.length()).map { Signer.from(s.getJSONObject(it)) },
                (0 until f.length()).map { EsignField.from(f.getJSONObject(it)) }, RequestStatus.of(o.optString("status")),
                o.optLong("created"), o.optString("originalSha"), o.optString("finalSha"),
                (0 until a.length()).map { AuditEntry.from(a.getJSONObject(it)) }, o.optInt("pageCount"), o.optLong("expires"))
        }
    }
}

/** Values one signer submitted: field id -> text, or base64 PNG for signature/initials. */
data class SignerValues(val signer: Int, val values: Map<String, String>)

/**
 * Who may sign now, and how the request moves on. The worker implements the same rules (ai-proxy/esign/worker.js `flow*`).
 *  - SEQUENTIAL: only the first signer that is not done; everyone after waits.
 *  - PARALLEL: every signer that is not done.
 *  - Any decline ends the request (DECLINED); all signed = COMPLETED.
 */
object SignerFlow {
    fun active(order: SignOrder, signers: List<Signer>): Set<Int> {
        if (signers.any { it.status == SignerStatus.DECLINED }) return emptySet()
        val open = signers.indices.filter { !signers[it].done }
        return if (order == SignOrder.PARALLEL) open.toSet() else open.take(1).toSet()
    }

    /** Statuses after a (re)start: active signers PENDING (or VIEWED kept), the others WAITING. */
    fun normalize(order: SignOrder, signers: List<Signer>): List<Signer> {
        val act = active(order, signers)
        return signers.mapIndexed { i, s ->
            when {
                s.done -> s
                i in act -> if (s.status == SignerStatus.VIEWED) s else s.copy(status = SignerStatus.PENDING)
                else -> s.copy(status = SignerStatus.WAITING)
            }
        }
    }

    fun overall(signers: List<Signer>): RequestStatus = when {
        signers.any { it.status == SignerStatus.DECLINED } -> RequestStatus.DECLINED
        signers.isNotEmpty() && signers.all { it.status == SignerStatus.SIGNED } -> RequestStatus.COMPLETED
        else -> RequestStatus.OUT
    }

    class NotYourTurn : IllegalStateException("not your turn")

    fun sign(order: SignOrder, signers: List<Signer>, who: Int, at: Long): List<Signer> {
        if (who !in active(order, signers)) throw NotYourTurn()
        val next = signers.toMutableList().also { it[who] = it[who].copy(status = SignerStatus.SIGNED, signedAt = at) }
        return normalize(order, next)
    }

    fun decline(order: SignOrder, signers: List<Signer>, who: Int, reason: String, at: Long): List<Signer> {
        if (who !in active(order, signers)) throw NotYourTurn()
        return signers.toMutableList().also { it[who] = it[who].copy(status = SignerStatus.DECLINED, signedAt = at, declineReason = reason) }
    }

    /** Required fields of [who] that have no value. */
    fun missing(fields: List<EsignField>, who: Int, values: Map<String, String>): List<EsignField> =
        fields.filter { it.signer == who && it.required && values[it.id].isNullOrBlank() }
}

/** Formatting of hashes and the verification text printed on the certificate page. */
object AuditFormat {
    private val HEX = Regex("^[0-9a-f]{64}$")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun isSha256(s: String) = HEX.matches(s.lowercase())

    /** "ab12cd34 ef56…" — 8 groups of 8, in two lines of 4 groups, easy to compare by eye. */
    fun grouped(sha: String, perLine: Int = 4): List<String> {
        val h = sha.lowercase().filter { it.isLetterOrDigit() }
        require(isSha256(h)) { "not a SHA-256 hex string" }
        return h.chunked(8).chunked(perLine).map { it.joinToString(" ") }
    }

    /** Short form used in lists: first 8 … last 8. */
    fun short(sha: String): String = if (sha.length < 20) sha else sha.take(8) + "…" + sha.takeLast(8)

    fun verifyUrl(base: String, sha: String): String = base.trimEnd('/') + "/v/" + sha.lowercase()

    /** Compares a user-pasted hash with the expected one, ignoring spaces, case and line breaks. */
    fun matches(expected: String, pasted: String): Boolean {
        val a = expected.lowercase().filter { it.isLetterOrDigit() }
        val b = pasted.lowercase().filter { it.isLetterOrDigit() }
        return isSha256(a) && a == b
    }

    /** UTC timestamp "2026-10-26 14:05:09 UTC" (the certificate must not depend on the device time zone). */
    fun utc(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}
