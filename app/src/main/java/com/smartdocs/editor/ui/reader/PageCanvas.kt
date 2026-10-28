package com.smartdocs.editor.ui.reader

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.ui.reader.annot.drawAnnot2Overlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_RENDER_PX = 3200f

/** A request to type text for a tapped annotation tool. Position in PDF space. */
data class TextPrompt(val tool: ReaderTool, val page: Int, val x: Float, val y: Float, val rect: RectF? = null)

/**
 * One rendered page with every overlay (pending annotations, selection, search hits, links, placement, measure)
 * and the gesture handling of the active tool. [widthPx] is the layout width in pixels (already multiplied by zoom).
 */
@Composable
fun PageCanvas(
    state: ReaderState,
    page: Int,
    widthPx: Float,
    visibleClip: () -> Rect?,
    onTap: (Offset, Size) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onOpenLink: (PageLink) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val accent = MaterialTheme.colorScheme.primary
    val session = state.session
    val rotation = state.rotation
    val night = state.nightMode
    val version = state.renderVersion

    val size by produceState(state.pageSizes[page] ?: (595f to 842f), page, session) { value = state.pageSize(page) }
    val (pw, ph) = size
    val scale = widthPx / (if (rotation % 180 == 0) pw else ph)
    val frame = state.pageFrames[page]
    val g = remember(pw, ph, scale, rotation, frame) { PageGeometry(pw, ph, scale, rotation, frame?.rotate ?: 0, frame?.cropX ?: 0f, frame?.cropY ?: 0f) }
    val layout = g.layoutSize
    val factor = min(1f, MAX_RENDER_PX / max(g.bitmapW, g.bitmapH))
    val renderW = (g.bitmapW * factor).roundToInt().coerceAtLeast(1)
    val renderH = (g.bitmapH * factor).roundToInt().coerceAtLeast(1)
    // quantise so that a continuous pinch does not re-render every frame
    val renderKeyW = (renderW / 48) * 48

    var base by remember(page) { mutableStateOf<Bitmap?>(null) }
    var tile by remember(page) { mutableStateOf<Pair<Rect, Bitmap>?>(null) }
    // section 7.65: "hide comments" renders the page without annotations (PDFium FPDF_ANNOT off)
    val showAnnots = !state.annot2.cx.hidden
    LaunchedEffect(page, renderKeyW, night, version, session, showAnnots) {
        if (session == null || session.isClosed || page !in 0 until session.pageCount) return@LaunchedEffect
        if (base != null) delay(140)
        val w = renderKeyW.coerceAtLeast(1); val h = (w * g.bitmapH / g.bitmapW).roundToInt().coerceAtLeast(1)
        // keep the previous bitmap when a render fails (e.g. the session is being swapped during save)
        runCatching { session.render(page, w, h, night, showAnnots) }.getOrNull()?.let { base = it }
    }
    LaunchedEffect(page, factor < 0.999f, night, version, session, renderKeyW, showAnnots) {
        if (session == null || session.isClosed || factor >= 0.999f) { tile = null; return@LaunchedEffect }
        snapshotFlow { visibleClip() }
            .map { c -> c?.let { quantise(it) } }
            .distinctUntilChanged()
            .collectLatest { clip ->
                if (clip == null) { tile = null; return@collectLatest }
                delay(220)
                val screenClip = layoutFractionToScreenFraction(clip, g)
                val bmp = runCatching { session.renderRegion(page, g.bitmapW.roundToInt(), g.bitmapH.roundToInt(), RectF(screenClip.left, screenClip.top, screenClip.right, screenClip.bottom), night, showAnnots) }.getOrNull()
                tile = bmp?.let { screenClip to it }
            }
    }
    val links by produceState<List<PageLink>>(emptyList(), page, session) { value = runCatching { session?.links(page) }.getOrNull() ?: emptyList() }

    val draft = remember(page) { GestureDraft() }
    val annots = state.annots
    val selection = state.selection?.takeIf { it.page == page }
    val placement = state.placement?.takeIf { it.page == page }
    val measure = state.measure?.takeIf { it.page == page }
    val hits = state.searchHits
    val hitIndex = state.searchIndex
    // nav-prefs: search hit colours and the accessibility colour override (Settings → more preferences)
    val hitColor = Color(com.smartdocs.editor.core.data.NpfPrefs.hitColor); val hitCurColor = Color(com.smartdocs.editor.core.data.NpfPrefs.hitCurrentColor)
    val a11yFilter = NpfReader.pageFilter(night)
    val pageColor = state.pageColor
    val pressure = settings.pressureSensitivity
    val highlightLinks = settings.highlightLinks
    val wDp = with(density) { layout.width.toDp() }; val hDp = with(density) { layout.height.toDp() }
    val editTarget = state.edit.target?.takeIf { it.page == page && state.tool == null }
    val editAnnot = editTarget?.let { state.edit.current() }
    val editBounds = editTarget?.let { state.edit.bounds() }
    val pendingEditId = (editTarget as? AnnotTarget.Pending)?.id

    // audit RD-04: the tap detector below restarts only for a new page / geometry; it must still call the newest
    // callbacks (view mode, tool, links), not the ones of its first composition
    val onTapNow by rememberUpdatedState(onTap)
    val onDoubleTapNow by rememberUpdatedState(onDoubleTap)
    val onOpenLinkNow by rememberUpdatedState(onOpenLink)
    val onPromptNow by rememberUpdatedState(onPrompt)
    val linksNow by rememberUpdatedState(links)
    val layoutNow by rememberUpdatedState(layout)
    // audit RD-05: edge zones are measured on the screen, so taps are reported in root coordinates
    var rootPos by remember { mutableStateOf(Offset.Zero) }

    Box(modifier.size(wDp, hDp)) {
    Canvas(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { rootPos = it.positionInRoot() }
            .pointerInput(page, g) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        // a double tap finishes a polygon / polyline / cloud (MODULE_annot2)
                        val ds = g.layoutToScreen(p); val (dx, dy) = g.screenToPdf(ds.x, ds.y)
                        if (!state.annot2.onDoubleTap(page, dx, dy)) onDoubleTapNow(p)
                    },
                    onLongPress = { p ->
                        if (state.tool != null) return@detectTapGestures
                        val s = g.layoutToScreen(p); val (x, y) = g.screenToPdf(s.x, s.y)
                        // an annotation under the finger wins over text selection
                        if (state.placement == null && state.edit.selectAt(page, x, y, 8f / g.scale.coerceAtLeast(0.1f))) return@detectTapGestures
                        // nitro-fillsign2: a stamp on the clipboard can be pasted where the finger is
                        if (state.placement == null && StampClipboard.annot != null) StampClipboard.offer = StampClipboard.Offer(state, page, x, y)
                        state.scope.launch {
                            val boxes = state.charBoxes(page); val text = state.pageText(page)
                            val i = TextSelectionLogic.charAt(boxes, x, y) ?: return@launch
                            val r = TextSelectionLogic.wordAt(text, i)
                            state.selection = TextSelection(page, r.first, r.last, TextSelectionLogic.rects(boxes, r.first, r.last), TextSelectionLogic.text(text, r.first, r.last, boxes))
                        }
                    },
                    onTap = { p -> handleTap(state, page, g, p, linksNow, layoutNow, { tp, sz -> onTapNow(tp + rootPos, sz) }, onOpenLinkNow, onPromptNow) },
                )
            }
            .pageDrag(
                page, g,
                // only claim a drag that a tool, a placement frame or a selection handle really wants;
                // everything else stays free for the list / pager to scroll
                accept = { p ->
                    val s = g.layoutToScreen(p)
                    val pl = state.placement?.takeIf { it.page == page }
                    val sel = state.selection?.takeIf { it.page == page }
                    val ed = state.edit
                    val eb = if (state.tool == null && ed.target?.page == page && ed.movable) ed.bounds() else null
                    when {
                        state.annot2.wantsDrag(page, g, s) -> true      // lasso / snapshot / replace / multi move
                        pl != null && hitPlacement(pl, g, s) != PlacementHandle.NONE -> true
                        // only drags that start on the selected annotation's frame; elsewhere the page keeps scrolling
                        eb != null && hitFrame(frameRect(eb, g), s, resizable(ed.current())) != PlacementHandle.NONE -> true
                        state.tool == null && sel != null -> {
                            val (hs, he) = selectionHandles(sel, g)
                            hypot(s.x - hs.x, s.y - hs.y) < 36f || hypot(s.x - he.x, s.y - he.y) < 36f
                        }
                        else -> state.tool in dragTools
                    }
                },
                onDragStart = pageDragLabel@{ p ->
                        val s = g.layoutToScreen(p)
                        if (state.annot2.wantsDrag(page, g, s)) { state.annot2.dragStart(page, g, s); return@pageDragLabel }
                        val pl = state.placement?.takeIf { it.page == page }
                        if (pl != null) {
                            val h = hitPlacement(pl, g, s)
                            if (h != PlacementHandle.NONE) { draft.begin(null, s); draft.handle = h; draft.placementStart = pl; return@pageDragLabel }
                        }
                        val ed = state.edit
                        val eb = if (state.tool == null && ed.target?.page == page && ed.movable) ed.bounds() else null
                        if (eb != null) {
                            val fr = frameRect(eb, g)
                            val h = hitFrame(fr, s, resizable(ed.current()))
                            if (h != PlacementHandle.NONE) { draft.begin(null, s); draft.handle = h; draft.annotFrame = fr; return@pageDragLabel }
                        }
                        val sel = state.selection?.takeIf { it.page == page }
                        if (state.tool == null && sel != null) {
                            val (hs, he) = selectionHandles(sel, g)
                            when {
                                hypot(s.x - hs.x, s.y - hs.y) < 36f -> { draft.begin(null, s); draft.handle = PlacementHandle.TL; return@pageDragLabel }
                                hypot(s.x - he.x, s.y - he.y) < 36f -> { draft.begin(null, s); draft.handle = PlacementHandle.BR; return@pageDragLabel }
                            }
                        }
                        val t = state.tool ?: return@pageDragLabel
                        if (t in dragTools) draft.begin(t, s)
                        if (t == ReaderTool.MEASURE) { val (x, y) = g.screenToPdf(s.x, s.y); state.measure = Measure(page, x to y, null) }
                    },
                onDrag = pageDragLabel@{ change ->
                        if (state.annot2.isDragging(page)) { change.consume(); state.annot2.dragMove(page, g.layoutToScreen(change.position)); return@pageDragLabel }
                        if (!draft.active) return@pageDragLabel
                        change.consume()
                        val s = g.layoutToScreen(change.position)
                        if (draft.placementStart != null) {
                            state.placement = dragPlacement(draft.placementStart!!, draft.handle, g, draft.start, s); return@pageDragLabel
                        }
                        if (draft.annotFrame != null) { draft.current = s; return@pageDragLabel }
                        if (draft.tool == null && draft.handle != PlacementHandle.NONE) { moveSelectionHandle(state, page, g, s, draft.handle == PlacementHandle.TL); return@pageDragLabel }
                        draft.move(s, if (pressure) change.pressure else 1f)
                        when (draft.tool) {
                            ReaderTool.ERASER -> { val (x, y) = g.screenToPdf(s.x, s.y); inkHits(annots, page, x, y, 10f / scale.coerceAtLeast(0.1f)).forEach { state.removeAnnot(it) } }
                            ReaderTool.MEASURE -> { val (x, y) = g.screenToPdf(s.x, s.y); state.measure = state.measure?.copy(b = x to y) }
                            else -> {}
                        }
                    },
                onDragEnd = endLabel@{
                    if (state.annot2.isDragging(page)) { state.annot2.dragEnd(page, g); return@endLabel }
                    val fr = draft.annotFrame
                    if (fr != null) {
                        val nf = dragFrame(fr, draft.handle, draft.start, draft.current)
                        if (nf != fr) state.edit.transform(g.screenRectToPdf(fr), g.screenRectToPdf(nf))
                        draft.end()
                    } else finishDraft(state, draft, page, g, pressure, onPrompt)
                },
                onDragCancel = { draft.end(); state.annot2.dragCancel() },
            ),
    ) {
        withTransform({
            when (rotation) {
                90 -> { translate(left = g.bitmapH); rotate(90f, Offset.Zero) }
                180 -> { translate(left = g.bitmapW, top = g.bitmapH); rotate(180f, Offset.Zero) }
                270 -> { translate(top = g.bitmapW); rotate(270f, Offset.Zero) }
            }
        }) {
            val bw = g.bitmapW; val bh = g.bitmapH
            val b = base
            if (b == null) drawRect(if (night) Color(0xFF202020) else Color.White, Offset.Zero, Size(bw, bh))
            else drawImage(b.asImageBitmap(), dstSize = IntSize(bw.roundToInt(), bh.roundToInt()), colorFilter = a11yFilter)
            tile?.let { (clip, t) ->
                drawImage(t.asImageBitmap(), dstOffset = IntOffset((clip.left * bw).roundToInt(), (clip.top * bh).roundToInt()),
                    dstSize = IntSize((clip.width * bw).roundToInt().coerceAtLeast(1), (clip.height * bh).roundToInt().coerceAtLeast(1)), colorFilter = a11yFilter)
            }
            // page colour preset: white -> background, black -> matching text colour (view module)
            if (!night && a11yFilter == null) with(com.smartdocs.editor.ui.reader.view.PageTintDraw) { drawPageTint(pageColor, bw, bh) }
            if (highlightLinks) links.forEach { l -> val r = g.pdfRectToScreen(l.bounds); drawRect(Color(0x332196F3), r.topLeft, r.size) }
            hits.forEachIndexed { i, h -> if (h.page == page) drawPdfRects(h.rects, g, if (i == hitIndex) hitCurColor else hitColor, multiply = true) }
            ReaderAi6.ttsHighlight.value?.let { (p, rs) -> if (p == page) drawPdfRects(rs, g, Color(0x5542A5F5), multiply = true) }   // ai6 (C1): sentence read aloud
            val frameDrag = draft.active && draft.annotFrame != null
            if (showAnnots) annots.forEach { a -> if (a.page == page && !(frameDrag && a.id == pendingEditId)) drawAnnot(a, g) }
            // sticky-note indicator on markups that carry text (Foxit 24.13, optional)
            if (showAnnots && state.annot2.cx.noteIndicators) {
                annots.forEach { a -> if (a.page == page && a.text.isNotBlank() && a.kind in NOTE_INDICATOR_KINDS) drawNoteIndicator(annotBounds(a), g, annotColor(a.color)) }
                state.existing.forEach { e -> if (e.page == page && e.contents.isNotBlank() && e.subtype in NOTE_INDICATOR_SUBTYPES) drawNoteIndicator(PageGeometry.normPdf(e.rect), g, Color(0xFFFFC107)) }
            }
            if (editBounds != null) {
                val fr = frameRect(editBounds, g)
                val shown = if (frameDrag) dragFrame(draft.annotFrame!!, draft.handle, draft.start, draft.current) else fr
                // live preview of the moved / resized annotation (stored ones stay in the bitmap until converted)
                if (frameDrag && editAnnot != null) drawAnnot(mapAnnot(editAnnot, g.screenRectToPdf(draft.annotFrame!!), g.screenRectToPdf(shown)), g)
                drawAnnotFrame(shown, accent, resizable(editAnnot) && state.edit.movable)
            }
            if (draft.active && draft.version >= 0) drawDraft(draft, state, g)
            drawAnnot2Overlay(state, page, g, accent)
            selection?.let { sel ->
                drawPdfRects(sel.rects, g, accent.copy(alpha = 0.3f))
                val (hs, he) = selectionHandles(sel, g)
                drawCircle(accent, 11f, hs); drawCircle(accent, 11f, he)
            }
            measure?.let { m ->
                val a = g.pdfToScreen(m.a.first, m.a.second)
                val bb = m.b?.let { g.pdfToScreen(it.first, it.second) } ?: a
                drawLine(Color(0xFFE91E63), a, bb, 3f, cap = StrokeCap.Round)
                drawCircle(Color(0xFFE91E63), 6f, a); drawCircle(Color(0xFFE91E63), 6f, bb)
                m.b?.let { b2 ->
                    val d = hypot(b2.first - m.a.first, b2.second - m.a.second)
                    val label = formatDistance(d)
                    val mid = Offset((a.x + bb.x) / 2, (a.y + bb.y) / 2 - 18f)
                    drawRoundRect(Color(0xDDFFFFFF), Offset(mid.x - 90f, mid.y - 16f), Size(180f, 30f), androidx.compose.ui.geometry.CornerRadius(8f))
                    drawTextBlock(label, Rect(mid.x - 86f, mid.y - 17f, mid.x + 86f, mid.y + 14f), 20f, Color(0xFFE91E63), rtl = false)
                }
            }
            placement?.let { drawPlacement(it, g, accent) }
        }
    }
    com.smartdocs.editor.ui.forms5.InPageFormLayer(state, page, g)      // MODULE_forms5: fill fields on the page
    com.smartdocs.editor.ui.reader.annot.InPageVideoLayer(state, page, g, visibleClip)   // videos play inside the page
    if (editTarget != null && editBounds != null && !(draft.active && draft.annotFrame != null)) {
        AnnotActionBar(state, editAnnot, g.pdfRectToLayout(PageGeometry.normPdf(g.screenRectToPdf(frameRect(editBounds, g)))))
    }
    com.smartdocs.editor.ui.reader.annot.MultiActionBar(state, page, g)
    PasteHereBar(state, page, g)      // nitro-fillsign2: long-press "Paste here"
    }
}

/**
 * Drag detector that decides at touch-down whether this page wants the gesture ([accept]).
 * When it does not, nothing is consumed and the scroll container receives the drag untouched.
 */
private fun Modifier.pageDrag(
    key1: Any?, key2: Any?,
    accept: (Offset) -> Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(key1, key2) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!accept(down.position)) return@awaitEachGesture
        var started = false
        val first = awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
            ?: return@awaitEachGesture
        onDragStart(down.position); started = true
        onDrag(first)
        val completed = drag(first.id) { ch -> onDrag(ch); ch.consume() }
        if (started) { if (completed) onDragEnd() else onDragCancel() }
    }
}

private val dragTools = setOf(ReaderTool.INK, ReaderTool.ERASER, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.FS_RRECT,
    ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.LINK, ReaderTool.MEASURE)

private fun quantise(r: Rect): Rect {
    val q = 8f
    return Rect(floor((r.left * q) - 0.5f).coerceAtLeast(0f) / q, floor((r.top * q) - 0.5f).coerceAtLeast(0f) / q,
        (floor(r.right * q + 1.5f) / q).coerceAtMost(1f), (floor(r.bottom * q + 1.5f) / q).coerceAtMost(1f))
}

/** Fractional rect in layout space -> fractional rect in unrotated bitmap space. */
private fun layoutFractionToScreenFraction(f: Rect, g: PageGeometry): Rect {
    val l = g.layoutSize
    val a = g.layoutToScreen(Offset(f.left * l.width, f.top * l.height)); val b = g.layoutToScreen(Offset(f.right * l.width, f.bottom * l.height))
    return Rect(min(a.x, b.x) / g.bitmapW, min(a.y, b.y) / g.bitmapH, max(a.x, b.x) / g.bitmapW, max(a.y, b.y) / g.bitmapH)
}

fun selectionHandles(sel: TextSelection, g: PageGeometry): Pair<Offset, Offset> {
    val first = sel.rects.firstOrNull() ?: return Offset.Zero to Offset.Zero
    val last = sel.rects.last()
    val rtl = sel.text.any { it in '؀'..'ۿ' }
    val a = g.pdfRectToScreen(first); val b = g.pdfRectToScreen(last)
    return if (rtl) Offset(a.right, a.bottom) to Offset(b.left, b.bottom) else Offset(a.left, a.bottom) to Offset(b.right, b.bottom)
}

private fun moveSelectionHandle(state: ReaderState, page: Int, g: PageGeometry, s: Offset, startHandle: Boolean) {
    val sel = state.selection ?: return
    val (x, y) = g.screenToPdf(s.x, s.y)
    state.scope.launch {
        val boxes = state.charBoxes(page); val text = state.pageText(page)
        val i = TextSelectionLogic.charAt(boxes, x, y, 40f) ?: return@launch
        val ns = if (startHandle) i else sel.start; val ne = if (startHandle) sel.end else i
        val a = min(ns, ne); val b = max(ns, ne)
        state.selection = TextSelection(page, a, b, TextSelectionLogic.rects(boxes, a, b), TextSelectionLogic.text(text, a, b, boxes))
    }
}

private fun handleTap(
    state: ReaderState, page: Int, g: PageGeometry, p: Offset, links: List<PageLink>, layout: Size,
    onTap: (Offset, Size) -> Unit, onOpenLink: (PageLink) -> Unit, onPrompt: (TextPrompt) -> Unit,
) {
    val s = g.layoutToScreen(p); val (x, y) = g.screenToPdf(s.x, s.y)
    StampClipboard.offer = null
    val pl = state.placement
    if (pl != null && pl.page == page) {
        if (hitPlacement(pl, g, s) == PlacementHandle.NONE) state.confirmPlacement()
        return
    }
    if (state.selection != null) { state.selection = null; return }
    if (state.annot2.onTap(page, g, x, y)) return       // MODULE_annot2 tools and multi-selection
    if (state.tool == null) {
        val ed = state.edit
        val eb = ed.target?.let { ed.bounds() }
        if (eb != null) {
            // tap inside the frame keeps the selection; anywhere else first clears it
            val fr = frameRect(eb, g)
            if (ed.target?.page == page && hitFrame(fr, s, false) != PlacementHandle.NONE) return
            ed.clear()
            if (ed.selectAt(page, x, y, 6f / g.scale.coerceAtLeast(0.1f))) return
            return
        }
        if (ed.selectAt(page, x, y, 6f / g.scale.coerceAtLeast(0.1f))) return
    }
    when (val t = state.tool) {
        null -> {
            links.firstOrNull { l -> val r = PageGeometry.normPdf(l.bounds); x >= r.left && x <= r.right && y <= r.top && y >= r.bottom }?.let { onOpenLink(it); return }
            if (com.smartdocs.editor.ui.nve.NveLinks.tapUrl(state, page, x, y)) return     // NVE: plain-text URLs are tappable
            onTap(p, layout)
        }
        ReaderTool.NOTE, ReaderTool.FREETEXT, ReaderTool.TEXTBOX, ReaderTool.FS_TEXT, ReaderTool.FS_COMB -> onPrompt(TextPrompt(t, page, x, y))
        ReaderTool.FS_PROFILE, ReaderTool.FS_DATE -> {
            val text = state.pendingText.ifBlank { return }
            state.addAnnot(state.newAnnot(AnnotKind.FREETEXT, page, rects = listOf(textRectAt(x, y, text, state.fontSize, g.pageBox(), text.any { it in '؀'..'ۿ' })), text = text))
        }
        ReaderTool.FS_CHECK, ReaderTool.FS_CROSS, ReaderTool.FS_DOT, ReaderTool.FS_LINE -> {
            val sz = state.fontSize * 1.2f
            val a = state.newAnnot(AnnotKind.INK, page, paths = quickMarkPaths(t, x, y, sz, g.pageRotate))      // tab audit D9: upright on a turned page
            state.addAnnot(if (t == ReaderTool.FS_DOT) a.copy(strokeWidth = sz * 0.4f) else a.copy(strokeWidth = (state.thickness).coerceAtLeast(2f)))
        }
        ReaderTool.SIGNATURE, ReaderTool.STAMP, ReaderTool.IMAGE -> {
            val img = state.pendingImage ?: return
            // nitro-fillsign2: a signature's remembered default size ("Set current size as default" / "Keep original size")
            val pref = if (t == ReaderTool.SIGNATURE && state.pendingText.isNotBlank()) com.smartdocs.editor.core.data.SignatureMetaStore.get(state.context, state.pendingText).widthPt else 0f
            val w = if (pref > 0f) pref.coerceIn(8f, g.pageW * 0.95f) else (g.pageW * 0.35f).coerceAtLeast(40f); val h = w * img.height / img.width.coerceAtLeast(1)
            // tab audit D9: w x h is the upright (displayed) size; on a turned page its user space box is turned too
            state.placement = Placement(page, com.smartdocs.editor.core.fillsign2.FsGeom.stampBox(g.pageBox(), x, y, w, h).toPdfRect(), img)
        }
        ReaderTool.LINK -> onPrompt(TextPrompt(t, page, x, y))
        ReaderTool.MEASURE -> {
            val m = state.measure
            state.measure = if (m == null || m.b != null || m.page != page) Measure(page, x to y, null) else m.copy(b = x to y)
        }
        ReaderTool.ERASER -> {
            inkHits(state.annots, page, x, y, 10f / g.scale.coerceAtLeast(0.1f)).forEach { state.removeAnnot(it) }
            state.eraseStoredInk(page, listOf(x to y), 10f / g.scale.coerceAtLeast(0.1f))   // audit AN-13: saved ink too
        }
        ReaderTool.INK, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.FS_RRECT -> {
            // a tap with the pen makes a dot
            if (t == ReaderTool.INK) state.addAnnot(state.newAnnot(AnnotKind.INK, page, paths = listOf(listOf(x to y, x + 0.1f to y))))
        }
        else -> {
            // markup tools: tap selects the word so the user can hit the tool again or drag handles
            state.scope.launch {
                val boxes = state.charBoxes(page); val text = state.pageText(page)
                val i = TextSelectionLogic.charAt(boxes, x, y) ?: return@launch
                val r = TextSelectionLogic.wordAt(text, i)
                val kind = markupKind(t) ?: return@launch
                state.addAnnot(state.newAnnot(kind, page, rects = TextSelectionLogic.rects(boxes, r.first, r.last), text = TextSelectionLogic.text(text, r.first, r.last, boxes)))
            }
        }
    }
}

fun markupKind(t: ReaderTool?): AnnotKind? = when (t) {
    ReaderTool.HIGHLIGHT -> AnnotKind.HIGHLIGHT; ReaderTool.UNDERLINE -> AnnotKind.UNDERLINE
    ReaderTool.STRIKEOUT -> AnnotKind.STRIKEOUT; ReaderTool.SQUIGGLY -> AnnotKind.SQUIGGLY; else -> null
}

private fun finishDraft(state: ReaderState, draft: GestureDraft, page: Int, g: PageGeometry, pressure: Boolean, onPrompt: (TextPrompt) -> Unit) {
    if (!draft.active) return
    val tool = draft.tool
    if (draft.placementStart != null || tool == null) { draft.end(); return }
    when (tool) {
        ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY -> {
            val rect = g.screenRectToPdf(draft.rect)
            state.scope.launch {
                val boxes = state.charBoxes(page); val text = state.pageText(page)
                val range = TextSelectionLogic.charsInRect(boxes, rect) ?: return@launch
                val kind = markupKind(tool) ?: return@launch
                state.addAnnot(state.newAnnot(kind, page, rects = TextSelectionLogic.rects(boxes, range.first, range.last), text = TextSelectionLogic.text(text, range.first, range.last, boxes).take(200)))
            }
        }
        ReaderTool.LINK -> { val r = draft.rect; if (r.width > 4f && r.height > 4f) onPrompt(TextPrompt(tool, page, 0f, 0f, g.screenRectToPdf(r))) }
        // audit AN-13: the pending ink went while dragging; saved ink is erased in one write at the end
        ReaderTool.ERASER -> state.eraseStoredInk(page, draft.points.map { g.screenToPdf(it.x, it.y) }, 10f / g.scale.coerceAtLeast(0.1f))
        ReaderTool.MEASURE -> {}
        else -> draftToAnnot(state, draft, page, g, pressure)?.let { state.annot2.cx.addNew(it) }
    }
    draft.end()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDraft(draft: GestureDraft, state: ReaderState, g: PageGeometry) {
    val c = annotColor(state.color, state.opacity)
    val sw = (state.thickness * g.scale).coerceAtLeast(1f)
    when (draft.tool) {
        ReaderTool.INK -> {
            val p = Path(); draft.points.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            drawPath(p, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        ReaderTool.LINE -> drawLine(c, draft.start, draft.current, sw, cap = StrokeCap.Round)
        ReaderTool.ARROW -> { drawLine(c, draft.start, draft.current, sw, cap = StrokeCap.Round); drawArrowHead(draft.start, draft.current, c, sw) }
        ReaderTool.RECT -> drawRect(c, draft.rect.topLeft, draft.rect.size, style = Stroke(sw))
        ReaderTool.FS_RRECT -> {
            val r = com.smartdocs.editor.core.fillsign2.RoundRect.defaultRadius(draft.rect.width / g.scale.coerceAtLeast(0.01f), draft.rect.height / g.scale.coerceAtLeast(0.01f)) * g.scale
            drawRoundRect(c, draft.rect.topLeft, draft.rect.size, androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(sw))
        }
        ReaderTool.OVAL -> drawOval(c, draft.rect.topLeft, draft.rect.size, style = Stroke(sw))
        ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.LINK ->
            drawRect(c.copy(alpha = 0.25f), draft.rect.topLeft, draft.rect.size)
        ReaderTool.ERASER -> drawCircle(Color(0x66F44336), 10f, draft.current)
        else -> {}
    }
}
