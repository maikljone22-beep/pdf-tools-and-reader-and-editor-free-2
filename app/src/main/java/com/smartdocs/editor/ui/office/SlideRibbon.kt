package com.smartdocs.editor.ui.office

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.common.rememberImagePicker

private val SLIDE_TABS = listOf(
    RibbonTab("home", R.string.o5_tab_home), RibbonTab("insert", R.string.o5_tab_insert), RibbonTab("design", R.string.o5_tab_design),
    RibbonTab("transitions", R.string.o5_tab_transitions), RibbonTab("show", R.string.o5_tab_show), RibbonTab("view", R.string.o5_tab_view),
)

@Composable
fun SlideRibbon(s: SlideState) {
    var tab by remember { mutableStateOf("home") }
    val tick = s.tick
    val tabs = SLIDE_TABS + listOfNotNull(RibbonTab("shape", R.string.o5_tab_shape).takeIf { s.selected != null })
    LaunchedEffect(s.selected != null) { if (s.selected == null && tab == "shape") tab = "home" }
    Column {
        RibbonTabs(tabs, tab, { tab = it })
        when (tab) {
            "home" -> SlideHome(s)
            "insert" -> SlideInsert(s)
            "design" -> SlideDesign(s)
            "transitions" -> SlideTransitions(s)
            "show" -> RibbonRow {
                RibbonButton(Icons.Default.Slideshow, R.string.o5_show_start) { s.commitPending(); s.showFrom = 0; s.bump() }
                RibbonButton(Icons.Default.PlayArrow, R.string.o5_show_current) { s.commitPending(); s.showFrom = s.current; s.bump() }
            }
            "view" -> RibbonRow {
                RibbonButton(Icons.Default.ViewSidebar, R.string.o5_thumbnails, s.thumbs) { s.thumbs = !s.thumbs; s.bump() }
            }
            "shape" -> SlideShapeTab(s)
        }
        HorizontalDivider()
    }
}

@Composable
private fun SlideHome(s: SlideState) {
    val r = s.firstRun()
    var color by remember { mutableStateOf(false) }
    val has = s.selected != null
    RibbonRow {
        RibbonButton(Icons.Default.AddBox, R.string.o5_new_slide) { s.newSlide() }
        RibbonButton(Icons.Default.ContentCopy, R.string.o5_duplicate_slide) { s.duplicate() }
        RibbonButton(Icons.Default.Delete, R.string.o5_delete_slide, enabled = s.count > 1) { s.deleteSlide() }
        RibbonButton(Icons.Default.ArrowUpward, R.string.o5_move_up, enabled = s.current > 0) { s.moveSlide(-1) }
        RibbonButton(Icons.Default.ArrowDownward, R.string.o5_move_down, enabled = s.current < s.count - 1) { s.moveSlide(1) }
        RibbonDivider()
        RibbonButton(Icons.Default.EditNote, R.string.o5_edit_text, enabled = has) { s.beginText() }
        RibbonButton(Icons.Default.FormatBold, R.string.o5_bold, r?.bold == true, enabled = has) { val on = r?.bold != true; s.formatText { it.set("b", if (on) "1" else "0") } }
        RibbonButton(Icons.Default.FormatItalic, R.string.o5_italic, r?.italic == true, enabled = has) { val on = r?.italic != true; s.formatText { it.set("i", if (on) "1" else "0") } }
        RibbonButton(Icons.Default.FormatUnderlined, R.string.o5_underline, r?.underline == true, enabled = has) { val on = r?.underline != true; s.formatText { it.set("u", if (on) "sng" else "none") } }
        val size = r?.sizePt ?: s.selectedShape()?.defaultSize ?: 18f
        RibbonButton(Icons.Default.TextDecrease, R.string.o5_smaller, enabled = has) { s.formatText { it.set("sz", (nextSize(size, false) * 100).toInt().toString()) } }
        RibbonButton(Icons.Default.TextIncrease, R.string.o5_bigger, enabled = has) { s.formatText { it.set("sz", (nextSize(size, true) * 100).toInt().toString()) } }
        RibbonColor(Icons.Default.FormatColorText, R.string.o5_font_color, Color.hex(r?.color)) { if (has) color = true }
        RibbonDivider()
        RibbonButton(Icons.Default.FormatAlignRight, R.string.o5_align_right, enabled = has) { s.align("r") }
        RibbonButton(Icons.Default.FormatAlignCenter, R.string.o5_align_center, enabled = has) { s.align("ctr") }
        RibbonButton(Icons.Default.FormatAlignLeft, R.string.o5_align_left, enabled = has) { s.align("l") }
        RibbonButton(Icons.AutoMirrored.Filled.FormatTextdirectionRToL, R.string.o5_rtl, enabled = has) { s.align("r", true) }
    }
    if (color) ColorPickerDialog(R.string.o5_font_color, true, onDismiss = { color = false }) { c -> color = false; s.formatText { rp -> s.doc.setRunColor(rp, c) } }
}

@Composable
private fun SlideInsert(s: SlideState) {
    val pick = rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { f -> runCatching { f.readBytes() }.getOrNull()?.let { s.addImage(it) } } }
    val hint = stringResource(R.string.o5_textbox_hint)
    RibbonRow {
        RibbonButton(Icons.Default.TextFields, R.string.o5_text_box) { s.addTextBox(hint) }
        RibbonButton(Icons.Default.Image, R.string.o5_picture) { pick() }
        RibbonDivider()
        RibbonButton(Icons.Default.Rectangle, R.string.o5_rectangle) { s.addShape("rect") }
        RibbonButton(Icons.Default.RoundedCorner, R.string.o5_rounded_rect) { s.addShape("roundRect") }
        RibbonButton(Icons.Default.Circle, R.string.o5_ellipse) { s.addShape("ellipse") }
        RibbonButton(Icons.Default.ChangeHistory, R.string.o5_triangle) { s.addShape("triangle") }
        RibbonButton(Icons.Default.East, R.string.o5_arrow) { s.addShape("rightArrow") }
        RibbonButton(Icons.Default.HorizontalRule, R.string.o5_line) { s.addShape("line") }
    }
}

@Composable
private fun SlideDesign(s: SlideState) {
    var bg by remember { mutableStateOf(false) }
    RibbonRow { RibbonButton(Icons.Default.Wallpaper, R.string.o5_background) { bg = true } }
    if (bg) ColorPickerDialog(R.string.o5_background, false, onDismiss = { bg = false }) { c -> bg = false; c?.let { s.background(it) } }
}

@Composable
private fun SlideTransitions(s: SlideState) {
    val cur = remember(s.tick, s.current) { s.doc.transition(s.current) }
    var all by remember { mutableStateOf(false) }
    RibbonRow {
        listOf(null to R.string.o5_tr_none, "fade" to R.string.o5_tr_fade, "push" to R.string.o5_tr_push, "wipe" to R.string.o5_tr_wipe).forEach { (k, l) ->
            RibbonChip(stringResource(l), cur == k) { s.transition(k, all) }
        }
        RibbonDivider()
        RibbonChip(stringResource(R.string.o5_apply_all), all) { all = !all }
    }
}

@Composable
private fun SlideShapeTab(s: SlideState) {
    var dlg by remember { mutableIntStateOf(0) }
    RibbonRow {
        RibbonButton(Icons.Default.FormatColorFill, R.string.o5_fill) { dlg = 1 }
        RibbonButton(Icons.Default.BorderStyle, R.string.o5_outline_color) { dlg = 2 }
        RibbonButton(Icons.Default.FlipToFront, R.string.o5_bring_forward) { s.reorderShape(1) }
        RibbonButton(Icons.Default.FlipToBack, R.string.o5_send_backward) { s.reorderShape(-1) }
        RibbonButton(Icons.Default.EditNote, R.string.o5_edit_text) { s.beginText() }
        RibbonButton(Icons.Default.Delete, R.string.o5_delete) { s.deleteShape() }
    }
    if (dlg != 0) ColorPickerDialog(if (dlg == 1) R.string.o5_fill else R.string.o5_outline_color, true, onDismiss = { dlg = 0 }) { c -> val w = dlg; dlg = 0; if (w == 1) s.fill(c) else s.line(c) }
}

/** Full-screen slide show: tap or swipe left to advance, swipe right to go back, simple fade/push transitions. */
@Composable
fun SlideShow(s: SlideState, from: Int, onExit: () -> Unit) {
    var index by remember { mutableIntStateOf(from.coerceIn(0, (s.count - 1).coerceAtLeast(0))) }
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            }
        }
        var forward by remember { mutableStateOf(true) }
        fun next() { if (index < s.count - 1) { forward = true; index++ } else onExit() }
        fun prev() { if (index > 0) { forward = false; index-- } }
        Box(Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { next() }, onDoubleTap = { next() }) }
            .pointerInput(Unit) { var dx = 0f; detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -60f) next() else if (dx > 60f) prev() }) { _, d -> dx += d } },
            contentAlignment = Alignment.Center) {
            AnimatedContent(index, transitionSpec = {
                val kind = s.doc.transition(targetState)
                if (kind == "push" || kind == "wipe") (slideInHorizontally(tween(400)) { w -> if (forward) w else -w } + fadeIn(tween(400))) togetherWith (slideOutHorizontally(tween(400)) { w -> if (forward) -w else w } + fadeOut(tween(300)))
                else fadeIn(tween(450)) togetherWith fadeOut(tween(350))
            }, label = "show") { i ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SlideView(s.slide(i)) }
            }
            Text("${index + 1} / ${s.count}", Modifier.align(Alignment.BottomCenter).padding(8.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

