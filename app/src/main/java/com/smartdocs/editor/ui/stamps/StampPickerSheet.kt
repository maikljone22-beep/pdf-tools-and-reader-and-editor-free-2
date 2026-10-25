package com.smartdocs.editor.ui.stamps

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.CustomStamp
import com.smartdocs.editor.core.pdf.StampRenderer
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Predefined bilingual stamps: Arabic string resource, English label, colour. */
data class PresetStamp(val arabicRes: Int, val english: String, val color: Int)

val presetStamps = listOf(
    PresetStamp(R.string.stamp_approved, "APPROVED", StampRenderer.GREEN),
    PresetStamp(R.string.stamp_confidential, "CONFIDENTIAL", StampRenderer.RED),
    PresetStamp(R.string.stamp_draft, "DRAFT", StampRenderer.BLUE),
    PresetStamp(R.string.stamp_final, "FINAL", StampRenderer.GREEN),
    PresetStamp(R.string.stamp_received, "RECEIVED", StampRenderer.BLUE),
    PresetStamp(R.string.stamp_sent, "SENT", StampRenderer.BLUE),
    PresetStamp(R.string.stamp_reviewed, "REVIEWED", StampRenderer.ORANGE),
    PresetStamp(R.string.stamp_revised, "REVISED", StampRenderer.ORANGE),
    PresetStamp(R.string.stamp_void, "VOID", StampRenderer.RED),
    PresetStamp(R.string.stamp_completed, "COMPLETED", StampRenderer.GREEN),
    PresetStamp(R.string.stamp_urgent, "URGENT", StampRenderer.RED),
    PresetStamp(R.string.stamp_sign_here, "SIGN HERE", StampRenderer.ORANGE),
)

/** Bottom sheet to choose a stamp bitmap: predefined / dynamic (author + date) / custom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampPickerSheet(onPicked: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings = LocalSettings.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val custom by app.db.stamps().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val today = remember { SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date()) }
    val arabicLabels = presetStamps.map { stringResource(it.arabicRes) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Text(stringResource(R.string.sa_stamp_pick_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            TabRow(tab) {
                listOf(R.string.sa_stamp_tab_ready, R.string.sa_stamp_tab_dynamic, R.string.sa_stamp_tab_custom).forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(s)) })
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                when (tab) {
                    0 -> StampGrid(presetStamps.indices.toList(), key = { "p$it" }, render = { i -> StampRenderer.renderBilingual(arabicLabels[i], presetStamps[i].english, presetStamps[i].color, 500) }, onPicked = onPicked)
                    1 -> StampGrid(presetStamps.indices.toList(), key = { "d$it" }, render = { i ->
                        StampRenderer.renderDynamic(arabicLabels[i], settings.authorName, today, presetStamps[i].color, 500)
                    }, onPicked = onPicked)
                    else -> if (custom.isEmpty()) EmptyState(Icons.Default.Approval, stringResource(R.string.sa_stamps_empty))
                    else StampGrid(custom, key = { "c${it.id}" }, render = { s: CustomStamp -> CustomStamps.render(s, 500) }, onPicked = onPicked)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun <T> StampGrid(items: List<T>, key: (T) -> Any, render: (T) -> Bitmap?, onPicked: (Bitmap) -> Unit) {
    BoxWithConstraints {
        val cols = if (maxWidth > 700.dp) 4 else 2
        LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = key) { item ->
                val bmp by produceState<Bitmap?>(null, item) { value = withContext(Dispatchers.Default) { render(item) } }
                Box(
                    Modifier.fillMaxWidth().height(90.dp).background(Color.White, RoundedCornerShape(12.dp))
                        .clickable(enabled = bmp != null) { bmp?.let(onPicked) }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
