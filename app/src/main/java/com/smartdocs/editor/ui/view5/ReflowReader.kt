package com.smartdocs.editor.ui.view5

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.CommentFilter
import com.smartdocs.editor.core.view5.PageGraphics
import com.smartdocs.editor.core.view5.RBlock
import com.smartdocs.editor.core.view5.RImage
import com.smartdocs.editor.core.view5.RLine
import com.smartdocs.editor.core.view5.ReflowBlocks
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.view.ViewExtras
import com.smartdocs.editor.ui.reader.view.pageTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lines of a page from PDFium text + glyph boxes (box index = text index). */
internal fun pageLines(text: String, boxes: List<RectF>): List<RLine> {
    val out = ArrayList<RLine>()
    val n = minOf(text.length, boxes.size)
    var start = 0
    fun emit(end: Int) {
        if (end <= start) return
        val sb = StringBuilder(); val hs = ArrayList<Float>()
        var top = -Float.MAX_VALUE; var bot = Float.MAX_VALUE; var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE
        for (i in start until end) {
            val c = text[i]; sb.append(c)
            val b = boxes[i]
            if (b.width() <= 0f && kotlin.math.abs(b.top - b.bottom) <= 0f) continue
            val t = maxOf(b.top, b.bottom); val bt = minOf(b.top, b.bottom)
            if (c.isLetterOrDigit()) hs += t - bt
            top = maxOf(top, t); bot = minOf(bot, bt); l = minOf(l, b.left); r = maxOf(r, b.right)
        }
        val s = sb.toString().trim()
        if (s.isNotEmpty() && top > bot) out += RLine(s, hs.sorted().getOrNull(hs.size / 2) ?: (top - bot), top, bot, l, r)
    }
    for (i in 0 until n) if (text[i] == '\n' || text[i] == '\r') { emit(i); start = i + 1 }
    emit(n)
    return out
}

/**
 * Full reflow reading mode: the whole document as headings, paragraphs and images (pages built in the
 * background, a page mark between pages), with font size, line spacing, serif, justify, images toggle,
 * headings outline and search. Replaces the page-text ReflowView (hook).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflowReader(s: ReaderState) {
    val vx = s.vx
    val blocks = remember(s.renderVersion) { mutableStateListOf<RBlock>() }
    var built by remember(s.renderVersion) { mutableIntStateOf(0) }
    var lineSpacing by remember { mutableFloatStateOf(1.6f) }
    var serif by remember { mutableStateOf(false) }
    var justify by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf(true) }
    var outline by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val startPage = remember { s.currentPage }

    LaunchedEffect(s.session, s.renderVersion) {
        val f = s.file ?: return@LaunchedEffect
        blocks.clear(); built = 0
        val order = (startPage until s.pageCount) + (0 until startPage)       // the page being read first
        val all = HashMap<Int, List<RBlock>>()
        order.chunked(12).forEach { chunk ->
            val imgs = runCatching { PageGraphics.images(f, s.password, chunk.min()..chunk.max()) }.getOrDefault(emptyMap())
            chunk.forEach { p ->
                val (pw, ph) = s.pageSize(p)
                val lines = withContext(Dispatchers.Default) { pageLines(s.pageText(p), s.charBoxes(p)) }
                val area = pw * ph
                val im = (imgs[p] ?: emptyList()).filter { val a = (it.right - it.left) * (it.top - it.bottom); a > area * 0.004f && a < area * 0.92f }
                val b = if (lines.isEmpty()) listOf(RBlock.PageMark(p), RBlock.Image(p, RImage(ph, 0f, 0f, pw)))
                else ReflowBlocks.build(p, lines, im, ph)
                all[p] = b
            }
            // keep document order in the list
            val ordered = (0 until s.pageCount).mapNotNull { all[it] }.flatten()
            blocks.clear(); blocks.addAll(ordered); built = all.size
        }
    }
    // start at the page being read
    var positioned by remember(s.renderVersion) { mutableStateOf(false) }
    LaunchedEffect(built) {
        if (positioned || built == 0) return@LaunchedEffect
        val i = blocks.indexOfFirst { it is RBlock.PageMark && it.page == startPage }
        if (i >= 0) { list.scrollToItem(i); positioned = true }
    }
    // keep the reader's page in sync
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { i -> blocks.getOrNull(i)?.page?.let { if (it != s.currentPage) s.rememberPage(it) } }
    }

    val bg = if (s.nightMode) Color(0xFF121212) else Color(s.pageColor or 0xFF000000L)
    val fg = if (s.nightMode) Color(0xFFE0E0E0) else Color(pageTextColor(s.pageColor) or 0xFF000000L)
    val fontSp = ViewExtras.REFLOW_BASE_SP * vx.reflowScale
    val folded = CommentFilter.fold(query.trim())
    val hits = remember(folded, blocks.size) {
        if (folded.length < 2) emptyList() else blocks.withIndex().filter { (_, b) ->
            (b as? RBlock.Para)?.text?.let { CommentFilter.fold(it).contains(folded) } == true || (b as? RBlock.Heading)?.text?.let { CommentFilter.fold(it).contains(folded) } == true
        }.map { it.index }
    }
    var hitIdx by remember(hits) { mutableIntStateOf(0) }
    LaunchedEffect(hits, hitIdx) { hits.getOrNull(hitIdx)?.let { list.animateScrollToItem(it) } }

    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { s.reflow = false }) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = fg) }
            IconButton(onClick = { vx.reflowSmaller() }) { Icon(Icons.Default.TextDecrease, stringResource(R.string.vw_font_smaller), tint = fg) }
            IconButton(onClick = { vx.reflowLarger() }) { Icon(Icons.Default.TextIncrease, stringResource(R.string.vw_font_larger), tint = fg) }
            IconButton(onClick = { lineSpacing = if (lineSpacing >= 2.1f) 1.3f else lineSpacing + 0.3f }) { Icon(Icons.Default.FormatLineSpacing, stringResource(R.string.v5_line_spacing), tint = fg) }
            IconButton(onClick = { serif = !serif }) { Icon(Icons.Default.FontDownload, stringResource(R.string.v5_serif), tint = if (serif) MaterialTheme.colorScheme.primary else fg) }
            IconButton(onClick = { justify = !justify }) { Icon(Icons.Default.FormatAlignJustify, stringResource(R.string.v5_justify), tint = if (justify) MaterialTheme.colorScheme.primary else fg) }
            IconButton(onClick = { images = !images }) { Icon(if (images) Icons.Default.Image else Icons.Default.HideImage, stringResource(R.string.v5_images), tint = fg) }
            IconButton(onClick = { outline = true }) { Icon(Icons.Default.Toc, stringResource(R.string.v5_headings), tint = fg) }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, stringResource(R.string.search), tint = fg) }
            IconButton(onClick = { s.nightMode = !s.nightMode }) { Icon(Icons.Default.DarkMode, stringResource(R.string.night_mode), tint = fg) }
            if (built < s.pageCount) Text(stringResource(R.string.v5_reflow_building, built, s.pageCount), color = fg, style = MaterialTheme.typography.labelSmall)
        }
        if (searching) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.search)) })
            Text(if (hits.isEmpty()) "0" else "${hitIdx + 1}/${hits.size}", color = fg, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { if (hits.isNotEmpty()) hitIdx = (hitIdx - 1 + hits.size) % hits.size }) { Icon(Icons.Default.KeyboardArrowUp, null, tint = fg) }
            IconButton(onClick = { if (hits.isNotEmpty()) hitIdx = (hitIdx + 1) % hits.size }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = fg) }
        }
        val family = if (serif) FontFamily.Serif else FontFamily.Default
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                itemsIndexed(blocks) { i, b ->
                    when (b) {
                        is RBlock.PageMark -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f), color = fg.copy(alpha = 0.2f))
                            Text(" ${b.page + 1} ", color = fg.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                            HorizontalDivider(Modifier.weight(1f), color = fg.copy(alpha = 0.2f))
                        }
                        is RBlock.Heading -> Text(highlight(b.text, folded, i in hits), Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), color = fg,
                            fontSize = (fontSp * (if (b.level == 1) 1.5f else 1.25f)).sp, fontWeight = FontWeight.Bold, fontFamily = family,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), lineHeight = (fontSp * 1.9f).sp)
                        is RBlock.Para -> Text(highlight(b.text, folded, i in hits), Modifier.fillMaxWidth().padding(vertical = 4.dp), color = fg, fontSize = fontSp.sp,
                            lineHeight = (fontSp * lineSpacing).sp, fontFamily = family,
                            textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = if (b.rtl) TextDirection.Rtl else TextDirection.Ltr))
                        is RBlock.Image -> if (images) ReflowImage(s, b)
                    }
                }
            }
        }
    }
    if (outline) {
        val heads = blocks.withIndex().filter { it.value is RBlock.Heading }
        ModalBottomSheet(onDismissRequest = { outline = false }) {
            Text(stringResource(R.string.v5_headings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            if (heads.isEmpty()) Text(stringResource(R.string.v5_no_headings), Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(heads) { _, iv ->
                    val h = iv.value as RBlock.Heading
                    ListItem(headlineContent = { Text(h.text, maxLines = 2) }, supportingContent = { Text(stringResource(R.string.v5_page_n, h.page + 1)) },
                        modifier = Modifier.padding(start = if (h.level == 2) 16.dp else 0.dp).clickable { outline = false; scope.launch { list.scrollToItem(iv.index) } })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun highlight(text: String, folded: String, on: Boolean) = buildAnnotatedString {
    if (!on || folded.length < 2) { append(text); return@buildAnnotatedString }
    // folding keeps one char per char except removed marks; find on a folded copy aligned by index map
    val map = ArrayList<Int>(); val f = StringBuilder()
    text.forEachIndexed { i, c -> val x = CommentFilter.fold(c.toString()); x.forEach { ch -> f.append(ch); map += i } }
    var from = 0; var pos = 0
    while (true) {
        val k = f.indexOf(folded, pos); if (k < 0) break
        val a = map[k]; val b = map[(k + folded.length - 1).coerceAtMost(map.size - 1)] + 1
        append(text.substring(from, a)); withStyle(SpanStyle(background = Color(0x88FFEB3B))) { append(text.substring(a, b)) }
        from = b; pos = k + folded.length
    }
    append(text.substring(from))
}

@Composable
private fun ReflowImage(s: ReaderState, b: RBlock.Image) {
    val bmp by produceState<Bitmap?>(null, b, s.renderVersion, s.nightMode) {
        val sess = s.session ?: return@produceState
        val (pw, ph) = s.pageSize(b.page)
        val i = b.img
        val clip = RectF((i.left / pw).coerceIn(0f, 1f), ((ph - i.top) / ph).coerceIn(0f, 1f), (i.right / pw).coerceIn(0f, 1f), ((ph - i.bottom) / ph).coerceIn(0f, 1f))
        if (clip.width() <= 0f || clip.height() <= 0f) return@produceState
        val fullW = (1200 / clip.width()).toInt().coerceAtMost(4000); val fullH = (fullW * ph / pw).toInt()
        value = runCatching { sess.renderRegion(b.page, fullW, fullH, clip, s.nightMode) }.getOrNull()
    }
    val bm = bmp
    if (bm != null) Image(bm.asImageBitmap(), null, Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(max = 520.dp), contentScale = ContentScale.Fit)
    else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}
