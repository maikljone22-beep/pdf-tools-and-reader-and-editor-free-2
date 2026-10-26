package com.smartdocs.editor.ui.pageflip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.PageFlipPrefs
import com.smartdocs.editor.core.pageflip.PfEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turn state machine of the page-turn host. A [Turn] always describes the scene "earlier screen a -> later screen b"
 * with raw progress t (0 = a, 1 = b); a forward turn runs t 0 -> 1, a backward one 1 -> 0. While the finger drives it
 * ([Turn.interactive]) t comes from [Turn.dragT], otherwise from the [Turn.anim] Animatable (spring / tween).
 */
class FlipDriver(
    private val scope: CoroutineScope,
    private val prefs: () -> PageFlipPrefs,
    private val effect: () -> PfEffect,
    private val itemCount: () -> Int,
    private val isCoverTurn: (Int) -> Boolean,
    private val onCommitted: (item: Int) -> Unit,
) {
    class Turn(val from: Int, val to: Int, val effect: PfEffect, val grabY: Float, val stack: List<Int>, val hardCover: Boolean) {
        val a: Int get() = min(from, to)
        val b: Int get() = max(from, to)
        val forward: Boolean get() = to > from
        val anim = Animatable(if (to > from) 0f else 1f)
        var dragT by mutableFloatStateOf(if (to > from) 0f else 1f)
        var interactive by mutableStateOf(false)
        /** Leaf-space finger of a curl drag (null = automatic path). */
        var finger by mutableStateOf<FloatArray?>(null)
        /** Finger at release: the settle blends from it to the automatic path. */
        var releaseFinger: FloatArray? = null
        var releaseT = 0f
        var endT = 0f
        /** Finished (committed or not); only the final frame is still shown. */
        var done = false
        val t: Float get() = if (interactive) dragT else anim.value
        /** Progress towards the target screen, 0..1. */
        val toward: Float get() = if (forward) t else 1f - t
    }

    var item by mutableIntStateOf(0)
    var turn by mutableStateOf<Turn?>(null); private set
    private var job: Job? = null
    private var queued: Int? = null

    val busy: Boolean get() = turn != null

    /** Reset to [i] without animation (layout / spread / document changes). */
    fun reset(i: Int) { job?.cancel(); turn = null; queued = null; item = i.coerceIn(0, max(0, itemCount() - 1)) }

    private fun newTurn(to: Int, grabY: Float): Turn {
        val from = item
        val e = effect()
        val dist = abs(to - from)
        val stack = if (e.book && dist > 1) {
            val k = FlipMath.stackSize(dist)
            val lo = min(from, to); val hi = max(from, to)
            (1 until k).map { i -> lo + (i * (hi - lo) / k.toFloat()).roundToInt() }.distinct().filter { it in (lo + 1) until hi }
        } else emptyList()
        val hard = e == PfEffect.BOOK && dist == 1 && isCoverTurn(min(from, to))
        return Turn(from, to, e, grabY, stack, hard)
    }

    /** Animated turn to screen [target] (tap, key, wheel, jump). Rapid requests finish the running turn first. */
    fun go(target: Int, grabY: Float = 0.5f) {
        val to = target.coerceIn(0, max(0, itemCount() - 1))
        val cur = turn
        if (cur != null && cur.done) turn = null
        else if (cur != null) {
            if (cur.interactive) return
            queued = to
            job?.cancel()
            finish(cur, commit = abs(cur.endT - (if (cur.forward) 1f else 0f)) < 0.001f, hold = false)
            return
        }
        if (to == item) return
        val e = effect()
        if (e == PfEffect.NONE) { item = to; onCommitted(to); return }
        val tr = newTurn(to, grabY)
        turn = tr
        settle(tr, commit = true, velocity = 0f)
    }

    /** Start a finger-driven turn by [delta] screens; false at the document ends. */
    fun begin(delta: Int, grabY: Float): Boolean {
        queued = null
        turn?.let { cur ->
            if (cur.done) turn = null
            else { job?.cancel(); finish(cur, commit = !cur.interactive && abs(cur.endT - (if (cur.forward) 1f else 0f)) < 0.001f, hold = false) }
        }
        val to = item + delta
        if (delta == 0 || to !in 0 until itemCount()) return false
        val tr = newTurn(to, grabY)
        if (tr.effect == PfEffect.NONE) { turn = tr; tr.interactive = true; return true }
        tr.interactive = true
        turn = tr
        return true
    }

    /** Long-press peek: lift the page a little and hold it. */
    fun peek(delta: Int, grabY: Float): Boolean {
        if (!begin(delta, grabY)) return false
        val tr = turn ?: return false
        tr.interactive = false
        val start = if (tr.forward) 0f else 1f
        val target = if (tr.forward) 0.2f else 0.8f
        tr.endT = target
        job = scope.launch {
            tr.anim.snapTo(start)
            tr.anim.animateTo(target, tween(220, easing = FastOutSlowInEasing))
        }
        return true
    }

    /** Take over a peeking / settling turn with the finger (keeps its current progress). */
    fun grab(): Turn? {
        val tr = turn?.takeUnless { it.done } ?: return null
        job?.cancel()
        tr.dragT = tr.anim.value
        tr.interactive = true
        return tr
    }

    fun drag(t: Float, finger: FloatArray?) {
        val tr = turn ?: return
        tr.dragT = t.coerceIn(0f, 1f)
        tr.finger = finger
    }

    /** Finger lifted with velocity [vRaw] (raw t units per second). */
    fun release(vRaw: Float) {
        val tr = turn ?: return
        val toward = if (tr.forward) vRaw else -vRaw
        val commit = FlipMath.shouldCommit(tr.toward, toward)
        settle(tr, commit, vRaw)
    }

    fun cancel() { turn?.let { settle(it, commit = false, velocity = 0f) } }

    private fun settle(tr: Turn, commit: Boolean, velocity: Float) {
        val p = prefs()
        val start = tr.t
        val end = if (commit == tr.forward) 1f else 0f
        tr.releaseFinger = tr.finger
        tr.releaseT = start
        tr.endT = end
        job = scope.launch {
            tr.anim.snapTo(start)
            tr.interactive = false
            if (tr.effect != PfEffect.NONE && abs(end - start) > 0.0005f) {
                val bouncy = p.bouncy && !tr.effect.book
                val spec = if (p.spring) spring(if (bouncy) 0.72f else 1f, FlipMath.stiffness(p.speed), 0.0005f)
                else tween(FlipMath.durationMs(abs(end - start), abs(velocity), tr.effect.baseMs * (if (tr.stack.isNotEmpty()) 2 else 1), p.speed), easing = FastOutSlowInEasing)
                tr.anim.animateTo(end, spec, initialVelocity = if (p.spring && !tr.effect.book) velocity else if (p.spring) velocity * 0.5f else 0f)
            }
            finish(tr, commit, hold = true)
        }
    }

    private fun finish(tr: Turn, commit: Boolean, hold: Boolean) {
        if (turn !== tr) return
        if (!tr.done && commit) { item = tr.to; onCommitted(tr.to) }
        tr.done = true
        val next = queued
        queued = null
        if (next != null && next != item) { turn = null; go(next); return }
        if (hold && commit) {
            // keep the final frame a moment so the interactive page underneath has drawn its bitmap
            scope.launch { delay(150); if (turn === tr) turn = null }
        } else turn = null
    }
}
