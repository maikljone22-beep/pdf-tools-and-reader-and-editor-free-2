package com.smartdocs.editor.core.connected

import android.content.Context
import com.smartdocs.editor.core.esign.EsignComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the reader should know about a connected document when it opens.
 * [status]: ok | pending | blocked | revoked | gone | offline | unknown.  [password] is set for protected files the reader may open.
 */
data class ConnectedStatus(
    val docId: String, val title: String, val owner: String, val status: String, val protected: Boolean,
    val newer: ConnVersion?, val password: String?, val offline: Boolean, val offlineUntil: Long, val isOwner: Boolean,
    /** fc-sync: the owner's open message ("" = none). */
    val message: String = "",
) {
    /** Protected file that cannot be opened now. */
    val locked: Boolean get() = protected && password == null
    /** Something to show in the banner. */
    val noteworthy: Boolean get() = newer != null || status != "ok" || offline || message.isNotBlank()
}

object ConnectedDocs {
    private const val DAY = 24L * 3600 * 1000
    private const val MAX_HASH = 100L * 1024 * 1024

    /** Fast local check (no network): the id marker, if any. */
    fun markOf(file: File): ConnMarker.Mark? = runCatching { ConnMarker.find(file) }.getOrNull()

    private fun readerName(c: Context): String = runCatching {
        c.getSharedPreferences("collab", Context.MODE_PRIVATE).getString("author", null)
    }.getOrNull().orEmpty()

    /** Contacts the server (short timeouts); falls back to the offline key cache. Null when the file is not connected. */
    suspend fun checkOnOpen(context: Context, file: File): ConnectedStatus? = withContext(Dispatchers.IO) {
        if (com.smartdocs.editor.core.data.FcsPrefs.connectedOff) return@withContext null   // fc-settings: ConnectedPDF turned off
        val mark = markOf(file) ?: return@withContext null
        statusById(context, mark.docId, mark.protected, file)
    }

    suspend fun statusById(context: Context, docId: String, protected: Boolean, file: File?): ConnectedStatus = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        ConnStore.get(c, docId)?.let { own ->
            return@withContext ConnectedStatus(docId, own.title, "", "ok", protected, null, if (protected) ConnKeyCache.get(c, docId) else null,
                offline = false, offlineUntil = 0L, isOwner = true)
        }
        val sha = file?.takeIf { it.length() <= MAX_HASH }?.let { runCatching { EsignComposer.sha256(it) }.getOrNull() }.orEmpty()
        try {
            if (!ConnectedConfig.enabled) throw java.io.IOException("not configured")
            val a = ConnectedClient.open(docId, ConnStore.readerId(c), readerName(c), sha)
            ConnStore.setLastStatus(c, docId, a.status)
            var pw: String? = null
            if (a.allowed && a.key != null) { ConnKeyCache.put(c, docId, a.key, System.currentTimeMillis() + a.offlineDays.coerceAtLeast(0) * DAY); pw = a.key }
            if (!a.allowed) ConnKeyCache.remove(c, docId)
            ConnectedStatus(docId, a.title, a.owner, a.status, protected, a.latest?.takeIf { !a.isLatest }, if (a.allowed) pw else null,
                offline = false, offlineUntil = 0L, isOwner = false, message = a.message)
        } catch (e: Exception) {
            if (ConnectedClient.isGone(e)) { ConnKeyCache.remove(c, docId); return@withContext ConnectedStatus(docId, "", "", "gone", protected, null, null, false, 0L, false) }
            val cached = ConnKeyCache.get(c, docId)
            val last = ConnStore.lastStatus(c, docId)
            ConnectedStatus(docId, "", "", if (last == "blocked" || last == "revoked") last else "offline", protected, null,
                if (last == "blocked" || last == "revoked") null else cached, offline = true, offlineUntil = ConnKeyCache.expiry(c, docId), isOwner = false)
        }
    }

    /**
     * Registers [src] as a connected document: server id (+ key in protected mode), stamped copy in [outDir],
     * first version published with its file (so readers can download updates).
     */
    suspend fun register(context: Context, src: File, outDir: File, title: String, owner: String, protected: Boolean,
                         requireApproval: Boolean, offlineDays: Int, uploadFile: Boolean, srcPassword: String? = null): OwnedDoc = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        val reg = ConnectedClient.register(title, owner, protected, requireApproval, offlineDays)
        val key = if (protected) reg.key ?: throw IllegalStateException("server returned no key") else null
        val base = src.nameWithoutExtension
        var out = File(outDir, "$base (connected).pdf"); var n = 2
        while (out.exists()) { out = File(outDir, "$base (connected $n).pdf"); n++ }
        ConnectedStamp.stamp(src, out, reg.id, key, srcPassword)
        if (key != null) ConnKeyCache.put(c, reg.id, key, 0L)
        val doc = OwnedDoc(reg.id, title, reg.ownerKey, protected, out.absolutePath, System.currentTimeMillis(), 1)
        ConnStore.upsert(c, doc)
        runCatching { ConnectedClient.publishVersion(reg.id, reg.ownerKey, EsignComposer.sha256(out), out.length(), "", if (uploadFile) out else null) }
        doc
    }

    /** Stamps [newSource] with the same id (and key) and publishes it as the next version. Returns the stamped file. */
    suspend fun publishVersion(context: Context, doc: OwnedDoc, newSource: File, outDir: File, note: String, uploadFile: Boolean): File = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        val key = if (doc.protected) ConnKeyCache.get(c, doc.id) ?: throw IllegalStateException("owner key missing on this device") else null
        val srcPw = if (markOf(newSource)?.docId == doc.id) key else null
        val out = File(outDir, newSource.nameWithoutExtension.substringBefore(" (connected") + " (connected v${doc.lastVersion + 1}).pdf")
        ConnectedStamp.stamp(newSource, out, doc.id, key, srcPw)
        val n = ConnectedClient.publishVersion(doc.id, doc.ownerKey, EsignComposer.sha256(out), out.length(), note, if (uploadFile) out else null)
        ConnStore.upsert(c, doc.copy(localPath = out.absolutePath, lastVersion = if (n > 0) n else doc.lastVersion + 1))
        out
    }

    /**
     * fc-sync (Foxit §33 "Clone document"): a new connected document from [doc]'s current copy: new id (and key), no
     * readers, no history; the original stays as it is. Settings are copied from [info] when given.
     */
    suspend fun clone(context: Context, doc: OwnedDoc, outDir: File, title: String, owner: String, info: ConnDocInfo?, uploadFile: Boolean): OwnedDoc {
        val src = File(doc.localPath).takeIf { it.isFile } ?: throw IllegalStateException("local copy missing")
        val srcPw = if (doc.protected) ConnKeyCache.get(context.applicationContext, doc.id) ?: throw IllegalStateException("owner key missing on this device") else null
        val copy = register(context, src, outDir, title, owner, doc.protected, info?.requireApproval ?: false, info?.offlineDays ?: 7, uploadFile, srcPw)
        if (!info?.openMessage.isNullOrBlank()) runCatching {
            withContext(Dispatchers.IO) { ConnectedClient.settings(copy.id, copy.ownerKey, info!!.requireApproval, info.offlineDays, false, info.openMessage) }
        }
        return copy
    }

    suspend fun downloadLatest(context: Context, docId: String, outDir: File, title: String): File = withContext(Dispatchers.IO) {
        val safe = title.ifBlank { docId }.replace(Regex("""[\\/:*?"<>|]"""), "_")
        var out = File(outDir, "$safe.pdf"); var n = 2
        while (out.exists()) { out = File(outDir, "$safe ($n).pdf"); n++ }
        ConnectedClient.downloadLatest(docId, ConnStore.readerId(context), out)
        out
    }

    suspend fun requestAccess(context: Context, docId: String, message: String) = withContext(Dispatchers.IO) {
        ConnectedClient.requestAccess(docId, ConnStore.readerId(context), readerName(context), message)
    }
}
