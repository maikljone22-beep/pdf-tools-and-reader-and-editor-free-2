package com.smartdocs.editor.core.billing

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.smartdocs.editor.core.review.BuildCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client of the licence server (`ai-proxy/license/worker.js`). Configured by `license.properties` →
 * BuildConfig LICENSE_URL / LICENSE_PUBLIC_KEY (read through [BuildCfg], so the app compiles and works without them).
 * With an empty URL everything stays client-side exactly as before (offline codes, Play Billing only).
 */
object LicenseClient {
    val baseUrl: String get() = BuildCfg.string("LICENSE_URL").trimEnd('/')
    private val publicKeyText: String get() = BuildCfg.string("LICENSE_PUBLIC_KEY")
    val publicKey: ByteArray? get() = LicenseToken.publicKey(publicKeyText)

    /** Server mode: the URL and a valid public key are both set. */
    val configured: Boolean get() = baseUrl.isNotEmpty() && publicKey != null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Server answer: [status] is the HTTP code, [body] the parsed JSON (an `error` field on failure). */
    class Reply(val status: Int, val body: JSONObject) {
        val ok get() = status in 200..299
        val error: String get() = body.optString("error")
    }

    class NetworkException(cause: Throwable) : IOException(cause.message, cause)

    /** SHA-256(ANDROID_ID + package), first 32 hex chars: stable per app install signature + device, never the raw id. */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString("device", null)?.let { return it }
        val androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
        val seed = androidId.ifEmpty { java.util.UUID.randomUUID().toString() } + ":" + context.packageName
        val id = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        p.edit().putString("device", id).apply()
        return id
    }

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences("license_state", Context.MODE_PRIVATE)

    var Context.lastCheck: Long
        get() = prefs(this).getLong("last_check", 0L)
        set(v) { prefs(this).edit().putLong("last_check", v).apply() }

    private fun post(path: String, body: JSONObject): Reply {
        val req = Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(JSON)).build()
        val r = try { http.newCall(req).execute() } catch (e: IOException) { throw NetworkException(e) }
        r.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("error", "bad_response"))
            if (it.code >= 500) throw NetworkException(IOException("server ${it.code}: ${json.optString("error")}"))
            return Reply(it.code, json)
        }
    }

    /** Verifies [token] for this device; null when forged, for another device, or the server is not configured. */
    fun claimsFor(context: Context, token: String?): LicenseToken.Claims? {
        val key = publicKey ?: return null
        val c = LicenseToken.verify(token ?: return null, key) ?: return null
        return c.takeIf { it.device == deviceId(context) }
    }

    // ------------------------------------------------------------------ calls (blocking: use Dispatchers.IO)

    /** POST /activate {code, device} → {token} | 403 revoked | 409 device_limit | 410 expired | 400 invalid. */
    fun activate(context: Context, code: String): Reply =
        post("/activate", JSONObject().put("code", ActivationCodes.normalize(code)).put("device", deviceId(context)).put("app", context.packageName))

    /** POST /verify-purchase {packageName, productId, purchaseToken, device} → {active, token?}. The server acknowledges. */
    fun verifyPurchase(context: Context, productId: String, purchaseToken: String): Reply =
        post("/verify-purchase", JSONObject().put("packageName", context.packageName).put("productId", productId)
            .put("purchaseToken", purchaseToken).put("device", deviceId(context)))

    /** POST /check {device, tokens:[…]} → {licenses:[{lid, active, token?, reason?}]}. */
    fun check(context: Context, tokens: List<String>): Reply =
        post("/check", JSONObject().put("device", deviceId(context)).put("tokens", JSONArray(tokens)))

    // ------------------------------------------------------------------ high level

    /** True when a re-check is due (every 3 days) in server mode. */
    fun checkDue(context: Context): Boolean = configured && LicenseRules.checkDue(context.lastCheck, System.currentTimeMillis())

    /** Stores a signed licence as the Pro entitlement. Returns false when the token does not verify for this device. */
    fun store(context: Context, token: String, purchaseToken: String? = null): Boolean {
        val c = claimsFor(context, token) ?: return false
        val source = if (c.kind == "play") Pro.Source.PLAY else Pro.Source.CODE
        val now = System.currentTimeMillis()
        context.lastCheck = now
        Pro.set(context, Pro.Entitlement(source, c.product ?: "${c.kind}:${c.plan}", c.until, now, autoRenewing = c.kind == "play" && c.until == 0L,
            license = token, provisional = false))
        if (purchaseToken != null) prefs(context).edit().putString("purchase_token", purchaseToken).apply()
        return true
    }

    /**
     * Periodic re-check (worker + app start). Re-validates the stored licence; migrates a code that was activated
     * offline before the server existed. Network failures keep the current state (grace rules decide). Never throws.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        val ent = Pro.load(context)
        runCatching {
            when {
                ent.license != null -> {
                    val r = check(context, listOf(ent.license))
                    if (!r.ok) return@runCatching false
                    val lic = r.body.optJSONArray("licenses")?.optJSONObject(0) ?: return@runCatching false
                    val fresh = lic.optString("token")
                    if (lic.optBoolean("active") && fresh.isNotEmpty()) store(context, fresh)
                    else { Pro.set(context, Pro.Entitlement()); context.lastCheck = System.currentTimeMillis(); false }
                }
                ent.source == Pro.Source.CODE -> { // activated offline: move it to the server once
                    val code = Pro.storedCode(context) ?: return@runCatching false
                    val r = activate(context, code)
                    val token = r.body.optString("token")
                    if (r.ok && token.isNotEmpty()) store(context, token)
                    else { if (r.status in 400..499) Pro.set(context, Pro.Entitlement()); false }
                }
                else -> false
            }
        }.getOrDefault(false)
    }
}
