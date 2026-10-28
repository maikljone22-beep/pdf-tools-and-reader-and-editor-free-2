package com.smartdocs.editor.ui.search

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.search.IndexExtras
import com.smartdocs.editor.core.search.IndexWorker

/**
 * fc-sync (Foxit §37): extra index preferences inside the index sheet: metadata to index, character width, titles in
 * results, the size limit and the indexing log. Options that change the stored words start a rebuild.
 */
@Composable
fun IndexExtrasSection() {
    val ctx = LocalContext.current
    var o by remember { mutableStateOf(IndexExtras.options(ctx)) }
    var width by remember { mutableStateOf(IndexExtras.widthFold(ctx)) }
    var title by remember { mutableStateOf(IndexExtras.showTitle(ctx)) }
    var limit by remember { mutableIntStateOf(IndexExtras.limitMb(ctx)) }
    var log by remember { mutableStateOf(IndexExtras.logging(ctx)) }
    @Composable
    fun row(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange); Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
    Text(stringResource(R.string.fcy_idx_title), style = MaterialTheme.typography.labelLarge)
    row(stringResource(R.string.fcy_idx_custom), o.custom) { o = o.copy(custom = it); IndexExtras.setOptions(ctx, o) }
    row(stringResource(R.string.fcy_idx_xmp), o.xmp) { o = o.copy(xmp = it); IndexExtras.setOptions(ctx, o) }
    row(stringResource(R.string.fcy_idx_tags), o.tags) { o = o.copy(tags = it); IndexExtras.setOptions(ctx, o) }
    row(stringResource(R.string.fcy_idx_width), width) { width = it; IndexExtras.setWidthFold(ctx, it); IndexWorker.runNow(ctx, force = true) }
    row(stringResource(R.string.fcy_idx_show_title), title) { title = it; IndexExtras.setShowTitle(ctx, it) }
    Text(stringResource(R.string.fcy_idx_limit), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IndexExtras.LIMITS_MB.forEach { mb ->
            FilterChip(selected = mb == limit, onClick = { limit = mb; IndexExtras.setLimitMb(ctx, mb) },
                label = { Text(if (mb == 0) stringResource(R.string.fcy_idx_no_limit) else stringResource(R.string.fcy_idx_mb, mb)) })
        }
    }
    row(stringResource(R.string.fcy_idx_log), log) { log = it; IndexExtras.setLogging(ctx, it) }
    if (log) TextButton(onClick = {
        val f = IndexExtras.logFile(ctx)
        if (f.isFile) runCatching {
            val i = (ctx.applicationContext as SmartDocsApp).documents.shareIntent(listOf(f), "text/plain")
            ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }) { Text(stringResource(R.string.fcy_idx_share_log)) }
    Text(stringResource(R.string.fcy_idx_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
