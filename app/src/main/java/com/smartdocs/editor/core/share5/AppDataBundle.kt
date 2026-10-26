package com.smartdocs.editor.core.share5

import android.content.Context
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.data.CustomStamp
import com.smartdocs.editor.core.data.DocTag
import com.smartdocs.editor.core.data.FillProfileField
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.data.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * App data that follows the user between devices (O4): signatures and initials, custom stamps, the fill profile,
 * tags (and which documents carry them), favourites and the author name. A ZIP with `manifest.json` + images.
 * Import only MERGES (nothing local is deleted); documents are matched by file name + size.
 */
object AppDataBundle {
    const val VERSION = 1

    data class Summary(val signatures: Int, val stamps: Int, val fields: Int, val tags: Int, val favorites: Int)
    data class Built(val file: File, val hash: String, val summary: Summary)

    private fun app(c: Context) = c.applicationContext as SmartDocsApp
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(f: File) = runCatching { sha(f.readBytes()) }.getOrDefault("")

    /** Writes the bundle to [out]. [Built.hash] covers the content only (not the time), so unchanged data gives the same hash. */
    suspend fun export(c: Context, out: File): Built = withContext(Dispatchers.IO) {
        val a = app(c); val db = a.db
        val sigs = db.signatures().all().first().filter { File(it.path).exists() }
        val stamps = db.stamps().all().first()
        val fields = db.fillProfile().all().first()
        val tags = db.tags().tags().first()
        val favs = db.docs().favorites().first()
        val m = JSONObject().put("version", VERSION)
        val images = ArrayList<Pair<String, File>>()
        m.put("signatures", JSONArray().apply {
            sigs.sortedBy { it.created }.forEachIndexed { i, s -> val n = "sig/$i.${File(s.path).extension.ifEmpty { "png" }}"; images += n to File(s.path)
                put(JSONObject().put("file", n).put("initials", s.isInitials).put("sha", sha(File(s.path)))) }
        })
        m.put("stamps", JSONArray().apply {
            stamps.sortedBy { it.id }.forEachIndexed { i, s ->
                val o = JSONObject().put("text", s.text ?: "").put("color", s.color)
                s.imagePath?.let(::File)?.takeIf { it.exists() }?.let { f -> val n = "stamp/$i.${f.extension.ifEmpty { "png" }}"; images += n to f; o.put("file", n).put("sha", sha(f)) }
                put(o)
            }
        })
        m.put("fill", JSONArray().apply { fields.sortedBy { it.key }.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("value", it.value).put("order", it.order)) } })
        m.put("tags", JSONArray().apply { tags.sortedBy { it.name }.forEach { put(JSONObject().put("name", it.name).put("color", it.color)) } })
        m.put("docTags", JSONArray().apply {
            tags.sortedBy { it.name }.forEach { t -> db.tags().docsForTag(t.id).first().sortedBy { it.name }.forEach { d -> put(JSONObject().put("tag", t.name).put("name", d.name).put("size", d.size)) } }
        })
        m.put("favorites", JSONArray().apply { favs.sortedBy { it.name }.forEach { put(JSONObject().put("name", it.name).put("size", it.size)) } })
        val author = a.settings.settings.first().authorName
        m.put("author", author).put("collabAuthor", CollabStore.authorName(c))
        val hash = sha(m.toString().toByteArray())
        m.put("created", System.currentTimeMillis()).put("hash", hash)
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(m.toString(2).toByteArray()); z.closeEntry()
            images.forEach { (n, f) -> z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        Built(out, hash, Summary(sigs.size, stamps.size, fields.size, tags.size, favs.size))
    }

    /** Hash stored inside a bundle (null when it is not a bundle). */
    fun hashOf(zip: File): String? = runCatching { ZipFile(zip).use { z -> JSONObject(z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()).optString("hash").ifEmpty { null } } }.getOrNull()

    /** Merges [zip] into this device. Returns what was added. Throws IllegalArgumentException for a file that is not a bundle. */
    suspend fun import(c: Context, zip: File): Summary = withContext(Dispatchers.IO) {
        val a = app(c); val db = a.db
        ZipFile(zip).use { z ->
            val me = z.getEntry("manifest.json") ?: throw IllegalArgumentException("not a SmartDocs data bundle")
            val m = JSONObject(z.getInputStream(me).readBytes().decodeToString())
            if (m.optInt("version") > VERSION) throw IllegalArgumentException("bundle from a newer app version")
            fun bytes(name: String): ByteArray? = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            var nSig = 0; var nStamp = 0; var nField = 0; var nTag = 0; var nFav = 0

            val haveSig = db.signatures().all().first().map { sha(File(it.path)) }.toMutableSet()
            m.optJSONArray("signatures")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val data = bytes(o.optString("file")) ?: continue
                val h = sha(data); if (h in haveSig) continue
                val f = File(a.documents.signaturesDir, "sync_${System.currentTimeMillis()}_$i.${o.optString("file").substringAfterLast('.', "png")}")
                f.writeBytes(data); db.signatures().insert(SavedSignature(path = f.absolutePath, isInitials = o.optBoolean("initials"))); haveSig += h; nSig++
            } }

            val stampsDir = File(c.filesDir, "stamps").apply { mkdirs() }
            val haveStamp = db.stamps().all().first().map { "${it.text.orEmpty()}|${it.color}|${it.imagePath?.let { p -> sha(File(p)) }.orEmpty()}" }.toMutableSet()
            m.optJSONArray("stamps")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val data = o.optString("file").takeIf { it.isNotEmpty() }?.let(::bytes)
                val key = "${o.optString("text")}|${o.optLong("color")}|${data?.let(::sha).orEmpty()}"
                if (key in haveStamp) continue
                val path = data?.let { d -> File(stampsDir, "sync_${System.currentTimeMillis()}_$i.${o.optString("file").substringAfterLast('.', "png")}").also { it.writeBytes(d) }.absolutePath }
                db.stamps().insert(CustomStamp(text = o.optString("text").ifEmpty { null }, imagePath = path, color = o.optLong("color"))); haveStamp += key; nStamp++
            } }

            val fields = db.fillProfile().all().first().associateBy { it.key }
            m.optJSONArray("fill")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val k = o.optString("key"); if (k.isEmpty()) continue
                val local = fields[k]
                if (local == null || (local.value.isBlank() && o.optString("value").isNotBlank())) {
                    db.fillProfile().upsert(FillProfileField(k, o.optString("label"), o.optString("value"), o.optInt("order"))); nField++
                }
            } }

            val tagNames = db.tags().tags().first().map { it.name }.toMutableSet()
            m.optJSONArray("tags")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val n = o.optString("name"); if (n.isEmpty() || n in tagNames) continue
                db.tags().insert(Tag(name = n, color = o.optLong("color"))); tagNames += n; nTag++
            } }

            val recents = db.docs().recents().first()
            fun doc(name: String, size: Long) = recents.firstOrNull { it.name == name && it.size == size }
            val tagIds = db.tags().tags().first().associate { it.name to it.id }
            m.optJSONArray("docTags")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = doc(o.optString("name"), o.optLong("size")) ?: continue
                val id = tagIds[o.optString("tag")] ?: continue
                db.tags().attach(DocTag(d.uri, id))
            } }
            m.optJSONArray("favorites")?.let { arr -> for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = doc(o.optString("name"), o.optLong("size")) ?: continue
                if (!d.favorite) { db.docs().setFavorite(d.uri, true); nFav++ }
            } }

            m.optString("author").takeIf { it.isNotBlank() }?.let { name ->
                if (a.settings.settings.first().authorName.isBlank()) a.settings.update { it.copy(authorName = name) }
            }
            m.optString("collabAuthor").takeIf { it.isNotBlank() && CollabStore.authorName(c).isBlank() }?.let { CollabStore.setAuthorName(c, it) }
            Summary(nSig, nStamp, nField, nTag, nFav)
        }
    }
}
