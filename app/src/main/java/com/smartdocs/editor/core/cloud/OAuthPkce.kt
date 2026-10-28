package com.smartdocs.editor.core.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.smartdocs.editor.core.review.BuildCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Configuration read from `cloud.properties` (through BuildConfig fields, see MODULE_acct.md).
 * A provider whose client id is empty is shown disabled.
 */
object CloudConfig {
    val oneDriveClientId: String get() = BuildCfg.string("CLOUD_ONEDRIVE_CLIENT_ID")
    val dropboxAppKey: String get() = BuildCfg.string("CLOUD_DROPBOX_APP_KEY")

    /** Redirect URIs registered with the providers. Default: the app's own scheme (intent-filter `smartdocs://oauth`).
     *  If a provider refuses custom schemes, register `https://<collab-worker>/oauth/<provider>` (the worker bounces to the app). */
    val oneDriveRedirect: String get() = BuildCfg.string("CLOUD_ONEDRIVE_REDIRECT", "smartdocs://oauth/onedrive")
    val dropboxRedirect: String get() = BuildCfg.string("CLOUD_DROPBOX_REDIRECT", "smartdocs://oauth/dropbox")

    /** Box (share6): client id of a Box "OAuth 2.0 user authentication" app. */
    val boxClientId: String get() = BuildCfg.string("CLOUD_BOX_CLIENT_ID")

    /** Box only accepts https redirects: default = the collab worker's bounce page `/oauth/box` (forwards to the app). */
    val boxRedirect: String get() = BuildCfg.string("CLOUD_BOX_REDIRECT").ifEmpty {
        BuildCfg.string("COLLAB_URL").trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/oauth/box" } ?: "smartdocs://oauth/box"
    }

    /**
     * Box needs the client secret to exchange codes. Preferred: the collab worker keeps it (`wrangler secret put BOX_CLIENT_SECRET`)
     * and exposes `/oauth-token/box`. Fallback (less safe, the secret ships in the APK): `boxClientSecret=` in cloud.properties.
     */
    val boxClientSecret: String get() = BuildCfg.string("CLOUD_BOX_CLIENT_SECRET")
    val boxTokenUrl: String get() = BuildCfg.string("CLOUD_BOX_TOKEN_URL").ifEmpty {
        if (boxClientSecret.isNotEmpty()) "https://api.box.com/oauth2/token"
        else BuildCfg.string("COLLAB_URL").trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/oauth-token/box" } ?: ""
    }
}

/** Tokens of one OAuth provider, persisted in private SharedPreferences. */
class OAuthTokens(context: Context, provider: String) {
    private val p = context.getSharedPreferences("cloud_$provider", Context.MODE_PRIVATE)
    var access: String? get() = p.getString("access", null); set(v) { p.edit().putString("access", v).apply() }
    var refresh: String? get() = p.getString("refresh", null); set(v) { p.edit().putString("refresh", v).apply() }
    var expiresAt: Long get() = p.getLong("expires", 0L); set(v) { p.edit().putLong("expires", v).apply() }
    var account: String? get() = p.getString("account", null); set(v) { p.edit().putString("account", v).apply() }
    val connected: Boolean get() = refresh != null || access != null
    fun clear() = p.edit().clear().apply()
}

/** Authorization-code flow with PKCE in a Custom Tab; the redirect comes back to MainActivity as `smartdocs://oauth/<provider>`. */
object OAuthPkce {
    data class Endpoint(val provider: ProviderId, val authUrl: String, val tokenUrl: String, val clientId: String, val redirect: String, val scope: String?, val extra: Map<String, String>,
                        val clientSecret: String? = null)

    /** [refresh]: the endpoint for a token refresh (OneDrive keeps its old scope until a wider sign-in succeeded). */
    fun endpoint(id: ProviderId, refresh: Boolean = false): Endpoint? = when (id) {
        ProviderId.ONEDRIVE -> CloudConfig.oneDriveClientId.takeIf { it.isNotEmpty() }?.let {
            Endpoint(id, "https://login.microsoftonline.com/common/oauth2/v2.0/authorize", "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                it, CloudConfig.oneDriveRedirect, SharePoint.Scopes.scope(refresh, SharePoint.Scopes.signInWithLibraries, SharePoint.Scopes.librariesGranted),
                mapOf("response_mode" to "query", "prompt" to "select_account"))
        }
        ProviderId.DROPBOX -> CloudConfig.dropboxAppKey.takeIf { it.isNotEmpty() }?.let {
            Endpoint(id, "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
                it, CloudConfig.dropboxRedirect, null, mapOf("token_access_type" to "offline"))
        }
        ProviderId.BOX -> CloudConfig.boxClientId.takeIf { it.isNotEmpty() && CloudConfig.boxTokenUrl.isNotEmpty() }?.let {
            Endpoint(id, "https://account.box.com/api/oauth2/authorize", CloudConfig.boxTokenUrl, it, CloudConfig.boxRedirect, null, emptyMap(),
                clientSecret = CloudConfig.boxClientSecret.ifEmpty { null })
        }
        ProviderId.DRIVE -> null
    }

    private val random = SecureRandom()
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun pending(context: Context) = context.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)

    /** Opens the provider's sign-in page. Returns false when the provider is not configured. */
    fun start(context: Context, id: ProviderId): Boolean {
        SharePoint.Scopes.load(context)
        val ep = endpoint(id) ?: return false
        val verifier = b64(ByteArray(48).also { random.nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64(ByteArray(16).also { random.nextBytes(it) })
        pending(context).edit().putString("state", state).putString("verifier", verifier).putString("provider", id.key).apply()
        val uri = Uri.parse(ep.authUrl).buildUpon()
            .appendQueryParameter("client_id", ep.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", ep.redirect)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply { ep.scope?.let { appendQueryParameter("scope", it) }; ep.extra.forEach { (k, v) -> appendQueryParameter(k, v) } }
            .build()
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
            .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    /** Handles `smartdocs://oauth/<provider>?code=…&state=…`. Returns the connected provider, or throws. */
    suspend fun complete(context: Context, redirect: Uri): ProviderId = withContext(Dispatchers.IO) {
        val p = pending(context)
        val id = ProviderId.of(p.getString("provider", null)) ?: throw IllegalStateException("no pending sign-in")
        SharePoint.Scopes.load(context)
        val ep = endpoint(id) ?: throw IllegalStateException("not configured")
        redirect.getQueryParameter("error")?.let { throw IllegalStateException(redirect.getQueryParameter("error_description") ?: it) }
        if (redirect.getQueryParameter("state") != p.getString("state", null)) throw SecurityException("state mismatch")
        val code = redirect.getQueryParameter("code") ?: throw IllegalStateException("no code")
        val form = FormBody.Builder()
            .add("client_id", ep.clientId).add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", ep.redirect).add("code_verifier", p.getString("verifier", "") ?: "")
            .apply { ep.scope?.let { add("scope", it) }; ep.clientSecret?.let { add("client_secret", it) } }
            .build()
        val json = JSONObject(CloudHttp.string(Request.Builder().url(ep.tokenUrl).post(form).build()))
        save(OAuthTokens(context, id.key), json)
        p.edit().clear().apply()
        if (id == ProviderId.ONEDRIVE) SharePoint.Scopes.signedIn(context)   // fc-sync: library scopes now granted
        id
    }

    private fun save(t: OAuthTokens, json: JSONObject) {
        t.access = json.getString("access_token")
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { t.refresh = it }
        t.expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L
    }

    /** Valid access token for [id], refreshed when it expires within a minute. */
    fun accessToken(context: Context, id: ProviderId, force: Boolean = false): String = synchronized(this) { // Box refresh tokens are single-use
        val t = OAuthTokens(context, id.key)
        val access = t.access
        if (!force && access != null && System.currentTimeMillis() < t.expiresAt - 60_000) return@synchronized access
        val refresh = t.refresh ?: throw AuthRequiredException()
        if (id == ProviderId.ONEDRIVE) SharePoint.Scopes.load(context)
        val ep = endpoint(id, refresh = true) ?: throw AuthRequiredException("not configured")
        val form = FormBody.Builder().add("client_id", ep.clientId).add("grant_type", "refresh_token").add("refresh_token", refresh)
            .apply { ep.scope?.let { add("scope", it) }; ep.clientSecret?.let { add("client_secret", it) } }.build()
        val json = try { JSONObject(CloudHttp.string(Request.Builder().url(ep.tokenUrl).post(form).build())) }
            catch (e: HttpException) { if (e.code == 400) { t.clear(); throw AuthRequiredException("refresh rejected") } else throw e }
        save(t, json)
        t.access!!
    }
}
