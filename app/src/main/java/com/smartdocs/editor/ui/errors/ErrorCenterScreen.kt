package com.smartdocs.editor.ui.errors

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.errors.ErrorCenter
import com.smartdocs.editor.core.errors.ErrorReporter
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.settings.SwitchRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Route of the error centre (registered in Navigation). */
const val ERROR_CENTER_ROUTE = "errors"

/** Error centre: every recorded error, details, export / share, send to the developer, auto-send switch, clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorCenterScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tick by remember { mutableIntStateOf(0) }
    val entries = remember(tick) { ErrorCenter.all() }
    var open by remember { mutableStateOf<ErrorCenter.Entry?>(null) }
    var sending by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(ErrorReporter.autoSend(ctx)) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    fun share(list: List<ErrorCenter.Entry>) = runCatching {
        val f = java.io.File(app.documents.tempDir, "smartdocs-errors.txt").apply { writeText(ErrorCenter.report(list)) }
        ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), "text/plain"), null))
    }
    fun send(list: List<ErrorCenter.Entry>) {
        if (sending || list.isEmpty()) return
        sending = true
        scope.launch {
            val msg = runCatching { ErrorReporter.send(list) }.fold(
                { ctx.getString(R.string.ec_sent, it) }, { ctx.getString(R.string.ec_send_failed, it.message ?: "") })
            sending = false; tick++
            snack.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.ec_title), onBack = { nav.popBackStack() }) {
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { share(entries) }) { Icon(Icons.Default.Share, stringResource(R.string.ec_export)) }
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.ec_clear)) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ErrorReporter.configured) {
                SwitchRow(stringResource(R.string.ec_auto), auto, stringResource(R.string.ec_auto_sub)) { auto = it; ErrorReporter.setAutoSend(ctx, it) }
                val unsent = entries.filter { it.unsent }
                if (unsent.isNotEmpty()) Button(onClick = { send(unsent) }, enabled = !sending, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ec_send_all, unsent.size))
                }
            } else {
                Text(stringResource(R.string.ec_no_server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Text(stringResource(R.string.ec_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (entries.isEmpty()) EmptyState(Icons.Default.CheckCircle, stringResource(R.string.ec_empty))
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.id }) { e ->
                    ListItem(
                        modifier = Modifier.clickable { open = e },
                        leadingContent = { Icon(if (e.kind == "CRASH") Icons.Default.Error else Icons.Default.Warning, null,
                            tint = if (e.kind == "CRASH") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) },
                        headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${e.area} · ${fmt.format(Date(e.lastAt))} · ×${e.count}", style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { if (!e.unsent) Icon(Icons.Default.CloudDone, stringResource(R.string.ec_sent_badge), tint = MaterialTheme.colorScheme.primary) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    open?.let { e ->
        val clip = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(e.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("${e.kind} · ${e.area} · ×${e.count}\n${fmt.format(Date(e.firstAt))} → ${fmt.format(Date(e.lastAt))}\n${e.version} · ${e.device}\nid ${e.id}",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(e.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { clip.setText(AnnotatedString(ErrorCenter.report(listOf(e)))) }) { Text(stringResource(R.string.ec_copy)) }
                    TextButton(onClick = { share(listOf(e)) }) { Text(stringResource(R.string.ec_export)) }
                    if (ErrorReporter.configured) TextButton(onClick = { send(listOf(e)); open = null }) { Text(stringResource(R.string.ec_send)) }
                }
            },
            dismissButton = { TextButton(onClick = { ErrorCenter.delete(e.id); open = null; tick++ }) { Text(stringResource(R.string.ec_delete)) } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.ec_clear_confirm)) },
        confirmButton = { TextButton(onClick = { ErrorCenter.clear(); confirmClear = false; tick++ }) { Text(stringResource(R.string.ec_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
