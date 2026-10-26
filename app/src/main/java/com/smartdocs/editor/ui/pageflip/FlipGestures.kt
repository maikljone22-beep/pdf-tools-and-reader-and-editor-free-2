package com.smartdocs.editor.ui.pageflip

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.PfAxis
import kotlin.math.abs
import kotlin.math.max

/** Receiver of the page-turn gestures (implemented by the host; values are read at event time). */
interface FlipGestureTarget {
    /** Zoomed in: one finger pans, no turn gestures. */
    val zoomed: Boolean
    /** A tool / selection / placement owns the page: drags are left to the page canvas. */
    val busy: Boolean
    /** Edge zone width (fraction of the width), null = no edge zones. */
    val edgeZone: Float?
    val rtl: Boolean
    val axis: PfAxis
    val peekEnabled: Boolean
    fun onPinch(zoom: Float, centroid: Offset, pan: Offset)
    fun onPan(delta: Offset)
    fun onZoneTap(zone: Int, at: Offset)
    fun onTurnStart(delta: Int, start: Offset, fromZone: Boolean): Boolean
    fun onTurnDrag(pos: Offset)
    fun onTurnEnd(velocity: Velocity, peekOnly: Boolean)
    fun onPeek(zone: Int, at: Offset): Boolean
    fun onRiffleStart(at: Offset)
    fun onRiffle(distance: Float)
    fun onRiffleEnd(cancel: Boolean)
    fun onWheel(dy: Float, dx: Float)
}

private enum class G { UNDECIDED, TURN, PAN, PINCH, PEEK, RIFFLE, IGNORE }

/**
 * Pointer layer of the page-turn host, handled in the Initial pass (before the page canvas):
 * - edge zones (outer strips, when enabled and not zoomed / busy): the down is consumed; tap = turn, long press = peek
 *   (hold, then drag to turn or release to cancel), horizontal drag = turn following the finger (corner = diagonal curl),
 *   vertical drag along the edge = riffle through the page stack;
 * - elsewhere a drag along the paging axis turns the page (decided at touch slop, so taps, long-press selection,
 *   links and annotation tools keep working); zoomed in, one finger pans;
 * - two fingers pinch-zoom; mouse wheel / touchpad scroll turns pages (or pans when zoomed).
 */
fun Modifier.flipGestures(target: FlipGestureTarget): Modifier = this
    .pointerInput(target) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Scroll) {
                    val c = e.changes.firstOrNull() ?: continue
                    target.onWheel(c.scrollDelta.y, c.scrollDelta.x)
                    c.consume()
                }
            }
        }
    }
    .pointerInput(target) {
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val t0 = SystemClock.uptimeMillis()
            val zoneW = target.edgeZone
            val zone = if (zoneW != null && !target.busy && !target.zoomed) FlipMath.zoneOf(down.position.x, size.width.toFloat(), zoneW, target.rtl) else 0
            if (zone != 0) down.consume()
            var mode = G.UNDECIDED
            var total = Offset.Zero
            var peekMoved = false
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var last = down.position
            while (true) {
                val ev: PointerEvent? = if (mode == G.UNDECIDED && zone != 0 && target.peekEnabled) {
                    val remaining = longPress - (SystemClock.uptimeMillis() - t0)
                    withTimeoutOrNull(max(1L, remaining)) { awaitPointerEvent(PointerEventPass.Initial) }
                } else awaitPointerEvent(PointerEventPass.Initial)
                if (ev == null) {
                    mode = if (target.onPeek(zone, last)) G.PEEK else G.IGNORE
                    continue
                }
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    when (mode) {
                        G.TURN, G.PEEK -> target.onTurnEnd(Velocity.Zero, peekOnly = mode == G.PEEK && !peekMoved)
                        G.RIFFLE -> target.onRiffleEnd(cancel = true)
                        else -> Unit
                    }
                    mode = G.PINCH
                    val z = ev.calculateZoom(); val c = ev.calculateCentroid(); val p = ev.calculatePan()
                    if (z != 1f || p != Offset.Zero) target.onPinch(z, c, p)
                    ev.changes.forEach { it.consume() }
                    continue
                }
                if (pressed.isEmpty()) break
                if (mode == G.PINCH) { ev.changes.forEach { it.consume() }; continue }
                val ch = pressed[0]
                last = ch.position
                tracker.addPosition(ch.uptimeMillis, ch.position)
                val d = ch.positionChange()
                when (mode) {
                    G.UNDECIDED -> {
                        total += d
                        if (total.getDistance() > slop) {
                            mode = when {
                                target.busy -> G.IGNORE
                                target.zoomed -> G.PAN
                                zone != 0 && target.axis == PfAxis.HORIZONTAL && abs(total.y) > abs(total.x) * 1.3f -> { target.onRiffleStart(down.position); G.RIFFLE }
                                FlipMath.alongAxis(total.x, total.y, target.axis) ->
                                    if (target.onTurnStart(FlipMath.dragDelta(total.x, total.y, target.axis, target.rtl), down.position, zone != 0)) G.TURN else G.IGNORE
                                else -> G.IGNORE
                            }
                            when (mode) {
                                G.TURN -> target.onTurnDrag(ch.position)
                                G.PAN -> target.onPan(total)
                                G.RIFFLE -> target.onRiffle(ch.position.y - down.position.y)
                                else -> Unit
                            }
                        }
                        if (zone != 0 || mode == G.TURN || mode == G.PAN || mode == G.RIFFLE) ch.consume()
                    }
                    G.TURN -> { target.onTurnDrag(ch.position); ch.consume() }
                    G.PEEK -> {
                        total += d
                        if (peekMoved || total.getDistance() > slop) { peekMoved = true; target.onTurnDrag(ch.position) }
                        ch.consume()
                    }
                    G.PAN -> { target.onPan(d); ch.consume() }
                    G.RIFFLE -> { target.onRiffle(ch.position.y - down.position.y); ch.consume() }
                    G.IGNORE -> if (zone != 0) ch.consume()
                    G.PINCH -> Unit
                }
            }
            when (mode) {
                G.UNDECIDED -> if (zone != 0) target.onZoneTap(zone, down.position)
                G.TURN -> target.onTurnEnd(tracker.calculateVelocity(), peekOnly = false)
                G.PEEK -> target.onTurnEnd(tracker.calculateVelocity(), peekOnly = !peekMoved)
                G.RIFFLE -> target.onRiffleEnd(cancel = false)
                else -> Unit
            }
        }
    }
