package com.smartdocs.editor.ui.reader.annot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.smartdocs.editor.SmartDocsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Intents for attachments, videos and snapshots (FileProvider URIs, clipboard, picked content). */
object MediaIntents {

    /** Open [file] in another app (video player, viewer…) through the app's FileProvider. */
    fun view(context: Context, app: SmartDocsApp, file: File, mime: String) {
        val uri = app.documents.shareUri(file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(i, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, file.name, Toast.LENGTH_SHORT).show() }
    }

    /** Put an image file on the clipboard as a content URI (pasteable as an image in other apps). */
    fun copyImage(context: Context, app: SmartDocsApp, file: File) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, app.documents.shareUri(file)))
        }
    }

    fun share(context: Context, app: SmartDocsApp, file: File, mime: String) {
        runCatching { context.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), mime), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Display name of a picked content URI. */
    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** Copy a picked content URI into [dir] keeping its name; returns the file and its display name. */
    suspend fun importUri(context: Context, uri: Uri, dir: File): Pair<File, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName(context, uri).replace(Regex("""[\\/:*?"<>|]"""), "_")
            val out = File(dir.apply { mkdirs() }, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: return@runCatching null
            out to name
        }.getOrNull()
    }

    /** Write [file] into a SAF document the user created. */
    suspend fun writeTo(context: Context, file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }
}
