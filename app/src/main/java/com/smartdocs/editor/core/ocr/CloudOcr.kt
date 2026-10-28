package com.smartdocs.editor.core.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.core.ai.AiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * "Pro OCR" in the cloud (PRO_OCR_r5, user opt-in through [OcrEngineMode.CLOUD]): the page image goes to the app's
 * own proxy (`POST /ocr`, ai-proxy/worker.js), which calls the OCR service configured on the server (Google Cloud
 * Vision, Azure Read or a vision model). No key is in the app. When the proxy is not configured, offline, or the
 * server answers without word boxes, [recognize] returns null and the offline engines run instead.
 */
object CloudOcr {
    /** Longest side of the image sent (Cloud Vision / Azure read 300-dpi pages well at this size). */
    const val MAX_SIDE = 2600

    fun available(): Boolean = AiClient.usesProxy()

    /** Words in [bitmap] pixels, or null (not configured, no network, error, no boxes). */
    suspend fun recognize(bitmap: Bitmap, langs: String?): List<OcrRefine.Tok>? {
        if (!available()) return null
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val img = if (k < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt().coerceAtLeast(1), (bitmap.height * k).toInt().coerceAtLeast(1), true) else bitmap
        val jpeg = ByteArrayOutputStream().use { o -> img.compress(Bitmap.CompressFormat.JPEG, 88, o); o.toByteArray() }
        if (img !== bitmap) img.recycle()
        val client = AiClient(BuildConfig.AI_PROXY_URL.trimEnd('/'), "", "", BuildConfig.AI_APP_TOKEN)
        val body = buildJsonObject {
            put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            putJsonArray("languages") { languages(langs).forEach { add(JsonPrimitive(it)) } }
        }
        return parse(client.postJson("/ocr", body), 1f / k)
    }

    /** ISO 639-1 hints for the server from Tesseract codes ("ara+eng" -> ar, en). */
    fun languages(langs: String?): List<String> = (langs ?: "ara+eng").split('+').mapNotNull {
        when (it) { "ara", "Arabic" -> "ar"; "eng" -> "en"; "fra" -> "fr"; "deu" -> "de"; "spa" -> "es"; "tur" -> "tr"; "fas" -> "fa"; "urd" -> "ur"; else -> null }
    }.distinct().ifEmpty { listOf("ar", "en") }

    /** The proxy's answer as words scaled by [scale] back to the page bitmap; null when it has no word boxes. */
    fun parse(o: JsonObject, scale: Float): List<OcrRefine.Tok>? {
        val words = runCatching { o["words"]?.jsonArray }.getOrNull() ?: return null
        if (words.isEmpty()) return null
        return words.mapNotNull { e ->
            val w = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val text = w["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            fun n(k: String) = w[k]?.jsonPrimitive?.floatOrNull ?: 0f
            OcrRefine.Tok(OcrRefine.foldScript(text), (n("x0") * scale).toInt(), (n("y0") * scale).toInt(), (n("x1") * scale).toInt(), (n("y1") * scale).toInt(),
                n("conf"), w["line"]?.jsonPrimitive?.intOrNull ?: -1)
        }.ifEmpty { null }
    }
}
