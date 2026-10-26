package com.smartdocs.editor.core.pageflip

/** How pages are laid out. CLASSIC = the reader's own PageView (continuous list or plain pager), unchanged. */
enum class PfLayout { CLASSIC, PAGED, SCROLL_H }

/** Axis of paging in [PfLayout.PAGED]: horizontal (book-like) or vertical (pages move up / down). */
enum class PfAxis { HORIZONTAL, VERTICAL }

/** Reading direction. AUTO = from the document's script (Arabic / Hebrew -> RTL), else the UI direction. */
enum class PfDirection { AUTO, LTR, RTL }

/** Two-page spread choice of the page-turn host (maps to the reader's ViewMode SINGLE / FACING / COVER). */
enum class PfSpread { SINGLE, TWO, TWO_COVER }

/**
 * The de-duplicated effect library. [book] = realistic paper effects (mesh based, horizontal axis only);
 * [vertical] = the effect has a vertical variant.
 */
enum class PfEffect(val book: Boolean, val vertical: Boolean, val baseMs: Int) {
    CURL(true, false, 620),
    BOOK(true, false, 700),
    SLIDE(false, true, 380),
    REVEAL(false, true, 400),
    PARALLAX(false, true, 420),
    FADE(false, true, 320),
    SCALE_FADE(false, true, 360),
    ZOOM(false, true, 400),
    WIPE(false, true, 420),
    ROTATE(false, false, 460),
    ROTATE_3D(false, true, 520),
    CUBE(false, true, 520),
    FLIP_CARD(false, true, 560),
    NONE(false, true, 0),
}

/**
 * User preferences of the page navigation library. Serialised as `key=value` pairs separated by `;`
 * (stable, human readable, unknown keys ignored, missing keys default) so old saves keep working.
 */
data class PageFlipPrefs(
    val layout: PfLayout = PfLayout.CLASSIC,
    val axis: PfAxis = PfAxis.HORIZONTAL,
    val spread: PfSpread = PfSpread.SINGLE,
    val effect: PfEffect = PfEffect.CURL,
    val direction: PfDirection = PfDirection.AUTO,
    /** Speed multiplier 0.5 (slow) .. 2 (fast). */
    val speed: Float = 1f,
    /** Strength of lighting / dimming / parallax depth 0..1. */
    val intensity: Float = 0.7f,
    val shadows: Boolean = true,
    /** Page curvature 0 (sharp fold) .. 1 (wide roll). */
    val curvature: Float = 0.5f,
    /** Paper thickness edges + spine decoration. */
    val thickness: Boolean = true,
    /** Book margins around a spread, dp 0..48. */
    val marginDp: Float = 12f,
    val spring: Boolean = true,
    val bouncy: Boolean = false,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
    val edgeZones: Boolean = true,
    /** Width of each edge zone as a fraction of the view width 0.08..0.3. */
    val edgeZone: Float = 0.16f,
    val peek: Boolean = true,
) {
    fun encode(): String = buildString {
        fun put(k: String, v: Any) { if (isNotEmpty()) append(';'); append(k).append('=').append(v) }
        put("layout", layout.name); put("axis", axis.name); put("spread", spread.name); put("effect", effect.name)
        put("dir", direction.name); put("speed", speed); put("intensity", intensity); put("shadows", shadows)
        put("curv", curvature); put("thick", thickness); put("margin", marginDp); put("spring", spring)
        put("bouncy", bouncy); put("haptics", haptics); put("reduced", reducedMotion); put("zones", edgeZones)
        put("zone", edgeZone); put("peek", peek)
    }

    /** Effect actually used: vertical axis drops effects without a vertical variant; reduced motion -> fade / none. */
    fun effectiveEffect(systemAnimationsOff: Boolean): PfEffect = when {
        systemAnimationsOff -> PfEffect.NONE
        reducedMotion -> if (effect == PfEffect.NONE) PfEffect.NONE else PfEffect.FADE
        axis == PfAxis.VERTICAL && !effect.vertical -> PfEffect.SLIDE
        else -> effect
    }

    companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2f

        fun decode(s: String?): PageFlipPrefs {
            if (s.isNullOrBlank()) return PageFlipPrefs()
            val m = s.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).trim() to p.substring(it + 1).trim() } }.toMap()
            val d = PageFlipPrefs()
            fun f(k: String, def: Float, lo: Float, hi: Float) = m[k]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(lo, hi) ?: def
            fun b(k: String, def: Boolean) = when (m[k]) { "true" -> true; "false" -> false; else -> def }
            return PageFlipPrefs(
                layout = enumOr(m["layout"], d.layout), axis = enumOr(m["axis"], d.axis), spread = enumOr(m["spread"], d.spread),
                effect = enumOr(m["effect"], d.effect), direction = enumOr(m["dir"], d.direction),
                speed = f("speed", d.speed, SPEED_MIN, SPEED_MAX), intensity = f("intensity", d.intensity, 0f, 1f),
                shadows = b("shadows", d.shadows), curvature = f("curv", d.curvature, 0f, 1f), thickness = b("thick", d.thickness),
                marginDp = f("margin", d.marginDp, 0f, 48f), spring = b("spring", d.spring), bouncy = b("bouncy", d.bouncy),
                haptics = b("haptics", d.haptics), reducedMotion = b("reduced", d.reducedMotion), edgeZones = b("zones", d.edgeZones),
                edgeZone = f("zone", d.edgeZone, 0.08f, 0.3f), peek = b("peek", d.peek),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
    }
}
