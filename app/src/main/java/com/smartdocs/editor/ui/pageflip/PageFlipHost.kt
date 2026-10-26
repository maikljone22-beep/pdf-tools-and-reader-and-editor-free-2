package com.smartdocs.editor.ui.pageflip

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.core.pageflip.CurlMath
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.PageFlipPrefs
import com.smartdocs.editor.core.pageflip.PfAxis
import com.smartdocs.editor.core.pageflip.PfEffect
import com.smartdocs.editor.core.pageflip.PfLayout
import com.smartdocs.editor.core.pageflip.SpreadMap
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.PageCanvas
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextPrompt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "page-turn" view mode of the reader (replaces PageView while [PageFlipController.hosts]): the current screen is
 * the normal interactive [PageCanvas] (selection, links, annotations, forms, search hits, zoom); while a page turns, a
 * full-viewport canvas draws the transition on page snapshots, then the interactive page is shown again.
 * Every jump of the reader (`pendingJump`: keys, auto flip, outline, search, history) is animated here.
 */
@Composable
fun PageFlipHost(
    state: ReaderState,
    chrome: Boolean,
    onTapCenter: () -> Unit,
    onOpenLink: (PageLink) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pf = remember(state) { PageFlipController.of(state) }
    if (pf.prefs.layout == PfLayout.SCROLL_H) { HorizontalScrollHost(state, pf, chrome, onTapCenter, onOpenLink, onPrompt, modifier); return }
    val session = state.session ?: return
    val prefs = pf.prefs
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(session) { pf.detectDirection() }
    val rtl = pf.rtl(uiRtl)
    val spreadKind = pf.spread()
    LaunchedEffect(spreadKind) { pf.rememberSpread() }
    val map = remember(spreadKind) { SpreadMap(spreadKind) }
    val animOff = remember { PageFlipController.animatorScale(ctx) == 0f }
    val effect = prefs.effectiveEffect(animOff)
    val book = effect == PfEffect.BOOK
    val lowRam = remember { PageFlipController.lowRam(ctx) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val vw = viewport.width.toFloat(); val vh = viewport.height.toFloat()
    val marginPx = with(density) { (if (book) prefs.marginDp.dp + (if (prefs.thickness) 6.dp else 0.dp) else 6.dp).toPx() }
    val gapPx = if (effect.book || !state.vx.facingSeparator) 0f else with(density) { 8.dp.toPx() }
    val sizesKnown = state.pageSizes.size
    val geo = remember(map, state.pageCount, rtl, vw, vh, marginPx, gapPx, sizesKnown) {
        FlipGeometry(map, state.pageCount, rtl, vw, vh, marginPx, gapPx) { p -> state.pageSizes[p] ?: state.pageSizes[0] ?: (595f to 842f) }
    }
    val cache = remember(session, state.renderVersion, state.nightMode) { PageSnapshotCache(session, state.nightMode, lowRam) }
    val renderer = remember { FlipRenderer() }
    val driver = remember(map, state.pageCount) {
        FlipDriver(scope, { pf.prefs }, { pf.prefs.effectiveEffect(animOff) }, { map.itemCount(state.pageCount) }, { map.isCoverTurn(it, state.pageCount) }) { item ->
            state.rememberPage(map.firstPage(item)); pf.haptic(view)
        }.also { it.item = map.pageToItem(state.currentPage).coerceIn(0, (map.itemCount(state.pageCount) - 1).coerceAtLeast(0)) }
    }
    val turn = driver.turn
    SideEffect { pf.animating = turn != null }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var riffleTarget by remember { mutableStateOf<Int?>(null) }

    fun clampPan() {
        val (mx, my) = FlipGeometry.maxPan(geo.bounds(driver.item), state.zoom, vw, vh)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
    }
    LaunchedEffect(state.zoom, driver.item, viewport) { if (state.zoom <= 1.01f) { panX = 0f; panY = 0f } else clampPan() }

    com.smartdocs.editor.ui.reader.view.AutoFlipEffect(state)   // auto flip timer -> vx.flip -> animated jump
    // every jump of the reader is animated
    LaunchedEffect(state.pendingJump, driver) {
        val p = state.pendingJump ?: return@LaunchedEffect
        state.pendingJump = null
        val target = map.pageToItem(p).coerceIn(0, (map.itemCount(state.pageCount) - 1).coerceAtLeast(0))
        if (state.zoom > 1.01f) state.zoom = 1f
        driver.go(target, grabY = 0.86f)
    }
    // snapshots of the screens around the current one (±1, ±2 when memory allows)
    LaunchedEffect(driver.item, cache, geo) {
        val range = if (lowRam) 1 else 2
        for (d in listOf(1, -1, 2, -2)) {
            if (abs(d) > range) continue
            val it = driver.item + d
            if (it !in 0 until map.itemCount(state.pageCount)) continue
            geo.rects(it).forEach { (p, r, _) -> cache.request(scope, p, r.width().roundToInt(), r.height().roundToInt()) }
        }
        cache.retainAround(map.firstPage(driver.item), 6)
    }
    LaunchedEffect(turn, cache) {
        val tr = turn ?: return@LaunchedEffect
        listOf(tr.a, tr.b).forEach { i -> geo.rects(i).forEach { (p, r, _) -> cache.request(scope, p, r.width().roundToInt(), r.height().roundToInt()) } }
        tr.stack.forEach { i -> geo.rects(i).forEach { (p, r, _) -> cache.requestThumb(scope, p, (r.width() * 0.35f).roundToInt(), r.width() / r.height()) } }
    }

    fun snap(item: Int, thumb: Boolean = false): ItemSnap = ItemSnap(item, geo.rects(item).map { (p, r, side) ->
        val w = r.width().roundToInt(); val h = r.height().roundToInt()
        PageSnap(p, r, side, if (thumb) cache.peek(p, (w * 0.35f).roundToInt(), (h * 0.35f).roundToInt()) else cache.peek(p, w, h))
    })
    val bg = if (state.nightMode) 0xFF121212.toInt() else MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    fun style(firstItem: Int, lastItem: Int) = FlipStyle(
        prefs.intensity, prefs.shadows, prefs.curvature, prefs.thickness && book, bg, density.density,
        pagesBefore = map.firstPage(firstItem), pagesAfter = (state.pageCount - 1 - (map.pages(lastItem, state.pageCount).maxOrNull() ?: 0)).coerceAtLeast(0),
        book = book, pageColor = if (state.nightMode) 0xFFFFFFFFL else state.pageColor,
    )
    val lift = 0.05f + 0.06f * prefs.intensity
    fun scene(tr: FlipDriver.Turn): FlipScene {
        val a = snap(tr.a); val b = snap(tr.b)
        var finger = tr.finger
        val rf = tr.releaseFinger
        if (!tr.interactive && rf != null && tr.effect.book) {
            val leaf = Leaf.of(a, b, rtl)
            if (leaf != null) {
                val auto = FloatArray(2); CurlMath.autoPath(tr.t, tr.grabY, leaf.w, leaf.h, lift, auto)
                val span = abs(tr.endT - tr.releaseT)
                val k = if (span < 0.0001f) 1f else (abs(tr.t - tr.releaseT) / span).coerceIn(0f, 1f)
                finger = floatArrayOf(rf[0] + (auto[0] - rf[0]) * k, rf[1] + (auto[1] - rf[1]) * k)
            }
        } else if (!tr.interactive) finger = null
        return FlipScene(tr.effect, prefs.axis, rtl, a, b, tr.t, finger, tr.grabY, tr.stack.map { snap(it, thumb = true) }, tr.hardCover)
    }

    // ---- gestures ----
    val g = remember { HostGestures() }
    g.bind(state, pf, driver, geo, map, rtl, prefs, vw, vh, view,
        pan = { d -> panX += d.x; panY += d.y; clampPan() },
        zoomBy = { z, c, p ->
            val z0 = state.zoom; val z1 = (z0 * z).coerceIn(MIN_ZOOM, MAX_ZOOM); val r = z1 / z0
            val cx = c.x - vw / 2f; val cy = c.y - vh / 2f
            panX = cx - (cx - panX) * r + p.x; panY = cy - (cy - panY) * r + p.y
            state.zoom = z1; clampPan()
        },
        riffle = { riffleTarget = it },
    )

    Box(modifier.fillMaxSize().background(Color(bg))) {
    // pages + transition overlay take the gestures; the controls below sit outside the gesture layer
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.clipToBounds().flipGestures(g)) {
        if (vw <= 0f || state.pageCount == 0) return@Box
        val item = driver.item
        val base = geo.rects(item)
        val zoom = state.zoom
        val shown = base.map { (p, r, side) -> Triple(p, FlipGeometry.zoomed(r, zoom, panX, panY, vw, vh), side) }
        val settledStyle = style(item, item)
        if (book) Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { renderer.bookUnder(it.nativeCanvas, ItemSnap(item, shown.map { s -> PageSnap(s.first, s.second, s.third, null) }), settledStyle, rtl) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
            base.forEachIndexed { k, (page, r0, _) ->
                val r = shown[k].second
                key(page) {
                    PageCanvas(
                        state, page, r.width(),
                        visibleClip = { visibleOf(FlipGeometry.zoomed(r0, state.zoom, panX, panY, vw, vh), vw, vh) },
                        onTap = { _, _ -> onTapCenter() },
                        onDoubleTap = { if (state.zoom > 1.05f) state.zoom = 1f else { state.zoom = 2.5f; clampPan() } },
                        onOpenLink = onOpenLink, onPrompt = onPrompt,
                        modifier = Modifier.absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                            .wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
                            .shadow(2.dp, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        if (book) Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { renderer.bookOver(it.nativeCanvas, ItemSnap(item, shown.map { s -> PageSnap(s.first, s.second, s.third, null) }), settledStyle, rtl) }
        }
        if (turn != null) {
            val trStyle = style(turn.a, turn.b)
            Canvas(Modifier.fillMaxSize()) {
                cache.version.intValue
                drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, scene(turn), trStyle) }
            }
        }
    }
        riffleTarget?.let { t -> StackPreview(state, cache, scope, map, t, driver.item, Modifier.align(Alignment.Center)) }
        com.smartdocs.editor.ui.reader.view.AutoFlipBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        if (chrome && turn == null && state.pageCount > 1) PageScrubber(
            state, cache, scope, rtl, view, pf,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 44.dp),
        )
    }
}

/** Visible part of a viewport rect in fractions of that rect (null when off screen). */
internal fun visibleOf(r: RectF, vw: Float, vh: Float): Rect? {
    if (r.width() <= 0f || r.height() <= 0f) return null
    val l = ((0f - r.left) / r.width()).coerceIn(0f, 1f); val rr = ((vw - r.left) / r.width()).coerceIn(0f, 1f)
    val t = ((0f - r.top) / r.height()).coerceIn(0f, 1f); val b = ((vh - r.top) / r.height()).coerceIn(0f, 1f)
    return if (rr <= l || b <= t) null else Rect(l, t, rr, b)
}

/** Gesture receiver of [PageFlipHost]; rebound on every composition so it always sees the current values. */
private class HostGestures : FlipGestureTarget {
    private lateinit var s: ReaderState
    private lateinit var pf: PageFlipController
    private lateinit var driver: FlipDriver
    private lateinit var geo: FlipGeometry
    private lateinit var map: SpreadMap
    private lateinit var view: View
    private var prefs = PageFlipPrefs()
    private var vw = 1f; private var vh = 1f
    private var pan: (Offset) -> Unit = {}
    private var zoomBy: (Float, Offset, Offset) -> Unit = { _, _, _ -> }
    private var riffle: (Int?) -> Unit = {}
    private var rtlV = false
    override val rtl: Boolean get() = rtlV

    fun bind(
        s: ReaderState, pf: PageFlipController, driver: FlipDriver, geo: FlipGeometry, map: SpreadMap, rtl: Boolean,
        prefs: PageFlipPrefs, vw: Float, vh: Float, view: View,
        pan: (Offset) -> Unit, zoomBy: (Float, Offset, Offset) -> Unit, riffle: (Int?) -> Unit,
    ) {
        this.s = s; this.pf = pf; this.driver = driver; this.geo = geo; this.map = map; this.rtlV = rtl; this.prefs = prefs
        this.vw = vw.coerceAtLeast(1f); this.vh = vh.coerceAtLeast(1f); this.view = view
        this.pan = pan; this.zoomBy = zoomBy; this.riffle = riffle
    }

    override val zoomed: Boolean get() = s.zoom > 1.01f
    override val busy: Boolean get() = s.tool != null || s.placement != null || s.selection != null
    override val edgeZone: Float? get() = if (prefs.edgeZones) prefs.edgeZone else null
    override val axis: PfAxis get() = if (prefs.effectiveEffect(false).book) PfAxis.HORIZONTAL else prefs.axis
    override val peekEnabled: Boolean get() = prefs.peek

    // drag bookkeeping
    private var start = Offset.Zero
    private var t0 = 0f
    private var curl = false
    private var gain = 1f
    private var leaf: Leaf? = null
    private val f0 = FloatArray(2)
    private var grabbed = false
    private var peeking = false
    private var riffleBase = 0
    private var riffleLast: Int? = null
    private var wheelAcc = 0f
    private var wheelAt = 0L

    override fun onPinch(zoom: Float, centroid: Offset, pan: Offset) = zoomBy(zoom, centroid, pan)
    override fun onPan(delta: Offset) = pan(delta)

    override fun onZoneTap(zone: Int, at: Offset) {
        val target = driver.item + zone
        if (target in 0 until map.itemCount(s.pageCount)) driver.go(target, grabY = 0.86f)
    }

    private fun prepare(start: Offset, fromZone: Boolean) {
        val tr = driver.turn ?: return
        curl = tr.effect.book
        val a = geoSnap(tr.a); val b = geoSnap(tr.b)
        leaf = Leaf.of(a, b, rtl)
        this.start = start
        t0 = tr.t
        gain = if (fromZone) 1f else 1.6f
        val l = leaf
        if (curl && l != null) {
            if (tr.t <= 0.0005f || tr.t >= 0.9995f) { f0[0] = if (tr.t < 0.5f) l.w else -l.w; f0[1] = tr.grabY * l.h }
            else CurlMath.autoPath(tr.t, tr.grabY, l.w, l.h, 0.05f + 0.06f * prefs.intensity, f0)
        }
    }

    private fun geoSnap(item: Int) = ItemSnap(item, geo.rects(item).map { (p, r, side) -> PageSnap(p, r, side, null) })

    private fun grabYAt(y: Float, item: Int): Float {
        val r = geo.bounds(item)
        return FlipMath.grabY(((y - r.top) / r.height().coerceAtLeast(1f)))
    }

    override fun onTurnStart(delta: Int, start: Offset, fromZone: Boolean): Boolean {
        peeking = false; grabbed = true
        if (!driver.begin(delta, grabYAt(start.y, driver.item))) return false
        prepare(start, fromZone)
        return true
    }

    override fun onPeek(zone: Int, at: Offset): Boolean {
        if (!prefs.peek) return false
        if (!driver.peek(zone, grabYAt(at.y, driver.item))) return false
        peeking = true; grabbed = false
        pf.haptic(view, strong = true)
        return true
    }

    override fun onTurnDrag(pos: Offset) {
        if (peeking && !grabbed) { driver.grab() ?: return; grabbed = true; prepare(pos, true) }
        val tr = driver.turn ?: return
        val dir = if (rtl) -1f else 1f
        val l = leaf
        if (curl && l != null) {
            val fx = f0[0] + dir * (pos.x - start.x) * gain
            val fy = f0[1] + (pos.y - start.y)
            val out = FloatArray(2)
            CurlMath.constrain(fx, fy, l.w, tr.grabY * l.h, l.h, out)
            driver.drag(CurlMath.progressOf(out[0], l.w), floatArrayOf(fx, fy))
        } else {
            val dt = if (axis == PfAxis.VERTICAL) -(pos.y - start.y) / vh else -(dir * (pos.x - start.x)) / vw
            driver.drag(t0 + dt * gain.coerceAtMost(1.3f), null)
        }
    }

    override fun onTurnEnd(velocity: Velocity, peekOnly: Boolean) {
        if (peeking && (peekOnly || !grabbed)) { peeking = false; driver.cancel(); return }
        peeking = false
        val dir = if (rtl) -1f else 1f
        val l = leaf
        val v = if (curl && l != null) -(dir * velocity.x * gain) / (2f * l.w)
        else if (axis == PfAxis.VERTICAL) -velocity.y / vh else -(dir * velocity.x) / vw
        driver.release(v)
    }

    override fun onRiffleStart(at: Offset) { riffleBase = driver.item; riffleLast = null }

    override fun onRiffle(distance: Float) {
        val step = 18f * view.resources.displayMetrics.density
        val target = (riffleBase + FlipMath.rifflePages(distance, step)).coerceIn(0, (map.itemCount(s.pageCount) - 1).coerceAtLeast(0))
        if (target != riffleLast) { riffleLast = target; riffle(target); pf.haptic(view) }
    }

    override fun onRiffleEnd(cancel: Boolean) {
        val t = riffleLast
        riffle(null); riffleLast = null
        if (!cancel && t != null && t != driver.item) driver.go(t, grabY = 0.86f)
    }

    override fun onWheel(dy: Float, dx: Float) {
        if (zoomed) { pan(Offset(-dx * 48f, -dy * 48f)); return }
        wheelAcc += if (abs(dy) >= abs(dx)) dy else (if (rtl) -dx else dx)
        val now = SystemClock.uptimeMillis()
        if (abs(wheelAcc) >= 1f && now - wheelAt > 140L) {
            val d = if (wheelAcc > 0) 1 else -1
            wheelAcc = 0f; wheelAt = now
            val target = driver.item + d
            if (target in 0 until map.itemCount(s.pageCount)) driver.go(target, grabY = 0.86f)
        }
    }
}
