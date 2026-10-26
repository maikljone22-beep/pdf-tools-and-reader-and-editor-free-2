package com.smartdocs.editor.ui.office

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.WBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Word editor: ribbon, page-like document column, outline side panel on tablets, status bar. */
@Composable
fun WordEditor(s: WordState) {
    val tick = s.tick
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize()) {
            if (!s.reading) WordRibbon(s, wide) else ReadingBar(s)
            if (s.findOpen) {
                val ms = s.matches
                FindBar(s.query, { s.query = it; s.matchIndex = 0; s.bump() }, s.replacement, { s.replacement = it }, ms.size, s.matchIndex.coerceAtMost((ms.size - 1).coerceAtLeast(0)),
                    onPrev = { s.gotoMatch(s.matchIndex - 1); scroll(s, scope) }, onNext = { s.gotoMatch(s.matchIndex + 1); scroll(s, scope) },
                    onReplaceOne = { s.replaceCurrent() }, onReplaceAll = { s.replaceAll() }, onClose = { s.findOpen = false; s.bump() })
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide && s.outline) Outline(s, Modifier.width(260.dp).fillMaxHeight()) { i -> scope.launch { s.list.animateScrollToItem(i) } }
                Pages(s, Modifier.weight(1f).fillMaxHeight())
            }
            StatusBar(s, tick)
        }
    }
}

private fun scroll(s: WordState, scope: kotlinx.coroutines.CoroutineScope) {
    val i = if (s.focus >= 0) s.focus else s.cell?.block ?: return
    scope.launch { runCatching { s.list.animateScrollToItem(i) } }
}

@Composable
private fun Pages(s: WordState, modifier: Modifier) {
    val blocks = s.blocks
    val cw = remember(s.tick) { s.doc.contentWidthEmu() }
    val landscape = remember(s.tick) { s.doc.isLandscape() }
    BoxWithConstraints(modifier.background(Color(0xFFE6E8EB))) {
        val pageMax = if (landscape) 1100.dp else 820.dp
        val margin = if (maxWidth < 500.dp) 18.dp else 48.dp
        LazyColumn(Modifier.fillMaxSize(), state = s.list, contentPadding = PaddingValues(vertical = 12.dp)) {
            itemsIndexed(blocks) { i, b ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = pageMax).fillMaxWidth().background(Color.White).padding(horizontal = margin)
                        .padding(top = if (i == 0) margin else 0.dp, bottom = if (i == blocks.size - 1) margin * 2 else 0.dp)) {
                        when (b) {
                            is WBlock.Para -> {
                                if (b.p.pageBreakBefore) PageBreakLine()
                                ParagraphBlock(s, i, b.p, cw)
                            }
                            is WBlock.Table -> TableBlock(s, i, b)
                            is WBlock.PageBreak -> PageBreakLine(Modifier.clickable(enabled = !s.reading) { s.selectImage(i) }, selected = s.image == i)
                            is WBlock.Other -> OtherBlock(b.text, s.zoom)
                        }
                    }
                }
            }
            if (!s.reading) item {
                // tap below the last block to continue writing
                Box(Modifier.fillMaxWidth().height(120.dp).clickable { s.clearFocus(); s.insertParagraph() })
            }
        }
    }
}

@Composable
private fun PageBreakLine(modifier: Modifier = Modifier, selected: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFB0B4BA))
        Text(stringResource(R.string.o5_page_break), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = Color(0xFF7A7F86))
        HorizontalDivider(Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFB0B4BA))
    }
}

@Composable
private fun Outline(s: WordState, modifier: Modifier, onGo: (Int) -> Unit) {
    val heads = remember(s.tick) { s.blocks.withIndex().filter { (_, b) -> b is WBlock.Para && b.p.heading != 0 && b.p.text.isNotBlank() }.map { (i, b) -> Triple(i, (b as WBlock.Para).p.heading, b.p.text) } }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(8.dp)) {
            Text(stringResource(R.string.o5_outline), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            if (heads.isEmpty()) Text(stringResource(R.string.o5_outline_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            LazyColumn {
                itemsIndexed(heads) { _, (i, lvl, t) ->
                    Text(t, Modifier.fillMaxWidth().clickable { onGo(i) }.padding(start = (8 + 12 * (maxOf(lvl, 1) - 1)).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                }
            }
        }
    }
}

@Composable
private fun StatusBar(s: WordState, tick: Int) {
    val stats by produceState(s.doc.stats(), tick) { delay(500); value = s.doc.stats() }
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.o5_word_status, stats.words, stats.chars, stats.pagesEstimate), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${(s.zoom * 100).toInt()}%", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReadingBar(s: WordState) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.o5_reading_mode), Modifier.weight(1f))
            TextButton(onClick = { s.reading = false; s.bump() }) { Text(stringResource(R.string.o5_edit)) }
        }
    }
}
