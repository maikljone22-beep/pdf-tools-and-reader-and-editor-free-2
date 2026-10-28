package com.smartdocs.editor.ui.openall

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.openall.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Lines of a text file read so far, with the charset used and whether more is left. */
private class TextLoad(val lines: List<String>, val charset: Charset, val bytes: Long, val complete: Boolean, val states: List<Syntax.State>?)

private object TextLoader {
    const val CHUNK_CHARS = 3_000_000
    const val MAX_CHARS = 48_000_000
    const val MAX_HIGHLIGHT_LINES = 150_000

    /** Reads up to [maxChars] characters with [forced] (or the detected) charset, without loading the rest. */
    fun load(f: java.io.File, forced: Charset?, maxChars: Int, lang: Lang, pretty: Boolean): TextLoad {
        val (detected, bom) = TextDecode.detectFile(f)
        val cs = forced ?: detected
        val skip = if (forced == null || forced == detected) bom.toLong() else 0L
        val sb = StringBuilder()
        var complete = true
        f.inputStream().buffered().use { raw ->
            var s = skip; while (s > 0) { val k = raw.skip(s); if (k <= 0) break; s -= k }
            InputStreamReader(raw, cs).use { r ->
                val buf = CharArray(65536)
                while (true) {
                    val n = r.read(buf); if (n <= 0) break
                    sb.append(buf, 0, n)
                    if (sb.length >= maxChars) { complete = r.read() < 0; break }
                }
            }
        }
        var text = sb.toString()
        if (pretty && complete) {
            when (lang) { Lang.JSON -> Pretty.json(text)?.let { text = it }; Lang.XML -> Pretty.xml(text)?.let { text = it }; else -> {} }
        }
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val states = if (lang != Lang.NONE && lines.size <= MAX_HIGHLIGHT_LINES) {
            val out = ArrayList<Syntax.State>(lines.size); var st = Syntax.State()
            for (l in lines) { out += st; st = Syntax.line(l, lang, st).second }
            out
        } else null
        return TextLoad(lines, cs, f.length(), complete, states)
    }
}

private class TokColors(val kw: Color, val str: Color, val com: Color, val num: Color, val tag: Color, val attr: Color, val key: Color)

@Composable
private fun tokColors(): TokColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) TokColors(Color(0xFFC792EA), Color(0xFFC3E88D), Color(0xFF8A8F98), Color(0xFFF78C6C), Color(0xFF82AAFF), Color(0xFF89DDFF), Color(0xFFFFCB6B))
    else TokColors(Color(0xFF7B1FA2), Color(0xFF2E7D32), Color(0xFF757575), Color(0xFFD84315), Color(0xFF1565C0), Color(0xFF00838F), Color(0xFF6A1B9A))
}

private fun highlight(line: String, lang: Lang, st: Syntax.State?, c: TokColors, query: String, hit: Color): AnnotatedString = buildAnnotatedString {
    append(line)
    if (st != null) for (sp in Syntax.line(line, lang, st).first) {
        val s = sp.start.coerceIn(0, line.length); val e = sp.end.coerceIn(s, line.length)
        val style = when (sp.kind) {
            Tok.KEYWORD -> SpanStyle(color = c.kw, fontWeight = FontWeight.SemiBold)
            Tok.STRING -> SpanStyle(color = c.str)
            Tok.COMMENT -> SpanStyle(color = c.com, fontStyle = FontStyle.Italic)
            Tok.NUMBER -> SpanStyle(color = c.num)
            Tok.TAG -> SpanStyle(color = c.tag)
            Tok.ATTR -> SpanStyle(color = c.attr)
            Tok.KEY -> SpanStyle(color = c.key)
            Tok.PLAIN -> null
        }
        if (style != null && e > s) addStyle(style, s, e)
    }
    if (query.isNotEmpty()) {
        var i = line.indexOf(query, ignoreCase = true)
        while (i >= 0) { addStyle(SpanStyle(background = hit), i, i + query.length); i = line.indexOf(query, i + query.length, ignoreCase = true) }
    }
}

private val ENCODINGS = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1256", "windows-1252", "ISO-8859-6", "ISO-8859-1", "windows-1251", "Shift_JIS", "GBK")

/**
 * Streamed text / source / data viewer (open-all): encoding detection with a manual override, syntax colours,
 * JSON/XML pretty print, search, wrap toggle, font size; big files are read in chunks.
 */
@Composable
fun TextViewer(nav: NavHostController, v: ViewFile, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = v.file!!
    val ext = f.extension.lowercase()
    val lang = remember(f) { Syntax.langOf(ext.ifEmpty { if (v.type == FType.CODE) "json" else "" }).let { if (it == Lang.NONE && v.type == FType.CODE) Lang.CLIKE else it } }
    var forced by remember { mutableStateOf<Charset?>(null) }
    var maxChars by remember { mutableIntStateOf(TextLoader.CHUNK_CHARS) }
    var pretty by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var size by remember { mutableFloatStateOf(14f) }
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }
    var encMenu by remember { mutableStateOf(false) }
    var xOff by remember { mutableFloatStateOf(0f) }
    val load by produceState<Result<TextLoad>?>(null, f, forced, maxChars, pretty) {
        value = withContext(Dispatchers.IO) { runCatching { TextLoader.load(f, forced, maxChars, lang, pretty) } }
    }
    val data = load?.getOrNull()
    val list = rememberLazyListState()
    val hits = remember(data, query) { if (query.isEmpty() || data == null) emptyList() else data.lines.indices.filter { data.lines[it].contains(query, ignoreCase = true) } }
    val colors = tokColors()
    val hitColor = MaterialTheme.colorScheme.tertiaryContainer
    val editable = ext in setOf("txt", "text", "log", "md") && f.length() <= OpenAll.TEXT_EDITOR_MAX
    val actions = buildList {
        add(ViewerAction(R.string.oa_search, Icons.Default.Search) { findOpen = !findOpen })
        add(ViewerAction(R.string.oa_wrap, Icons.AutoMirrored.Filled.WrapText) { wrap = !wrap; xOff = 0f })
        if (lang == Lang.JSON || lang == Lang.XML) add(ViewerAction(R.string.oa_pretty, Icons.Default.DataObject) { pretty = !pretty })
        add(ViewerAction(R.string.oa_font_bigger, Icons.Default.TextIncrease) { size = (size + 2f).coerceAtMost(32f) })
        add(ViewerAction(R.string.oa_font_smaller, Icons.Default.TextDecrease) { size = (size - 2f).coerceAtLeast(9f) })
        add(ViewerAction(R.string.oa_choose_encoding, Icons.Default.Translate) { encMenu = true })
        if (editable) add(ViewerAction(R.string.oa_edit, Icons.Default.Edit) { nav.navigate(com.smartdocs.editor.ui.office.OfficeRoutes.route(Uri.fromFile(f).toString())) })
    }
    ViewerScaffold(nav, v, v.name, actions = actions, onBack = onBack, convert = { out -> ViewerConvert.toPdf(ctx, ViewFile(f, if (v.type == FType.CSV) FType.CSV else FType.CODE, v.uri, v.name, v.depth), out) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (findOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it; hitIndex = 0 }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.oa_find_hint)) })
                Text(if (query.isEmpty()) "" else if (hits.isEmpty()) stringResource(R.string.oa_no_match) else stringResource(R.string.oa_matches, hitIndex + 1, hits.size), Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { if (hits.isNotEmpty()) { hitIndex = (hitIndex - 1 + hits.size) % hits.size; scope.launch { list.scrollToItem(hits[hitIndex]) } } }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.oa_prev)) }
                IconButton(onClick = { if (hits.isNotEmpty()) { hitIndex = (hitIndex + 1) % hits.size; scope.launch { list.scrollToItem(hits[hitIndex]) } } }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.oa_next)) }
            }
            when {
                load == null -> CenteredProgress()
                data == null -> ErrorPane(load?.exceptionOrNull()?.message)
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.oa_encoding, data.charset.name()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(DocumentRepository.formatSize(data.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val mono = lang != Lang.NONE && lang != Lang.MARKDOWN || ext == "log"
                    val gutter = (data.lines.size.toString().length * (size * 0.62f)).dp
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.pointerInput(Unit) { detectHorizontalDragGestures { _, d -> xOff = (xOff - d).coerceIn(0f, 40000f) } }),
                        ) {
                            itemsIndexed(data.lines) { i, line ->
                                Row(Modifier.fillMaxWidth().then(if (hits.isNotEmpty() && hits.getOrNull(hitIndex) == i) Modifier.background(hitColor.copy(alpha = 0.35f)) else Modifier)) {
                                    if (mono) Text((i + 1).toString(), Modifier.width(gutter + 10.dp).padding(start = 4.dp, end = 6.dp), fontSize = (size * 0.8f).sp, color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                                    val text = highlight(line, lang, data.states?.getOrNull(i), colors, query, hitColor)
                                    val style = TextStyle(fontSize = size.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface)
                                    if (wrap) Text(text, Modifier.weight(1f).padding(end = 8.dp), style = style)
                                    else Text(text, Modifier.weight(1f).wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-xOff.toInt(), 0) }, style = style, softWrap = false, maxLines = 1)
                                }
                            }
                            if (!data.complete) item {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(stringResource(R.string.oa_text_partial, data.lines.size.toString()), style = MaterialTheme.typography.bodySmall)
                                        if (maxChars < TextLoader.MAX_CHARS) FilledTonalButton(onClick = { maxChars = (maxChars * 2).coerceAtMost(TextLoader.MAX_CHARS) }) { Text(stringResource(R.string.oa_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (encMenu) AlertDialog(onDismissRequest = { encMenu = false }, title = { Text(stringResource(R.string.oa_choose_encoding)) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { forced = null; encMenu = false }) { Text(stringResource(R.string.oa_encoding_auto)) }
                ENCODINGS.forEach { n -> runCatching { Charset.forName(n) }.getOrNull()?.let { cs -> TextButton(onClick = { forced = cs; encMenu = false }) { Text(n) } } }
            }
        },
        confirmButton = { TextButton(onClick = { encMenu = false }) { Text(stringResource(R.string.oa_close)) } })
}

/** Read-only grid for big or unusual delimited files (the CSV editor takes the normal ones). */
@Composable
fun CsvViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val rows by produceState<Result<List<List<String>>>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val (cs, bom) = TextDecode.detectFile(f)
                val text = f.inputStream().use { s -> s.skip(bom.toLong()); val b = s.readNBytesCompat(8 shl 20); String(b, cs) }
                val delim = CsvSniff.delimiter(text, f.extension)
                com.smartdocs.editor.core.office5.CsvIO.parse(text, delim).take(20_000)
            }
        }
    }
    val hs = rememberScrollState()
    ViewerScaffold(nav, v, v.name, convert = { out -> ViewerConvert.toPdf(ctx, v, out) }) { pad ->
        val r = rows?.getOrNull()
        when {
            rows == null -> Box(Modifier.padding(pad)) { CenteredProgress() }
            r == null -> Box(Modifier.padding(pad)) { ErrorPane(rows?.exceptionOrNull()?.message) }
            else -> {
                val cols = r.maxOfOrNull { it.size } ?: 0
                LazyColumn(Modifier.padding(pad).fillMaxSize().horizontalScroll(hs)) {
                    itemsIndexed(r) { i, row ->
                        Row(Modifier.background(if (i == 0) MaterialTheme.colorScheme.secondaryContainer else if (i % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)) {
                            for (c in 0 until cols) Text(row.getOrElse(c) { "" }, Modifier.width(140.dp).padding(horizontal = 6.dp, vertical = 4.dp), maxLines = 2,
                                style = TextStyle(fontSize = 13.sp, textDirection = TextDirection.Content, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }
    }
}

/** InputStream.readNBytes for API < 33. */
fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(max, 1 shl 20))
    val buf = ByteArray(65536); var total = 0
    while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n <= 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}
