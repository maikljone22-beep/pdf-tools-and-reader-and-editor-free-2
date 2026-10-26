package com.smartdocs.editor.core.files

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Cheap identity of a local file: size + mtime + CRC of its head, middle and tail (64 KB each), so a 60 MB PDF is
 * fingerprinted without reading it all. Serialised as "size|mtime|crc".
 */
data class FileFingerprint(val size: Long, val mtime: Long, val crc: Long) {
    fun encode() = "$size|$mtime|$crc"

    /** Same content as [other]; the mtime is only a shortcut, the size + CRC decide. */
    fun sameContent(other: FileFingerprint) = size == other.size && crc == other.crc

    companion object {
        private const val CHUNK = 64 * 1024

        fun decode(s: String?): FileFingerprint? = s?.split('|')?.takeIf { it.size == 3 }?.let { p ->
            val size = p[0].toLongOrNull(); val mtime = p[1].toLongOrNull(); val crc = p[2].toLongOrNull()
            if (size == null || mtime == null || crc == null) null else FileFingerprint(size, mtime, crc)
        }

        fun of(f: File): FileFingerprint {
            val crc = CRC32()
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val buf = ByteArray(CHUNK)
                for (start in listOf(0L, (len / 2 - CHUNK / 2).coerceAtLeast(0L), (len - CHUNK).coerceAtLeast(0L)).distinct()) {
                    raf.seek(start)
                    val n = raf.read(buf, 0, minOf(CHUNK.toLong(), len - start).toInt().coerceAtLeast(0))
                    if (n > 0) crc.update(buf, 0, n)
                }
                crc.update(len.toString().toByteArray())
            }
            return FileFingerprint(f.length(), f.lastModified(), crc.value)
        }
    }
}

/**
 * What the working copy and its source looked like the last time they were known to be identical (after the import
 * or after a successful write-back). Serialised as "<local fingerprint>;<srcSize>;<srcMtime>" (-1 = unknown).
 */
data class SyncRecord(val local: FileFingerprint, val srcSize: Long, val srcMtime: Long) {
    fun encode() = "${local.encode()};$srcSize;$srcMtime"

    companion object {
        fun decode(s: String?): SyncRecord? = s?.split(';')?.takeIf { it.size == 3 }?.let { p ->
            val fp = FileFingerprint.decode(p[0]) ?: return null
            SyncRecord(fp, p[1].toLongOrNull() ?: -1L, p[2].toLongOrNull() ?: -1L)
        }
    }
}

/**
 * Decides what `DocumentRepository.localFile` does when a content URI that already has a working copy is opened
 * again (audit DI-02 / DI-20 / DI-01). The rule: a working copy that holds edits the source does not have is never
 * overwritten.
 */
object ImportDecision {
    enum class Action {
        /** Open the existing working copy as is. */
        REUSE,
        /** The source changed and the working copy has no unsynced edits: copy the source over it. */
        REFRESH,
        /** Both changed (or we cannot tell): keep the working copy untouched, import the source as a new file. */
        IMPORT_AS_NEW,
    }

    /**
     * @param record what both sides looked like when last in sync; null for working copies made before records existed.
     * @param localNow the working copy's current fingerprint.
     * @param srcSize / [srcMtime] the source as the provider reports it now (-1 = unknown).
     * @param writeBackPending a write-back to the source was started and never finished (crash / kill mid-write):
     *        the source may be truncated by us, the working copy is the authority.
     */
    fun decide(record: SyncRecord?, localNow: FileFingerprint, srcSize: Long, srcMtime: Long, writeBackPending: Boolean): Action {
        if (writeBackPending) return Action.REUSE
        if (record == null) {
            // Legacy copy: we do not know whether it was edited. Same size -> as before, reuse. Different size ->
            // it may hold edits (read-only sources keep the old size), so never overwrite it.
            return if (srcSize < 0 || srcSize == localNow.size) Action.REUSE else Action.IMPORT_AS_NEW
        }
        val srcChanged = (srcSize >= 0 && record.srcSize >= 0 && srcSize != record.srcSize) ||
            (srcMtime > 0 && record.srcMtime > 0 && srcMtime != record.srcMtime)
        if (!srcChanged) return Action.REUSE
        val localEdited = !localNow.sameContent(record.local)
        return if (localEdited) Action.IMPORT_AS_NEW else Action.REFRESH
    }
}

/** "Pick the original again" (audit DI-13): does the picked document look like the one the working copy came from? */
object OriginCheck {
    /**
     * True when the names match (case-insensitively) and, when both sizes are known, the picked document still has the
     * size recorded at the last sync. Anything else (including no recorded name) must be confirmed by the user.
     */
    fun looksLikeOriginal(recordedName: String?, recordedSize: Long, pickedName: String?, pickedSize: Long): Boolean {
        if (recordedName.isNullOrBlank() || pickedName.isNullOrBlank()) return false
        if (!recordedName.trim().equals(pickedName.trim(), ignoreCase = true)) return false
        return recordedSize < 0 || pickedSize < 0 || recordedSize == pickedSize
    }
}
