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
 * Honest limits: codes are device-agnostic (one code can be shared), cannot be revoked offline, and the secret
 * can be extracted from the APK by a determined attacker. Good enough for small-scale offline sales; use signed
 * server-side licences for stronger protection.
 */
object ActivationCodes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val DAY = 24L * 3600 * 1000

    enum class Plan(val code: Int, val days: Int) { MONTH(1, 31), YEAR(2, 366), LIFETIME(3, 0) }

    sealed interface Result {
        data class Valid(val plan: Plan, val expires: Long) : Result
        data object Malformed : Result
        data object BadSignature : Result
        data object Expired : Result
        data object Disabled : Result
    }

    private val secret: ByteArray get() = BuildCfg.string("PRO_CODE_SECRET").toByteArray(Charsets.UTF_8)
    val enabled: Boolean get() = secret.isNotEmpty()

    private fun mac(data: ByteArray, key: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data).copyOf(8)

    fun normalize(input: String): String = input.uppercase().filter { !it.isWhitespace() && it != '-' }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> it } }.joinToString("")

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    fun decode(code: String): ByteArray? {
        val s = normalize(code)
        if (s.length != 24) return null
        val out = ArrayList<Byte>(15); var buffer = 0; var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c); if (v < 0) return null
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { out += ((buffer shr (bits - 8)) and 0xFF).toByte(); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size == 15 }
    }

    fun verify(code: String, now: Long = System.currentTimeMillis()): Result {
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

    /** Verifies and stores the entitlement. */
    fun activate(context: Context, code: String): Result {
        val r = verify(code)
        if (r is Result.Valid) {
            Pro.set(context, Pro.Entitlement(Pro.Source.CODE, "code:${r.plan.name.lowercase()}", r.expires, System.currentTimeMillis()))
            context.applicationContext.getSharedPreferences("pro_entitlement", Context.MODE_PRIVATE).edit().putString("code", normalize(code)).apply()
        }
        return r
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
