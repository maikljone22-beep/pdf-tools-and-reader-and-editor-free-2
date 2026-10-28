package com.smartdocs.editor.ui.view5

import com.smartdocs.editor.ui.reader.view.pageTint
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class Transition5(val label: Int) {
    NONE(R.string.v5_tr_none), FADE(R.string.v5_tr_fade), PUSH(R.string.v5_tr_push), COVER(R.string.v5_tr_cover),
    ZOOM(R.string.v5_tr_zoom), WIPE(R.string.v5_tr_wipe), FLIP_UP(R.string.v5_tr_up),
}

private fun spec(t: Transition5, forward: Boolean, rtl: Boolean): ContentTransform {
    val d = 420
    val dir = if (forward != rtl) 1 else -1           // +1: new page comes from the right
    return when (t) {
        Transition5.NONE -> EnterTransition.None togetherWith ExitTransition.None
        Transition5.FADE -> fadeIn(tween(d)) togetherWith fadeOut(tween(d))
        Transition5.PUSH -> slideInHorizontally(tween(d)) { it * dir } togetherWith slideOutHorizontally(tween(d)) { -it * dir }
        Transition5.COVER -> (slideInHorizontally(tween(d)) { it * dir } togetherWith fadeOut(tween(d, delayMillis = d))).apply { targetContentZIndex = 1f }
        Transition5.ZOOM -> (scaleIn(tween(d), initialScale = 0.6f) + fadeIn(tween(d))) togetherWith (scaleOut(tween(d), targetScale = 1.2f) + fadeOut(tween(d)))
        Transition5.WIPE -> expandHorizontally(tween(d), expandFrom = if (dir > 0) Alignment.End else Alignment.Start) togetherWith fadeOut(tween(d, delayMillis = d / 2))
        Transition5.FLIP_UP -> slideInVertically(tween(d)) { if (forward) it else -it } togetherWith slideOutVertically(tween(d)) { if (forward) -it else it }
    }
}

/**
 * Presentation mode: black full screen, one fitted page, page transitions, tap / swipe / keyboard / volume
 * to move, optional auto-advance and loop, and a laser pointer while the finger is held down.
 */
@Composable
fun PresentationDialog(v: V5State) {
    val s = v.s
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var page by remember { mutableIntStateOf(s.currentPage) }
    var forward by remember { mutableStateOf(true) }
    var transition by remember { mutableStateOf(Transition5.entries.getOrElse(v.prefs.getInt("pr_tr", 2)) { Transition5.PUSH }) }
    var auto by remember { mutableIntStateOf(v.prefs.getInt("pr_auto", 0)) }
    var loop by remember { mutableStateOf(v.prefs.getBoolean("pr_loop", false)) }
    var chrome by remember { mutableStateOf(true) }
    var laser by remember { mutableStateOf<Offset?>(null) }
    val focus = remember { FocusRequester() }
    fun go(d: Int) {
        val n = page + d
        val t = when { n in 0 until s.pageCount -> n; loop && n >= s.pageCount -> 0; loop && n < 0 -> s.pageCount - 1; else -> return }
        forward = d > 0; page = t
    }
    fun save() { v.prefs.edit().putInt("pr_tr", transition.ordinal).putInt("pr_auto", auto).putBoolean("pr_loop", loop).apply() }
    LaunchedEffect(auto, page) { if (auto > 0) { delay(auto * 1000L); go(1) } }
    // NVE: transitions / auto-advance stored in the PDF (/Trans, /Dur) win over the app's own choice
    val nveTrans by produceState(emptyMap<Int, com.smartdocs.editor.core.nve.PageTrans>()) { value = com.smartdocs.editor.ui.nve.NvePresentation.load(s) }
    LaunchedEffect(page, nveTrans) { val d = nveTrans[page]?.autoAdvance; if (auto == 0 && d != null && d > 0f) { delay((d * 1000).toLong()); go(1) } }
    LaunchedEffect(chrome) { if (chrome) { delay(3500); chrome = false } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { s.jumpTo(page) } }

    Dialog(onDismissRequest = { save(); v.close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // tab-view-core V-33: immersive — the status and navigation bars are hidden (a swipe shows them for a moment)
        val dlgView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(dlgView) {
            val w = (dlgView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val c = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, dlgView) }
            c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF000000.toInt() or com.smartdocs.editor.core.data.NpfPrefs.presentBackground))   // nav-prefs: full-screen background
           .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER -> { go(if (rtl && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) -1 else 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DEL -> { go(if (rtl && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) 1 else -1); true }
                    KeyEvent.KEYCODE_MOVE_HOME -> { forward = false; page = 0; true }
                    KeyEvent.KEYCODE_MOVE_END -> { forward = true; page = s.pageCount - 1; true }
                    KeyEvent.KEYCODE_ESCAPE -> { save(); v.close(); true }
                    else -> false
                }
            }
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = Offset.Zero; var long = false
                    val t0 = down.uptimeMillis
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        moved = c.position - down.position
                        if (!long && c.uptimeMillis - t0 > 350 && moved.getDistance() < 24f) long = true
                        if (long) { laser = c.position; c.consume() }
                    } while (ev.changes.any { it.pressed })
                    laser = null
                    if (long) return@awaitEachGesture
                    val w = size.width.toFloat()
                    when {
                        abs(moved.x) > 80f && abs(moved.x) > abs(moved.y) -> go(if ((moved.x < 0) != rtl) 1 else -1)
                        moved.getDistance() < 24f -> {
                            val frac = down.position.x / w
                            when {
                                frac > 0.7f -> go(if (rtl) -1 else 1)
                                frac < 0.3f -> go(if (rtl) 1 else -1)
                                else -> chrome = !chrome
                            }
                        }
                    }
                }
            }) {
            val density = LocalDensity.current
            val vw = with(density) { maxWidth.toPx() }; val vh = with(density) { maxHeight.toPx() }
            AnimatedContent(page, transitionSpec = { com.smartdocs.editor.ui.nve.NvePresentation.spec(nveTrans[targetState], forward, rtl) ?: spec(transition, forward, rtl) }, label = "present", modifier = Modifier.fillMaxSize()) { p ->
                val bmp by produceState<Bitmap?>(null, p, vw, vh, s.nightMode) {
                    val sess = s.session ?: return@produceState
                    val (pw, ph) = s.pageSize(p)
                    if (pw <= 0f || ph <= 0f || p !in 0 until s.pageCount) return@produceState
                    val k = minOf(vw / pw, vh / ph)
                    value = runCatching { sess.render(p, (pw * k).toInt().coerceIn(1, 4096), (ph * k).toInt().coerceIn(1, 4096), s.nightMode, true) }.getOrNull()   // tab-view-core V-33: night mode
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // tab-view-core V-38: the page colour too (the image is exactly the page, so the tint covers only it)
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(with(density) { it.width.toDp() }, with(density) { it.height.toDp() }).pageTint(s.pageColor, s.nightMode), contentScale = ContentScale.Fit) }
                }
            }
            laser?.let { l -> Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0x66FF1744), 34f, l); drawCircle(Color(0xFFFF1744), 12f, l) } }
            if (chrome) Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding(), color = Color(0xCC212121), shape = MaterialTheme.shapes.large) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { save(); v.close() }) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
                    IconButton(onClick = { go(-1) }) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), tint = Color.White) }
                    Text("${page + 1} / ${s.pageCount}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { go(1) }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next), tint = Color.White) }
                    var trOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { trOpen = true }) { Icon(Icons.Default.Animation, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text(stringResource(transition.label), color = Color.White) }
                        DropdownMenu(trOpen, { trOpen = false }) {
                            Transition5.entries.forEach { t -> DropdownMenuItem(text = { Text(stringResource(t.label)) }, onClick = { transition = t; trOpen = false; save() }) }
                        }
                    }
                    var autoOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { autoOpen = true }) { Icon(Icons.Default.Timer, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text(if (auto == 0) stringResource(R.string.v5_auto_off) else stringResource(R.string.v5_seconds, auto), color = Color.White) }
                        DropdownMenu(autoOpen, { autoOpen = false }) {
                            listOf(0, 5, 10, 15, 20, 30, 60).forEach { a -> DropdownMenuItem(text = { Text(if (a == 0) stringResource(R.string.v5_auto_off) else stringResource(R.string.v5_seconds, a)) }, onClick = { auto = a; autoOpen = false; save() }) }
                        }
                    }
                    IconButton(onClick = { loop = !loop; save() }) { Icon(Icons.Default.Loop, stringResource(R.string.v5_loop), tint = if (loop) Color(0xFF80CBC4) else Color.White) }
                }
            }
        }
    }
}
