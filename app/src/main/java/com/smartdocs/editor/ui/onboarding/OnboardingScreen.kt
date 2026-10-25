package com.smartdocs.editor.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.review.AcctPrefs
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.TileColors
import com.smartdocs.editor.ui.help.PrivacyPolicyContent
import kotlinx.coroutines.launch

private data class OnbPage(val title: Int, val text: Int, val art: @Composable () -> Unit)

/**
 * First-run onboarding: three swipeable feature pages + a consent page (privacy policy + optional anonymous
 * usage statistics, OFF by default). Back is consumed while it is shown. [onDone] is called after consent is stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val tiles = Brand.tiles
    var analytics by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    val pages = listOf(
        OnbPage(R.string.ac_onb1_title, R.string.ac_onb1_text) { Orbit(Icons.Default.PictureAsPdf, listOf(Icons.Default.Draw to tiles.blue, Icons.Default.DocumentScanner to tiles.teal, Icons.Default.Edit to tiles.amber, Icons.Default.Lock to tiles.rose)) },
        OnbPage(R.string.ac_onb2_title, R.string.ac_onb2_text) { ArabicArt() },
        OnbPage(R.string.ac_onb3_title, R.string.ac_onb3_text) { Orbit(Icons.Default.PhoneAndroid, listOf(Icons.Default.Shield to tiles.green, Icons.Default.CloudOff to tiles.blue, Icons.Default.VisibilityOff to tiles.purple, Icons.Default.Key to tiles.amber)) },
        OnbPage(R.string.ac_onb4_title, R.string.ac_onb4_text) { Orbit(Icons.Default.VerifiedUser, listOf(Icons.Default.Policy to tiles.teal, Icons.Default.Insights to tiles.purple)) },
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val last = pager.currentPage == pages.lastIndex

    BackHandler { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    fun finish() {
        AcctPrefs.completeOnboarding(ctx, analytics)
        scope.launch { runCatching { app.settings.update { it.copy(onboardingDone = true) } } }
        onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pages.lastIndex) } }) { Text(stringResource(R.string.ac_onb_skip)) }
                else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val p = pages[i]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) { p.art() }
                    Spacer(Modifier.height(24.dp))
                    if (i == 0) Text(stringResource(R.string.ac_onb_welcome, stringResource(R.string.app_name)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(p.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(p.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (i == pages.lastIndex) {
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { showPolicy = true }) { Icon(Icons.Default.Policy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_read_policy)) }
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.ac_analytics_title), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.ac_analytics_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(analytics, { analytics = it })
                            }
                        }
                    }
                }
            }
            // page indicator
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    val sel = pager.currentPage == i
                    val w by animateDpAsState(if (sel) 22.dp else 8.dp, label = "dot")
                    val c by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotc")
                    Box(Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(PillShape).background(c))
                }
            }
            Button(
                onClick = { if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).height(54.dp),
                shape = PillShape,
            ) { Text(stringResource(if (last) R.string.ac_onb_start else R.string.next), style = MaterialTheme.typography.titleMedium) }
        }
    }

    if (showPolicy) ModalBottomSheet(onDismissRequest = { showPolicy = false }) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) { PrivacyPolicyContent(Modifier.fillMaxSize()) }
    }
}

/** A large centre icon with small colored tiles around it. */
@Composable
private fun Orbit(center: ImageVector, satellites: List<Pair<ImageVector, TileColors>>) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(200.dp).clip(CircleShape).background(Brush.radialGradient(listOf(primary.copy(alpha = 0.18f), primary.copy(alpha = 0.02f)))))
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) {
            Icon(center, null, Modifier.size(60.dp), tint = Color.White)
        }
        val spots = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.BottomStart)
        satellites.forEachIndexed { i, (icon, t) ->
            Box(Modifier.align(spots[i % spots.size]).padding(18.dp).rotate(if (i % 2 == 0) -8f else 8f)) { Tile(icon, t, 56.dp) }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, t: TileColors, size: Dp) {
    Surface(shape = RoundedCornerShape(18.dp), color = t.container, shadowElevation = 4.dp) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.5f), tint = t.content) }
    }
}

/** Arabic-first page: a big "ع" with RTL arrows and text lines. */
@Composable
private fun ArabicArt() {
    val primary = MaterialTheme.colorScheme.primary
    val tiles = Brand.tiles
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(200.dp).clip(CircleShape).background(Brush.radialGradient(listOf(primary.copy(alpha = 0.18f), primary.copy(alpha = 0.02f)))))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp) {
            Column(Modifier.width(150.dp).padding(18.dp), horizontalAlignment = Alignment.End) {
                Text("ع", style = MaterialTheme.typography.displayMedium, color = primary, fontWeight = FontWeight.Bold)
                listOf(1f, 0.8f, 0.9f, 0.6f).forEach { f ->
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(f).height(8.dp).clip(PillShape).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) { Tile(Icons.Default.FormatTextdirectionRToL, tiles.teal, 52.dp) }
        Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) { Tile(Icons.Default.RecordVoiceOver, tiles.amber, 52.dp) }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Tile(Icons.Default.TextFields, tiles.purple, 52.dp) }
    }
}
