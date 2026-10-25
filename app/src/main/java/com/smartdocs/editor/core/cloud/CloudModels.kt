package com.smartdocs.editor.core.cloud

import android.content.Context
import java.io.File

/** One file or folder in a cloud drive. [id] is the provider's stable identifier (Drive id, Graph id, Dropbox path id). */
data class CloudItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val mime: String? = null,
    val parentId: String? = null,
    /** Dropbox works with paths; other providers leave it null. */
    val path: String? = null,
)

enum class ProviderId(val key: String, val label: String) {
    DRIVE("drive", "Google Drive"),
    ONEDRIVE("onedrive", "OneDrive"),
    DROPBOX("dropbox", "Dropbox");

    companion object { fun of(key: String?): ProviderId? = entries.firstOrNull { it.key == key } }
}

/** Common operations every direct cloud integration offers. All calls block: run them on Dispatchers.IO. */
interface CloudProvider {
    val id: ProviderId

    /** Root folder id used when [list] gets null. */
    val rootId: String

    fun isConfigured(): Boolean
    fun isConnected(): Boolean

    /** Folder listing (folders first). */
    suspend fun list(folderId: String?): List<CloudItem>
    suspend fun search(query: String): List<CloudItem>
    suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)? = null)

    /** Creates a new file named [name] inside [parentId]. */
    suspend fun upload(local: File, parentId: String?, name: String = local.name): CloudItem

    /** Replaces the content of an existing file. */
    suspend fun update(item: CloudItem, local: File): CloudItem
    suspend fun createFolder(parentId: String?, name: String): CloudItem
    suspend fun disconnect()
}

object Clouds {
    fun provider(context: Context, id: ProviderId): CloudProvider = when (id) {
        ProviderId.DRIVE -> GoogleDrive.get(context)
        ProviderId.ONEDRIVE -> OneDrive.get(context)
        ProviderId.DROPBOX -> Dropbox.get(context)
    }

    fun all(context: Context): List<CloudProvider> = ProviderId.entries.map { provider(context, it) }

    /** Local folder where opened cloud files are kept: <docs>/Cloud/<provider>/ */
    fun cacheDir(context: Context, id: ProviderId): File {
        val docs = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents")
        return File(docs, "Cloud/${id.key}").apply { mkdirs() }
    }

    fun isPdf(item: CloudItem) = !item.isFolder && (item.mime == "application/pdf" || item.name.endsWith(".pdf", true))
}
