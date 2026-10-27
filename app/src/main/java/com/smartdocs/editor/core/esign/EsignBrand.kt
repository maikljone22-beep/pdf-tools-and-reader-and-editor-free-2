package com.smartdocs.editor.core.esign

import org.json.JSONObject
import kotlin.math.pow

/**
 * Sender branding sent with requests and online forms: shown on the web signing page header, its buttons and the e-mails
 * (ai-proxy/esign/worker.js `brandIn`). [logoBase64] = PNG/JPEG/WebP bytes (<= [MAX_LOGO_BYTES]) on the way out; requests read
 * back from the server only carry [hasLogo] (the image itself is served from `/logo/<id>`).
 */
data class EsignBrand(val name: String = "", val color: String = "", val logoBase64: String = "", val hasLogo: Boolean = false) {
    val isEmpty get() = name.isBlank() && color.isBlank() && logoBase64.isBlank() && !hasLogo

    fun toJson(): JSONObject = JSONObject().put("name", name.take(80)).put("color", normalizeColor(color)).put("logo", logoBase64)

    companion object {
        const val MAX_LOGO_BYTES = 100 * 1024
        private val HEX6 = Regex("^#?([0-9a-fA-F]{6})$")
        private val HEX3 = Regex("^#?([0-9a-fA-F]{3})$")

        /** Suggested theme colours (sender can also type any #rrggbb). */
        val PRESETS = listOf("#1e3a8a", "#2563eb", "#0f766e", "#15803d", "#b45309", "#b91c1c", "#7c3aed", "#db2777", "#334155", "#111827")

        /** "#ABC" / "abc123" / " #1E3A8A " -> "#1e3a8a"; anything else -> "". */
        fun normalizeColor(s: String): String {
            val t = s.trim()
            HEX6.find(t)?.let { return "#" + it.groupValues[1].lowercase() }
            HEX3.find(t)?.let { m -> return "#" + m.groupValues[1].lowercase().map { "$it$it" }.joinToString("") }
            return ""
        }

        /** 0xAARRGGBB of a normalized colour, or null. */
        fun argb(s: String): Long? = normalizeColor(s).takeIf { it.isNotEmpty() }?.let { 0xFF000000L or it.substring(1).toLong(16) }

        /** WCAG relative luminance of #rrggbb (0..1). */
        fun luminance(s: String): Double {
            val c = normalizeColor(s).ifEmpty { return 0.0 }.substring(1).toInt(16)
            fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin((c shr 16) and 255) + 0.7152 * lin((c shr 8) and 255) + 0.0722 * lin(c and 255)
        }

        /** Text colour readable on [s]: dark on light colours, white otherwise (same rule as `ink()` in the worker). */
        fun onColor(s: String): String = if (luminance(s) > 0.179) "#111111" else "#ffffff"

        /** Image type from the magic bytes, as accepted by the worker; null = not usable as a logo. */
        fun imageType(b: ByteArray): String? = when {
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }

        /** True when a request's brand has anything to show (name, colour or logo). */
        fun shows(b: EsignBrand?): Boolean = b != null && !b.isEmpty

        /** Name shown as the sender: the brand name when set, else the request's sender. */
        fun senderLabel(b: EsignBrand?, sender: String): String = b?.name?.trim()?.ifBlank { null } ?: sender

        fun from(o: JSONObject?): EsignBrand? = o?.let { EsignBrand(it.optString("name"), normalizeColor(it.optString("color")), "", it.optBoolean("logo")) }
    }
}
