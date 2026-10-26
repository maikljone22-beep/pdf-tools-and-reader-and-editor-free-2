package com.smartdocs.editor.core.redact

import android.content.Context
import com.smartdocs.editor.core.search.PatternKind
import org.json.JSONArray
import org.json.JSONObject

/** A saved redaction setup: what to search for and how marks look. */
data class RedactProfile(
    val name: String,
    val words: List<String> = emptyList(),
    val patterns: Set<PatternKind> = emptySet(),
    val regexes: List<String> = emptyList(),
    val style: RedactStyle = RedactStyle(),
    val wholeWord: Boolean = false,
    val keepLast: Int = 0,
)

/** Stored redaction settings: code sets for overlay text, the last style, profiles and the names list. */
object RedactPrefs {
    private const val FILE = "search5_redact"

    /** Arabic default redaction codes (editable). */
    val DEFAULT_CODES = listOf(
        "محجوب", "سري", "بيانات شخصية", "خصوصية", "بيانات مالية", "سر تجاري", "أمن", "مراسلات داخلية", "امتياز قانوني", "REDACTED",
    )

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun codes(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("codes", null) ?: JSONArray(DEFAULT_CODES).toString())
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(DEFAULT_CODES)

    fun saveCodes(c: Context, codes: List<String>) = prefs(c).edit().putString("codes", JSONArray(codes.filter { it.isNotBlank() }.distinct()).toString()).apply()

    fun styleToJson(s: RedactStyle) = JSONObject().apply {
        put("fill", s.fill); put("outline", s.outline ?: -1); put("overlay", s.overlay); put("textColor", s.textColor)
        put("fontSize", s.fontSize.toDouble()); put("repeat", s.repeat); put("align", s.align)
    }

    fun styleFromJson(o: JSONObject?) = if (o == null) RedactStyle() else RedactStyle(
        fill = o.optInt("fill", 0), outline = o.optInt("outline", 0xE53935).takeIf { it >= 0 }, overlay = o.optString("overlay", ""),
        textColor = o.optInt("textColor", 0xFFFFFF), fontSize = o.optDouble("fontSize", 0.0).toFloat(), repeat = o.optBoolean("repeat", false),
        align = o.optInt("align", 1),
    )

    fun lastStyle(c: Context): RedactStyle = runCatching { styleFromJson(JSONObject(prefs(c).getString("style", null) ?: "{}")) }.getOrDefault(RedactStyle())
    fun saveStyle(c: Context, s: RedactStyle) = prefs(c).edit().putString("style", styleToJson(s).toString()).apply()

    fun names(c: Context): String = prefs(c).getString("names", "") ?: ""
    fun saveNames(c: Context, names: String) = prefs(c).edit().putString("names", names).apply()

    fun profiles(c: Context): List<RedactProfile> = runCatching {
        val a = JSONArray(prefs(c).getString("profiles", null) ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun list(k: String) = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
            RedactProfile(
                name = o.getString("name"), words = list("words"),
                patterns = list("patterns").mapNotNull { n -> PatternKind.values().firstOrNull { it.name == n } }.toSet(),
                regexes = list("regexes"), style = styleFromJson(o.optJSONObject("style")),
                wholeWord = o.optBoolean("wholeWord", false), keepLast = o.optInt("keepLast", 0),
            )
        }
    }.getOrDefault(emptyList())

    fun saveProfile(c: Context, p: RedactProfile) {
        val all = profiles(c).filterNot { it.name == p.name } + p
        writeProfiles(c, all)
    }

    fun deleteProfile(c: Context, name: String) = writeProfiles(c, profiles(c).filterNot { it.name == name })

    private fun writeProfiles(c: Context, all: List<RedactProfile>) {
        val a = JSONArray()
        all.forEach { p ->
            a.put(JSONObject().apply {
                put("name", p.name); put("words", JSONArray(p.words)); put("patterns", JSONArray(p.patterns.map { it.name }))
                put("regexes", JSONArray(p.regexes)); put("style", styleToJson(p.style)); put("wholeWord", p.wholeWord); put("keepLast", p.keepLast)
            })
        }
        prefs(c).edit().putString("profiles", a.toString()).apply()
    }
}
