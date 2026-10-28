package com.smartdocs.editor.ui.reader

import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.ui.reader.view.AutoFlipBar
import com.smartdocs.editor.ui.reader.view.AutoFlipEffect
import com.smartdocs.editor.ui.reader.view.CropController
import com.smartdocs.editor.ui.reader.view.FastScroller
import com.smartdocs.editor.ui.reader.view.MagnifierWindow
import com.smartdocs.editor.ui.reader.view.PageSpread
import com.smartdocs.editor.ui.reader.view.readerGestures
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// NVE (feature/nitro-view-edit): 25 % of fit width up to 32x (zoom presets, numeric zoom, marquee zoom)
const val MIN_ZOOM = 0.25f
const val MAX_ZOOM = 32f

/**
 * Page area of the reader: continuous vertical list, single-page pager, facing pages or cover-facing pages
 * (page 1 alone, then 2-3, 4-5…), with pinch zoom, panning, double-tap zoom, page tracking, jumps, crop mode
 * (pages shown cut to their content box), the floating pan & zoom window and the auto-flip timer.
 *
 * Horizontal pan uses absolute offsets so the maths is identical in RTL and LTR layouts.
 */
@Composable
fun PageView(
    state: ReaderState,
    onTapCenter: () -> Unit,
    onOpenLink: (PageLink) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    // the document's own direction (/ViewerPreferences /Direction) wins over the app language
    val rtl = state.docRtl ?: (LocalLayoutDirection.current == LayoutDirection.Rtl)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val vx = state.vx
    val gapPx = with(density) { 8.dp.toPx() }
    val pairGapPx = if (vx.facingSeparator) gapPx else 0f
    val pairGapDp = if (vx.facingSeparator) 8.dp else 0.dp
    var viewport by remember { mutableStateOf(Size.Zero) }
    var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }     // only used by the pager modes
    val mode = state.viewMode
    val spread = remember(mode) { PageSpread(mode) }
    // NVE: continuous facing (two-up scrolling list, optional cover page) and reverse view (last page first)
    val nve = com.smartdocs.editor.ui.nve.NveState.of(state)
    val contSpread = remember(mode, nve.continuousFacing, nve.continuousCover) {
        if (mode == ViewMode.CONTINUOUS && nve.continuousFacing) PageSpread(if (nve.continuousCover) ViewMode.COVER else ViewMode.FACING) else null
    }
    val rev = nve.reverseView
    /** Display position <-> page (an involution: the same mapping both ways). */
    fun dp(i: Int): Int = if (rev) (state.pageCount - 1 - i).coerceAtLeast(0) else i
    fun contItemCount(): Int = contSpread?.itemCount(state.pageCount) ?: state.pageCount
    fun contPages(item: Int): List<Int> = contSpread?.pages(item, state.pageCount)?.map { dp(it) } ?: listOf(dp(item))
    fun contItemOf(page: Int): Int = contSpread?.pageToItem(dp(page)) ?: dp(page)
    val perItem = contSpread?.perItem ?: spread.perItem
    val cropOn = state.vx.crop.enabled
    // fresh scroll states per mode, starting at the page the user is on
    val listState = key(mode, contSpread, rev) { rememberLazyListState(initialFirstVisibleItemIndex = contItemOf(state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))) }
    val pagerState = key(mode, rev) { rememberPagerState(initialPage = spread.pageToItem(dp(state.currentPage))) { spread.itemCount(state.pageCount) } }

    // page size from the preloaded table (never suspends, never 0) so list items keep a stable height
    fun sizeOf(page: Int): Pair<Float, Float> = state.pageSizes[page] ?: state.pageSizes[state.currentPage] ?: state.pageSizes[0] ?: (595f to 842f)
    /** Crop of a page in fractions of its rotated layout (FULL when not cropped). */
    fun cropOf(page: Int): Rect = vx.crop.rectFor(page)?.let { CropController.toLayout(it, state.rotation) } ?: CropController.FULL
    /** Displayed aspect (width / height) of a page after view rotation and crop. */
    fun aspectOf(page: Int): Float {
        val (w, h) = sizeOf(page)
        val lw = if (state.rotation % 180 == 0) w else h; val lh = if (state.rotation % 180 == 0) h else w
        val c = cropOf(page)
        return (lw * c.width) / (lh * c.height).coerceAtLeast(0.001f)
    }
    // displayed page width (layout px) at zoom 1 — fit width unless "fit page"
    fun baseWidth(page: Int): Float {
        val avail = (viewport.width - gapPx * 2) / perItem - (if (perItem == 2) pairGapPx / 2 else 0f)
        if (!state.fitPage) return avail.coerceAtLeast(1f)
        return min(avail, (viewport.height - gapPx * 2) * aspectOf(page)).coerceAtLeast(1f)
    }
    fun pageHeight(page: Int, width: Float): Float = width / aspectOf(page)

    // ---- paged modes: geometry of the row (one page or a spread) shown by a pager item ----
    fun itemPages(item: Int): List<Int> = spread.pages(item, state.pageCount).map { dp(it) }   // NVE: reverse view
    fun rowSize(item: Int): Size {
        var w = 0f; var h = 0f
        itemPages(item).forEachIndexed { k, p ->
            val pw = baseWidth(p) * state.zoom
            w += pw + (if (k > 0) pairGapPx else 0f); h = max(h, pageHeight(p, pw))
        }
        return Size(w, h)
    }
    fun pagerContentSize(): Size = rowSize(pagerState.currentPage).let { Size(it.width + gapPx * 2, it.height + gapPx * 2) }
    /** Row rect in viewport px (centred, shifted by the pan when it is the current item). */
    fun rowRect(item: Int): Rect {
        val r = rowSize(item); val cur = item == pagerState.currentPage
        val left = (viewport.width - r.width) / 2f + (if (cur) panX else 0f)
        val top = (viewport.height - r.height) / 2f + (if (cur) panY else 0f)
        return Rect(left, top, left + r.width, top + r.height)
    }
    /** Rect of the k-th page of an item in viewport px (the first page is on the right in RTL). */
    fun pageRect(item: Int, k: Int): Rect {
        val pages = itemPages(item); val row = rowRect(item)
        val widths = pages.map { baseWidth(it) * state.zoom }
        var before = 0f; for (j in 0 until k) before += widths[j] + pairGapPx
        val w = widths[k]; val h = pageHeight(pages[k], w)
        val left = if (rtl) row.right - before - w else row.left + before
        val top = row.top + (row.height - h) / 2f
        return Rect(left, top, left + w, top + h)
    }
    /** Visible part of a viewport rect, in fractions of that rect (null when off screen). */
    fun visibleOf(r: Rect): Rect? {
        if (r.width <= 0f || r.height <= 0f) return null
        val l = ((0f - r.left) / r.width).coerceIn(0f, 1f); val rr = ((viewport.width - r.left) / r.width).coerceIn(0f, 1f)
        val t = ((0f - r.top) / r.height).coerceIn(0f, 1f); val b = ((viewport.height - r.top) / r.height).coerceIn(0f, 1f)
        return if (rr <= l || b <= t) null else Rect(l, t, rr, b)
    }
    /** NVE: width of one continuous row (one page, or two facing pages). */
    fun contRowWidth(item: Int): Float = contPages(item).mapIndexed { k, p -> baseWidth(p) * state.zoom + (if (k > 0) pairGapPx else 0f) }.sum()
    /** NVE: left edge (viewport px) of [page] inside its continuous row. */
    fun contPageLeft(item: Int, page: Int, w: Float): Float {
        val rowPages = contPages(item); val k = rowPages.indexOf(page).coerceAtLeast(0)
        val rowW = contRowWidth(item)
        var before = 0f; for (j in 0 until k) before += baseWidth(rowPages[j]) * state.zoom + pairGapPx
        val rowLeft = (viewport.width - rowW) / 2f + panX
        return if (rtl) rowLeft + rowW - before - w else rowLeft + before
    }
    fun continuousContentWidth(): Float = max(viewport.width, contRowWidth(contItemOf(state.currentPage)) + gapPx * 2)
    /** Visible part of a page box in the continuous list, in fractions of the displayed box. */
    fun continuousVisible(page: Int, w: Float): Rect? {
        val info = listState.layoutInfo
        val itemIndex = contItemOf(page)
        val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return null
        val top = item.offset.toFloat(); val h = item.size.toFloat().coerceAtLeast(1f)
        // the list is centred in the viewport and shifted by panX; the page is centred in its row (NVE: facing rows)
        val left = contPageLeft(itemIndex, page, w)
        val l = ((0f - left) / w).coerceIn(0f, 1f); val r = ((viewport.width - left) / w).coerceIn(0f, 1f)
        val t = ((info.viewportStartOffset - top) / h).coerceIn(0f, 1f); val b = ((info.viewportEndOffset - top) / h).coerceIn(0f, 1f)
        return if (r <= l || b <= t) null else Rect(l, t, r, b)
    }

    fun clampPan() {
        if (mode == ViewMode.CONTINUOUS) {
            val maxX = max(0f, (continuousContentWidth() - viewport.width) / 2f)
            panX = panX.coerceIn(-maxX, maxX); panY = 0f
        } else {
            val c = pagerContentSize()
            val maxX = max(0f, (c.width - viewport.width) / 2f); val maxY = max(0f, (c.height - viewport.height) / 2f)
            panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
        }
    }
    fun overflowX(): Boolean = if (mode == ViewMode.CONTINUOUS) continuousContentWidth() > viewport.width + 1f else pagerContentSize().width > viewport.width + 1f
    fun overflowY(): Boolean = mode != ViewMode.CONTINUOUS && pagerContentSize().height > viewport.height + 1f

    fun applyZoom(zc: Float, centroid: Offset, pan: Offset) {
        val z0 = state.zoom; val z1 = (z0 * zc).coerceIn(MIN_ZOOM, MAX_ZOOM); val r = z1 / z0
        val cx = centroid.x - viewport.width / 2f
        panX = cx - (cx - panX) * r + pan.x
        if (mode == ViewMode.CONTINUOUS) {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            if (first != null && abs(r - 1f) > 0.0005f) {
                val off = listState.firstVisibleItemScrollOffset.toFloat()
                val newOff = ((off + centroid.y) * r - centroid.y - pan.y).roundToInt().coerceAtLeast(0)
                state.zoom = z1
                listState.requestScrollToItem(first.index, newOff)
            } else {
                state.zoom = z1
                if (pan.y != 0f) listState.dispatchRawDelta(-pan.y)
            }
        } else {
            val cy = centroid.y - viewport.height / 2f
            panY = cy - (cy - panY) * r + pan.y
            state.zoom = z1
        }
        clampPan()
    }

    fun doubleTap(at: Offset) {
        if (state.zoom > 1.05f) { state.zoom = 1f; panX = 0f; panY = 0f }
        else applyZoom(2.5f, Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero)
    }

    // jumps requested by the toolbar / panels / search / auto flip / keys
    LaunchedEffect(state.pendingJump, mode) {
        val p = state.pendingJump ?: return@LaunchedEffect
        if (mode == ViewMode.CONTINUOUS) {
            listState.scrollToItem(contItemOf(p))
            // a point inside the page (JS doc.scroll): scroll that share of the page's height further
            state.pendingJumpTop?.let { f -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == contItemOf(p) }?.let { item -> listState.scrollBy(item.size * f) } }
        } else pagerState.animateScrollToPage(spread.pageToItem(dp(p)))
        state.pendingJump = null; state.pendingJumpTop = null
    }
    // keep the current page in sync with what the user sees
    if (mode == ViewMode.CONTINUOUS) {
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: -1
            }.collect { item -> val pg = if (item >= 0) contPages(item).firstOrNull() ?: -1 else -1; if (pg >= 0 && pg != state.currentPage) state.rememberPage(pg) }
        }
    } else {
        LaunchedEffect(pagerState, spread) { snapshotFlow { itemPages(pagerState.currentPage).firstOrNull() ?: dp(spread.firstPage(pagerState.currentPage)) }.collect { if (it != state.currentPage) state.rememberPage(it) } }
    }
    AutoFlipEffect(state)
    if (mode == ViewMode.CONTINUOUS) com.smartdocs.editor.ui.view5.V5AutoScrollEffect(state, listState)   // MODULE view5: smooth auto-scroll
    // reset pan when zoom returns to 1 or the layout changes
    LaunchedEffect(state.zoom, mode, state.fitPage, state.rotation, viewport, cropOn, vx.facingSeparator, contSpread) { clampPan() }
    // a new page in the pager starts at its top
    LaunchedEffect(pagerState.currentPage, mode) { if (mode != ViewMode.CONTINUOUS) { panY = Float.MAX_VALUE; clampPan() } }

    // audit RD-05: [p] is in root coordinates (PageCanvas adds its position); the zones are the screen's edges
    val onTap: (Offset, Size) -> Unit = { p, _ ->
        if (settings.tapEdgesFlip && state.tool == null) {
            when (TapZones.zone(p.x - viewportOrigin.x, viewport.width, rtl, zoomed = state.zoom > 1.01f)) {
                TapZones.Zone.PREV -> if (vx.canFlip(-1)) vx.flip(-1) else onTapCenter()
                TapZones.Zone.NEXT -> if (vx.canFlip(1)) vx.flip(1) else onTapCenter()
                TapZones.Zone.CENTER -> onTapCenter()
            }
        } else onTapCenter()
    }

    val decay = remember(density) { androidx.compose.animation.splineBasedDecay<Float>(density) }
    var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val toolBusy = { state.tool != null || state.placement != null }

    val gestureModifier = Modifier.readerGestures(
        // decide once per gesture, when the finger crosses the touch slop
        canTake = { d ->
            if (toolBusy() || state.selection != null) false
            else if (mode == ViewMode.CONTINUOUS) state.zoom > 1.01f              // zoomed list: we own both axes
            else {
                val ox = overflowX(); val oy = overflowY()
                when {
                    ox -> true                                                     // zoomed page: pan freely
                    oy -> abs(d.y) > abs(d.x)                                      // tall page: vertical drags pan, horizontal ones turn pages
                    else -> false
                }
            }
        },
        onDown = { flingJob?.cancel() },
        onPinch = { z, c, p -> flingJob?.cancel(); applyZoom(z, c, p) },
        onPan = { d0 ->
            // NVE: scroll lock (1 = horizontal locked, 2 = vertical locked)
            val d = when (nve.scrollLock) { 1 -> Offset(0f, d0.y); 2 -> Offset(d0.x, 0f); else -> d0 }
            panX += d.x
            if (mode == ViewMode.CONTINUOUS) listState.dispatchRawDelta(-d.y) else panY += d.y
            clampPan()
        },
        onFling = { v ->
            flingJob = scope.launch {
                launch {
                    var last = 0f
                    androidx.compose.animation.core.AnimationState(0f, v.x).animateDecay(decay) {
                        val before = panX; panX += value - last; last = value; clampPan()
                        if (panX == before) cancelAnimation()     // reached the horizontal edge
                    }
                }
                if (mode == ViewMode.CONTINUOUS) {
                    listState.scroll {
                        var last = 0f
                        androidx.compose.animation.core.AnimationState(0f, -v.y).animateDecay(decay) {
                            val delta = value - last; last = value
                            val used = scrollBy(delta)
                            if (abs(used - delta) > 0.5f) cancelAnimation()
                        }
                    }
                } else {
                    var last = 0f
                    androidx.compose.animation.core.AnimationState(0f, v.y).animateDecay(decay) {
                        val before = panY; panY += value - last; last = value; clampPan()
                        if (panY == before) cancelAnimation()
                    }
                }
            }
        },
    )

    // ---- floating pan & zoom window: mini-map of the current page (continuous) or spread (paged) ----
    val showMagnifier = vx.magnifier && !cropOn && !state.reflow
    var magDragging by remember { mutableStateOf(false) }
    var magPages by remember { mutableStateOf(emptyList<Int>()) }
    val shownPages = if (mode == ViewMode.CONTINUOUS) listOf(state.currentPage) else itemPages(pagerState.currentPage)
    LaunchedEffect(shownPages, magDragging) { if (!magDragging) magPages = shownPages }
    fun magVisible(): Rect? = if (mode == ViewMode.CONTINUOUS) {
        magPages.firstOrNull()?.let { p -> continuousVisible(p, baseWidth(p) * state.zoom) }
    } else visibleOf(rowRect(pagerState.currentPage))
    fun magMove(c: Offset) {
        if (mode == ViewMode.CONTINUOUS) {
            val page = magPages.firstOrNull() ?: return
            val w = baseWidth(page) * state.zoom; val h = pageHeight(page, w)
            panX = w * (0.5f - c.x); clampPan()
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == contItemOf(page) }
            if (item == null) { scope.launch { listState.scrollToItem(contItemOf(page)) }; return }
            val desiredTop = (info.viewportStartOffset + info.viewportEndOffset) / 2f - c.y * h
            listState.dispatchRawDelta(item.offset - desiredTop)
        } else {
            val row = rowSize(pagerState.currentPage)
            panX = row.width * (0.5f - c.x); panY = row.height * (0.5f - c.y); clampPan()
        }
    }

    // NVE: publish the page-area geometry for zoom presets, marquee zoom, page-anchored grid / guides
    com.smartdocs.editor.ui.nve.NveZoom.bind(nve, viewport, ::baseWidth, ::applyZoom) {
        if (mode == ViewMode.CONTINUOUS) {
            val info = listState.layoutInfo
            info.visibleItemsInfo.flatMap { vi ->
                contPages(vi.index).map { p ->
                    val w = baseWidth(p) * state.zoom; val h = pageHeight(p, w)
                    val left = contPageLeft(vi.index, p, w)
                    val top = (vi.offset - info.viewportStartOffset).toFloat() + (vi.size - h) / 2f
                    com.smartdocs.editor.ui.nve.VisiblePage(p, Rect(left, top, left + w, top + h))
                }
            }
        } else {
            val cur = pagerState.currentPage; val ps = itemPages(cur)
            ps.indices.map { k -> com.smartdocs.editor.ui.nve.VisiblePage(ps[k], pageRect(cur, k)) }
        }
    }
    Box(modifier.fillMaxSize().background(if (state.nightMode) Color_Night else MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .onGloballyPositioned { viewportOrigin = it.positionInRoot() }
                .clipToBounds().then(gestureModifier),
        ) {
            if (viewport == Size.Zero || state.pageCount == 0) return@Box
            when (mode) {
                ViewMode.CONTINUOUS -> {
                    val contentW = continuousContentWidth()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.absoluteOffset { IntOffset(panX.roundToInt(), 0) }.requiredWidth(with(density) { contentW.toDp() }).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = state.zoom <= 1.01f || toolBusy(),
                    ) {
                        items(contItemCount(), key = { it }) { item ->
                            // NVE: one page per row, or two facing pages (continuous facing); reverse view maps rows to pages
                            val rowPages = contPages(item)
                            // tab-view-core V-15: facing rows in the document's direction (as contPageLeft computes them)
                            val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                            Row(horizontalArrangement = Arrangement.spacedBy(pairGapDp), verticalAlignment = Alignment.CenterVertically) {
                                (if (rtl == uiRtl) rowPages else rowPages.reversed()).forEach { page ->
                                    if (cropOn) LaunchedEffect(page, vx.crop.manual, state.renderVersion) { vx.crop.ensure(page); vx.crop.ensure(page + 1) }
                                    val w = baseWidth(page) * state.zoom
                                    DisplayedPage(
                                        state, page, w, pageHeight(page, w), cropOf(page),
                                        visibleBox = { continuousVisible(page, w) },
                                        onTap = onTap, onDoubleTap = ::doubleTap, onOpenLink = onOpenLink, onPrompt = onPrompt,
                                    )
                                }
                            }
                        }
                    }
                    if (state.pageCount > 3) FastScroller(
                        listState = listState, pageCount = contItemCount(), currentPage = contItemOf(state.currentPage),
                        modifier = Modifier.align(AbsoluteAlignment.CenterRight),
                    )
                }
                ViewMode.SINGLE, ViewMode.FACING, ViewMode.COVER -> {
                    // tab-view-core V-15: swipe direction and spread order follow the document's direction (docRtl), like
                    // the tap zones; the pages themselves keep the app's direction
                    val uiDir = LocalLayoutDirection.current
                    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    HorizontalPager(
                        state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                        userScrollEnabled = !overflowX() && !toolBusy(),
                    ) { item ->
                        val pages = itemPages(item)
                        if (cropOn) LaunchedEffect(item, vx.crop.manual, state.renderVersion) { pages.forEach { vx.crop.ensure(it) } }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(
                                Modifier.absoluteOffset { if (item == pagerState.currentPage) IntOffset(panX.roundToInt(), panY.roundToInt()) else IntOffset.Zero }
                                    .wrapContentSize(Alignment.Center, unbounded = true),
                                horizontalArrangement = Arrangement.spacedBy(pairGapDp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                pages.forEachIndexed { k, page ->
                                    val w = baseWidth(page) * state.zoom
                                    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides uiDir) {
                                    DisplayedPage(
                                        state, page, w, pageHeight(page, w), cropOf(page),
                                        visibleBox = { visibleOf(pageRect(item, k)) },
                                        onTap = onTap, onDoubleTap = ::doubleTap, onOpenLink = onOpenLink, onPrompt = onPrompt,
                                    )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        if (showMagnifier && viewport != Size.Zero && state.pageCount > 0) MagnifierWindow(
            state = state, pages = magPages, bounds = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt()),
            visible = ::magVisible, onMove = ::magMove, onDragging = { magDragging = it },
            onZoom = { z -> applyZoom(z / state.zoom, Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero) },
            onClose = { vx.magnifier = false },
            modifier = Modifier.align(AbsoluteAlignment.TopLeft),
        )
        // 7.10: floating pop-up note window
        if (viewport != Size.Zero) com.smartdocs.editor.ui.reader.annot.FloatingNoteWindow(state, IntSize(viewport.width.roundToInt(), viewport.height.roundToInt()),
            Modifier.align(AbsoluteAlignment.TopLeft))
        AutoFlipBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }
}

/**
 * One page as displayed: the full [PageCanvas] when not cropped; otherwise the canvas is laid out at full-page
 * size ([w] / crop width) and shifted inside a clipped box of [w]x[h], so its geometry (annotations, selection,
 * links, tools) is exactly the uncropped one.
 */
@Composable
private fun DisplayedPage(
    state: ReaderState, page: Int, w: Float, h: Float, crop: Rect,
    visibleBox: () -> Rect?,
    onTap: (Offset, Size) -> Unit, onDoubleTap: (Offset) -> Unit, onOpenLink: (PageLink) -> Unit, onPrompt: (TextPrompt) -> Unit,
) {
    if (crop == CropController.FULL) {
        PageCanvas(state, page, w, visibleClip = visibleBox, onTap = onTap, onDoubleTap = onDoubleTap, onOpenLink = onOpenLink, onPrompt = onPrompt,
            modifier = Modifier.pageShadow())
        return
    }
    val density = LocalDensity.current
    val fullW = w / crop.width; val fullH = h / crop.height
    Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).pageShadow().clipToBounds()) {
        PageCanvas(
            state, page, fullW,
            visibleClip = { visibleBox()?.let { CropController.boxToFull(it, crop) } },
            onTap = onTap, onDoubleTap = onDoubleTap, onOpenLink = onOpenLink, onPrompt = onPrompt,
            modifier = Modifier.wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
                .absoluteOffset { IntOffset(-(crop.left * fullW).roundToInt(), -(crop.top * fullH).roundToInt()) },
        )
    }
}

private val Color_Night = androidx.compose.ui.graphics.Color(0xFF121212)

private fun Modifier.pageShadow(): Modifier = this.then(Modifier.shadow(2.dp, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
