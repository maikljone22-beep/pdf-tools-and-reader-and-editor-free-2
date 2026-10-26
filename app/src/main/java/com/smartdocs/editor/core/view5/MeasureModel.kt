package com.smartdocs.editor.core.view5

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** A point in PDF page space (points, origin bottom-left). Pure Kotlin so it can be unit tested. */
data class P(val x: Double, val y: Double)

/** Length units with their count per inch (1 in = 72 pt). */
enum class MUnit(val code: String, val perInch: Double) {
    PT("pt", 72.0), IN("in", 1.0), MM("mm", 25.4), CM("cm", 2.54), M("m", 0.0254), KM("km", 0.0000254),
    FT("ft", 1.0 / 12.0), YD("yd", 1.0 / 36.0), MI("mi", 1.0 / 63360.0);

    companion object {
        private val aliases = mapOf(
            "pt" to PT, "point" to PT, "points" to PT, "نقطة" to PT,
            "in" to IN, "inch" to IN, "inches" to IN, "\"" to IN, "إنش" to IN, "بوصة" to IN,
            "mm" to MM, "مم" to MM, "ملم" to MM, "cm" to CM, "سم" to CM, "m" to M, "م" to M, "متر" to M,
            "km" to KM, "كم" to KM, "ft" to FT, "foot" to FT, "feet" to FT, "'" to FT, "قدم" to FT,
            "yd" to YD, "yard" to YD, "yards" to YD, "ياردة" to YD, "mi" to MI, "mile" to MI, "miles" to MI, "ميل" to MI,
        )
        fun parse(s: String): MUnit? = aliases[s.trim().lowercase(Locale.ROOT)]
        /** Convert [v] from unit [a] to unit [b]. */
        fun convert(v: Double, a: MUnit, b: MUnit): Double = v / a.perInch * b.perInch
    }
}

enum class MeasureType { DISTANCE, PERIMETER, AREA, CIRCLE, ANGLE, COUNT }

/**
 * Drawing scale: [docValue] [docUnit] on the page = [realValue] [realUnit] in the world (e.g. "1 in = 10 ft").
 * Stored in the PDF as the /R string of a /Measure /RL dictionary.
 */
data class MeasureScale(val docValue: Double = 1.0, val docUnit: MUnit = MUnit.IN, val realValue: Double = 1.0, val realUnit: MUnit = MUnit.IN) {
    /** Real-world length of [pt] page points. */
    fun length(pt: Double): Double = MUnit.convert(pt, MUnit.PT, docUnit) / docValue * realValue
    /** Real-world area of [pt2] square page points. */
    fun area(pt2: Double): Double { val k = length(1.0); return pt2 * k * k }
    /** Real units per page point (the /C factor of the /X number format). */
    val factor: Double get() = length(1.0)
    fun label(): String = "${num(docValue)} ${docUnit.code} = ${num(realValue)} ${realUnit.code}"

    companion object {
        val IDENTITY_CM = MeasureScale(1.0, MUnit.CM, 1.0, MUnit.CM)

        private fun num(v: Double): String = if (abs(v - round(v)) < 1e-9) round(v).toLong().toString() else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

        /** "1 in = 10 ft", "1cm=2.5m", "١ سم = ٥ م", or a ratio "1:100" (both sides in [ratioUnit]). Null when unparsable. */
        fun parse(raw: String, ratioUnit: MUnit = MUnit.CM): MeasureScale? {
            val s = Digits.toLatin(raw).replace('٫', '.').replace('،', ',').trim()
            Regex("""^\s*([0-9]*[.,]?[0-9]+)\s*:\s*([0-9]*[.,]?[0-9]+)\s*$""").find(s)?.let { m ->
                val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
                val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
                if (a <= 0 || b <= 0) return null
                return MeasureScale(a, ratioUnit, b, ratioUnit)
            }
            val m = Regex("""^\s*([0-9]*[.,]?[0-9]+)\s*([^\s=0-9]+)\s*=\s*([0-9]*[.,]?[0-9]+)\s*([^\s=0-9]+)\s*$""").find(s) ?: return null
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val ua = MUnit.parse(m.groupValues[2]) ?: return null
            val b = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
            val ub = MUnit.parse(m.groupValues[4]) ?: return null
            if (a <= 0 || b <= 0) return null
            return MeasureScale(a, ua, b, ub)
        }

        /** Calibration: a segment of [measuredPt] points is known to be [known] [unit] long. */
        fun calibrate(measuredPt: Double, known: Double, unit: MUnit): MeasureScale? {
            if (measuredPt <= 0.0 || known <= 0.0) return null
            val inches = measuredPt / 72.0
            return MeasureScale(1.0, MUnit.IN, known / inches, unit)
        }
    }
}

/** Geometry used by the measure tools. All inputs are PDF points. */
object Geom {
    fun dist(a: P, b: P): Double = hypot(b.x - a.x, b.y - a.y)

    fun pathLength(pts: List<P>, closed: Boolean = false): Double {
        if (pts.size < 2) return 0.0
        var s = 0.0
        for (i in 1 until pts.size) s += dist(pts[i - 1], pts[i])
        if (closed && pts.size > 2) s += dist(pts.last(), pts.first())
        return s
    }

    /** Shoelace formula (absolute area, any winding). */
    fun polygonArea(pts: List<P>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) { val a = pts[i]; val b = pts[(i + 1) % pts.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2.0
    }

    fun circleArea(r: Double): Double = Math.PI * r * r
    fun circumference(r: Double): Double = 2 * Math.PI * r

    /** Angle at [vertex] between rays to [a] and [b], in degrees 0..180. */
    fun angleDeg(a: P, vertex: P, b: P): Double {
        val ux = a.x - vertex.x; val uy = a.y - vertex.y; val vx = b.x - vertex.x; val vy = b.y - vertex.y
        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
        if (lu < 1e-9 || lv < 1e-9) return 0.0
        val c = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** Direction of a→b in degrees (0 = east, counter-clockwise, 0..360). */
    fun bearing(a: P, b: P): Double { val d = Math.toDegrees(atan2(b.y - a.y, b.x - a.x)); return if (d < 0) d + 360 else d }

    fun centroid(pts: List<P>): P = if (pts.isEmpty()) P(0.0, 0.0) else P(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)

    fun midpoint(a: P, b: P): P = P((a.x + b.x) / 2, (a.y + b.y) / 2)

    /** Move [p] so that the segment from [anchor] has a direction that is a multiple of 45°. */
    fun constrain45(anchor: P, p: P): P {
        val d = dist(anchor, p); if (d < 1e-9) return p
        val ang = Math.toRadians(round(bearing(anchor, p) / 45.0) * 45.0)
        return P(anchor.x + d * cos(ang), anchor.y + d * sin(ang))
    }

    /** Nearest candidate to [p] within [tol] points, or null. */
    fun snap(p: P, candidates: List<P>, tol: Double): P? {
        var best: P? = null; var bd = tol
        for (c in candidates) { val d = dist(p, c); if (d <= bd) { bd = d; best = c } }
        return best
    }

    /** Closest point to [p] on segment a-b. */
    fun nearestOnSegment(p: P, a: P, b: P): P {
        val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
        if (l2 < 1e-12) return a
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
        return P(a.x + t * dx, a.y + t * dy)
    }

    /** Intersection point of segments a1-a2 and b1-b2, or null. */
    fun intersect(a1: P, a2: P, b1: P, b2: P): P? {
        val d = (a2.x - a1.x) * (b2.y - b1.y) - (a2.y - a1.y) * (b2.x - b1.x)
        if (abs(d) < 1e-12) return null
        val t = ((b1.x - a1.x) * (b2.y - b1.y) - (b1.y - a1.y) * (b2.x - b1.x)) / d
        val u = ((b1.x - a1.x) * (a2.y - a1.y) - (b1.y - a1.y) * (a2.x - a1.x)) / d
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return P(a1.x + t * (a2.x - a1.x), a1.y + t * (a2.y - a1.y))
    }

    /**
     * Snap with priorities: end points / mid points / intersections of [segments] first, then the nearest
     * point on a segment. Returns null when nothing is within [tol].
     */
    fun snapToSegments(p: P, segments: List<Pair<P, P>>, tol: Double, extraPoints: List<P> = emptyList()): P? {
        val pts = ArrayList<P>(segments.size * 3 + extraPoints.size)
        segments.forEach { (a, b) -> pts += a; pts += b; pts += midpoint(a, b) }
        pts += extraPoints
        snap(p, pts, tol)?.let { return it }
        // intersections only among nearby segments (keeps it fast on busy drawings)
        val near = segments.filter { (a, b) -> dist(p, nearestOnSegment(p, a, b)) <= tol * 3 }.take(60)
        val xs = ArrayList<P>()
        for (i in near.indices) for (j in i + 1 until near.size) intersect(near[i].first, near[i].second, near[j].first, near[j].second)?.let { xs += it }
        snap(p, xs, tol)?.let { return it }
        var best: P? = null; var bd = tol
        near.forEach { (a, b) -> val q = nearestOnSegment(p, a, b); val d = dist(p, q); if (d <= bd) { bd = d; best = q } }
        return best
    }
}

/** One finished measurement (also a CSV row). */
data class MeasureRow(val page: Int, val type: MeasureType, val value: Double, val unit: String, val label: String = "", val author: String = "", val scale: String = "")

object MeasureFormat {
    /** "12.35 cm", with Arabic-Indic digits when [arabicDigits]. */
    fun value(v: Double, precision: Int, unit: String, arabicDigits: Boolean = false): String {
        val s = String.format(Locale.US, "%.${precision.coerceIn(0, 6)}f", v)
        return (if (arabicDigits) Digits.toArabic(s) else s) + (if (unit.isEmpty()) "" else " $unit")
    }

    fun unitFor(type: MeasureType, scale: MeasureScale): String = when (type) {
        MeasureType.AREA, MeasureType.CIRCLE -> scale.realUnit.code + "²"
        MeasureType.ANGLE -> "°"
        MeasureType.COUNT -> ""
        else -> scale.realUnit.code
    }

    private fun csvCell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** CSV with UTF-8 BOM so Excel opens Arabic correctly; ends with totals per type and unit. */
    fun csv(rows: List<MeasureRow>, header: List<String> = listOf("page", "type", "value", "unit", "label", "author", "scale"), totalLabel: String = "total"): String {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { csvCell(it) }).append("\r\n")
        rows.forEach { r ->
            sb.append(listOf((r.page + 1).toString(), r.type.name.lowercase(Locale.ROOT), String.format(Locale.US, "%.4f", r.value), r.unit, r.label, r.author, r.scale)
                .joinToString(",") { csvCell(it) }).append("\r\n")
        }
        rows.filter { it.type != MeasureType.ANGLE }.groupBy { it.type to it.unit }.forEach { (k, list) ->
            sb.append(listOf(totalLabel, k.first.name.lowercase(Locale.ROOT), String.format(Locale.US, "%.4f", list.sumOf { it.value }), k.second, "n=${list.size}", "", "")
                .joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}

/** Latin / Arabic-Indic / Persian digit conversion. */
object Digits {
    fun toLatin(s: String): String = buildString(s.length) {
        s.forEach { c ->
            append(when (c.code) { in 0x0660..0x0669 -> '0' + (c.code - 0x0660); in 0x06F0..0x06F9 -> '0' + (c.code - 0x06F0); else -> c })
        }
    }
    fun toArabic(s: String): String = buildString(s.length) { s.forEach { c -> append(if (c in '0'..'9') (0x0660 + (c - '0')).toChar() else if (c == '.') '٫' else c) } }
}
