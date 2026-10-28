package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ColorDot
import com.smartdocs.editor.ui.reader.PALETTE
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.ToolChip

/**
 * Extra chips of the Comment tab (insert / replace text, polyline, polygon, cloud, attachment, sound,
 * video, snapshot, multi-select) followed by the comments "more" menu. Emitted inside the tab's tool row.
 */
@Composable
fun Annot2CommentChips(state: ReaderState, actions: ReaderActions) {
    ToolCatalog.commentExtras.forEach { e ->
        ToolChip(e.icon, stringResource(e.label), selected = state.tool == e.tool) { clickTool(state, actions, e.tool) }
    }
    CommentsMoreChip(state)
}

/** "More" chip: summary, export XFDF / FDF, import, merge, handwriting → text. */
@Composable
fun CommentsMoreChip(state: ReaderState) {
    val h = state.annot2
    var open by remember { mutableStateOf(false) }
    Box {
        ToolChip(Icons.Default.MoreHoriz, stringResource(R.string.an_more), selected = open) { open = true }
        DropdownMenu(open, { open = false }) { CommentsMenuItems(h) { open = false } }
    }
}

/** Menu items shared by the toolbar chip and the comments panel. */
@Composable
fun CommentsMenuItems(h: Annot2Host, close: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.an_summary)) }, leadingIcon = { Icon(Icons.Default.Summarize, null) }, onClick = { close(); h.launchSummary() })
    DropdownMenuItem(text = { Text(stringResource(R.string.an_export_xfdf)) }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { close(); h.launchExport(true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.an_export_fdf)) }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { close(); h.launchExport(false) })
    DropdownMenuItem(text = { Text(stringResource(R.string.an_import)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { close(); h.launchImport() })
    DropdownMenuItem(text = { Text(stringResource(R.string.an_merge)) }, leadingIcon = { Icon(Icons.Default.MergeType, null) }, onClick = { close(); h.launchMerge() })
    DropdownMenuItem(text = { Text(stringResource(R.string.an_handwriting)) }, leadingIcon = { Icon(Icons.Default.Gesture, null) }, onClick = { close(); h.startHandwriting() })
    CommentExtrasMenuItems(h, close)
}

/**
 * Property-bar row for the tools of this module: hint, polygon progress (done / undo point / cancel),
 * fill colour and cloud intensity, thickness for the shape tools.
 */
@Composable
fun Annot2PropertyRow(state: ReaderState) {
    val h = state.annot2
    val t = state.tool ?: return
    if (t !in Annot2Host.OWN_TOOLS) return
    val hint = when (t) {
        ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD -> R.string.an_hint_poly
        ReaderTool.CARET -> R.string.an_hint_caret
        ReaderTool.REPLACE -> R.string.an_hint_replace
        ReaderTool.ATTACH, ReaderTool.SOUND, ReaderTool.VIDEO -> R.string.an_hint_media
        ReaderTool.SNAPSHOT -> R.string.an_hint_snapshot
        ReaderTool.TICKMARK -> R.string.cx_hint_tick
        else -> R.string.an_hint_select
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (t in h.polyTools) {
                if (h.vertices.isNotEmpty()) {
                    FilledTonalButton(onClick = { h.finishPoly() }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.done) + " · " + stringResource(R.string.an_vertices, h.vertices.size))
                    }
                    IconButton(onClick = { h.undoVertex() }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.an_undo_vertex)) }
                    IconButton(onClick = { h.cancelPoly() }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    VerticalDivider(Modifier.height(24.dp))
                }
                Text("${stringResource(R.string.prop_thickness)} ${"%.0f".format(state.thickness)}", style = MaterialTheme.typography.labelSmall)
                Slider(state.thickness.coerceIn(0.5f, 24f), { state.thickness = it }, valueRange = 0.5f..24f, modifier = Modifier.width(120.dp).height(28.dp))   // same range as the property bar (CM-34)
                if (t != ReaderTool.POLYLINE) {
                    VerticalDivider(Modifier.height(24.dp))
                    Text(stringResource(R.string.an_fill), style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.size(26.dp).clip(CircleShape).border(if (h.fillColor == null) 3.dp else 1.dp,
                        if (h.fillColor == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { h.fillColor = null }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Block, stringResource(R.string.an_no_fill), Modifier.size(16.dp)) }
                    PALETTE.forEach { c -> ColorDot(c, h.fillColor == c, 26.dp) { h.fillColor = c } }
                }
                if (t == ReaderTool.CLOUD) {
                    VerticalDivider(Modifier.height(24.dp))
                    FilterChip(h.cloudIntensity == 1, { h.cloudIntensity = 1 }, label = { Text(stringResource(R.string.an_cloud_1)) }, modifier = Modifier.height(30.dp))
                    FilterChip(h.cloudIntensity == 2, { h.cloudIntensity = 2 }, label = { Text(stringResource(R.string.an_cloud_2)) }, modifier = Modifier.height(30.dp))
                }
            }
            if (t == ReaderTool.SELECT && h.multi.active) {
                Text(stringResource(R.string.an_selected_n, h.multi.count), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { h.multi.clear() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
