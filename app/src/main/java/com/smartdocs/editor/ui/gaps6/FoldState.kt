package com.smartdocs.editor.ui.gaps6

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.smartdocs.editor.core.gaps6.FoldLayout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Current fold / posture of the window (androidx.window WindowInfoTracker) + width class.
 * [hinge] is in window pixels (null without a folding feature).
 */
@Immutable
data class FoldState(
    val widthDp: Int,
    val hasFold: Boolean,
    val vertical: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
    val hinge: Rect?,
) {
    private val input get() = FoldLayout.Input(widthDp, hasFold, vertical, halfOpened, separating)
    val posture: FoldLayout.Posture get() = FoldLayout.posture(input)
    /** Home / Files: list + detail side by side. */
    val listDetail: FoldLayout.Mode get() = FoldLayout.listDetail(input)
    val twoPane: Boolean get() = listDetail == FoldLayout.Mode.TWO_PANE
    /** Reader: BOOK = two pages across a vertical hinge, TABLETOP = document over panel, TWO_PANE = document + side panel. */
    val reader: FoldLayout.Mode get() = FoldLayout.reader(input)
    val isBook: Boolean get() = reader == FoldLayout.Mode.BOOK
    val isTabletop: Boolean get() = reader == FoldLayout.Mode.TABLETOP
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Observes folding features of the current activity window. Cheap; call where the layout decision is made. */
@Composable
fun rememberFoldState(): FoldState {
    val ctx = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val activity = remember(ctx) { ctx.activity() }
    val flow = remember(activity) {
        if (activity == null) flowOf(null)
        else runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() }
        }.getOrElse { flowOf(null) }
    }
    val fold by flow.collectAsState(initial = null)
    val f = fold
    return remember(f, widthDp) {
        if (f == null) FoldState(widthDp, false, true, false, false, null)
        else FoldState(
            widthDp, true,
            vertical = f.orientation == FoldingFeature.Orientation.VERTICAL,
            halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
            separating = f.isSeparating,
            hinge = Rect(f.bounds),
        )
    }
}

/**
 * Two panes side by side. With a vertical hinge inside the container the split follows the hinge (nothing is drawn
 * under it); otherwise the first pane takes [firstWeight]. In RTL the first pane is on the right.
 */
@Composable
fun TwoPane(
    fold: FoldState,
    modifier: Modifier = Modifier,
    firstWeight: Float = 0.42f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var left by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    val hinge = fold.hinge
    val split = if (hinge != null && fold.vertical && fold.separating && width > 0) {
        FoldLayout.splitAtHinge(left, width, hinge.left, hinge.right)
    } else null
    Box(modifier.fillMaxSize().onGloballyPositioned { c -> val b = c.boundsInWindow(); left = b.left.toInt(); width = b.width.toInt() }) {
        Row(Modifier.fillMaxSize()) {
            if (split != null) {
                // physical left part, hinge gap, physical right part (Row mirrors in RTL, so swap the sizes)
                val (l, gap) = split
                val leftDp = with(density) { l.toDp() }
                val gapDp = with(density) { gap.toDp() }.coerceAtLeast(androidx.compose.ui.unit.Dp(0f))
                val rightDp = with(density) { (width - l - gap).toDp() }
                Box(Modifier.width(if (rtl) rightDp else leftDp).fillMaxHeight()) { first() }
                Spacer(Modifier.width(gapDp))
                Box(Modifier.width(if (rtl) leftDp else rightDp).fillMaxHeight()) { second() }
            } else {
                Box(Modifier.weight(firstWeight).fillMaxHeight()) { first() }
                VerticalDivider()
                Box(Modifier.weight(1f - firstWeight).fillMaxHeight()) { second() }
            }
        }
    }
}
