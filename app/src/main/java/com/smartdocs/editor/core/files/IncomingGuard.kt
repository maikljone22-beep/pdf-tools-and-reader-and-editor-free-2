package com.smartdocs.editor.core.files

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Hostile review C2: another app could hand SmartDocs a `file://` link to the app's own private files (the settings
 * that hold the AI key, cloud sign-ins…) or a link through the app's own FileProvider; the app read it with its own
 * rights and wrote the content where others can read it. Such links are refused at the door.
 */
object IncomingGuard {
    /** True when [uri], coming from outside, points into this app's private storage. */
    fun isPrivate(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme == "content") return uri.authority == ctx.packageName + ".fileprovider"
        if (uri.scheme != "file") return false
        val path = uri.path ?: return true
        val canon = runCatching { File(path).canonicalPath }.getOrNull() ?: return true
        return privateRoots(ctx).any { r -> canon == r || canon.startsWith("$r/") }
    }

    internal fun privateRoots(ctx: Context): List<String> = listOfNotNull(
        ctx.applicationInfo.dataDir, ctx.filesDir?.parent, ctx.filesDir?.path, ctx.cacheDir?.path, ctx.noBackupFilesDir?.path,
        ctx.applicationInfo.deviceProtectedDataDir,
        // review: the app's external folders (Android/data/<package>) are private to it too
        runCatching { ctx.getExternalFilesDir(null)?.parentFile?.path }.getOrNull(), runCatching { ctx.externalCacheDir?.path }.getOrNull(),
    ).mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.distinct()
}
