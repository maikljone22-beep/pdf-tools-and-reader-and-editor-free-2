package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.history.HistoryStep

/**
 * Unified undo / redo of the reader (core/history): the top-bar pair, the history sheet and the snackbar.
 * The arrows are AutoMirrored, so in RTL the undo arrow points right.
 */
@Composable
fun HistoryButtons(state: ReaderState, onOpenHistory: () -> Unit) {
    HistoryIcon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.hi_undo), state.canUndo, onClick = { state.undo() }, onLongClick = onOpenHistory)
    HistoryIcon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.hi_redo), state.canRedo, onClick = { state.redo() }, onLongClick = onOpenHistory)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    // IconButton has no long press: same 40dp circle with combinedClickable (long press works even when disabled)
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .combinedClickable(onClickLabel = label, role = Role.Button, onClick = { if (enabled) onClick() }, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.alpha(if (enabled) 1f else 0.38f))
    }
}

/** Snackbar after an undo / redo: «تم التراجع: <label>» with «إعادة» (and the reverse after a redo). */
@Composable
fun HistorySnackbar(state: ReaderState, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    LaunchedEffect(state.historyEvent) {
        val e = state.historyEvent ?: return@LaunchedEffect
        snack.currentSnackbarData?.dismiss()
        // cleared only after showing: clearing first would restart (cancel) this effect before the snackbar is seen
        if (e.error != null) {
            snack.showSnackbar(ctx.getString(if (e.undo) R.string.hi_undo_failed else R.string.hi_redo_failed, e.error))
            if (state.historyEvent === e) state.historyEvent = null
            return@LaunchedEffect
        }
        val r = snack.showSnackbar(
            ctx.getString(if (e.undo) R.string.hi_undone else R.string.hi_redone, e.label),
            actionLabel = ctx.getString(if (e.undo) R.string.hi_redo else R.string.hi_undo),
            withDismissAction = true, duration = SnackbarDuration.Short,
        )
        if (state.historyEvent === e) state.historyEvent = null
        if (r == SnackbarResult.ActionPerformed) { if (e.undo) state.redo() else state.undo() }
    }
}

/**
 * Every step of the document's history, newest first: steps that can be redone (faded), the current state
 * (highlighted), earlier steps, then the start of the history. Each row is the document as it was right after
 * that step; tapping it undoes / redoes back to that point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(state: ReaderState, onDismiss: () -> Unit) {
    val h = state.history
    val undo = h.undoList()          // oldest first
    val redo = h.redoList()          // next to redo first
    val busy = h.busy || state.saving
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hi_history), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.hi_history_info, undo.size + redo.size, DocumentRepository.formatSize(remember(h.version) { h.totalBytes() })),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = { state.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.hi_undo)) }
            IconButton(onClick = { state.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.hi_redo)) }
        }
        if (undo.isEmpty() && redo.isEmpty()) {
            Text(stringResource(R.string.hi_history_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            return@ModalBottomSheet
        }
        Text(stringResource(R.string.hi_history_hint), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            if (redo.isNotEmpty()) item("hdr_redo") { SectionHeader(stringResource(R.string.hi_section_redo)) }
            items(redo.asReversed(), key = { "r${it.id}" }) { st ->
                StepRow(st, fmt, faded = true, current = false, enabled = !busy) { state.redoTo(st.id) }
            }
            if (undo.isNotEmpty()) item("hdr_undo") { SectionHeader(stringResource(R.string.hi_section_undo)) }
            items(undo.indices.reversed().toList(), key = { "u${undo[it].id}" }) { i ->
                val st = undo[i]
                val isCurrent = i == undo.lastIndex
                // the document right after step i: undo every newer step
                StepRow(st, fmt, faded = false, current = isCurrent, enabled = !busy && !isCurrent) { undo.getOrNull(i + 1)?.let { state.undoTo(it.id) } }
            }
            item("origin") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.hi_origin)) },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    colors = if (undo.isEmpty()) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                    modifier = Modifier.clickable(enabled = !busy && undo.isNotEmpty()) { undo.firstOrNull()?.let { state.undoTo(it.id) } },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StepRow(st: HistoryStep, fmt: java.text.DateFormat, faded: Boolean, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val file = st is HistoryStep.FileSnapshot
    val meta = buildList {
        add(fmt.format(java.util.Date(st.time)))
        if (st.page >= 0) add(stringResource(R.string.hi_page, st.page + 1))
        if (file) add(stringResource(R.string.hi_file_step))
    }.joinToString(" • ")
    ListItem(
        headlineContent = { Text(st.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(meta) },
        leadingContent = { Icon(if (file) Icons.Default.Description else Icons.Default.Edit, null) },
        trailingContent = { if (current) Icon(Icons.Default.RadioButtonChecked, stringResource(R.string.hi_now), tint = MaterialTheme.colorScheme.primary) },
        colors = if (current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = Modifier.alpha(if (faded) 0.6f else 1f).clickable(enabled = enabled, onClick = onClick),
    )
}
