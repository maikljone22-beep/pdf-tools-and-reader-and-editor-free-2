package com.smartdocs.editor.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.review.AcctPrefs
import kotlinx.coroutines.delay

/**
 * Coach marks (spotlight overlay) shown once per key.
 *
 * Usage from any screen:
 *   1. tag targets:   `IconButton(…, modifier = Modifier.coachTarget(CoachIds.READER_TABS))`
 *   2. declare steps: `CoachMark("reader", listOf(CoachStep(CoachIds.READER_TABS, R.string.ac_coach_reader_tabs_t, R.string.ac_coach_reader_tabs_b), …))`
 * Steps whose target is not on screen are skipped; nothing is marked "seen" until something was actually shown.
 * Ready-made sets: [HomeCoachMarks] and [ReaderCoachMarks].
 */
object CoachIds {
    const val HOME_OPEN = "home_open"
    const val HOME_SCAN = "home_scan"
    const val READER_TABS = "reader_tabs"
    const val READER_TOOLBAR = "reader_toolbar"
}

data class CoachStep(val target: String, val title: Int, val text: Int)

/** Tiny registry of target bounds (window coordinates), filled by [coachTarget]. */
object CoachMarkRegistry {
    val bounds = mutableStateMapOf<String, Rect>()
    /** Set false to suppress all coach marks (e.g. UI tests). */
    var enabled by mutableStateOf(true)
}

fun Modifier.coachTarget(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { CoachMarkRegistry.bounds.remove(id) } }
    onGloballyPositioned { c ->
        val p = c.positionInWindow()
        CoachMarkRegistry.bounds[id] = Rect(p, Size(c.size.width.toFloat(), c.size.height.toFloat()))
    }
}

@Composable
fun HomeCoachMarks() = CoachMark("home", listOf(
    CoachStep(CoachIds.HOME_OPEN, R.string.ac_coach_home_open_t, R.string.ac_coach_home_open_b),
    CoachStep(CoachIds.HOME_SCAN, R.string.ac_coach_home_scan_t, R.string.ac_coach_home_scan_b),
))

@Composable
fun ReaderCoachMarks() = CoachMark("reader", listOf(
    CoachStep(CoachIds.READER_TABS, R.string.ac_coach_reader_tabs_t, R.string.ac_coach_reader_tabs_b),
    CoachStep(CoachIds.READER_TOOLBAR, R.string.ac_coach_reader_tools_t, R.string.ac_coach_reader_tools_b),
))

/** Shows [steps] once (per [key]) after the targets have been laid out. */
@Composable
fun CoachMark(key: String, steps: List<CoachStep>, startDelayMs: Long = 700, onFinished: () -> Unit = {}) {
    val ctx = LocalContext.current
    var seen by remember(key) { mutableStateOf(AcctPrefs.coachSeen(ctx, key)) }
    if (seen || !CoachMarkRegistry.enabled || !AcctPrefs.onboardingDone(ctx)) return
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(key) { delay(startDelayMs); ready = true }
    if (!ready) return
    val visible = steps.filter { CoachMarkRegistry.bounds[it.target] != null }
    if (visible.isEmpty()) return
    var index by remember(key) { mutableIntStateOf(0) }
    val step = visible.getOrNull(index)
    fun finish() { AcctPrefs.setCoachSeen(ctx, key); seen = true; onFinished() }
    if (step == null) { LaunchedEffect(Unit) { finish() }; return }
    val target = CoachMarkRegistry.bounds[step.target] ?: return

    Popup(popupPositionProvider = WindowOrigin, onDismissRequest = { finish() }, properties = PopupProperties(focusable = true)) {
        SpotlightOverlay(target, step, index, visible.size, onNext = { if (index + 1 >= visible.size) finish() else index++ }, onSkip = { finish() })
    }
}

/** Places the popup at the window origin so window coordinates can be used directly. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@Composable
private fun SpotlightOverlay(target: Rect, step: CoachStep, index: Int, count: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val alpha by animateFloatAsState(1f, label = "coach")
    val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onNext)
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; this.alpha = alpha }) {
            drawRect(Color.Black.copy(alpha = 0.66f))
            drawRoundRect(Color.Transparent, topLeft = Offset(hole.left, hole.top), size = hole.size, cornerRadius = CornerRadius(pad * 2), blendMode = BlendMode.Clear)
            drawRoundRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(hole.left, hole.top), size = hole.size, cornerRadius = CornerRadius(pad * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }))
        }
        val screenH = with(density) { maxHeight.toPx() }
        val below = hole.bottom < screenH * 0.6f
        val bubbleY = with(density) { (if (below) hole.bottom + pad else hole.top - pad).coerceIn(0f, screenH).toDp() }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .then(if (below) Modifier.padding(top = bubbleY) else Modifier.fillMaxHeight().padding(bottom = maxHeight - bubbleY)),
            contentAlignment = if (below) androidx.compose.ui.Alignment.TopCenter else androidx.compose.ui.Alignment.BottomCenter,
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 420.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(step.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${index + 1}/$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.weight(1f))
                        if (index + 1 < count) TextButton(onClick = onSkip) { Text(stringResource(R.string.ac_onb_skip)) }
                        Button(onClick = onNext) { Text(stringResource(if (index + 1 < count) R.string.next else R.string.done)) }
                    }
                }
            }
        }
    }
}
