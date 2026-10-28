package com.smartdocs.editor.ui.selmenu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.TextAction
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.core.selmenu.SelCtx
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.core.selmenu.SelMenuModel
import com.smartdocs.editor.core.selmenu.SelMenus
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.quick.QuickController
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextPrompt
import com.smartdocs.editor.ui.reader.TextSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Gap between the selection and the bar, plus room for the drag handles drawn under the text (px). */
private const val GAP_PX = 14f
private const val HANDLE_PX = 30f

/** One selection / object menu "what is the menu for right now". */
private data class MenuFor(val ctx: SelCtx, val sel: TextSelection?, val obj: SelObject?)

/** Long-pressing one word gives the WORD context (offers "Select sentence"), anything longer is TEXT. */
private fun ctxOf(sel: TextSelection): SelCtx = if (sel.text.length <= 48 && sel.text.none { it.isWhitespace() }) SelCtx.WORD else SelCtx.TEXT

/**
 * The selection-menu layer of the reader (feature/selection-menus; hook at the end of the page-area Box of
 * ReaderScreen). Draws:
 * - the floating bar next to the text selection or the long-pressed link / empty spot: above it when there is room,
 *   below it otherwise, clamped inside the page area, so it never leaves the screen (phone, tablet, RTL);
 * - the "More" sheet with every item of the registry, grouped;
 * - the right-click / Shift+F10 / Menu-key context menu (same registry), replacing NVE's page context menu.
 */
@Composable
fun SelMenuLayer(
    state: ReaderState, actions: ReaderActions, quick: QuickController, snack: SnackbarHostState,
    onTextTool: (TextSelection, TextAction) -> Unit,
    onReadAloud: (page: Int, fromChar: Int) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
    onOpenLink: (PageLink) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = SelMenuState.of(state)
    val n = NveState.of(state)
    val ai = com.smartdocs.editor.LocalSettings.current.aiEnabled
    val tablet = LocalConfiguration.current.screenWidthDp >= 600
    SignedProbe(state)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var dialog by remember { mutableStateOf<Pair<SelDialog, TextSelection>?>(null) }
    val runner = SelRunner(ctx, state, actions, quick, toast = { t -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(t) } },
        onTextTool = onTextTool, onReadAloud = onReadAloud, onPrompt = onPrompt, onOpenLink = onOpenLink,
        openDialog = { d, s -> dialog = d to s })

    // a new tab / tool / selection ends the object menu; a selection replaces it
    LaunchedEffect(state.tab, state.tool) { m.closeAll() }
    LaunchedEffect(state.selection) { if (state.selection != null) m.closeObject() else m.moreOpen = false }

    // right-click from NVE's mouse modifier: resolve the context here
    val rightClick = n.contextMenuAt
    LaunchedEffect(rightClick) {
        val at = rightClick ?: return@LaunchedEffect
        n.contextMenuAt = null
        m.closeObject()
        if (state.selection == null) {
            val hit = com.smartdocs.editor.ui.nve.pdfAt(state, at)
            if (hit != null) {
                val (page, x, y) = hit
                val links = runCatching { state.session?.links(page) }.getOrNull().orEmpty()
                val l = links.firstOrNull { l -> val r = PageGeometry.normPdf(l.bounds); x >= r.left && x <= r.right && y <= r.top && y >= r.bottom }
                m.target = SelObject(if (l != null) SelCtx.LINK else SelCtx.EMPTY, page, x, y, l)
            }
        }
        m.dropdownAt = at
    }

    val sel = state.selection
    val target = m.target
    val menuFor = when {
        sel != null -> MenuFor(ctxOf(sel), sel, null)
        target != null -> MenuFor(target.ctx, null, target)
        else -> null
    }
    val model: SelMenuModel? = menuFor?.let { f ->
        SelMenus.build(m.env(f.ctx, f.sel?.text.orEmpty(), ai, hasRects = f.sel?.rects?.isNotEmpty() ?: true, link = f.obj?.link),
            if (tablet) SelMenus.BAR_TABLET else SelMenus.BAR_PHONE)
    }
    fun runItem(item: SelItem) {
        val f = menuFor ?: return
        val close = runner.run(item, f.sel, f.obj)
        if (close) { m.moreOpen = false; m.dropdownAt = null; if (f.obj != null) m.closeObject() }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); size = it.size }) {
        // tap outside an object menu closes it (a text selection closes through the page's own tap handling)
        if (target != null && sel == null && m.dropdownAt == null) Box(Modifier.fillMaxSize().pointerInput(target) { detectTapGestures { m.closeObject() } })

        val anchorRoot: Rect? = when {
            sel != null -> m.anchor?.takeIf { m.anchorKey === sel }
            target != null -> m.objectAnchor?.let { Rect(it, it) }
            else -> null
        }
        if (model != null && anchorRoot != null && !m.moreOpen && m.dropdownAt == null && state.tool == null && model.bar.isNotEmpty()) {
            val local = anchorRoot.translate(-origin)
            FloatingPlacement(local, handles = sel != null) {
                SelBar(model, tablet, onItem = { runItem(it) }, onMore = { m.moreOpen = true })
            }
        }

        // right-click / keyboard menu
        val dAt = m.dropdownAt
        if (dAt != null && model == null) LaunchedEffect(dAt) { m.dropdownAt = null }     // nothing under the pointer (gap between pages)
        if (dAt != null && model != null) {
            val density = LocalDensity.current
            val p = if (dAt.x < 0f) (anchorRoot?.let { Offset(it.center.x, it.bottom) - origin } ?: Offset(size.width / 2f, size.height / 3f)) else dAt
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(p.x.roundToInt(), p.y.roundToInt()) }) {
                DropdownMenu(true, { m.dropdownAt = null; if (sel == null) m.closeObject() }, offset = DpOffset.Zero, modifier = Modifier.heightIn(max = with(density) { (size.height * 0.85f).toDp() })) {
                    model.more.forEachIndexed { gi, sec ->
                        if (gi > 0) HorizontalDivider()
                        sec.items.forEach { item ->
                            val reason = item.why
                            DropdownMenuItem(
                                text = { Text(stringResource(SelBindings.label(item.action)), Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                                leadingIcon = { Icon(SelBindings.icon(item.action), null, Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                                trailingIcon = if (reason == null) null else ({
                                    Text(stringResource(SelBindings.why(reason)), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                                }),
                                onClick = { runItem(item) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (m.moreOpen && model != null) SelMoreSheet(model, menuFor?.sel?.text, tablet, onItem = { runItem(it) }) { m.moreOpen = false }

    // dialogs opened from the menu (existing reader dialogs)
    when (val d = dialog) {
        null -> {}
        else -> when (d.first) {
            SelDialog.PDF_BOOKMARK -> com.smartdocs.editor.ui.edit5.ReaderBookmarkFromSelection(state) { dialog = null; state.selection = null }
            SelDialog.CREATE_LINK -> com.smartdocs.editor.ui.edit5.ReaderLinkFromSelection(state) { dialog = null; state.selection = null }
            SelDialog.WORD_COUNT -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.nt_wc_selection)) },
                text = { com.smartdocs.editor.ui.tools5.WordCountTable(com.smartdocs.editor.core.tools5.WordCount.count(d.second.text)) },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ok)) } })
            SelDialog.EXPORT -> ExportSelection(state, d.second) { dialog = null }
        }
    }
}

/** Saves the selected text as a .txt file (system file picker). */
@Composable
private fun ExportSelection(state: ReaderState, sel: TextSelection, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(sel.text.toByteArray()) } != null }.getOrDefault(false) }
            android.widget.Toast.makeText(ctx, if (ok) R.string.success else R.string.nv_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
        onDone()
    }
    LaunchedEffect(sel) { runCatching { launcher.launch((state.file?.nameWithoutExtension ?: "selection") + "_p${sel.page + 1}.txt") }.onFailure { onDone() } }
}

/**
 * Places [content] centred over the anchor, above it when it fits, else below it (leaving room for the handles),
 * else at the bottom of the area when the anchor is off screen or taller than the area. Always clamped inside.
 */
@Composable
private fun FloatingPlacement(anchor: Rect, handles: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { ms, c ->
        val p = ms.first().measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, c.maxHeight) {
            val margin = 8.dp.toPx()
            val w = c.maxWidth.toFloat(); val h = c.maxHeight.toFloat()
            val x = (anchor.center.x - p.width / 2f).coerceIn(margin, max(margin, w - p.width - margin))
            val above = anchor.top - GAP_PX - p.height
            val below = anchor.bottom + GAP_PX + (if (handles) HANDLE_PX else 0f)
            val y = when {
                anchor.bottom < 0f || anchor.top > h -> h - p.height - margin * 6          // scrolled away: keep it reachable
                above >= margin -> above
                below + p.height <= h - margin -> below
                else -> min(max(margin, anchor.top + margin), h - p.height - margin)       // tall selection: inside it
            }
            p.place(x.roundToInt(), y.coerceIn(margin, max(margin, h - p.height - margin)).roundToInt())
        }
    }
}

/** The floating bar: icon + short label per item, then "More". Layout direction (RTL) orders the row. */
@Composable
private fun SelBar(model: SelMenuModel, tablet: Boolean, onItem: (SelItem) -> Unit, onMore: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            model.bar.forEach { item -> BarButton(stringResource(SelBindings.label(item.action)), item, tablet) { onItem(item) } }
            BarButton(stringResource(R.string.more), null, tablet, onClick = onMore)
        }
    }
}

@Composable
private fun BarButton(label: String, item: SelItem?, tablet: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(if (tablet) 76.dp else 62.dp).clickable(onClickLabel = label, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Icon(item?.let { SelBindings.icon(it.action) } ?: Icons.Default.MoreHoriz, null, Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** "More": every visible item of the registry by group; disabled items say why when tapped. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SelMoreSheet(model: SelMenuModel, text: String?, tablet: Boolean, onItem: (SelItem) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!text.isNullOrBlank()) item {
                Text(text.replace('\n', ' ').take(160), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(model.more, key = { it.group.name }) { sec ->
                Text(stringResource(SelBindings.group(sec.group)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sec.items.forEach { item ->
                        val label = stringResource(SelBindings.label(item.action))
                        Column(
                            Modifier.width(if (tablet) 104.dp else 82.dp).alpha(if (item.enabled) 1f else 0.45f).clickable(onClickLabel = label) { onItem(item) }.padding(vertical = 8.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            Icon(SelBindings.icon(item.action), null)
                            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            item.entity?.let { e -> Text(e.text.take(24), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hook inside PageCanvas's page Box: publishes, in root px, where this page's text selection / long-pressed object
 * is, whenever it or the page moves (scroll, zoom, rotation). Draws nothing and takes no touches.
 */
@Composable
fun SelAnchor(state: ReaderState, page: Int, g: PageGeometry) {
    val m = SelMenuState.of(state)
    val sel = state.selection?.takeIf { it.page == page }
    val obj = m.target?.takeIf { it.page == page }
    if (sel == null && obj == null) return
    val selRect = remember(sel, g) {
        sel?.rects?.map { g.pdfRectToLayout(PageGeometry.normPdf(it)) }?.reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }
    val objPt = remember(obj, g) { obj?.let { g.pdfRectToLayout(android.graphics.RectF(it.x, it.y, it.x, it.y)).topLeft } }
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun publish() {
        val c = holder[0]?.takeIf { it.isAttached } ?: return
        if (sel != null && selRect != null) {
            val a = c.localToRoot(selRect.topLeft); val b = c.localToRoot(selRect.bottomRight)
            m.anchor = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)); m.anchorKey = sel
        }
        if (obj != null && objPt != null) m.objectAnchor = c.localToRoot(objPt)
    }
    SideEffect { publish() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { holder[0] = it; publish() })
}
