package com.smartdocs.editor.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.smartdocs.editor.R
import kotlinx.coroutines.delay

/** Drag bookkeeping for reorder-by-drag inside the grid. */
private class DragState {
    var fromPos by mutableStateOf<Int?>(null)        // position inside the visible list
    var toPos by mutableStateOf<Int?>(null)
    var offset by mutableStateOf(Offset.Zero)
    var moved = false
    var overscroll by mutableFloatStateOf(0f)
    fun reset() { fromPos = null; toPos = null; offset = Offset.Zero; moved = false; overscroll = 0f }
}

private fun LazyGridState.itemAt(p: Offset): LazyGridItemInfo? = layoutInfo.visibleItemsInfo.firstOrNull { i ->
    p.x >= i.offset.x && p.x <= i.offset.x + i.size.width && p.y >= i.offset.y && p.y <= i.offset.y + i.size.height
}

/**
 * Grid of page thumbnails. [pages] are page indexes of the current version (already filtered).
 * Tap → [onTap]; long-press without movement → [onLongPress]; long-press + drag → [onReorder] (positions in [pages]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageGrid(
    state: OrganizeState,
    pages: List<Int>,
    minCell: androidx.compose.ui.unit.Dp,
    dragEnabled: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onReorder: (fromPos: Int, toPos: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Per-thumbnail menu (Foxit: redact / OCR / print … one page); null = no menu. */
    pageActions: List<Pair<Int, (Int) -> Unit>>? = null,
) {
    val grid = rememberLazyGridState()
    val drag = remember { DragState() }

    // auto-scroll while dragging near the edges
    LaunchedEffect(drag.fromPos) {
        while (drag.fromPos != null) {
            if (drag.overscroll != 0f) grid.scrollBy(drag.overscroll)
            delay(16)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell),
        state = grid,
        modifier = modifier.pointerInput(dragEnabled, pages) {
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    val item = grid.itemAt(p) ?: return@detectDragGesturesAfterLongPress
                    drag.fromPos = item.index; drag.toPos = item.index; drag.offset = Offset.Zero; drag.moved = false
                },
                onDrag = { change, amount ->
                    change.consume()
                    if (!dragEnabled) { drag.moved = false; return@detectDragGesturesAfterLongPress }
                    drag.offset += amount
                    if (drag.offset.getDistance() > 12f) drag.moved = true
                    val from = drag.fromPos ?: return@detectDragGesturesAfterLongPress
                    val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from }
                    if (info != null) {
                        val center = Offset(info.offset.x + info.size.width / 2f + drag.offset.x, info.offset.y + info.size.height / 2f + drag.offset.y)
                        grid.itemAt(center)?.let { drag.toPos = it.index }
                        val vh = grid.layoutInfo.viewportEndOffset.toFloat()
                        drag.overscroll = when { center.y < 80f -> -18f; center.y > vh - 80f -> 18f; else -> 0f }
                    }
                },
                onDragEnd = {
                    val from = drag.fromPos; val to = drag.toPos
                    if (from != null) {
                        if (!drag.moved) pages.getOrNull(from)?.let(onLongPress)
                        else if (to != null && to != from) onReorder(from, to)
                    }
                    drag.reset()
                },
                onDragCancel = { drag.reset() },
            )
        },
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> "${state.version}:$p" }) { pos, page ->
            val dragging = drag.fromPos == pos && drag.moved
            val target = drag.toPos == pos && drag.moved && drag.fromPos != pos
            PageCell(
                state = state, page = page, dragging = dragging, dropTarget = target, actions = pageActions,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) { translationX = drag.offset.x; translationY = drag.offset.y; scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 16f }
                    }
                    .combinedClickable(onClick = { onTap(page) }, onLongClick = null),
            )
        }
    }
}

@Composable
private fun PageCell(state: OrganizeState, page: Int, dragging: Boolean, dropTarget: Boolean, modifier: Modifier, actions: List<Pair<Int, (Int) -> Unit>>? = null) {
    val key = state.thumbKey(page)
    val bmp = state.thumbs[key]
    // audit 5 G-07: rendered at the cell's width; asked again when the LRU dropped it or the cell grew
    var cellPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, cellPx, bmp == null) { if (cellPx > 0) state.requestThumb(page, cellPx.coerceIn(96, 1400)) }
    var ratio by remember(key) { mutableFloatStateOf(0.707f) }
    LaunchedEffect(key) { val (w, h) = state.pageSize(page); if (h > 0f) ratio = w / h }
    val selected = page in state.selected
    val border = when {
        dropTarget -> MaterialTheme.colorScheme.tertiary
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(modifier.onSizeChanged { cellPx = it.width }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 3f))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(if (selected || dropTarget) 3.dp else 1.dp, border, RoundedCornerShape(8.dp))
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.Center), strokeWidth = 2.dp)
            if (state.selectMode) Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (page in state.annotatedPages) Icon(Icons.Default.Comment, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                if (page in state.bookmarkedPages) Icon(Icons.Default.Bookmark, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
            }
            if (dragging) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pg_page_n, page + 1), style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // audit 5 G-14: the page menu stays available in selection mode
            if (actions != null) Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }, Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_page_menu, page + 1), Modifier.size(18.dp)) }
                DropdownMenu(open, { open = false }) {
                    actions.forEach { (label, act) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; act(page) }) }
                }
            }
        }
    }
}
