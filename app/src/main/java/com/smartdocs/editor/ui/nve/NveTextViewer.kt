package com.smartdocs.editor.ui.nve

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Width modes of the Text Viewer (Foxit "Text Viewer" width settings). */
enum class TvWidth(val label: Int) { WINDOW(R.string.nv_tv_w_window), PAGE(R.string.nv_tv_w_page), MINIMAL(R.string.nv_tv_w_min), KEEP_COLUMNS(R.string.nv_tv_w_keep) }

/**
 * Text Viewer: the document as plain text (all pages, or the current one), with font family / style / size,
 * foreground and background colours, width modes (window, page width, minimal, keep the original columns with
 * no wrap), search highlight-free selection/copy, and "Save as TXT". Settings in prefs "nve_view".
 */
@Composable
fun NveTextViewer(s: ReaderState) {
    val n = NveState.of(s)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = n.prefs
    var family by remember { mutableIntStateOf(p.getInt("tv_family", 0)) }
    var bold by remember { mutableStateOf(p.getBoolean("tv_bold", false)) }
    var italic by remember { mutableStateOf(p.getBoolean("tv_italic", false)) }
    var size by remember { mutableFloatStateOf(p.getFloat("tv_size", 16f)) }
    var fg by remember { mutableIntStateOf(p.getInt("tv_fg", 0x212121)) }
    var bg by remember { mutableIntStateOf(p.getInt("tv_bg", 0xFFFFFF)) }
    var width by remember { mutableStateOf(TvWidth.entries.getOrElse(p.getInt("tv_width", 0)) { TvWidth.WINDOW }) }
    var allPages by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val texts = remember { mutableStateMapOf<Int, String>() }
    fun persist() { p.edit().putInt("tv_family", family).putBoolean("tv_bold", bold).putBoolean("tv_italic", italic).putFloat("tv_size", size).putInt("tv_fg", fg).putInt("tv_bg", bg).putInt("tv_width", width.ordinal).apply() }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        val pages = if (allPages) 0 until s.pageCount else s.currentPage..s.currentPage
                        for (i in pages) { w.write(s.pageText(i).replace("\r\n", "\n")); w.write("\n\n") }
                    } != null
                }.getOrDefault(false)
            }
            NveSelectionOps.toast(ctx, ctx.getString(if (ok) R.string.success else R.string.nv_failed))
        }
    }
    val pages = if (allPages) (0 until s.pageCount).toList() else listOf(s.currentPage)
    val ff = when (family) { 1 -> FontFamily.Serif; 2 -> FontFamily.Monospace; else -> FontFamily.SansSerif }
    val pageWidthDp = (s.pageSizes[s.currentPage]?.first ?: 595f) / 72f * 160f
    Dialog(onDismissRequest = { persist(); n.close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF000000 or bg.toLong())) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { persist(); n.close() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                        Text(stringResource(R.string.nv_text_viewer), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        FilterChip(allPages, { allPages = !allPages }, label = { Text(stringResource(if (allPages) R.string.nv_wc_document else R.string.nv_wc_page)) })
                        IconButton(onClick = { size = (size - 1f).coerceAtLeast(8f) }) { Icon(Icons.Default.TextDecrease, stringResource(R.string.pc_cd_smaller_text)) }
                        IconButton(onClick = { size = (size + 1f).coerceAtMost(48f) }) { Icon(Icons.Default.TextIncrease, stringResource(R.string.pc_cd_larger_text)) }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, stringResource(R.string.nv_tv_settings)) }
                        IconButton(onClick = { if (s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) saveTxt.launch((s.file?.nameWithoutExtension ?: "document") + ".txt") }) { Icon(Icons.Default.SaveAlt, stringResource(R.string.nv_save_txt)) }
                    }
                }
                val maxW = when (width) { TvWidth.WINDOW -> Modifier.fillMaxWidth(); TvWidth.PAGE -> Modifier.widthIn(max = pageWidthDp.dp); TvWidth.MINIMAL -> Modifier.widthIn(max = 360.dp); TvWidth.KEEP_COLUMNS -> Modifier }
                // tab-view-core V-04: selection (and so copy) only when the document allows copying, as in reflow
                com.smartdocs.editor.ui.reader.CopyableIf(s.guard.allows(com.smartdocs.editor.core.security.DocAction.COPY)) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        items(pages.size) { k ->
                            val page = pages[k]
                            LaunchedEffect(page) { if (texts[page] == null) texts[page] = s.pageText(page) }
                            val t = texts[page] ?: ""
                            Column(maxW.then(if (width == TvWidth.KEEP_COLUMNS) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
                                Text(stringResource(R.string.page_of, page + 1, s.pageCount), color = Color(0xFF000000 or fg.toLong()).copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                Text(t, color = Color(0xFF000000 or fg.toLong()), fontSize = size.sp, fontFamily = ff, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, softWrap = width != TvWidth.KEEP_COLUMNS,
                                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content, lineHeight = (size * 1.5f).sp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false; persist() },
        title = { Text(stringResource(R.string.nv_tv_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nv_tv_font), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.ed_family_sans, R.string.ed_family_serif, R.string.ed_family_mono).forEachIndexed { i, l -> FilterChip(family == i, { family = i }, label = { Text(stringResource(l)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(bold, { bold = !bold }, label = { Text(stringResource(R.string.ed_bold)) })
                    FilterChip(italic, { italic = !italic }, label = { Text(stringResource(R.string.ed_italic)) })
                }
                Text(stringResource(R.string.nv_tv_fg), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { COLORS.forEach { c -> com.smartdocs.editor.ui.reader.ColorDot(c, fg == c) { fg = c } } }
                Text(stringResource(R.string.nv_tv_bg), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { BGS.forEach { c -> com.smartdocs.editor.ui.reader.ColorDot(c, bg == c) { bg = c } } }
                Text(stringResource(R.string.nv_tv_width), style = MaterialTheme.typography.labelLarge)
                TvWidth.entries.forEach { w -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(width == w, { width = w }); Text(stringResource(w.label)) } }
            }
        },
        confirmButton = { TextButton(onClick = { settings = false; persist() }) { Text(stringResource(R.string.ok)) } })
}

private val COLORS = listOf(0x212121, 0x000000, 0x37474F, 0x1A237E, 0x4E342E, 0x1B5E20, 0xFFFFFF, 0xFFF59D)
private val BGS = listOf(0xFFFFFF, 0xFAF3E0, 0xE8F5E9, 0xE3F2FD, 0x263238, 0x121212, 0x000000, 0xFFF8E1)
