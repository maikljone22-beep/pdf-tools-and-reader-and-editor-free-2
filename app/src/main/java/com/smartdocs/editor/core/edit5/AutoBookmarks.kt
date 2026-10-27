package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One text line of a page as the heading finder sees it: font, size, fill colour and position (user space). */
data class TextLine(val text: String, val page: Int, val size: Float, val top: Float?, val font: String = "", val color: Int? = null, val left: Float? = null)

/**
 * Rule of one bookmark level (Nitro "Auto bookmarks" level properties). [size] 0 = "the n-th largest heading size"
 * (automatic); otherwise lines of [size] ± [sizeTolerance] pt. [font] = part of the font name ("Bold", "Arial").
 * [color] with [colorTolerance] per channel; [indent] = left edge ± [indentTolerance] pt. [mask] = text pattern
 * (`*` any text, `?` one character, `#` one digit). [include] / [exclude] = comma-separated keywords.
 */
data class AutoBmLevel(
    val size: Float = 0f, val sizeTolerance: Float = 0.5f,
    val font: String = "",
    val color: Int? = null, val colorTolerance: Int = 40,
    val indent: Float? = null, val indentTolerance: Float = 12f,
    val mask: String = "",
    val include: String = "", val exclude: String = "",
)

enum class LeadingNumbers { KEEP, STRIP, REQUIRE }

/**
 * Auto-bookmark settings: up to five levels, the automatic size ratio, leading numbers (keep, strip, only headings
 * that start with one), [exactText] (keywords must match the whole line, no wrapped-line merging), the view the new
 * bookmarks open with, where they go, collapsed or open, and a page range ("" = all pages).
 */
data class AutoBmSettings(
    val levels: List<AutoBmLevel> = listOf(AutoBmLevel(), AutoBmLevel()),
    val minRatio: Float = 1.15f,
    val leadingNumbers: LeadingNumbers = LeadingNumbers.KEEP,
    val exactText: Boolean = false,
    val skipRepeated: Boolean = true,
    val view: DestView = DestView(DestFit.XYZ),
    val insert: AutoInsert = AutoInsert.REPLACE,
    val collapse: Boolean = false,
    val pages: String = "",
) {
    companion object { const val MAX_LEVELS = 5 }
}

/** Pure heading rules of the auto-bookmark tool (the text is collected by [OutlineTools.detectHeadings]). */
object AutoBookmarks {

    private val LEADING_NUM = Regex("""^\s*(?:[\d٠-٩]+(?:[.\-)][\d٠-٩]+)*[.)\-:]?|[IVXLCivxlc]+[.)]|[A-Za-z][.)])\s+""")

    fun stripLeadingNumber(s: String): String = LEADING_NUM.replaceFirst(s, "").trim()
    fun hasLeadingNumber(s: String): Boolean = LEADING_NUM.containsMatchIn(s)

    /** Text mask: `*` any text, `?` one character, `#` one digit; the whole line must match (case-insensitive). */
    fun maskMatches(mask: String, text: String): Boolean {
        if (mask.isBlank()) return true
        val re = buildString {
            append('^')
            for (c in mask.trim()) when (c) {
                '*' -> append(".*"); '?' -> append('.'); '#' -> append("[0-9٠-٩]")
                else -> append(Regex.escape(c.toString()))
            }
            append('$')
        }
        return Regex(re, RegexOption.IGNORE_CASE).matches(text.trim())
    }

    private fun words(s: String) = s.split(',', '،', ';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun colorNear(a: Int, b: Int, tol: Int): Boolean =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= tol

    /** True when [l] passes every filter of [rule] (the size is checked by the caller). */
    fun passes(rule: AutoBmLevel, l: TextLine, exact: Boolean): Boolean {
        if (rule.font.isNotBlank() && !l.font.contains(rule.font.trim(), ignoreCase = true)) return false
        if (rule.color != null && (l.color == null || !colorNear(rule.color, l.color, rule.colorTolerance))) return false
        if (rule.indent != null && (l.left == null || kotlin.math.abs(l.left - rule.indent) > rule.indentTolerance)) return false
        if (!maskMatches(rule.mask, l.text)) return false
        val inc = words(rule.include)
        if (inc.isNotEmpty() && inc.none { if (exact) l.text.trim().equals(it, true) else l.text.contains(it, true) }) return false
        val exc = words(rule.exclude)
        if (exc.any { l.text.contains(it, true) }) return false
        return true
    }

    /** Headings of [lines] with their level (0-based), wrapped titles merged, in reading order. */
    fun headings(lines: List<TextLine>, s: AutoBmSettings): List<Pair<TextLine, Int>> {
        if (lines.isEmpty()) return emptyList()
        val rules = s.levels.take(AutoBmSettings.MAX_LEVELS).ifEmpty { listOf(AutoBmLevel()) }
        val weights = HashMap<Float, Int>()
        lines.forEach { weights[it.size] = (weights[it.size] ?: 0) + it.text.length }
        val body = weights.maxByOrNull { it.value }!!.key
        val pageCount = lines.maxOf { it.page } + 1
        val repeated = if (s.skipRepeated) lines.groupingBy { it.text }.eachCount().filter { it.value > maxOf(3, pageCount / 2) }.keys else emptySet()
        val base = lines.filter { h ->
            h.text.length in 2..140 && h.text.any { it.isLetter() } && h.text !in repeated &&
                (s.leadingNumbers != LeadingNumbers.REQUIRE || hasLeadingNumber(h.text))
        }
        // automatic levels take the largest heading sizes in order (skipping the sizes fixed by other levels)
        val fixed = rules.map { it.size }.filter { it > 0f }
        val autoSizes = base.filter { it.size >= body * s.minRatio && fixed.none { f -> kotlin.math.abs(f - it.size) < 0.01f } }
            .map { it.size }.distinct().sortedDescending()
        var autoIdx = 0
        val levelSize = rules.map { r -> if (r.size > 0f) r.size else autoSizes.getOrNull(autoIdx++) }
        fun levelOf(l: TextLine): Int {
            for ((i, r) in rules.withIndex()) {
                val sz = levelSize[i] ?: continue
                val tol = if (r.size > 0f) r.sizeTolerance else 0.01f
                if (kotlin.math.abs(l.size - sz) <= tol && passes(r, l, s.exactText)) return i
            }
            return -1
        }
        val out = ArrayList<Pair<TextLine, Int>>()
        for (h in base) {
            val lvl = levelOf(h); if (lvl < 0) continue
            val last = out.lastOrNull()
            val lt = last?.first?.top; val ht = h.top
            if (!s.exactText && last != null && last.first.page == h.page && last.second == lvl && kotlin.math.abs(last.first.size - h.size) < 0.01f && lt != null && ht != null &&
                lt - ht in 0f..h.size * 1.8f) {
                out[out.size - 1] = last.first.copy(text = last.first.text + " " + h.text) to lvl
            } else out += h to lvl
        }
        return if (s.leadingNumbers == LeadingNumbers.STRIP) out.map { (h, l) -> h.copy(text = stripLeadingNumber(h.text).ifBlank { h.text }) to l } else out
    }

    /** New bookmarks for [found] with the settings' view and open / collapsed state. */
    fun toBookmarks(found: List<Pair<TextLine, Int>>, s: AutoBmSettings): List<E5Bookmark> {
        val list = found.map { (h, l) ->
            val v = s.view
            val view = when (v.fit) {
                DestFit.XYZ -> v.copy(top = h.top, left = v.left)
                DestFit.FITH, DestFit.FITBH -> v.copy(top = h.top)
                else -> v
            }
            E5Bookmark(OutlineModel.newKey(), h.text, h.page, l, bold = l == 0, open = !s.collapse).withView(if (view.fit == DestFit.XYZ && h.top == null) DestView(DestFit.FIT) else view)
        }
        return OutlineModel.normalize(list.map { it.row() }).let { n -> list.mapIndexed { i, b -> b.copy(level = n[i].level) } }
    }

    // ---------------- settings file (Nitro "Import / Export settings") ----------------

    private fun num(v: Float?) = v?.let { JsonPrimitive(it) } ?: JsonNull

    fun toJson(s: AutoBmSettings): String = JsonObject(mapOf(
        "format" to JsonPrimitive("smartdocs-autobookmarks"), "version" to JsonPrimitive(1),
        "levels" to JsonArray(s.levels.map { l -> JsonObject(mapOf(
            "size" to JsonPrimitive(l.size), "sizeTolerance" to JsonPrimitive(l.sizeTolerance), "font" to JsonPrimitive(l.font),
            "color" to (l.color?.let { JsonPrimitive("#%06X".format(it and 0xFFFFFF)) } ?: JsonNull), "colorTolerance" to JsonPrimitive(l.colorTolerance),
            "indent" to num(l.indent), "indentTolerance" to JsonPrimitive(l.indentTolerance), "mask" to JsonPrimitive(l.mask),
            "include" to JsonPrimitive(l.include), "exclude" to JsonPrimitive(l.exclude),
        )) }),
        "minRatio" to JsonPrimitive(s.minRatio), "leadingNumbers" to JsonPrimitive(s.leadingNumbers.name), "exactText" to JsonPrimitive(s.exactText),
        "skipRepeated" to JsonPrimitive(s.skipRepeated), "fit" to JsonPrimitive(s.view.fit.name), "zoom" to JsonPrimitive(s.view.zoom),
        "insert" to JsonPrimitive(s.insert.name), "collapse" to JsonPrimitive(s.collapse), "pages" to JsonPrimitive(s.pages),
    )).toString()

    /** Parse a settings file written by [toJson]; unknown or missing keys take the defaults. */
    fun fromJson(text: String): AutoBmSettings {
        val o = Json.parseToJsonElement(text).jsonObject
        fun p(e: JsonElement?) = e as? JsonPrimitive
        fun str(e: JsonElement?) = p(e)?.takeIf { it !is JsonNull }?.content
        val d = AutoBmSettings()
        val levels = (o["levels"] as? JsonArray)?.mapNotNull { el ->
            val l = el as? JsonObject ?: return@mapNotNull null
            val def = AutoBmLevel()
            AutoBmLevel(
                size = p(l["size"])?.floatOrNull ?: 0f, sizeTolerance = p(l["sizeTolerance"])?.floatOrNull ?: def.sizeTolerance, font = str(l["font"]) ?: "",
                color = str(l["color"])?.removePrefix("#")?.toIntOrNull(16), colorTolerance = p(l["colorTolerance"])?.intOrNull ?: def.colorTolerance,
                indent = p(l["indent"])?.floatOrNull, indentTolerance = p(l["indentTolerance"])?.floatOrNull ?: def.indentTolerance,
                mask = str(l["mask"]) ?: "", include = str(l["include"]) ?: "", exclude = str(l["exclude"]) ?: "",
            )
        }?.take(AutoBmSettings.MAX_LEVELS)?.ifEmpty { null } ?: d.levels
        return AutoBmSettings(
            levels = levels, minRatio = p(o["minRatio"])?.floatOrNull ?: d.minRatio,
            leadingNumbers = runCatching { LeadingNumbers.valueOf(str(o["leadingNumbers"])!!) }.getOrDefault(d.leadingNumbers),
            exactText = p(o["exactText"])?.booleanOrNull ?: d.exactText, skipRepeated = p(o["skipRepeated"])?.booleanOrNull ?: d.skipRepeated,
            view = DestView(runCatching { DestFit.valueOf(str(o["fit"])!!) }.getOrDefault(DestFit.XYZ), zoom = p(o["zoom"])?.floatOrNull ?: 0f),
            insert = runCatching { AutoInsert.valueOf(str(o["insert"])!!) }.getOrDefault(d.insert),
            collapse = p(o["collapse"])?.booleanOrNull ?: d.collapse, pages = str(o["pages"]) ?: "",
        )
    }
}
