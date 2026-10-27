package com.smartdocs.editor.ui.nve

import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.UrlFinder
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderIntents
import com.smartdocs.editor.ui.reader.ReaderState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

/**
 * Plain-text URLs / e-mail addresses on a page become tappable (Foxit "create links from URLs"), and every web link
 * shows its address first with Open / Copy (hooks: PageCanvas tap, ReaderScreen openLink).
 */
object NveLinks {
    private class PageUrls(val list: List<Pair<String, List<RectF>>>)
    private val cache = ConcurrentHashMap<String, PageUrls>()
    private fun key(s: ReaderState, page: Int) = "${s.fileUri}#${s.renderVersion}#$page"

    fun confirmLinks(s: ReaderState): Boolean = NveState.of(s).prefs.getBoolean("link_confirm", true)
    fun autoLinks(s: ReaderState): Boolean = NveState.of(s).prefs.getBoolean("auto_links", true)

    /** Find the URLs of [page] in the background (cached per document version). */
    fun prefetch(s: ReaderState, page: Int) {
        if (page !in 0 until s.pageCount || cache.containsKey(key(s, page))) return
        s.scope.launch {
            val text = s.pageText(page); val boxes = s.charBoxes(page)
            val found = UrlFinder.find(text).map { (url, range) ->
                url to com.smartdocs.editor.ui.reader.TextSelectionLogic.rects(boxes, range.first, minOf(range.last, boxes.size - 1))
            }
            cache[key(s, page)] = PageUrls(found)
        }
    }

    /** PageCanvas hook: a tap at PDF ([x], [y]) on a plain-text URL shows the link sheet. True when consumed. */
    fun tapUrl(s: ReaderState, page: Int, x: Float, y: Float): Boolean {
        if (!autoLinks(s)) return false
        val urls = cache[key(s, page)] ?: run { prefetch(s, page); return false }
        val hit = urls.list.firstOrNull { (_, rects) -> rects.any { r -> val n = PageGeometry.normPdf(r); x in n.left - 2f..n.right + 2f && y in n.bottom - 2f..n.top + 2f } } ?: return false
        NveState.of(s).linkPreview = hit.first
        return true
    }

    /** ReaderScreen hook: a web link annotation (true = handled by the preview sheet). */
    fun openWeb(s: ReaderState, uri: String?): Boolean {
        if (uri.isNullOrBlank() || !confirmLinks(s)) return false
        NveState.of(s).linkPreview = uri.trim(); return true
    }
}

/** The address of a tapped link with Open / Copy / Share and "don't ask again". */
@Composable
fun NveLinkPreview(s: ReaderState) {
    val n = NveState.of(s)
    val url = n.linkPreview ?: return
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(NveLinks.confirmLinks(s)) }
    AlertDialog(onDismissRequest = { n.linkPreview = null },
        icon = { Icon(Icons.Default.Link, null) },
        title = { Text(stringResource(R.string.nv_link_title)) },
        text = {
            Column {
                Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SwitchLine(stringResource(R.string.nv_link_ask), ask) { ask = it; n.prefs.edit().putBoolean("link_confirm", it).apply() }
            }
        },
        confirmButton = { TextButton(onClick = { n.linkPreview = null; ReaderIntents.openUri(ctx, if (url.contains("@") && !url.contains("://") && !url.startsWith("mailto:")) "mailto:$url" else url) }) { Text(stringResource(R.string.nv_open)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { ReaderIntents.copy(ctx, url, "url"); NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_copied)); n.linkPreview = null }) { Text(stringResource(R.string.nv_copy_url)) }
                TextButton(onClick = { n.linkPreview = null }) { Text(stringResource(R.string.cancel)) }
            }
        })
}

/** Keeps the URLs of the pages around the current one ready for taps. */
@Composable
fun NveLinkPrefetch(s: ReaderState) {
    LaunchedEffect(s.currentPage, s.renderVersion, s.session) { if (s.session != null) listOf(s.currentPage - 1, s.currentPage, s.currentPage + 1).forEach { NveLinks.prefetch(s, it) } }
}
