package com.smartdocs.editor.core.collab

import android.util.Base64
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Comment exchange format: every annotation is sent as the JSON form of its PDF dictionary
 * (the same information XFDF carries, plus the appearance stream so it looks identical everywhere).
 *
 * Encoding: dictionary → {"d":{key:value}}, stream → {"st":{dict},"raw":base64}, name → {"n":"…"},
 * string → {"s":base64 bytes}, array → [...], numbers/booleans/null as JSON. Indirect references are inlined;
 * /P, /Parent, /Popup and /IRT are dropped (they point into the sender's file).
 */
object CollabAnnots {
    data class Comment(val nm: String, val page: Int, val author: String, val subtype: String, val contents: String, val data: JSONObject, val hash: String)

    private val SKIP = setOf("P", "Parent", "Popup", "IRT")
    private val IGNORED_SUBTYPES = setOf("Link", "Widget", "Popup")
    private const val MAX_BYTES = 300 * 1024

    // ------------------------------------------------------------------ COS → JSON

    private fun enc(b: COSBase?, seen: IdentityHashMap<COSBase, Boolean>, depth: Int, top: Boolean = false, skipAp: Boolean = false): Any {
        if (b == null || depth > 24) return JSONObject.NULL
        val v = if (b is COSObject) b.getObject() ?: return JSONObject.NULL else b
        return when (v) {
            is COSName -> JSONObject().put("n", v.name)
            is COSString -> JSONObject().put("s", Base64.encodeToString(v.bytes, Base64.NO_WRAP))
            is COSInteger -> v.longValue()
            is COSFloat -> v.floatValue().toDouble()
            is COSBoolean -> v.value
            is COSNull -> JSONObject.NULL
            is COSArray -> JSONArray().apply { for (i in 0 until v.size()) put(enc(v.get(i), seen, depth + 1)) }
            is COSStream -> {
                if (seen.put(v, true) != null) return JSONObject.NULL
                val raw = v.createRawInputStream().use { it.readBytes() }
                JSONObject().put("st", dict(v, seen, depth, top = false, skipAp = false)).put("raw", Base64.encodeToString(raw, Base64.NO_WRAP))
                    .also { seen.remove(v) }
            }
            is COSDictionary -> {
                if (seen.put(v, true) != null) return JSONObject.NULL
                JSONObject().put("d", dict(v, seen, depth, top, skipAp)).also { seen.remove(v) }
            }
            else -> JSONObject.NULL
        }
    }

    private fun dict(d: COSDictionary, seen: IdentityHashMap<COSBase, Boolean>, depth: Int, top: Boolean, skipAp: Boolean): JSONObject {
        val o = JSONObject()
        for ((k, value) in d.entrySet()) {
            val key = k.name
            if (top && (key in SKIP || (skipAp && key == "AP"))) continue
            if (d is COSStream && (key == "Length")) continue
            o.put(key, enc(value, seen, depth + 1))
        }
        return o
    }

    fun encode(an: PDAnnotation): JSONObject {
        val full = enc(an.cosObject, IdentityHashMap(), 0, top = true) as JSONObject
        if (full.toString().length <= MAX_BYTES) return full
        return enc(an.cosObject, IdentityHashMap(), 0, top = true, skipAp = true) as JSONObject // too big: let viewers regenerate the look
    }

    // ------------------------------------------------------------------ JSON → COS

    private fun dec(v: Any?): COSBase = when (v) {
        null, JSONObject.NULL -> COSNull.NULL
        is Boolean -> COSBoolean.getBoolean(v)
        is Int -> COSInteger.get(v.toLong())
        is Long -> COSInteger.get(v)
        is Double -> COSFloat(v.toFloat())
        is Float -> COSFloat(v)
        is Number -> COSFloat(v.toFloat())
        is JSONArray -> COSArray().apply { for (i in 0 until v.length()) add(dec(v.opt(i))) }
        is JSONObject -> when {
            v.has("n") -> COSName.getPDFName(v.getString("n"))
            v.has("s") -> COSString(Base64.decode(v.getString("s"), Base64.NO_WRAP))
            v.has("st") -> {
                val s = COSStream()
                s.createRawOutputStream().use { it.write(Base64.decode(v.optString("raw"), Base64.NO_WRAP)) }
                val d = v.getJSONObject("st")
                d.keys().forEach { k -> if (k != "Length") s.setItem(COSName.getPDFName(k), dec(d.opt(k))) }
                s
            }
            v.has("d") -> COSDictionary().apply { val d = v.getJSONObject("d"); d.keys().forEach { k -> setItem(COSName.getPDFName(k), dec(d.opt(k))) } }
            else -> COSNull.NULL
        }
        else -> COSString(v.toString())
    }

    fun decode(data: JSONObject): COSDictionary = dec(data) as? COSDictionary ?: throw IllegalArgumentException("not a dictionary")

    // ------------------------------------------------------------------ document level

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun reviewable(an: PDAnnotation) = an.subtype !in IGNORED_SUBTYPES

    /**
     * Gives every reviewable annotation a unique /NM (needed to merge by id). Returns true when [doc] changed
     * (the caller must save it).
     */
    fun ensureNames(doc: PDDocument): Boolean {
        var changed = false
        doc.pages.forEach { page ->
            page.annotations.filter(::reviewable).forEach { an ->
                if (an.annotationName.isNullOrBlank()) { an.annotationName = "sd-" + UUID.randomUUID().toString(); changed = true }
            }
        }
        return changed
    }

    /** All reviewable annotations of [doc] (call [ensureNames] first). */
    fun read(doc: PDDocument): List<Comment> {
        val out = ArrayList<Comment>()
        doc.pages.forEachIndexed { pi, page ->
            page.annotations.filter(::reviewable).forEach { an ->
                val nm = an.annotationName ?: return@forEach
                val data = encode(an)
                out += Comment(nm, pi, (an as? PDAnnotationMarkup)?.titlePopup.orEmpty(), an.subtype ?: "", an.contents.orEmpty(), data, sha(data.toString()))
            }
        }
        return out
    }

    /**
     * Applies remote comments: inserts or replaces by /NM, removes tombstoned ones. Returns the number of changes.
     */
    fun apply(doc: PDDocument, upserts: List<Pair<Int, JSONObject>>, deletes: Set<String>): Int {
        var changes = 0
        val byPage = upserts.groupBy({ it.first }, { it.second })
        doc.pages.forEachIndexed { pi, page ->
            val list = page.annotations.toMutableList()
            var touched = false
            if (deletes.isNotEmpty()) {
                val before = list.size
                list.removeAll { it.annotationName != null && it.annotationName in deletes }
                if (list.size != before) { changes += before - list.size; touched = true }
            }
            byPage[pi]?.forEach { data ->
                val an = runCatching { PDAnnotation.createAnnotation(decode(data)) }.getOrNull() ?: return@forEach
                an.setPage(page)
                val nm = an.annotationName
                val idx = if (nm != null) list.indexOfFirst { it.annotationName == nm } else -1
                if (idx >= 0) list[idx] = an else list += an
                changes++; touched = true
            }
            if (touched) page.setAnnotations(list)
        }
        return changes
    }
}
