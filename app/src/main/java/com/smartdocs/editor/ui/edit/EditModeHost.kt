package com.smartdocs.editor.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import kotlinx.coroutines.delay

/**
 * Content-edit overlay shown above the reader's page view while Edit mode is on. It renders the current page
 * itself and edits the real page content: tap a text block to rewrite it (Arabic supported), tap an empty spot
 * to add text, switch to Images to replace / export / delete / rotate / move / resize pictures. Every commit
 * replaces the open document in place (see [applyEdit]); the undo button restores the previous version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditModeHost(state: com.smartdocs.editor.ui.reader.ReaderState, onExit: () -> Unit) {
    val c = rememberEditController(state)
    var showOutline by remember { mutableStateOf(false) }
    val exit = { state.jumpTo(c.page); onExit() }

    LaunchedEffect(c.page, state.renderVersion, state.file) { c.reload() }
    LaunchedEffect(c.message) { if (c.message != null) { delay(3500); c.message = null } }
    BackHandler {
        when {
            c.editingText || c.image != null || c.insertAt != null -> c.clearSelection()
            else -> exit()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            EditTopBar(c, onExit = exit, onOutline = { showOutline = true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EditPageView(c)
                if (c.loading || c.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                val hint = when {
                    c.model == null -> null
                    c.mode == EditMode.TEXT && !c.editingText -> if (c.model!!.blocks.isEmpty()) R.string.ed_no_text else R.string.ed_hint_text
                    c.mode == EditMode.IMAGE && c.image == null && c.insertAt == null -> if (c.model!!.images.isEmpty()) R.string.ed_no_images else R.string.ed_hint_image
                    else -> null
                }
                hint?.let {
                    Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)) {
                        Text(stringResource(it), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                c.message?.let { msg ->
                    Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp), action = {
                        TextButton(onClick = { c.message = null }) { Text(stringResource(R.string.ok)) }
                    }) { Text(msg) }
                }
            }
            when {
                c.mode == EditMode.TEXT && c.editingText -> TextFormatBar(c)
                c.mode == EditMode.IMAGE && c.image != null -> ImageActionBar(c)
                c.mode == EditMode.IMAGE && c.insertAt != null -> InsertImageBar(c)
            }
        }
    }
    if (showOutline) OutlineEditorSheet(state) { showOutline = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(c: EditController, onExit: () -> Unit, onOutline: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) { Icon(Icons.Default.Close, stringResource(R.string.ed_exit)) }
                Text(stringResource(R.string.ed_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { c.undo() }, enabled = c.canUndo && !c.busy) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = { c.redo() }, enabled = c.canRedo && !c.busy) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                IconButton(onClick = { c.zoom = when (c.zoom) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 3f; else -> 1f } }) {
                    BadgedBox(badge = { if (c.zoom != 1f) Badge { Text("×" + (if (c.zoom % 1f == 0f) c.zoom.toInt().toString() else c.zoom.toString())) } }) {
                        Icon(Icons.Default.ZoomIn, stringResource(R.string.ed_zoom))
                    }
                }
                IconButton(onClick = onOutline) { Icon(Icons.Default.Toc, stringResource(R.string.ed_outline_title)) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = c.mode == EditMode.TEXT, onClick = { c.clearSelection(); c.mode = EditMode.TEXT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Default.TextFields, null, Modifier.size(18.dp)) }) {
                        Text(stringResource(R.string.ed_mode_text))
                    }
                    SegmentedButton(selected = c.mode == EditMode.IMAGE, onClick = { c.clearSelection(); c.mode = EditMode.IMAGE },
                        shape = SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) }) {
                        Text(stringResource(R.string.ed_mode_image))
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { c.goToPage(c.page - 1) }, enabled = c.page > 0 && !c.busy) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.ed_prev_page)) }
                Text("${c.page + 1} / ${c.state.pageCount}", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { c.goToPage(c.page + 1) }, enabled = c.page < c.state.pageCount - 1 && !c.busy) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.ed_next_page)) }
            }
        }
    }
}
