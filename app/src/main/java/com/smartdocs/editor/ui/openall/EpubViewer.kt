package com.smartdocs.editor.ui.openall

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.openall.EpubBook
import com.smartdocs.editor.core.openall.EpubParser
import com.smartdocs.editor.core.openall.TextDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

private const val BOOK = "https://book.smartdocs.local/"

private fun mimeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "xhtml", "html", "htm" -> "text/html"; "css" -> "text/css"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
    "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"
    "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"; "xml", "opf", "ncx" -> "application/xml"; else -> "application/octet-stream"
}

/** Reader CSS: font size handled by textZoom; this adds direction, margins and night colours. */
private fun readerCss(rtl: Boolean, night: Boolean): String =
    "<style>html{direction:${if (rtl) "rtl" else "ltr"}}body{margin:0 auto!important;padding:12px 16px 48px!important;max-width:780px;line-height:1.7!important;word-wrap:break-word}" +
        "img,svg{max-width:100%!important;height:auto!important}" + (if (night) "html,body{background:#121212!important;color:#ddd!important}*{color:inherit!important;background-color:transparent!important}a{color:#8ab4f8!important}img{filter:brightness(.85)}" else "") +
        "</style>"

/**
 * EPUB reader (open-all): spine chapters served straight from the ZIP to a safe WebView (no scripts, no network),
 * table of contents (nav / NCX), right-to-left books, font size, night mode, last position remembered.
 */
@Composable
fun EpubViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val prefs = remember { ctx.getSharedPreferences("openall_epub", Context.MODE_PRIVATE) }
    val key = remember(f) { f.absolutePath + ":" + f.length() }
    val zip = remember(f) { runCatching { ZipFile(f) }.getOrNull() }
    DisposableEffect(zip) { onDispose { runCatching { zip?.close() } } }
    val book by produceState<Result<EpubBook>?>(null, zip) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val z = zip ?: throw IllegalStateException("not a ZIP")
                EpubParser.parse { p -> z.getEntry(p)?.takeIf { it.size <= 32L * 1024 * 1024 }?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
            }
        }
    }
    var chapter by remember { mutableIntStateOf(prefs.getInt("$key:c", 0)) }
    var fragment by remember { mutableStateOf("") }
    var zoom by remember { mutableIntStateOf(prefs.getInt("zoom", 100)) }
    var night by remember { mutableStateOf(prefs.getBoolean("night", false)) }
    var toc by remember { mutableStateOf(false) }
    val b = book?.getOrNull()
    LaunchedEffect(chapter) { prefs.edit().putInt("$key:c", chapter).apply() }
    LaunchedEffect(zoom, night) { prefs.edit().putInt("zoom", zoom).putBoolean("night", night).apply() }
    val actions = listOf(
        ViewerAction(R.string.oa_font_bigger, Icons.Default.TextIncrease) { zoom = (zoom + 10).coerceAtMost(250) },
        ViewerAction(R.string.oa_font_smaller, Icons.Default.TextDecrease) { zoom = (zoom - 10).coerceAtLeast(60) },
        ViewerAction(R.string.oa_night, Icons.Default.DarkMode) { night = !night },
    )
    val spine = b?.spine.orEmpty()
    val cur = chapter.coerceIn(0, (spine.size - 1).coerceAtLeast(0))
    fun go(i: Int) { if (i in spine.indices) { chapter = i; fragment = "" } }
    ViewerScaffold(nav, v, b?.title?.ifBlank { null } ?: v.name, actions = actions,
        topIcons = { if (b != null && b.toc.isNotEmpty()) IconButton(onClick = { toc = true }) { Icon(Icons.Default.Toc, stringResource(R.string.oa_toc)) } },
        convert = { out -> ViewerConvert.toPdf(ctx, v, out) },
        bottomBar = {
            if (b != null && spine.size > 1) {
                // page turning follows the book's direction: in a right-to-left book "next" is on the left
                val bookRtl = b.rtl
                val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                CompositionLocalProvider(LocalLayoutDirection provides if (bookRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(tonalElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { go(cur - 1) }, enabled = cur > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.oa_prev)) }
                            CompositionLocalProvider(LocalLayoutDirection provides if (uiRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                Text(stringResource(R.string.oa_chapter, cur + 1, spine.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            IconButton(onClick = { go(cur + 1) }, enabled = cur < spine.size - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.oa_next)) }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                book == null -> CenteredProgress()
                b == null || spine.isEmpty() -> ErrorPane(book?.exceptionOrNull()?.message)
                else -> {
                    val path = spine[cur]
                    val z = zip!!
                    SafeWebView(
                        url = BOOK + path.split('/').joinToString("/") { android.net.Uri.encode(it) } + (if (fragment.isNotEmpty()) "#$fragment" else ""),
                        resolver = { u ->
                            if (!u.startsWith(BOOK)) null
                            else {
                                val p = android.net.Uri.decode(u.removePrefix(BOOK).substringBefore('?'))
                                z.getEntry(p)?.takeIf { it.size <= 32L * 1024 * 1024 }?.let { e ->
                                    val bytes = z.getInputStream(e).use { it.readBytes() }
                                    val mime = mimeOf(p)
                                    if (mime == "text/html" || p.endsWith(".xhtml", true)) {
                                        val html = EpubParser.cleanChapter(TextDecode.decode(bytes, truncated = false).text)
                                        WebRes("text/html", injectHead(html, readerCss(b.rtl, night)).toByteArray(Charsets.UTF_8), "utf-8")
                                    } else WebRes(mime, bytes)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        textZoom = zoom,
                        reloadKey = night,
                        onLink = { u ->
                            if (u.startsWith(BOOK)) {
                                val p = android.net.Uri.decode(u.removePrefix(BOOK).substringBefore('#'))
                                val i = spine.indexOf(p)
                                if (i >= 0) { chapter = i; fragment = u.substringAfter('#', "") }
                            } else openLink(ctx, u)
                        },
                    )
                }
            }
        }
    }
    if (toc && b != null) TocSheet(b.toc, onPick = { t ->
        val i = spine.indexOf(t.path)
        if (i >= 0) { chapter = i; fragment = t.fragment }
        toc = false
    }, onDismiss = { toc = false })
}
