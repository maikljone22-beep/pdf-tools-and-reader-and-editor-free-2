package com.smartdocs.editor.core.gaps6

import java.security.MessageDigest

/**
 * Invite-a-friend referral codes. Each install has a stable random id ([InstallId]); its referral code is the first
 * 40 bits of SHA-256(id) in Crockford base32 (8 chars, no I/L/O/U) — short enough to type, stable per install.
 * Link format: `https://smartdocs.app/invite?ref=<code>`.
 *
 * There is no referral server yet: the counter screen counts invites sent from this device and whether this
 * install redeemed a friend's code. "Friends who joined" needs a server (see MODULE_gaps6_r5.md).
 */
object Referral {
    const val BASE_URL = "https://smartdocs.app/invite"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LEN = 8

    fun codeFor(installId: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 5) v = (v shl 8) or (h[i].toLong() and 0xFF)
        val sb = StringBuilder()
        for (i in CODE_LEN - 1 downTo 0) sb.append(ALPHABET[((v shr (i * 5)) and 31).toInt()])
        return sb.toString()
    }

    fun link(code: String): String = "$BASE_URL?ref=$code"

    /** Upper-cases and maps look-alikes (O→0, I/L→1, U→V); strips spaces and dashes. */
    fun normalize(input: String): String = input.uppercase().filter { !it.isWhitespace() && it != '-' }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> it } }.joinToString("")

    fun isValid(code: String): Boolean {
        val c = normalize(code)
        return c.length == CODE_LEN && c.all { it in ALPHABET }
    }

    /** fc-settings: true for an invite link opened from outside ([BASE_URL], http or https). */
    fun isInviteLink(scheme: String?, host: String?, path: String?): Boolean =
        (scheme == "https" || scheme == "http") && host.equals("smartdocs.app", ignoreCase = true) && (path ?: "").trimEnd('/') == "/invite"

    /** Code from an invite link or a bare code; null when neither. */
    fun parse(input: String): String? {
        val t = input.trim()
        val fromLink = Regex("""[?&]ref=([A-Za-z0-9-]+)""").find(t)?.groupValues?.get(1)
        val c = normalize(fromLink ?: t)
        return c.takeIf { isValid(it) }
    }

    /** A friend's code may be redeemed once, never your own. */
    fun canRedeem(own: String, friend: String, alreadyRedeemed: String?): Boolean {
        val f = parse(friend) ?: return false
        return alreadyRedeemed == null && f != normalize(own)
    }
}
