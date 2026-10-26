package com.smartdocs.editor.core.share5

import android.content.Context
import android.util.Base64
import com.smartdocs.editor.core.collab.CollabAnnots
import org.json.JSONObject

/**
 * Per-review privacy choice of this reviewer (shared review, SHR-4): consent given, and whether comments are
 * published anonymously. [author] and [outgoing] are called by `CollabStore.publish` for every outgoing batch.
 */
object ReviewConsent {
    const val VERSION = 1

    data class State(val consented: Boolean, val anonymous: Boolean, val time: Long)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share5_consent", Context.MODE_PRIVATE)

    fun state(c: Context, reviewId: String): State? {
        val p = prefs(c)
        if (!p.contains("t_$reviewId")) return null
        return State(p.getBoolean("c_$reviewId", false), p.getBoolean("a_$reviewId", false), p.getLong("t_$reviewId", 0L))
    }

    fun set(c: Context, reviewId: String, consented: Boolean, anonymous: Boolean) {
        prefs(c).edit().putBoolean("c_$reviewId", consented).putBoolean("a_$reviewId", anonymous)
            .putLong("t_$reviewId", System.currentTimeMillis()).apply()
    }

    fun isAnonymous(c: Context, reviewId: String): Boolean = prefs(c).getBoolean("a_$reviewId", false)

    /** Stable pseudonym "مراجع NN" derived from this installation's participant id. */
    fun pseudonym(participant: String): String {
        val n = (participant.hashCode() and 0x7fffffff) % 90 + 10
        return "مراجع $n"
    }

    /** Author name to publish under for [reviewId]. */
    fun author(c: Context, reviewId: String, real: String): String =
        if (isAnonymous(c, reviewId)) pseudonym(com.smartdocs.editor.core.collab.CollabStore.participantId(c)) else real

    /** Rewrites the /T (author) entry of an outgoing comment when the reviewer chose anonymity. */
    fun outgoing(c: Context, reviewId: String, comment: CollabAnnots.Comment): CollabAnnots.Comment {
        if (!isAnonymous(c, reviewId)) return comment
        val name = pseudonym(com.smartdocs.editor.core.collab.CollabStore.participantId(c))
        val data = JSONObject(comment.data.toString())
        data.optJSONObject("d")?.let { d ->
            if (d.has("T")) d.put("T", JSONObject().put("s", Base64.encodeToString(pdfText(name), Base64.NO_WRAP)))
            d.optJSONObject("Popup")?.optJSONObject("d")?.let { if (it.has("T")) it.put("T", JSONObject().put("s", Base64.encodeToString(pdfText(name), Base64.NO_WRAP))) }
        }
        return comment.copy(author = name, data = data)
    }

    /** PDF text string bytes: UTF-16BE with a byte-order mark (works for Arabic). */
    fun pdfText(s: String): ByteArray = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
}
