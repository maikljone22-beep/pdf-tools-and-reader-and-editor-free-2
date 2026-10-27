package com.smartdocs.editor.ui.tools

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.WifiTransferServer
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.PrintPipeline
import com.smartdocs.editor.core.view5.PrintPresets
import com.smartdocs.editor.core.view5.PrintSettings
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner

/**
 * Tool "print": print options (range, what to print, scale, N-up, booklet, poster, greyscale, as image, comment
 * summary, presets) then the system print dialog; "Batch print" prints many files.
 */
@Composable
fun PrintScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var range by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(PrintPresets.last(ctx) ?: PrintSettings()) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val preparing = stringResource(R.string.nbt_print_preparing)
    ToolScaffold(stringResource(R.string.tool_print), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        Text(stringResource(R.string.tl_print_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v5_range)) },
            supportingText = { Text(stringResource(R.string.v5_range_hint)) })
        com.smartdocs.editor.ui.view5.PrintOptionsForm(settings) { settings = it }
        PrimaryButton(stringResource(R.string.print), enabled = file != null, icon = Icons.Default.Print) {
            val f = file ?: return@PrimaryButton
            runner.run(preparing) {
                PrintPresets.remember(ctx, settings)
                val (out, n) = PrintPipeline.prepare(ctx, f, null, settings.toOptions(range), settings.extras(), app.documents.tempDir, com.smartdocs.editor.ui.view5.printSummaryLabels(ctx))
                PdfPrint.print(ctx, out, f.nameWithoutExtension, n, mono = settings.grayscale, media = settings.paper)
                null
            }
        }
        OutlinedButton(onClick = { nav.navigate(Routes.tool("batch_print")) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.LocalPrintshop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nbt_batch_print))
        }
    }
}

/** Tool "read_aloud": opens the reader, which hosts the TTS bar. */
@Composable
fun ReadAloudScreen(nav: NavHostController, uri: String?) {
    var file by rememberInputFile(uri)
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    ToolScaffold(stringResource(R.string.tool_read_aloud), nav) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.tl_read_aloud_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.tts_no_arabic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.tl_open_in_reader), enabled = file != null, icon = Icons.Default.PlayArrow) {
            val f = file ?: return@PrimaryButton
            nav.navigate(Routes.reader(Uri.fromFile(f).toString()))
        }
    }
}

/** Tool "wifi": local HTTP server for sending / receiving files from a computer. */
@Composable
fun WifiTransferScreen(nav: NavHostController) {
    val app = LocalApp.current
    val server = remember { WifiTransferServer(app.documents.docsDir) }
    var running by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploads by remember { mutableIntStateOf(0) }
    val noNetwork = stringResource(R.string.tl_wifi_no_network)

    DisposableEffect(Unit) {
        server.onFilesChanged = { uploads++ }
        onDispose { server.stop() }
    }

    ToolScaffold(stringResource(R.string.wifi_transfer), nav) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Wifi, null, Modifier.size(56.dp), tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                val addr = address
                if (running && addr != null) {
                    Text(stringResource(R.string.tl_wifi_running), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(addr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.wifi_transfer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (uploads > 0) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.tl_wifi_uploaded, uploads), style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(stringResource(R.string.tl_wifi_stopped), style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(stringResource(R.string.tl_wifi_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!running) PrimaryButton(stringResource(R.string.wifi_start), icon = Icons.Default.PlayArrow) {
            error = null
            val ip = WifiTransferServer.deviceIp()
            if (ip == null) { error = noNetwork; return@PrimaryButton }
            runCatching { server.start() }
                .onSuccess { address = "http://$ip:${server.port}"; running = true }
                .onFailure { error = it.message ?: it.toString() }
        } else OutlinedButton(onClick = { server.stop(); running = false }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_stop), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.tl_wifi_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class HelpItem(val icon: ImageVector, val title: Int, val body: Int)

private val helpItems = listOf(
    HelpItem(Icons.Default.MenuBook, R.string.tl_help_read_t, R.string.tl_help_read_b),
    HelpItem(Icons.Default.Draw, R.string.tl_help_annotate_t, R.string.tl_help_annotate_b),
    HelpItem(Icons.Default.AutoAwesomeMotion, R.string.tl_help_pages_t, R.string.tl_help_pages_b),
    HelpItem(Icons.Default.SwapHoriz, R.string.tl_help_convert_t, R.string.tl_help_convert_b),
    HelpItem(Icons.Default.DocumentScanner, R.string.tl_help_scan_t, R.string.tl_help_scan_b),
    HelpItem(Icons.Default.Lock, R.string.tl_help_secure_t, R.string.tl_help_secure_b),
    HelpItem(Icons.Default.Checklist, R.string.tl_help_forms_t, R.string.tl_help_forms_b),
    HelpItem(Icons.Default.AutoAwesome, R.string.tl_help_ai_t, R.string.tl_help_ai_b),
    HelpItem(Icons.Default.Wifi, R.string.tl_help_wifi_t, R.string.tl_help_wifi_b),
)

/** Tool "help": static help / about page. */
@Composable
fun HelpScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0" }.getOrDefault("1.0") }
    ToolScaffold(stringResource(R.string.tl_help_title), nav) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PictureAsPdf, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tl_help_intro), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        FieldLabel(stringResource(R.string.tl_help_features))
        helpItems.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column { Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall); Text(stringResource(item.body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text(stringResource(R.string.tl_help_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
}
