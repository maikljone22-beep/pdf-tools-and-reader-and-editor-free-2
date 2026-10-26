package com.smartdocs.editor.core.billing

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.util.Base64

/**
 * Signed licence tokens issued by `ai-proxy/license/worker.js` (pure JVM logic: no android.*, unit-tested).
 *
 * Token = base64url(payload JSON) + "." + base64url(Ed25519 signature over the payload's base64url text).
 * Payload: {v:1, lid, kind:"play"|"code", plan, product, dev, iat, exp, until, active}
 *  - exp:   the token itself stops counting at this time (the server signs ~10 days ahead)
 *  - until: end of the paid period (0 = lifetime / auto-renewing subscription)
 *  - dev:   device id the token was issued to
 */
object LicenseToken {
    data class Claims(
        val lid: String, val kind: String, val plan: String, val product: String?, val device: String,
        val issuedAt: Long, val expiresAt: Long, val until: Long, val active: Boolean,
    )

    private val b64url: Base64.Decoder = Base64.getUrlDecoder()

    /** Decodes a 32-byte raw Ed25519 public key given as base64 or base64url (padding optional). */
    fun publicKey(text: String): ByteArray? = runCatching {
        val t = text.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        b64url.decode(t).takeIf { it.size == 32 }
    }.getOrNull()

    /** Verifies the signature and returns the claims; null for a malformed or forged token. Does not check time or device. */
    fun verify(token: String, publicKey: ByteArray): Claims? {
        val parts = token.trim().split('.')
        if (parts.size != 2 || publicKey.size != 32) return null
        return runCatching {
            val sig = b64url.decode(parts[1])
            if (sig.size != 64) return null
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            val msg = parts[0].toByteArray(Charsets.US_ASCII)
            signer.update(msg, 0, msg.size)
            if (!signer.verifySignature(sig)) return null
            parse(String(b64url.decode(parts[0]), Charsets.UTF_8))
        }.getOrNull()
    }

    fun parse(json: String): Claims? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("v", 0) != 1) return null
        Claims(
            lid = o.getString("lid"), kind = o.getString("kind"), plan = o.optString("plan"),
            product = o.optString("product").ifEmpty { null }, device = o.getString("dev"),
            issuedAt = o.optLong("iat"), expiresAt = o.getLong("exp"), until = o.optLong("until"), active = o.optBoolean("active", true),
        )
    }.getOrNull()
}

/**
 * Grace rules of server mode (pure, unit-tested).
 *  - signed licence: counts while active, issued for this device, before the signed `exp`, before the paid `until`,
 *    and at most [CHECK_GRACE_MS] after the last successful server check;
 *  - provisional Play purchase (server unreachable at purchase time): counts [PROVISIONAL_MS] after the purchase;
 *  - anything else (an unsigned activation code) does not count while the server is configured.
 */
object LicenseRules {
    const val DAY = 24L * 3600 * 1000
    const val CHECK_GRACE_MS = 7 * DAY
    const val PROVISIONAL_MS = 3 * DAY
    const val CHECK_INTERVAL_MS = 3 * DAY

    fun signedActive(c: LicenseToken.Claims?, device: String, lastCheck: Long, now: Long): Boolean {
        if (c == null || !c.active || c.device != device) return false
        if (now >= c.expiresAt) return false
        if (c.until != 0L && now >= c.until) return false
        val since = maxOf(lastCheck, c.issuedAt)
        return now - since < CHECK_GRACE_MS && now >= since - DAY // a clock moved far back does not stretch the window
    }

    fun provisionalActive(verifiedAt: Long, now: Long): Boolean = verifiedAt > 0 && now >= verifiedAt - DAY && now - verifiedAt < PROVISIONAL_MS

    fun checkDue(lastCheck: Long, now: Long): Boolean = now - lastCheck >= CHECK_INTERVAL_MS || now < lastCheck
}

/** Activation-code text format (24 Crockford-base32 characters = 15 bytes), shared with the server and its tests. */
object CodeFormat {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

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
}
