package com.smartdocs.editor.ui.share5

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.share5.StorageManager
import com.smartdocs.editor.core.share5.StorageManager.Kind
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route "share5/storage" (O5): sizes per category, clear, unlink cloud accounts, clear all app data. */
@Composable
fun StorageScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var buckets by remember { mutableStateOf<List<StorageManager.Bucket>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var accounts by remember { mutableStateOf<List<CloudProvider>>(emptyList()) }
    var unlink by remember { mutableStateOf<CloudProvider?>(null) }
    var confirmClear by remember { mutableStateOf<StorageManager.Bucket?>(null) }
    var wipe by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        buckets = StorageManager.scan(ctx)
        accounts = withContext(Dispatchers.IO) { Clouds.all(ctx).filter { it.isConnected() } }
    }
    fun freed(n: Long) = Toast.makeText(ctx, ctx.getString(R.string.sh5_freed, DocumentRepository.formatSize(n)), Toast.LENGTH_SHORT).show()

    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_storage), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val list = buckets
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            else {
                val cacheTotal = list.filter { it.kind in setOf(Kind.THUMBS, Kind.TEMP, Kind.AI_INDEX, Kind.AI_VOICE, Kind.OTHER_CACHE) }.sumOf { it.bytes }
                Text(stringResource(R.string.sh5_storage_total, DocumentRepository.formatSize(list.sumOf { it.bytes })), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                GroupCard(stringResource(R.string.sh5_sec_cache)) {
                    list.filter { it.clearable && it.kind != Kind.CLOUD }.forEachIndexed { i, b ->
                        if (i > 0) GroupDivider()
                        GroupRow(stringResource(label(b.kind)), DocumentRepository.formatSize(b.bytes) + " · " + stringResource(R.string.sh5_n_files, b.files), icon = icon(b.kind),
                            trailing = { TextButton(enabled = b.bytes > 0, onClick = { if (b.kind == Kind.TRASH || b.kind == Kind.OCR) confirmClear = b else scope.launch { freed(StorageManager.clear(ctx, b)); reload++ } }) { Text(stringResource(R.string.sh5_clear)) } })
                    }
                }
                Button(onClick = { scope.launch { freed(StorageManager.clearCaches(ctx)); reload++ } }, enabled = cacheTotal > 0, shape = PillShape,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
                    Icon(Icons.Default.CleaningServices, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_clear_caches, DocumentRepository.formatSize(cacheTotal)))
                }
                val cloud = list.filter { it.kind == Kind.CLOUD }
                if (cloud.isNotEmpty()) GroupCard(stringResource(R.string.sh5_sec_cloud_copies)) {
                    cloud.forEachIndexed { i, b ->
                        if (i > 0) GroupDivider()
                        // files with edits not uploaded yet are kept by "clear" (audit DI-24): say how many
                        val pending = if (b.unsynced > 0) " · " + stringResource(R.string.sh5_n_unsynced, b.unsynced, DocumentRepository.formatSize(b.unsyncedBytes)) else ""
                        GroupRow(b.provider?.label ?: "", DocumentRepository.formatSize(b.bytes) + " · " + stringResource(R.string.sh5_n_files, b.files) + pending, icon = Icons.Default.CloudDownload,
                            trailing = { TextButton(onClick = { confirmClear = b }) { Text(stringResource(R.string.sh5_clear)) } })
                    }
                }
                GroupCard(stringResource(R.string.sh5_sec_user_data)) {
                    list.filter { !it.clearable }.forEachIndexed { i, b ->
                        if (i > 0) GroupDivider()
                        GroupRow(stringResource(label(b.kind)), DocumentRepository.formatSize(b.bytes) + " · " + stringResource(R.string.sh5_n_files, b.files), icon = icon(b.kind))
                    }
                }
            }
            GroupCard(stringResource(R.string.sh5_sec_accounts)) {
                if (accounts.isEmpty()) GroupRow(stringResource(R.string.sh5_no_accounts))
                accounts.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    GroupRow(p.id.label, Clouds.accountOf(p) ?: stringResource(R.string.sh5_connected), icon = Icons.Default.Cloud,
                        trailing = { TextButton(onClick = { unlink = p }) { Text(stringResource(R.string.sh5_unlink)) } })
                }
            }
            GroupCard(stringResource(R.string.sh5_sec_danger)) {
                GroupRow(stringResource(R.string.sh5_wipe), stringResource(R.string.sh5_wipe_sub), icon = Icons.Default.DeleteForever, onClick = { wipe = true })
            }
        }
    }

    confirmClear?.let { b ->
        AlertDialog(onDismissRequest = { confirmClear = null }, title = { Text(stringResource(R.string.sh5_clear)) },
            text = { Text(stringResource(when (b.kind) { Kind.TRASH -> R.string.sh5_clear_trash_q; Kind.OCR -> R.string.sh5_clear_ocr_q; else -> R.string.sh5_clear_cloud_q })) },
            confirmButton = { TextButton(onClick = { confirmClear = null; scope.launch { freed(StorageManager.clear(ctx, b)); reload++ } }) { Text(stringResource(R.string.sh5_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(stringResource(R.string.cancel)) } })
    }
    unlink?.let { p ->
        var del by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { unlink = null }, title = { Text(stringResource(R.string.sh5_unlink)) },
            text = { Column {
                Text(stringResource(R.string.sh5_unlink_q, p.id.label))
                SwitchRow(stringResource(R.string.sh5_unlink_delete), null, del, true) { del = it }
            } },
            confirmButton = { TextButton(onClick = { unlink = null; scope.launch { StorageManager.unlink(ctx, p.id, del); reload++ } }) { Text(stringResource(R.string.sh5_unlink)) } },
            dismissButton = { TextButton(onClick = { unlink = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (wipe) AlertDialog(onDismissRequest = { wipe = false }, icon = { Icon(Icons.Default.Warning, null) }, title = { Text(stringResource(R.string.sh5_wipe)) },
        text = { Text(stringResource(R.string.sh5_wipe_q)) },
        confirmButton = { TextButton(onClick = { wipe = false; StorageManager.clearAllData(ctx) }) { Text(stringResource(R.string.sh5_wipe_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { wipe = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun label(k: Kind): Int = when (k) {
    Kind.THUMBS -> R.string.sh5_k_thumbs; Kind.TEMP -> R.string.sh5_k_temp; Kind.AI_INDEX -> R.string.sh5_k_ai; Kind.AI_VOICE -> R.string.sh5_k_voice
    Kind.OTHER_CACHE -> R.string.sh5_k_other; Kind.CLOUD -> R.string.sh5_k_cloud; Kind.OCR -> R.string.sh5_k_ocr; Kind.TRASH -> R.string.sh5_k_trash
    Kind.REVIEWS -> R.string.sh5_k_reviews; Kind.DOCUMENTS -> R.string.sh5_k_docs
}

private fun icon(k: Kind) = when (k) {
    Kind.THUMBS -> Icons.Default.Image; Kind.TEMP -> Icons.Default.HourglassEmpty; Kind.AI_INDEX -> Icons.Default.AutoAwesome; Kind.AI_VOICE -> Icons.Default.RecordVoiceOver
    Kind.OTHER_CACHE -> Icons.Default.Cached; Kind.CLOUD -> Icons.Default.Cloud; Kind.OCR -> Icons.Default.Translate; Kind.TRASH -> Icons.Default.Delete
    Kind.REVIEWS -> Icons.Default.RateReview; Kind.DOCUMENTS -> Icons.Default.Description
}
