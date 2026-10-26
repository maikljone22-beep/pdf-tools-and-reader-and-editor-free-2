package com.smartdocs.editor.ui.share5

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.smartdocs.editor.core.share5.AppDataBundle
import com.smartdocs.editor.core.share5.AppDataSync
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Route "share5/appdata" (O4): sync signatures, stamps, fill profile, tags and favourites across devices, or move them by file. */
@Composable
fun AppDataScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(AppDataSync.enabled(ctx)) }
    var last by remember { mutableLongStateOf(AppDataSync.lastSync(ctx)) }
    var err by remember { mutableStateOf(AppDataSync.lastError(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var resumeSync by remember { mutableStateOf(false) }

    fun summary(s: AppDataBundle.Summary) = ctx.getString(R.string.sh5_appdata_added, s.signatures, s.stamps, s.fields, s.tags, s.favorites)

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) { runCatching { AppDataSync.finishAuthorization(ctx, res.data) }; resumeSync = true }
        else busy = false
    }
    fun syncNow() {
        scope.launch {
            busy = true
            runCatching { AppDataSync.sync(ctx) }
                .onSuccess { o ->
                    when (o) {
                        is AppDataSync.Outcome.NeedsUi -> { consent.launch(IntentSenderRequest.Builder(o.pendingIntent.intentSender).build()); return@launch }
                        is AppDataSync.Outcome.Done -> Toast.makeText(ctx, ctx.getString(R.string.sh5_appdata_synced, o.imported) + "\n" + summary(o.added), Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
            enabled = AppDataSync.enabled(ctx); last = AppDataSync.lastSync(ctx); err = AppDataSync.lastError(ctx); busy = false
        }
    }
    LaunchedEffect(resumeSync) { if (resumeSync) { resumeSync = false; syncNow() } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                val tmp = File(ctx.cacheDir, "appdata_export.zip")
                AppDataBundle.export(ctx, tmp)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }; tmp.delete() }
            }.onSuccess { Toast.makeText(ctx, ctx.getString(R.string.sh5_exported), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                val tmp = File(ctx.cacheDir, "appdata_import.zip")
                withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
                AppDataBundle.import(ctx, tmp).also { tmp.delete() }
            }.onSuccess { Toast.makeText(ctx, summary(it), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(ctx, ctx.getString(R.string.sh5_not_bundle) + "\n" + (it.message ?: ""), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_appdata), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.sh5_appdata_explain), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp, 12.dp))
            GroupCard(stringResource(R.string.sh5_appdata_drive)) {
                GroupRow(stringResource(R.string.sh5_appdata_auto), stringResource(R.string.sh5_appdata_auto_sub), icon = Icons.Default.Sync,
                    trailing = { Switch(enabled, { on -> if (on) syncNow() else scope.launch { AppDataSync.disable(ctx); enabled = false } }) })
                GroupRow(stringResource(R.string.sh5_appdata_last), if (last > 0) S5Share.date(last) else stringResource(R.string.sh5_never_synced), icon = Icons.Default.Schedule)
                err?.let { GroupRow(stringResource(R.string.sh5_appdata_error), it, icon = Icons.Default.ErrorOutline) }
            }
            Button(onClick = { syncNow() }, enabled = !busy, shape = PillShape, modifier = Modifier.padding(24.dp, 8.dp).fillMaxWidth()) {
                Icon(Icons.Default.CloudSync, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_sync_now))
            }
            GroupCard(stringResource(R.string.sh5_appdata_file)) {
                GroupRow(stringResource(R.string.sh5_export_file), stringResource(R.string.sh5_export_file_sub), icon = Icons.Default.Upload, onClick = { export.launch("smartdocs-data.zip") })
                GroupRow(stringResource(R.string.sh5_import_file), stringResource(R.string.sh5_import_file_sub), icon = Icons.Default.Download,
                    onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) })
            }
            Text(stringResource(R.string.sh5_appdata_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp, 12.dp))
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_syncing))
}
