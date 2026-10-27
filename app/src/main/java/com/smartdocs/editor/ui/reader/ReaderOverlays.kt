package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.tts.ReadAloudController

/** Search field with prev/next, options and a collapsible result list. */
@Composable
fun SearchBar(state: ReaderState, quick: com.smartdocs.editor.ui.quick.QuickController? = null, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var replaceOn by remember { mutableStateOf(false) }      // MODULE_search5b: find & replace in the open document
    var showList by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                OutlinedTextField(
                    state.searchQuery, { state.runSearch(it) }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    trailingIcon = {
                        if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (state.searchHits.isNotEmpty()) Text(stringResource(R.string.search_results, state.searchHits.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp).clickable { showList = !showList })
                    },
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = { state.searchStep(-1) }, enabled = state.searchHits.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.previous)) }
                IconButton(onClick = { state.searchStep(1) }, enabled = state.searchHits.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.next)) }
                if (quick != null) com.smartdocs.editor.ui.search.ReaderReplaceToggle(replaceOn) { replaceOn = !replaceOn }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.case_sensitive)) }, leadingIcon = { Checkbox(state.matchCase, null) },
                            onClick = { state.matchCase = !state.matchCase; state.runSearch(state.searchQuery) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.whole_words)) }, leadingIcon = { Checkbox(state.wholeWords, null) },
                            onClick = { state.wholeWords = !state.wholeWords; state.runSearch(state.searchQuery) })
                        com.smartdocs.editor.ui.search.ReaderSearchMenuItems { state.runSearch(state.searchQuery) }
                    }
                }
            }
            if (replaceOn && quick != null) com.smartdocs.editor.ui.search.ReaderReplaceRow(state, quick)
            // NVE: find in bookmarks / comments / fields / attachments, their results, hit colour settings
            var nveHitOpts by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { com.smartdocs.editor.ui.nve.search.NveFindScopes(state) }
                IconButton(onClick = { nveHitOpts = !nveHitOpts }) { Icon(Icons.Default.Palette, stringResource(R.string.nv_hit_colours)) }
            }
            if (nveHitOpts) com.smartdocs.editor.ui.nve.search.NveHitStyleSettings()
            if (state.searchQuery.isNotBlank()) com.smartdocs.editor.ui.nve.search.NveFindExtraResults(state, state.searchQuery) { p -> state.jumpTo(p) }
            if (showList && state.searchHits.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    itemsIndexed(state.searchHits) { i, h ->
                        val sel = i == state.searchIndex
                        ListItem(
                            headlineContent = { Text(h.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = { Text((h.page + 1).toString(), style = MaterialTheme.typography.labelMedium) },
                            colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                            modifier = Modifier.clickable { state.searchIndex = i; state.jumpTo(h.page); showList = false },
                        )
                    }
                }
            }
        }
    }
}

/** Floating action row for the current text selection. */
@Composable
fun SelectionMenu(
    sel: TextSelection, state: ReaderState,
    onCopy: (String) -> Unit, onTranslate: (String) -> Unit, onAskAi: (String) -> Unit, onReadFrom: (TextSelection) -> Unit,
    onWebSearch: (String) -> Unit, onShare: (String) -> Unit,
    onRedact: ((TextSelection) -> Unit)? = null,
    onRewrite: ((TextSelection) -> Unit)? = null,   // ai6: AI rewrite / tone, replaces the text in the document
) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolChip(Icons.Default.ContentCopy, stringResource(R.string.copy_text)) { onCopy(sel.text) }
            ToolChip(Icons.Default.Highlight, stringResource(R.string.annot_highlight), tint = annotColor(0xFFC107)) { state.markupFromSelection(AnnotKind.HIGHLIGHT) }
            ToolChip(Icons.Default.FormatUnderlined, stringResource(R.string.annot_underline)) { state.markupFromSelection(AnnotKind.UNDERLINE) }
            ToolChip(Icons.Default.FormatStrikethrough, stringResource(R.string.annot_strikeout)) { state.markupFromSelection(AnnotKind.STRIKEOUT) }
            if (com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(Icons.Default.Translate, stringResource(R.string.ai_translate)) { onTranslate(sel.text) }
            if (com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(Icons.Default.AutoAwesome, stringResource(R.string.ai_explain)) { onAskAi(sel.text) }
            if (onRewrite != null && com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(Icons.Default.EditNote, stringResource(R.string.ai6r_rewrite_chip)) { onRewrite(sel) }   // ai6
            ToolChip(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tts_from_here)) { onReadFrom(sel) }
            ToolChip(Icons.Default.TravelExplore, stringResource(R.string.rd_web_search)) { onWebSearch(sel.text) }
            ToolChip(Icons.Default.Share, stringResource(R.string.share_text)) { onShare(sel.text) }
            var e5Link by remember { mutableStateOf(false) }
            ToolChip(Icons.Default.Link, stringResource(R.string.annot_link)) { e5Link = true }
            if (e5Link) com.smartdocs.editor.ui.edit5.ReaderLinkFromSelection(state) { e5Link = false }
            var nbmBookmark by remember { mutableStateOf(false) }   // nav-bookmarks: a real PDF bookmark from the selection
            ToolChip(Icons.Default.BookmarkAdd, stringResource(R.string.nbm_bm_from_selection)) { nbmBookmark = true }
            if (nbmBookmark) com.smartdocs.editor.ui.edit5.ReaderBookmarkFromSelection(state) { nbmBookmark = false }
            if (onRedact != null) ToolChip(Icons.Default.Block, stringResource(R.string.s5_rd_chip)) { onRedact(sel) }   // MODULE_search5b
            com.smartdocs.editor.ui.nve.NveSelectionChips(sel, state)   // NVE: squiggly, note, bookmark, word count, copy formatted / table, export, rewrite, edit
            var wordCount by remember { mutableStateOf(false) }
            ToolChip(Icons.Default.Numbers, stringResource(R.string.nt_wc_title)) { wordCount = true }   // Foxit "Word Count" of the selection
            if (wordCount) AlertDialog(onDismissRequest = { wordCount = false }, title = { Text(stringResource(R.string.nt_wc_selection)) },
                text = { com.smartdocs.editor.ui.tools5.WordCountTable(com.smartdocs.editor.core.tools5.WordCount.count(sel.text)) },
                confirmButton = { TextButton(onClick = { wordCount = false }) { Text(stringResource(R.string.ok)) } })
        }
    }
}

/** Mini player shown while reading aloud. */
@Composable
fun TtsBar(status: ReadAloudController.Status, onRateChange: (Float) -> Unit) {
    val playing = status.state == ReadAloudController.State.PLAYING
    Surface(tonalElevation = 4.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ReadAloudController.prevPage() }) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous)) }
                FilledIconButton(onClick = { ReadAloudController.toggle() }) {
                    if (status.state == ReadAloudController.State.LOADING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.tts_pause else R.string.tts_play))
                }
                IconButton(onClick = { ReadAloudController.nextPage() }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next)) }
                IconButton(onClick = { ReadAloudController.stop() }) { Icon(Icons.Default.Stop, stringResource(R.string.tts_stop)) }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(stringResource(R.string.tts_notification), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(stringResource(R.string.page_of, status.page + 1, status.pageCount) + if (status.chunkCount > 0) "  ·  ${status.chunkIndex + 1}/${status.chunkCount}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                        FilterChip(selected = kotlin.math.abs(status.rate - r) < 0.01f, onClick = { onRateChange(r) }, label = { Text("${r}x") }, modifier = Modifier.height(28.dp))
                    }
                }
            }
            if (status.noArabic) Text(stringResource(R.string.tts_no_arabic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** "Page X of Y" pill with a tap to open the go-to dialog. */
@Composable
fun PageIndicator(current: Int, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Text(stringResource(R.string.page_of, current + 1, count), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}
