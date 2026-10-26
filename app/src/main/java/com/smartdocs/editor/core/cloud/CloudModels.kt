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
    DROPBOX("dropbox", "Dropbox"),
    BOX("box", "Box");

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

    /** Deletes [item] (providers move it to their trash / recycle bin where they have one). Used by sync. */
    suspend fun delete(item: CloudItem) { throw UnsupportedOperationException("delete not supported") }

    /** Current metadata of a file (null when it no longer exists). Default: not available. */
    suspend fun metadataOf(item: CloudItem): CloudItem? = null

    /** True when [shareLink] can create a link for files of this provider. */
    val supportsLinks: Boolean get() = false

    /**
     * A shareable link to [item]. [anyone] = true asks for a link that works without signing in (where the account
     * policy allows it); false returns the provider's normal web link (people who already have access).
     */
    suspend fun shareLink(item: CloudItem, anyone: Boolean = true): String { throw UnsupportedOperationException("links not supported") }

    /** Opaque change cursor of the whole drive (null = this provider has no change feed). */
    suspend fun changeCursor(): String? = null

    /** Returns (something changed?, next cursor) since [cursor]. A reset cursor counts as a change. */
    suspend fun changesSince(cursor: String): Pair<Boolean, String> = true to cursor
}

object Clouds {
    fun provider(context: Context, id: ProviderId): CloudProvider = when (id) {
        ProviderId.DRIVE -> GoogleDrive.get(context)
        ProviderId.ONEDRIVE -> OneDrive.get(context)
        ProviderId.DROPBOX -> Dropbox.get(context)
        ProviderId.BOX -> Box.get(context)
    }

    fun all(context: Context): List<CloudProvider> = ProviderId.entries.map { provider(context, it) }

    /** Local folder where opened cloud files are kept: <docs>/Cloud/<provider>/ */
    fun cacheDir(context: Context, id: ProviderId): File {
        val docs = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents")
        return File(docs, "Cloud/${id.key}").apply { mkdirs() }
    }

    /** Signed-in account label of [p] (email / user name), when known. */
    fun accountOf(p: CloudProvider): String? = when (p) {
        is GoogleDrive -> p.accountEmail
        is OneDrive -> p.account
        is Dropbox -> p.account
        is Box -> p.account
        else -> null
    }

    fun isPdf(item: CloudItem) = !item.isFolder && (item.mime == "application/pdf" || item.name.endsWith(".pdf", true))
}
