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

/** Stored redaction settings: named code sets for overlay text, the last style, profiles and the names list. */
object RedactPrefs {
    private const val FILE = "search5_redact"

    /** Arabic default redaction codes (editable). */
    val DEFAULT_CODES = listOf(
        "محجوب", "سري", "بيانات شخصية", "خصوصية", "بيانات مالية", "سر تجاري", "أمن", "مراسلات داخلية", "امتياز قانوني", "REDACTED",
    )

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Codes of the active code set. */
    fun codes(c: Context): List<String> = codeSets(c).let { sets -> sets.firstOrNull { it.name == activeSet(c) } ?: sets.first() }.codes

    /** Replaces the codes of the active code set. */
    fun saveCodes(c: Context, codes: List<String>) = saveCodeSets(c, CodeSets.update(codeSets(c), activeSet(c), codes))

    /** Named code sets (Nitro "Redaction code sets"); the first run migrates the single list into the default set. */
    fun codeSets(c: Context): List<CodeSet> = runCatching {
        prefs(c).getString("codeSets", null)?.let { CodeSets.fromJson(JSONArray(it)) }?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: listOf(CodeSet(CodeSets.DEFAULT_NAME, legacyCodes(c)))

    fun saveCodeSets(c: Context, sets: List<CodeSet>) {
        val keep = sets.ifEmpty { listOf(CodeSet(CodeSets.DEFAULT_NAME, DEFAULT_CODES)) }
        prefs(c).edit().putString("codeSets", CodeSets.toJson(keep).toString()).apply()
        if (keep.none { it.name == activeSet(c) }) setActiveSet(c, keep.first().name)
    }

    fun activeSet(c: Context): String = prefs(c).getString("activeCodeSet", null) ?: CodeSets.DEFAULT_NAME
    fun setActiveSet(c: Context, name: String) = prefs(c).edit().putString("activeCodeSet", name).apply()

    private fun legacyCodes(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("codes", null) ?: JSONArray(DEFAULT_CODES).toString())
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(DEFAULT_CODES)

    fun styleToJson(s: RedactStyle) = JSONObject().apply {
        put("fill", s.fill); put("outline", s.outline ?: -1); put("overlay", s.overlay); put("textColor", s.textColor)
        put("fontSize", s.fontSize.toDouble()); put("repeat", s.repeat); put("align", s.align)
        put("font", s.font); put("case", s.textCase)
    }

    fun styleFromJson(o: JSONObject?) = if (o == null) RedactStyle() else RedactStyle(
        fill = o.optInt("fill", 0), outline = o.optInt("outline", 0xE53935).takeIf { it >= 0 }, overlay = o.optString("overlay", ""),
        textColor = o.optInt("textColor", 0xFFFFFF), fontSize = o.optDouble("fontSize", 0.0).toFloat(), repeat = o.optBoolean("repeat", false),
        align = o.optInt("align", 1), font = o.optString("font", ""), textCase = o.optInt("case", RedactStyle.CASE_AS_TYPED),
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

/** One named list of redaction codes. */
data class CodeSet(val name: String, val codes: List<String>)

/** Pure edits of the code-set list (new set, rename, remove, update codes) and its JSON form. */
object CodeSets {
    /** Stored name of the built-in set; the UI shows it translated. */
    const val DEFAULT_NAME = "default"

    private fun clean(codes: List<String>) = codes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Adds an empty set named [name] (trimmed, made unique with " 2", " 3"…). Returns the list and the name used. */
    fun add(sets: List<CodeSet>, name: String, codes: List<String> = emptyList()): Pair<List<CodeSet>, String> {
        val base = name.trim().ifEmpty { "Set" }
        var n = base; var i = 2
        while (sets.any { it.name == n }) n = "$base ${i++}"
        return (sets + CodeSet(n, clean(codes))) to n
    }

    /** Renames [old] to [new]; refused (unchanged list) when [new] is blank or already taken. */
    fun rename(sets: List<CodeSet>, old: String, new: String): List<CodeSet> {
        val t = new.trim()
        if (t.isEmpty() || sets.any { it.name == t }) return sets
        return sets.map { if (it.name == old) it.copy(name = t) else it }
    }

    /** Removes [name]; the last set cannot be removed. */
    fun remove(sets: List<CodeSet>, name: String): List<CodeSet> = if (sets.size <= 1) sets else sets.filterNot { it.name == name }

    fun update(sets: List<CodeSet>, name: String, codes: List<String>): List<CodeSet> =
        if (sets.any { it.name == name }) sets.map { if (it.name == name) it.copy(codes = clean(codes)) else it } else add(sets, name, codes).first

    fun toJson(sets: List<CodeSet>) = JSONArray().apply { sets.forEach { s -> put(JSONObject().put("name", s.name).put("codes", JSONArray(s.codes))) } }

    fun fromJson(a: JSONArray): List<CodeSet> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val codes = o.optJSONArray("codes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        CodeSet(o.optString("name").ifBlank { return@mapNotNull null }, clean(codes))
    }
}
