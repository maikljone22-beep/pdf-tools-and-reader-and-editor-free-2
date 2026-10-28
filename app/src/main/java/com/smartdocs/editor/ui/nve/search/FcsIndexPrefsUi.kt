package com.smartdocs.editor.ui.nve.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.search.FcsIndexPrefs
import com.smartdocs.editor.core.search.IndexWorker
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.ui.settings.GroupRow
import com.smartdocs.editor.ui.settings.SwitchRow

/**
 * fc-settings: Settings → Search, index and search preferences (Foxit §37): XMP / custom properties / structure tags
 * in the index, the index log, document title in results, ignore Asian character width and the index size cap.
 */
@Composable
fun FcsIndexPrefsRows() {
    val ctx = LocalContext.current
    var meta by remember { mutableStateOf(FcsIndexPrefs.meta(ctx)) }
    var log by remember { mutableStateOf(FcsIndexPrefs.logging(ctx)) }
    var title by remember { mutableStateOf(FcsIndexPrefs.showTitle(ctx)) }
    var cache by remember { mutableIntStateOf(FcsIndexPrefs.cacheMb(ctx)) }
    var width by remember { mutableStateOf(SearchPrefs.load(ctx).ignoreWidth) }
    var changed by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    fun setMeta(m: FcsIndexPrefs.Meta) { meta = m; FcsIndexPrefs.setMeta(ctx, m); changed = true }

    Text(stringResource(R.string.fcs_m_index_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    SwitchRow(stringResource(R.string.fcs_m_index_xmp), meta.xmp) { setMeta(meta.copy(xmp = it)) }
    SwitchRow(stringResource(R.string.fcs_m_index_custom), meta.customProps) { setMeta(meta.copy(customProps = it)) }
    SwitchRow(stringResource(R.string.fcs_m_index_tags), meta.structTags, stringResource(R.string.fcs_m_index_tags_sub)) { setMeta(meta.copy(structTags = it)) }
    if (changed) GroupRow(stringResource(R.string.fcs_m_index_rebuild), stringResource(R.string.fcs_m_index_rebuild_sub), icon = Icons.Default.Refresh,
        onClick = { IndexWorker.runNow(ctx, force = true); changed = false })
    SwitchRow(stringResource(R.string.fcs_m_show_title), title) { title = it; FcsIndexPrefs.setShowTitle(ctx, it) }
    SwitchRow(stringResource(R.string.fcs_m_ignore_width), width, stringResource(R.string.fcs_m_ignore_width_sub)) { v ->
        width = v; SearchPrefs.save(ctx, SearchPrefs.load(ctx).copy(ignoreWidth = v))
    }
    Text(stringResource(R.string.fcs_m_cache_limit), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 50, 100, 250, 500).forEach { mb ->
            FilterChip(cache == mb, { cache = mb; FcsIndexPrefs.setCacheMb(ctx, mb) }, label = { Text(if (mb == 0) stringResource(R.string.fcs_m_no_limit) else stringResource(R.string.fcs_m_mb, mb)) })
        }
    }
    SwitchRow(stringResource(R.string.fcs_m_index_log), log, stringResource(R.string.fcs_m_index_log_sub)) { log = it; FcsIndexPrefs.setLogging(ctx, it) }
    if (log || FcsIndexPrefs.logFile(ctx).exists()) GroupRow(stringResource(R.string.fcs_m_index_log_view), icon = Icons.Default.Description, onClick = { showLog = true })

    if (showLog) {
        val text = remember { runCatching { FcsIndexPrefs.logFile(ctx).readText().takeLast(20_000) }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.fcs_m_index_log)) },
            text = {
                Text(text.ifBlank { stringResource(R.string.fcs_m_index_log_empty) }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { FcsIndexPrefs.clearLog(ctx); showLog = false }) { Text(stringResource(R.string.fcs_m_clear)) } },
        )
    }
}
