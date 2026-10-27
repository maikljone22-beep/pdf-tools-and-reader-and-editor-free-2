package com.smartdocs.editor.core.cloud

import java.io.File
import java.security.MessageDigest

/**
 * Pure decisions about a local working copy of a cloud file (audit DI-23 / DI-24), unit tested in CloudCopyTest.
 *
 * A copy is "dirty" when it holds edits that are not in the cloud yet: the reader saved it and the upload has not
 * succeeded ([CloudLinks.Link.dirty]), or its bytes differ from the ones last downloaded / uploaded
 * ([CloudLinks.Link.uploadedHash]). A dirty copy is never overwritten by a download and never removed by
 * "clear cloud copies"; [CloudUploadWorker] uploads it when the network allows.
 */
object CloudCopy {
    /** What opening a cloud file does with the local copy. */
    enum class OpenAction {
        /** No local edits: (re)download the cloud version. */
        DOWNLOAD,
        /** Local edits not uploaded, cloud unchanged: open the local copy, upload it later. */
        KEEP_LOCAL,
        /** Local edits not uploaded and the cloud file changed too: open the local copy; its upload becomes a conflict copy. */
        CONFLICT,
    }

    data class Opened(val file: File, val action: OpenAction, val hash: String?)

    /** Some providers round times to seconds (same tolerance as folder sync). */
    const val CLOCK_TOLERANCE_MS = 2_000L

    fun isDirty(flag: Boolean, uploadedHash: String?, localHash: String?): Boolean =
        flag || (uploadedHash != null && localHash != null && localHash != uploadedHash)

    /**
     * The cloud file changed since [known] was recorded ([current] null = unknown, treated as unchanged). A [known]
     * time of 0 (a provider response without one) is not compared, so only the size counts then.
     */
    fun remoteChanged(known: CloudItem, current: CloudItem?): Boolean =
        current != null && (current.size != known.size || (known.modified > 0 && current.modified > known.modified + CLOCK_TOLERANCE_MS))

    fun openAction(localExists: Boolean, dirty: Boolean, remoteChanged: Boolean): OpenAction = when {
        !localExists || !dirty -> OpenAction.DOWNLOAD
        remoteChanged -> OpenAction.CONFLICT
        else -> OpenAction.KEEP_LOCAL
    }

    /**
     * Core of [CloudLinks.open]: [dest] is the working copy of [item] ([link] its stored link, null for a new copy).
     * A dirty copy is returned untouched; otherwise the cloud version is downloaded to a hidden sibling first and then
     * moved over [dest], so a failed download never leaves a truncated copy that would later look like a local edit.
     */
    suspend fun openInto(provider: CloudProvider, item: CloudItem, dest: File, link: CloudLinks.Link?, onProgress: ((Long, Long) -> Unit)? = null): Opened {
        if (dest.isFile && link != null) {
            val dirty = isDirty(link.dirty, link.uploadedHash, if (link.uploadedHash != null) sha256(dest) else null)
            if (dirty) {
                val current = runCatching { provider.metadataOf(link.item) }.getOrNull()
                return Opened(dest, openAction(true, true, remoteChanged(link.item, current)), null)
            }
        }
        val part = File(dest.parentFile, ".${dest.name}.part")
        try {
            provider.download(item, part, onProgress)
            moveOver(part, dest)
        } finally { part.delete() }
        return Opened(dest, OpenAction.DOWNLOAD, sha256(dest))
    }

    /** Replaces [dest] with [src] (rename; on file systems that refuse to rename over a file, delete first, then copy). */
    fun moveOver(src: File, dest: File) {
        if (src.renameTo(dest)) return
        if (dest.exists() && !dest.delete()) throw IllegalStateException("cannot replace ${dest.name}")
        if (!src.renameTo(dest)) src.copyTo(dest, overwrite = true)
    }

    /**
     * Frees a provider's cloud-copy folder except the [keep] files (absolute paths of dirty copies).
     * Returns the bytes freed. Pure java.io (used by StorageManager).
     */
    fun clearExcept(dir: File, keep: Set<String>): Long {
        var freed = 0L
        dir.walkBottomUp().forEach { f ->
            if (f == dir) return@forEach
            if (f.isFile) { if (f.absolutePath !in keep) { val n = f.length(); if (f.delete()) freed += n } }
            else if (f.listFiles().isNullOrEmpty()) f.delete()
        }
        return freed
    }

    fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
