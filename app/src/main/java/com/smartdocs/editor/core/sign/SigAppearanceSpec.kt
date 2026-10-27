package com.smartdocs.editor.core.sign

/**
 * Visible digital-signature appearance (the reference's "appearance designer"): which lines are shown, labels,
 * logo and layout. Pure model + text lines; drawing is in ui/signature/SigAppearanceRenderer.kt.
 */
data class SigAppearanceSpec(
    val showName: Boolean = true,
    val showReason: Boolean = true,
    val showLocation: Boolean = true,
    val showDate: Boolean = true,
    val showDn: Boolean = false,
    val showLabels: Boolean = true,
    val logoPath: String? = null,
    val layout: Layout = Layout.LOGO_LEFT,
    /** Maximum text size in appearance pixels (the 900 px wide bitmap); [AUTO_FONT] = automatic layout. */
    val maxFontSize: Int = AUTO_FONT,
    /** Typeface family of the text lines (Foxit appearance "font"). */
    val font: Font = Font.SANS,
    /** Text direction (Foxit appearance "text direction"); AUTO follows each line's first strong character. */
    val direction: Direction = Direction.AUTO,
) {
    enum class Layout { LOGO_LEFT, LOGO_RIGHT, LOGO_TOP }
    enum class Font { SANS, SERIF, MONO }
    enum class Direction { AUTO, LTR, RTL }

    /** Whether [line] is laid out right-to-left. */
    fun rtl(line: String): Boolean = when (direction) {
        Direction.RTL -> true
        Direction.LTR -> false
        Direction.AUTO -> line.firstOrNull { Character.isLetter(it) }?.let {
            val d = Character.getDirectionality(it)
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        } ?: false
    }

    /** Localised labels ("Digitally signed by", "Reason", …). */
    data class Labels(val signedBy: String, val reason: String, val location: String, val date: String, val dn: String)

    /** Text lines in display order; empty values are skipped. The name line is the big first line when shown. */
    fun lines(name: String, reason: String, location: String, date: String, dn: String, labels: Labels): List<String> {
        fun line(show: Boolean, label: String, value: String): String? =
            if (!show || value.isBlank()) null else if (showLabels) "$label: ${value.trim()}" else value.trim()
        return listOfNotNull(
            if (showName && name.isNotBlank()) (if (showLabels) "${labels.signedBy} ${name.trim()}" else name.trim()) else null,
            line(showReason, labels.reason, reason),
            line(showLocation, labels.location, location),
            line(showDate, labels.date, date),
            line(showDn, labels.dn, dn),
        )
    }

    /** Text size of line [index] (the name line is bigger): the automatic size capped by [maxFontSize]. */
    fun fontSize(index: Int): Float {
        val auto = if (index == 0 && showName) NAME_SIZE else LINE_SIZE
        return if (maxFontSize <= AUTO_FONT) auto else minOf(auto, maxFontSize.toFloat())
    }

    /** Line height: follows the biggest text so small capped text gives a compact appearance. */
    fun lineHeight(lineCount: Int): Float = ((0 until lineCount.coerceAtLeast(1)).maxOf { fontSize(it) } * 1.23f).coerceAtLeast(24f)

    // The font cap, family and direction ride in the layout field ("LOGO_LEFT:24", "LOGO_LEFT:0:SERIF:RTL") so specs
    // saved before them still decode.
    fun encode(): String {
        val extra = when {
            font != Font.SANS || direction != Direction.AUTO -> ":$maxFontSize:${font.name}:${direction.name}"
            maxFontSize > AUTO_FONT -> ":$maxFontSize"
            else -> ""
        }
        return listOf(showName, showReason, showLocation, showDate, showDn, showLabels).joinToString("") { if (it) "1" else "0" } +
            "|" + layout.name + extra + "|" + (logoPath ?: "")
    }

    companion object {
        const val AUTO_FONT = 0
        const val NAME_SIZE = 52f
        const val LINE_SIZE = 40f
        /** Slider range for the maximum font size. */
        const val MIN_FONT = 14
        const val MAX_FONT = 52

        fun decode(s: String?): SigAppearanceSpec {
            if (s.isNullOrBlank()) return SigAppearanceSpec()
            val parts = s.split('|', limit = 3)
            val b = parts[0]
            if (parts.size != 3 || b.length != 6 || b.any { it != '0' && it != '1' }) return SigAppearanceSpec()
            fun bit(i: Int, d: Boolean) = b.getOrNull(i)?.let { it == '1' } ?: d
            val d = SigAppearanceSpec()
            val lp = parts.getOrNull(1).orEmpty().split(':')
            return SigAppearanceSpec(
                bit(0, d.showName), bit(1, d.showReason), bit(2, d.showLocation), bit(3, d.showDate), bit(4, d.showDn), bit(5, d.showLabels),
                logoPath = parts.getOrNull(2)?.ifBlank { null },
                layout = lp[0].let { n -> Layout.entries.firstOrNull { it.name == n } } ?: d.layout,
                maxFontSize = lp.getOrNull(1)?.toIntOrNull()?.takeIf { it in MIN_FONT..MAX_FONT } ?: AUTO_FONT,
                font = lp.getOrNull(2)?.let { n -> Font.entries.firstOrNull { it.name == n } } ?: d.font,
                direction = lp.getOrNull(3)?.let { n -> Direction.entries.firstOrNull { it.name == n } } ?: d.direction,
            )
        }
    }
}

/**
 * Named appearance profiles (Nitro's "appearance" list) kept beside the single last-used spec. Pure list
 * operations + JSON (`[{"name":…,"spec":…}]`, spec in [SigAppearanceSpec.encode] form) so they are unit-tested.
 */
object SigAppearanceProfiles {
    data class Profile(val name: String, val spec: SigAppearanceSpec)

    fun encode(list: List<Profile>): String = org.json.JSONArray().apply {
        list.forEach { p -> put(org.json.JSONObject().put("name", p.name).put("spec", p.spec.encode())) }
    }.toString()

    fun decode(s: String?): List<Profile> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = runCatching { org.json.JSONArray(s) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().ifBlank { return@mapNotNull null }
            Profile(name, SigAppearanceSpec.decode(o.optString("spec")))
        }.distinctBy { it.name.lowercase() }
    }

    /** Saves [spec] under [name]; an existing profile with the same name (ignoring case) is replaced in place. */
    fun save(list: List<Profile>, name: String, spec: SigAppearanceSpec): List<Profile> {
        val n = name.trim()
        if (n.isEmpty()) return list
        val i = list.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        return if (i >= 0) list.toMutableList().also { it[i] = Profile(n, spec) } else list + Profile(n, spec)
    }

    /** Renames [from] to [to]; no change when [to] is blank or already used by another profile. */
    fun rename(list: List<Profile>, from: String, to: String): List<Profile> {
        val n = to.trim()
        if (n.isEmpty() || list.any { !it.name.equals(from, true) && it.name.equals(n, true) }) return list
        return list.map { if (it.name.equals(from, true)) it.copy(name = n) else it }
    }

    /** Copies [name] as "[name] ([copySuffix])", "[name] ([copySuffix] 2)", … right after the original. */
    fun duplicate(list: List<Profile>, name: String, copySuffix: String): List<Profile> {
        val i = list.indexOfFirst { it.name.equals(name, true) }
        if (i < 0) return list
        var k = 1
        var n: String
        do { n = if (k == 1) "$name ($copySuffix)" else "$name ($copySuffix $k)"; k++ } while (list.any { it.name.equals(n, true) })
        return list.toMutableList().also { it.add(i + 1, Profile(n, list[i].spec)) }
    }

    fun delete(list: List<Profile>, name: String): List<Profile> = list.filterNot { it.name.equals(name, true) }
}
