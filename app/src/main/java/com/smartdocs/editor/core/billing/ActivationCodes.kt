package com.smartdocs.editor.core.billing

import android.content.Context
import com.smartdocs.editor.core.review.BuildCfg
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline-verifiable Pro activation codes (sold through local agents / wallets).
 *
 * Format: 24 Crockford-base32 characters shown as XXXX-XXXX-XXXX-XXXX-XXXX-XXXX = 15 bytes:
 *   [0]      plan: 1 = 1 month, 2 = 1 year, 3 = lifetime
 *   [1..2]   expiry as days since 1970-01-01 (big endian), 0 = no expiry
 *   [3..6]   random serial
 *   [7..14]  first 8 bytes of HMAC-SHA256(secret, bytes[0..6])
 * The secret comes from `pro.properties` → BuildConfig.PRO_CODE_SECRET (see MODULE_acct.md); empty secret = activation disabled.
 *
 * Server mode (licence server configured, see [LicenseClient]): [redeem] sends the code to `/activate`, which checks the
 * same HMAC with the secret kept on the server, enforces a device limit and a revocation list, and returns a signed
 * licence. The embedded PRO_CODE_SECRET is then ignored (leave `codeSecret` empty in pro.properties).
 *
 * Honest limits (client mode): codes are device-agnostic (one code can be shared), cannot be revoked offline, and the secret
 * can be extracted from the APK by a determined attacker. Good enough for small-scale offline sales; use signed
 * server-side licences for stronger protection.
 */
object ActivationCodes {
    private const val DAY = 24L * 3600 * 1000

    enum class Plan(val code: Int, val days: Int) { MONTH(1, 31), YEAR(2, 366), LIFETIME(3, 0) }

    sealed interface Result {
        data class Valid(val plan: Plan, val expires: Long) : Result
        data object Malformed : Result
        data object BadSignature : Result
        data object Expired : Result
        data object Disabled : Result
        /** Server mode: the seller revoked this code. */
        data object Revoked : Result
        /** Server mode: the code is already active on the maximum number of devices. */
        data object DeviceLimit : Result
        /** Server mode: the licence server could not be reached. */
        data object Network : Result
    }

    private val secret: ByteArray get() = BuildCfg.string("PRO_CODE_SECRET").toByteArray(Charsets.UTF_8)
    /** Codes can be entered: a licence server is configured, or an offline secret is embedded. */
    val enabled: Boolean get() = LicenseClient.configured || secret.isNotEmpty()

    /** True when codes are checked by the licence server (the UI shows the "needs internet" note). */
    val online: Boolean get() = LicenseClient.configured

    private fun mac(data: ByteArray, key: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data).copyOf(8)

    fun normalize(input: String): String = CodeFormat.normalize(input)
    fun encode(bytes: ByteArray): String = CodeFormat.encode(bytes)
    fun decode(code: String): ByteArray? = CodeFormat.decode(code)

    fun verify(code: String, now: Long = System.currentTimeMillis()): Result {
        if (LicenseClient.configured) return Result.Disabled // the secret is not trusted in server mode: use redeem()
        val key = secret
        if (key.isEmpty()) return Result.Disabled
        val b = decode(code) ?: return Result.Malformed
        val expected = mac(b.copyOfRange(0, 7), key)
        if (!MessageDigest.isEqual(expected, b.copyOfRange(7, 15))) return Result.BadSignature
        val plan = Plan.entries.firstOrNull { it.code == b[0].toInt() } ?: return Result.Malformed
        val days = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        val expires = if (days == 0) 0L else days * DAY + DAY - 1
        if (expires != 0L && now > expires) return Result.Expired
        return Result.Valid(plan, expires)
    }

    /** Offline verification + store (client mode only; server mode returns [Result.Disabled] — call [redeem]). */
    fun activate(context: Context, code: String): Result {
        val r = verify(code)
        if (r is Result.Valid) {
            Pro.set(context, Pro.Entitlement(Pro.Source.CODE, "code:${r.plan.name.lowercase()}", r.expires, System.currentTimeMillis()))
            Pro.storeCode(context, normalize(code))
        }
        return r
    }

    /**
     * Redeems a code: through the licence server when it is configured (needs a connection), otherwise offline as
     * [activate]. Safe to call from the main thread (switches to IO).
     */
    suspend fun redeem(context: Context, code: String): Result {
        if (!LicenseClient.configured) return activate(context, code)
        if (decode(code) == null) return Result.Malformed
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val reply = try { LicenseClient.activate(context, code) } catch (e: LicenseClient.NetworkException) { return@withContext Result.Network }
            val token = reply.body.optString("token")
            when {
                reply.ok && token.isNotEmpty() -> {
                    if (!LicenseClient.store(context, token)) return@withContext Result.BadSignature
                    Pro.storeCode(context, normalize(code))
                    val c = Pro.load(context).claims
                    val plan = Plan.entries.firstOrNull { it.name.equals(c?.plan, true) } ?: Plan.MONTH
                    Result.Valid(plan, c?.until ?: 0L)
                }
                reply.status == 403 && reply.error == "revoked" -> Result.Revoked
                reply.status == 409 -> Result.DeviceLimit
                reply.status == 410 -> Result.Expired
                reply.status == 400 && reply.error == "malformed" -> Result.Malformed
                reply.status in 400..499 -> Result.BadSignature
                else -> Result.Network
            }
        }
    }

    /**
     * Generates a code (for the seller's own tooling / tests; never call this from user-facing UI).
     * [expiresAtDay] = absolute expiry as days since epoch; null → computed from the plan length starting today.
     */
    fun generate(plan: Plan, key: ByteArray, expiresAtDay: Int? = null): String {
        val day = expiresAtDay ?: if (plan.days == 0) 0 else (TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()) + plan.days).toInt()
        val b = ByteArray(15)
        b[0] = plan.code.toByte(); b[1] = (day shr 8).toByte(); b[2] = day.toByte()
        val serial = ByteArray(4).also { SecureRandom().nextBytes(it) }
        System.arraycopy(serial, 0, b, 3, 4)
        System.arraycopy(mac(b.copyOfRange(0, 7), key), 0, b, 7, 8)
        return encode(b)
    }
}
