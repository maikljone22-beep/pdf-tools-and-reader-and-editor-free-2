package com.smartdocs.editor.core.share5

import android.content.Context
import com.smartdocs.editor.core.annot.CommentExchange
import com.smartdocs.editor.core.collab.CollabAnnots
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * E-mail based review (SHR-3). [prepare] makes a copy that carries an id twice — trailer /ID and /Info SDReviewId —
 * and is kept as the "master" in Reviews/Email. When a reviewer sends back an annotated PDF, an XFDF or an FDF,
 * [match] finds the master and [merge] adds the returned comments to it (each returned file is merged once).
 */
object EmailReview {
    const val INFO_KEY = "SDReviewId"

    data class Record(val reviewId: String, val idHex: String, val fileName: String, val masterPath: String, val subject: String,
                      val recipients: String, val sentAt: Long, val merged: List<String> = emptyList()) {
        fun toSent() = EmailMatch.Sent(reviewId, idHex, fileName, sentAt)
        fun toJson(): JSONObject = JSONObject().put("rid", reviewId).put("id", idHex).put("file", fileName).put("path", masterPath).put("subject", subject)
            .put("to", recipients).put("sent", sentAt).put("merged", JSONArray(merged))
        companion object {
            fun from(o: JSONObject) = Record(o.getString("rid"), o.optString("id"), o.optString("file"), o.getString("path"), o.optString("subject"),
                o.optString("to"), o.optLong("sent"), o.optJSONArray("merged")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        }
    }

    data class Found(val record: Record, val how: EmailMatch.How, val authors: List<String>, val fingerprint: String, val kind: Kind)
    enum class Kind { PDF, XFDF, FDF }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share5_email", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(CollabStore.reviewsDir(c), "Email").apply { mkdirs() }

    fun records(c: Context): List<Record> {
        val a = runCatching { JSONArray(prefs(c).getString("records", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { runCatching { Record.from(a.getJSONObject(it)) }.getOrNull() }
    }
    private fun saveRecords(c: Context, list: List<Record>) = prefs(c).edit().putString("records", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    fun remove(c: Context, rid: String) = synchronized(this) { saveRecords(c, records(c).filterNot { it.reviewId == rid }) }

    private fun randomHex(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Writes the id-stamped master copy and remembers it. Returns the file to attach to the e-mail. */
    suspend fun prepare(c: Context, src: File, password: String?, subject: String, recipients: String): Record = withContext(Dispatchers.IO) {
        val rid = "er" + randomHex(8).lowercase()
        val idHex = randomHex(16)
        val name = ShareBuilder.safe(src.name.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" })
        var out = File(dir(c), name); var n = 2
        while (out.exists()) { out = File(dir(c), "${name.removeSuffix(".pdf")} ($n).pdf"); n++ }
        PdfOps.load(src, password).use { doc ->
            if (password != null) doc.isAllSecurityToBeRemoved = true   // the reviewer must be able to open and annotate it
            CollabAnnots.ensureNames(doc)
            doc.documentInformation.setCustomMetadataValue(INFO_KEY, rid)
            doc.document.documentID = COSArray().apply { add(COSString(hexBytes(idHex))); add(COSString(hexBytes(idHex))) }
            doc.save(out)
        }
        val rec = Record(rid, idHex, out.name, out.absolutePath, subject, recipients, System.currentTimeMillis())
        synchronized(this@EmailReview) { saveRecords(c, listOf(rec) + records(c)) }
        rec
    }

    fun kindOf(f: File): Kind? {
        val head = runCatching { f.inputStream().use { s -> ByteArray(512).let { b -> String(b, 0, s.read(b).coerceAtLeast(0), Charsets.ISO_8859_1) } } }.getOrDefault("")
        return when {
            head.startsWith("%PDF") -> Kind.PDF
            head.startsWith("%FDF") -> Kind.FDF
            head.trimStart().startsWith("<") && head.contains("xfdf", true) -> Kind.XFDF
            f.extension.equals("xfdf", true) -> Kind.XFDF
            f.extension.equals("fdf", true) -> Kind.FDF
            else -> null
        }
    }

    private fun fingerprint(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Is [f] a returned copy of something this device sent? null when it is not, when it is the master itself, or when
     * this exact file was merged before.
     */
    suspend fun match(c: Context, f: File): Found? = withContext(Dispatchers.IO) {
        val recs = records(c)
        if (recs.isEmpty() || !f.exists()) return@withContext null
        if (recs.any { File(it.masterPath).absolutePath == f.absolutePath }) return@withContext null
        val kind = kindOf(f) ?: return@withContext null
        var authors = emptyList<String>()
        val inc = when (kind) {
            Kind.PDF -> runCatching {
                PDDocument.load(f).use { doc ->
                    val id = (doc.document.documentID?.getObject(0) as? COSString)?.bytes?.joinToString("") { "%02X".format(it) }
                    authors = doc.pages.flatMap { p -> runCatching { p.annotations }.getOrDefault(emptyList()) }
                        .mapNotNull { (it as? PDAnnotationMarkup)?.titlePopup?.takeIf { t -> t.isNotBlank() } }.distinct()
                    EmailMatch.Incoming(doc.documentInformation?.getCustomMetadataValue(INFO_KEY), id, f.name)
                }
            }.getOrNull() ?: return@withContext null   // encrypted / unreadable: not ours
            Kind.XFDF -> f.readText().let { x ->
                authors = Regex("""title\s*=\s*"([^"]+)"""").findAll(x).map { it.groupValues[1] }.distinct().toList()
                EmailMatch.fromXfdf(x).let { if (it.href == null) it.copy(href = f.name) else it }
            }
            Kind.FDF -> EmailMatch.fromFdf(String(f.readBytes(), Charsets.ISO_8859_1)).let { if (it.href == null) it.copy(href = f.name) else it }
        }
        val m = EmailMatch.match(recs.map { it.toSent() }, inc) ?: return@withContext null
        val rec = recs.first { it.reviewId == m.sent.reviewId }
        if (!File(rec.masterPath).exists()) return@withContext null
        val fp = fingerprint(f)
        if (fp in rec.merged) return@withContext null
        val me = CollabStore.authorName(c)
        Found(rec, m.how, authors.filter { it != me }, fp, kind)
    }

    /** Adds the returned comments to the master copy. Returns (comments added, master file). */
    suspend fun merge(c: Context, found: Found, incoming: File): Pair<Int, File> = withContext(Dispatchers.IO) {
        val master = File(found.record.masterPath)
        val tmp = File(master.parentFile, ".${master.name}.merge")
        val n = when (found.kind) {
            Kind.PDF -> CommentExchange.merge(c, master, tmp, null, incoming)
            Kind.XFDF, Kind.FDF -> CommentExchange.importFile(c, master, tmp, null, incoming)
        }
        if (!tmp.renameTo(master)) { tmp.copyTo(master, overwrite = true); tmp.delete() }
        synchronized(this@EmailReview) {
            saveRecords(c, records(c).map { if (it.reviewId == found.record.reviewId) it.copy(merged = (it.merged + found.fingerprint).takeLast(200)) else it })
        }
        n to master
    }

    /** Remembers [found] as handled without merging (the user said "no"). */
    fun dismiss(c: Context, found: Found) = synchronized(this) {
        saveRecords(c, records(c).map { if (it.reviewId == found.record.reviewId) it.copy(merged = (it.merged + found.fingerprint).takeLast(200)) else it })
    }
}
