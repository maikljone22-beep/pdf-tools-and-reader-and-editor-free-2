package com.smartdocs.editor.core.esign

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A request I sent (owner key kept only here) or one I was asked to sign (signer token). */
data class EsignRef(
    val id: String, val title: String, val sent: Boolean, val secret: String, val created: Long,
    val localPath: String = "", val status: String = "out", val lastCheck: Long = 0L, val finalPath: String = "", val counts: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("sent", sent).put("secret", secret).put("created", created)
        .put("path", localPath).put("status", status).put("last", lastCheck).put("final", finalPath).put("counts", counts)
    companion object {
        fun from(o: JSONObject) = EsignRef(o.getString("id"), o.optString("title"), o.optBoolean("sent"), o.getString("secret"), o.optLong("created"),
            o.optString("path"), o.optString("status", "out"), o.optLong("last"), o.optString("final"), o.optString("counts"))
    }
}

object EsignStore {
    private val _items = MutableStateFlow<List<EsignRef>>(emptyList())
    val items: StateFlow<List<EsignRef>> = _items
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("esign_store", Context.MODE_PRIVATE)

    fun load(c: Context): List<EsignRef> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val arr = runCatching { JSONArray(prefs(c).getString("items", "[]")) }.getOrDefault(JSONArray())
                _items.value = (0 until arr.length()).mapNotNull { runCatching { EsignRef.from(arr.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _items.value
    }

    private fun save(c: Context, list: List<EsignRef>) {
        _items.value = list
        prefs(c).edit().putString("items", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun upsert(c: Context, r: EsignRef) = synchronized(this) { save(c, load(c).filterNot { it.id == r.id && it.sent == r.sent } + r) }
    fun remove(c: Context, id: String, sent: Boolean) = synchronized(this) { save(c, load(c).filterNot { it.id == id && it.sent == sent }) }
    fun sent(c: Context, id: String) = load(c).firstOrNull { it.id == id && it.sent }
    fun received(c: Context, id: String) = load(c).firstOrNull { it.id == id && !it.sent }
    fun update(c: Context, id: String, sent: Boolean, block: (EsignRef) -> EsignRef) = synchronized(this) {
        save(c, load(c).map { if (it.id == id && it.sent == sent) block(it) else it })
    }
}

/** An online form I created (owner key kept only here). */
data class EsignFormRef(
    val id: String, val title: String, val secret: String, val created: Long, val link: String,
    val localPath: String = "", val state: String = "active", val count: Int = 0, val max: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("secret", secret).put("created", created).put("link", link)
        .put("path", localPath).put("state", state).put("count", count).put("max", max)
    companion object {
        fun from(o: JSONObject) = EsignFormRef(o.getString("id"), o.optString("title"), o.getString("secret"), o.optLong("created"), o.optString("link"),
            o.optString("path"), o.optString("state", "active"), o.optInt("count"), o.optInt("max"))
    }
}

object EsignFormStore {
    private val _items = MutableStateFlow<List<EsignFormRef>>(emptyList())
    val items: StateFlow<List<EsignFormRef>> = _items
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("esign_forms", Context.MODE_PRIVATE)

    fun load(c: Context): List<EsignFormRef> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val arr = runCatching { JSONArray(prefs(c).getString("items", "[]")) }.getOrDefault(JSONArray())
                _items.value = (0 until arr.length()).mapNotNull { runCatching { EsignFormRef.from(arr.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _items.value
    }

    private fun save(c: Context, list: List<EsignFormRef>) {
        _items.value = list
        prefs(c).edit().putString("items", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun upsert(c: Context, r: EsignFormRef) = synchronized(this) { save(c, load(c).filterNot { it.id == r.id } + r) }
    fun remove(c: Context, id: String) = synchronized(this) { save(c, load(c).filterNot { it.id == id }) }
    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }
    fun update(c: Context, id: String, block: (EsignFormRef) -> EsignFormRef) = synchronized(this) { save(c, load(c).map { if (it.id == id) block(it) else it }) }
}

/** E-sign branding settings (sender name, theme colour, logo), sent with every new request and form. */
object EsignBrandStore {
    private const val MAX_SIDE = 256

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("esign_brand", Context.MODE_PRIVATE)
    fun logoFile(c: Context) = java.io.File(c.filesDir, "esign/brand_logo")

    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun name(c: Context) = prefs(c).getString("name", "").orEmpty()
    fun color(c: Context) = prefs(c).getString("color", "").orEmpty()

    fun save(c: Context, on: Boolean, name: String, color: String) {
        prefs(c).edit().putBoolean("on", on).putString("name", name.trim().take(80)).putString("color", EsignBrand.normalizeColor(color)).apply()
    }

    /** The brand to send, or null when branding is off or empty. */
    fun current(c: Context): EsignBrand? {
        if (!enabled(c)) return null
        val logo = logoFile(c).takeIf { it.exists() && it.length() in 1..EsignBrand.MAX_LOGO_BYTES }
            ?.let { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }.orEmpty()
        return EsignBrand(name(c), color(c), logo, logo.isNotEmpty()).takeIf { !it.isEmpty }
    }

    /** Scales the picked image to <= 256 px and stores it as PNG (JPEG when the PNG is over the server limit). */
    fun setLogo(c: Context, uri: android.net.Uri) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        require(o.outWidth > 0 && o.outHeight > 0) { "not an image" }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val src = c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("not an image")
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        var bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (bytes.size > EsignBrand.MAX_LOGO_BYTES) {
            val flat = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bytes = java.io.ByteArrayOutputStream().also { flat.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            flat.recycle()
        }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        logoFile(c).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    fun clearLogo(c: Context) { logoFile(c).delete() }
}
