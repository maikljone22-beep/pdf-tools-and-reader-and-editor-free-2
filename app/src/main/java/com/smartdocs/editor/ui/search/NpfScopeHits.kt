package com.smartdocs.editor.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.NpfPrefs
import com.smartdocs.editor.core.search.PartKind
import com.smartdocs.editor.core.search.ScopeHit
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.core.search.SearchRequest
import com.smartdocs.editor.core.search.TextSearch
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay

/**
 * nav-prefs (old plan "searchScopes code-only", "search in attachments"): under the reader's page hits, the hits
 * in comments, bookmarks, form fields and attachments (names, descriptions and the text inside text / PDF
 * attachments) of the open document, through [TextSearch.searchScopes]. Tapping a hit with a page jumps to it.
 * Off with Settings → more preferences → search.
 */
@Composable
fun NpfScopeHits(state: ReaderState) {
    if (!NpfPrefs.searchScopes) return
    val ctx = LocalContext.current
    var hits by remember { mutableStateOf<List<ScopeHit>>(emptyList()) }
    val q = state.searchQuery
    LaunchedEffect(q, state.matchCase, state.wholeWords, state.file) {
        hits = emptyList()
        val f = state.file ?: return@LaunchedEffect
        if (q.isBlank()) return@LaunchedEffect
        delay(400)
        val opts = SearchOptions(norm = SearchPrefs.load(ctx, state.matchCase), wholeWord = state.wholeWords)
        hits = runCatching { TextSearch.searchScopes(f, state.password, SearchRequest(query = q, options = opts)) }.getOrDefault(emptyList())
    }
    if (hits.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.npf_scope_title, hits.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            items(hits) { h ->
                val r = h.ranges.firstOrNull()
                val start = ((r?.first ?: 0) - 40).coerceAtLeast(0)
                val snippet = h.text.substring(start, ((r?.last ?: 0) + 80).coerceAtMost(h.text.length).coerceAtLeast(start))
                ListItem(
                    overlineContent = { Text(stringResource(npfKindLabel(h.kind))) },
                    headlineContent = { Text((if (start > 0) "…" else "") + snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { if (h.page >= 0) Text((h.page + 1).toString(), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.clickable(enabled = h.page >= 0) { state.jumpTo(h.page) },
                )
            }
        }
    }
}

private fun npfKindLabel(k: PartKind): Int = when (k) {
    PartKind.COMMENTS -> R.string.npf_scope_comments
    PartKind.BOOKMARK -> R.string.npf_scope_bookmarks
    PartKind.FIELD -> R.string.npf_scope_fields
    PartKind.ATTACHMENT -> R.string.npf_scope_attachments
    PartKind.METADATA -> R.string.fcs_m_scope_metadata
    PartKind.TEXT -> R.string.npf_scope_text
}
