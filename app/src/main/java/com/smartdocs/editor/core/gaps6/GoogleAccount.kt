package com.smartdocs.editor.core.gaps6

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Sign in with Google" for the Account tab: name, e-mail and photo of the user's Google account.
 *
 * play-services-auth 22 no longer ships the legacy GoogleSignIn API, and Credential Manager would be a new
 * dependency; so this uses Play services' AuthorizationClient with the basic `openid email profile` scopes (no
 * client id in code — the same Cloud Console Android OAuth client that Google Drive uses). The profile comes
 * from the result's GoogleSignInAccount, or from the OpenID userinfo endpoint when that is missing a field.
 * Only the profile is kept (SharedPreferences "g6_google"); the token is not stored.
 */
object GoogleAccount {
    data class Profile(val name: String, val email: String, val photoUrl: String?)

    sealed interface Step {
        data class SignedIn(val profile: Profile) : Step
        class NeedsUi(val pendingIntent: PendingIntent) : Step
    }

    private const val PREFS = "g6_google"
    private val scopes = listOf(Scope("openid"), Scope("email"), Scope("profile"))
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Profile? {
        if (!loaded) {
            val p = prefs(context)
            _profile.value = p.getString("email", null)?.let { Profile(p.getString("name", "") ?: "", it, p.getString("photo", null)) }
            loaded = true
        }
        return _profile.value
    }

    private fun request() = AuthorizationRequest.Builder().setRequestedScopes(scopes).build()

    /** Starts sign-in; silent when the user consented before. Launch [Step.NeedsUi.pendingIntent] otherwise. */
    suspend fun begin(context: Context): Step {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) return Step.NeedsUi(r.pendingIntent ?: throw IOException("no resolution"))
        return Step.SignedIn(store(context, r))
    }

    /** Result Intent of the launched PendingIntent. */
    suspend fun finish(context: Context, data: Intent?): Profile {
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return store(context, r)
    }

    private suspend fun store(context: Context, r: AuthorizationResult): Profile {
        val acc = runCatching { r.toGoogleSignInAccount() }.getOrNull()
        var name = acc?.displayName.orEmpty()
        var email = acc?.email.orEmpty()
        var photo = acc?.photoUrl?.toString()
        val token = r.accessToken
        if ((name.isBlank() || email.isBlank() || photo == null) && token != null) {
            runCatching { userInfo(token) }.getOrNull()?.let { o ->
                if (name.isBlank()) name = o.optString("name")
                if (email.isBlank()) email = o.optString("email")
                if (photo == null) photo = o.optString("picture").ifBlank { null }
            }
        }
        if (token != null) runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
        if (email.isBlank()) throw IOException("no account e-mail")
        val p = Profile(name.ifBlank { email.substringBefore('@') }, email, photo)
        prefs(context).edit().putString("name", p.name).putString("email", p.email).putString("photo", p.photoUrl).apply()
        _profile.value = p; loaded = true
        return p
    }

    /** Forgets the profile and revokes the basic scopes (best effort). */
    suspend fun signOut(context: Context) {
        runCatching { Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder().setScopes(scopes).build()).await() }
        prefs(context).edit().clear().apply()
        _profile.value = null; loaded = true
    }

    private val http by lazy { OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    private suspend fun userInfo(token: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://openidconnect.googleapis.com/v1/userinfo").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("userinfo ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { t ->
            if (t.isSuccessful) cont.resume(t.result) else cont.resumeWithException(t.exception ?: IOException("task failed"))
        }
    }
}
