package com.smartdocs.editor.core.cloud

import android.content.Context
import org.json.JSONObject

/**
 * fc-sync (Foxit §32 "OneDrive Business / SharePoint document library"): SharePoint libraries inside the OneDrive
 * provider. Work accounts already reach their OneDrive for Business through `/me/drive` (the sign-in uses the
 * `common` authority); this adds the document libraries of the sites the user follows.
 *
 * Ids: items of another drive are "sp:<driveId>|<itemId>" ([encode]); "sp:" alone is the virtual folder that lists the
 * libraries. Everything here is pure except [Scopes] (unit-tested in SharePointTest).
 *
 * The libraries need the delegated Graph permissions Sites.Read.All and Files.ReadWrite.All on the owner's Azure app
 * registration (OWNER-ACTION). They are asked for only when the user taps "SharePoint libraries" and signs in again;
 * refreshes keep the old scope until such a sign-in succeeded, so a refused consent never signs the user out.
 */
object SharePoint {
    const val LIBRARIES = "sp:"
    private const val PREFIX = "sp:"
    private const val SEP = '|'

    fun encode(driveId: String, itemId: String): String = "$PREFIX$driveId$SEP$itemId"

    /** (driveId, itemId) of a library item id; null for personal-drive ids and for [LIBRARIES]. */
    fun decode(id: String?): Pair<String, String>? {
        if (id == null || !id.startsWith(PREFIX) || id == LIBRARIES) return null
        val rest = id.removePrefix(PREFIX)
        val i = rest.indexOf(SEP)
        if (i <= 0 || i == rest.length - 1) return null
        return rest.substring(0, i) to rest.substring(i + 1)
    }

    fun isLibraryId(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    /** Sites from `/me/followedSites`: (siteId, display name). */
    fun sites(json: String): List<Pair<String, String>> {
        val arr = JSONObject(json.ifBlank { "{}" }).optJSONArray("value") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            id to o.optString("displayName").ifEmpty { o.optString("name").ifEmpty { id } }
        }
    }

    /** Document libraries from `/sites/{id}/drives`: (driveId, label "Site — Library"). */
    fun libraries(siteName: String, json: String): List<Pair<String, String>> {
        val arr = JSONObject(json.ifBlank { "{}" }).optJSONArray("value") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("driveType").let { it.isNotEmpty() && it != "documentLibrary" }) return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            id to "$siteName — ${o.optString("name").ifEmpty { id }}"
        }
    }

    /** Which Graph scopes OneDrive asks for (see the class comment). */
    object Scopes {
        const val BASE = "offline_access Files.ReadWrite User.Read"
        const val WORK = "offline_access Files.ReadWrite.All Sites.Read.All User.Read"

        @Volatile var signInWithLibraries = false; private set
        @Volatile var librariesGranted = false; private set

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("onedrive_scopes", Context.MODE_PRIVATE)

        fun load(c: Context) {
            val p = prefs(c)
            signInWithLibraries = p.getBoolean("ask", false)
            librariesGranted = p.getBoolean("granted", false)
        }

        /** Pure: scope for a sign-in ([refresh] = false) or a token refresh. */
        fun scope(refresh: Boolean, ask: Boolean, granted: Boolean): String = if ((refresh && granted) || (!refresh && (ask || granted))) WORK else BASE

        /** The next sign-in asks for the library permissions. */
        fun requestLibraries(c: Context) { prefs(c).edit().putBoolean("ask", true).apply(); load(c) }

        /** A sign-in finished: what it asked for is now granted. */
        fun signedIn(c: Context) {
            val p = prefs(c)
            if (p.getBoolean("ask", false)) p.edit().putBoolean("granted", true).putBoolean("ask", false).apply()
            load(c)
        }

        /** Sign-out: back to the base scope. */
        fun reset(c: Context) { prefs(c).edit().clear().apply(); load(c) }
    }
}
