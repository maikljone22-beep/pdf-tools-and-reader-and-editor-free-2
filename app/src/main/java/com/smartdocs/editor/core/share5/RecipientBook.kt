package com.smartdocs.editor.core.share5

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * fc-sync (Foxit §37 "To / Cc auto-complete"): e-mail addresses used before, offered while typing a recipient.
 * Kept on the device only (SharedPreferences), most used / most recent first, at most [MAX] addresses.
 * The list logic is pure and unit-tested (RecipientBookTest).
 */
object RecipientBook {
    const val MAX = 200

    data class Entry(val email: String, val uses: Int, val last: Long)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fcy_recipients", Context.MODE_PRIVATE)

    fun entries(c: Context): List<Entry> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getString("e"), it.optInt("n", 1), it.optLong("t")) } }
    }.getOrDefault(emptyList())

    /** Remembers the addresses of a message that was sent. */
    fun remember(c: Context, emails: Collection<String>, now: Long = System.currentTimeMillis()) = synchronized(this) {
        val list = add(entries(c), emails, now)
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("e", it.email).put("n", it.uses).put("t", it.last)) }
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    fun clear(c: Context) = prefs(c).edit().remove("list").apply()

    /** Suggestions for the address being typed at the end of [text] (addresses already in [text] are left out). */
    fun suggest(c: Context, text: String, max: Int = 5): List<String> = rank(entries(c), lastToken(text), tokens(text).dropLast(1).toSet(), max)

    // ---- pure ----

    private val SPLIT = Regex("""[,;\s،]+""")

    fun normalize(email: String): String = email.trim().trim('<', '>').lowercase()

    fun valid(email: String): Boolean = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(email)

    /** Every address-like token of [text], in order. */
    fun tokens(text: String): List<String> = text.split(SPLIT).map { normalize(it) }.filter { it.isNotEmpty() }

    /** The token being typed (after the last separator), "" right after a separator. */
    fun lastToken(text: String): String = if (text.isEmpty() || SPLIT.matches(text.last().toString())) "" else text.split(SPLIT).last().trim().lowercase()

    /** [text] with its last token replaced by [email] and a separator added, ready for the next address. */
    fun complete(text: String, email: String): String {
        val cut = text.length - (if (lastToken(text).isEmpty()) 0 else text.split(SPLIT).last().length)
        return text.substring(0, cut.coerceIn(0, text.length)) + email + ", "
    }

    fun add(list: List<Entry>, emails: Collection<String>, now: Long, max: Int = MAX): List<Entry> {
        val map = LinkedHashMap<String, Entry>(); list.forEach { map[it.email] = it }
        for (e in emails.map(::normalize).filter(::valid).distinct()) {
            val old = map[e]
            map[e] = Entry(e, (old?.uses ?: 0) + 1, now)
        }
        return map.values.sortedWith(compareByDescending<Entry> { it.last }.thenByDescending { it.uses }).take(max)
    }

    /** Matches of [prefix] (start of the address or of its name / domain part), most used first, then most recent. */
    fun rank(list: List<Entry>, prefix: String, exclude: Set<String>, max: Int): List<String> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return list.asSequence().filter { it.email !in exclude && it.email != p }
            .filter { it.email.startsWith(p) || it.email.split('@', '.', '_', '-').any { part -> part.startsWith(p) } }
            .sortedWith(compareByDescending<Entry> { it.email.startsWith(p) }.thenByDescending { it.uses }.thenByDescending { it.last })
            .map { it.email }.take(max).toList()
    }
}

/**
 * fc-sync (Foxit §37 "send comments as FDF when the file is larger than N MB"): when a reviewer sends an e-mail review
 * back, a large PDF is replaced by an FDF holding only the comments (the sender merges it into the master copy).
 */
object FdfRule {
    val LIMITS_MB = listOf(1, 2, 5, 10, 20)
    const val DEFAULT_MB = 5

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fcy_fdf_rule", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun limitMb(c: Context): Int = prefs(c).getInt("mb", DEFAULT_MB).let { if (it in LIMITS_MB) it else DEFAULT_MB }
    fun set(c: Context, on: Boolean, mb: Int) = prefs(c).edit().putBoolean("on", on).putInt("mb", mb).apply()

    /** Pure: attach the comments as FDF instead of the PDF of [sizeBytes]? */
    fun useFdf(sizeBytes: Long, enabled: Boolean, limitMb: Int): Boolean = enabled && sizeBytes > limitMb * 1024L * 1024L
}
