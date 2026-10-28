package com.smartdocs.editor.ui.reader.annot

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.smartdocs.editor.ui.reader.ReaderTool
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.CommentExchange
import com.smartdocs.editor.core.annot.CommentSummary
import com.smartdocs.editor.core.annot.MediaAnnots
import com.smartdocs.editor.core.annot.SummaryLabels
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity-result launchers and every dialog of MODULE_annot2. Call once from ReaderScreen:
 * `Annot2Dialogs(state, nav, snack)`.
 */
@Composable
fun Annot2Dialogs(state: ReaderState, nav: NavHostController, snack: SnackbarHostState) {
    val h = state.annot2
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.existing, state.session) { h.reloadComments() }
    LaunchedEffect(state.tool) { h.onToolChanged(state.tool) }
    val noInk = stringResource(R.string.an_hw_no_ink)
    LaunchedEffect(h.message) {
        val m = h.message ?: return@LaunchedEffect
        h.message = null
        snack.showSnackbar(if (m == "no_ink") noInk else m)
    }

    // ---------------- pickers ----------------
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tap = h.pendingTap; h.pendingTap = null
        if (uri != null && tap != null) scope.launch { MediaIntents.importUri(ctx, uri, h.mediaDir())?.let { (f, name) -> h.addMedia(tap, f, name) } }
    }
    val audioPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tap = h.pendingTap; h.pendingTap = null
        if (uri != null && tap != null) scope.launch { MediaIntents.importUri(ctx, uri, h.mediaDir())?.let { (f, name) -> h.addMedia(tap, f, name) } }
    }
    val msgImported = stringResource(R.string.an_imported); val msgNone = stringResource(R.string.an_nothing_imported)
    val msgMerged = stringResource(R.string.an_merged); val msgExported = stringResource(R.string.an_exported)
    val msgNoComments = stringResource(R.string.an_no_comments)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) h.launchBusy {
            val data = MediaIntents.importUri(ctx, uri, state.app.documents.tempDir)?.first ?: return@launchBusy
            var n = 0
            h.fileOp { src, out, pw -> n = CommentExchange.importFile(ctx, src, out, pw, data) }
            data.delete()
            snack.showSnackbar(if (n > 0) String.format(msgImported, n) else msgNone)
        }
    }
    val merge = rememberPdfPicker(multiple = false) { files ->
        val other = files.firstOrNull() ?: return@rememberPdfPicker
        h.launchBusy {
            var n = 0
            h.fileOp { src, out, pw -> n = CommentExchange.merge(ctx, src, out, pw, other) }
            snack.showSnackbar(if (n > 0) String.format(msgMerged, n) else msgNone)
        }
    }
    fun exportTo(uri: android.net.Uri?) {
        val tmp = h.pendingExport; val msg = h.pendingExportMsg; h.pendingExport = null; h.pendingExportMsg = null
        if (uri == null || tmp == null) { tmp?.delete(); return }
        // tabs audit CM-17: "exported n" only after the file was written to the chosen place
        scope.launch {
            runCatching { MediaIntents.writeTo(ctx, tmp, uri) }.onSuccess { msg?.let { snack.showSnackbar(it) } }.onFailure { state.error = it.message }
            tmp.delete()
        }
    }
    val saveXfdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.adobe.xfdf")) { exportTo(it) }
    val saveFdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.fdf")) { exportTo(it) }
    val summaryLabels = summaryLabels()

    SideEffect {
        h.launchAttach = { attach.launch(arrayOf("*/*")) }
        h.launchAudio = { audioPick.launch(arrayOf("audio/*")) }
        h.launchImport = { importer.launch(arrayOf("application/vnd.fdf", "application/vnd.adobe.xfdf", "application/xml", "text/xml", "application/octet-stream", "*/*")) }
        h.launchMerge = { merge() }
        h.launchExport = { xfdf ->
            // tabs audit CM-12: comment text leaves the document only when copying is allowed
            if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) h.launchBusy {
                val (f, cleanup) = h.exportSource() ?: return@launchBusy
                val pw = state.password     // the temp copy keeps the document's protection
                try {
                    val tmp = state.app.documents.newTemp("comments", if (xfdf) "xfdf" else "fdf")
                    val n = if (xfdf) CommentExchange.exportXfdf(f, pw, tmp) else CommentExchange.exportFdf(f, pw, tmp)
                    if (n == 0) { tmp.delete(); snack.showSnackbar(msgNoComments); return@launchBusy }
                    h.pendingExport = tmp; h.pendingExportMsg = String.format(msgExported, n)
                    val name = (state.file?.nameWithoutExtension ?: "comments") + if (xfdf) ".xfdf" else ".fdf"
                    if (xfdf) saveXfdf.launch(name) else saveFdf.launch(name)
                } finally { cleanup() }
            }
        }
        h.launchSummary = { h.cx.summaryAsk = true }       // section 7.70: options first
        h.cx.runSummary = { opts ->
            if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) h.launchBusy {     // CM-12
                val (f, cleanup) = h.exportSource() ?: return@launchBusy        // CM-36: pending edits via a temp copy
                try {
                    val out = state.app.documents.uniqueFile(state.app.documents.docsDir, "Summarize_" + (state.file?.nameWithoutExtension ?: f.nameWithoutExtension))
                    val n = CommentSummary.build(ctx, f, state.password, out, summaryLabels,
                        pageSize = { state.pageSize(it) },
                        thumb = { p, w, hh -> runCatching { state.session?.render(p, w, hh, false, true) }.getOrNull() },
                        opts = opts.copy(pageCount = state.pageCount))
                    if (n == 0) { out.delete(); snack.showSnackbar(msgNoComments) } else h.summaryFile = out
                } finally { cleanup() }
            }
        }
    }

    VideoDialogs(state)      // in-page video: picker, warnings, options, full screen, lifecycle
    CommentExtrasDialogs(state)      // section 7: properties, rotate, place on pages, calculator, export selected

    // ---------------- dialogs ----------------
    h.prompt?.let { p -> Prompt2Dialog(p, onOk = { h.confirmPrompt(p, it) }, onDismiss = { h.prompt = null }) }
    h.recordTap?.let { tap -> RecordDialog(h, tap) }
    h.playing?.let { f -> AudioPlayerDialog(f) { h.playing = null } }
    h.snapshot?.let { snap -> SnapshotSheet(state, snap) { h.snapshot = null } }
    h.handwriting?.let { req -> HandwritingDialog(state, req) }
    h.summaryFile?.let { f -> ResultSheet(f, nav, onDismiss = { h.summaryFile = null }) }
    if (h.busy || h.multi.busy) ProgressDialog(stringResource(R.string.processing))
}

/** Labels of the summary PDF, resolved from resources. */
@Composable
internal fun summaryLabels(): SummaryLabels {
    val ctx = LocalContext.current
    return remember(ctx) {
        SummaryLabels(
            title = ctx.getString(R.string.an_summary_title), pageFmt = ctx.getString(R.string.an_summary_page),
            continued = ctx.getString(R.string.an_summary_cont), repliesLabel = ctx.getString(R.string.an_reply_word),
            statusLabel = { ctx.getString(statusLabelRes(it)) },
            typeLabel = { sub -> subtypeLabelRes(sub)?.let { ctx.getString(it) } ?: sub },
            empty = ctx.getString(R.string.an_no_comments),
        )
    }
}

@Composable
private fun Prompt2Dialog(p: Prompt2, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (p.kind == AnnotKind.REPLACE) R.string.an_replace_title else R.string.an_insert_text_title)) },
        text = {
            Column {
                if (p.kind == AnnotKind.REPLACE && p.original.isNotBlank())
                    Text(stringResource(R.string.an_replace_orig, p.original.take(120)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2,   // NVE: spell check
                    visualTransformation = com.smartdocs.editor.ui.nve.spellTransformation(com.smartdocs.editor.ui.nve.rememberSpellIssues(text)), trailingIcon = { com.smartdocs.editor.ui.nve.SpellCheckButton(text, { text = it }) })
            }
        },
        confirmButton = { Button(onClick = { onOk(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RecordDialog(h: Annot2Host, tap: Tap2) {
    val ctx = LocalContext.current
    val recorder = remember { MediaAnnots.Recorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var denied by remember { mutableStateOf(false) }
    val title = stringResource(R.string.annot_audio)
    fun start() {
        runCatching { recorder.start(File(h.mediaDir(), "rec_${System.currentTimeMillis()}.m4a")); recording = true }
            .onFailure { h.s.error = it.message ?: it.toString() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else denied = true }
    // tabs audit CM-18: a recording in progress when the dialog goes away (rotation) is kept and added, not thrown away
    val recNow by rememberUpdatedState(recording)
    DisposableEffect(Unit) { onDispose {
        if (recNow) { val f = recorder.stop(); h.recordTap = null; if (f != null) h.addMedia(tap, f, title + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())) }
        else recorder.stop(discard = true)
    } }
    LaunchedEffect(recording) {
        while (recording) { elapsed = System.currentTimeMillis() - recorder.startedAt; level = (recorder.amplitude() / 20000f).coerceIn(0f, 1f); delay(150) }
    }
    fun finish() {
        val f = recorder.stop(); recording = false
        h.recordTap = null
        if (f != null) h.addMedia(tap, f, title + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()))
    }
    AlertDialog(
        onDismissRequest = { if (!recording) { recorder.stop(discard = true); h.recordTap = null } },
        // tabs audit CM-18: no tap-outside / back while recording (Stop or Cancel decide)
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = !recording, dismissOnBackPress = !recording),
        icon = { Icon(Icons.Default.Mic, null) },
        title = { Text(stringResource(R.string.an_record_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (denied) Text(stringResource(R.string.an_mic_denied), color = MaterialTheme.colorScheme.error)
                else if (recording) {
                    Text(stringResource(R.string.an_recording, String.format(java.util.Locale.US, "%d:%02d", elapsed / 60000, (elapsed / 1000) % 60)))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (!recording) Button(onClick = {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !denied) { Icon(Icons.Default.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.an_record_start)) }
            else Button(onClick = { finish() }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.an_record_stop)) }
        },
        dismissButton = {
            Row {
                // Foxit 19.x: add an existing sound file instead of recording
                if (!recording) TextButton(onClick = { recorder.stop(discard = true); h.recordTap = null; h.pendingTap = Tap2(ReaderTool.SOUND, tap.page, tap.x, tap.y); h.launchAudio() }) {
                    Text(stringResource(R.string.cx_pick_audio))
                }
                TextButton(onClick = { recorder.stop(discard = true); h.recordTap = null }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun AudioPlayerDialog(file: File, onDismiss: () -> Unit) {
    val player = remember(file) { runCatching { MediaPlayer().apply { setDataSource(file.absolutePath); prepare() } }.getOrNull() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    LaunchedEffect(player) { player?.setOnCompletionListener { playing = false; pos = 0f }; player?.start(); playing = player != null }
    LaunchedEffect(playing) {
        while (playing && player != null) { pos = runCatching { player.currentPosition.toFloat() / player.duration.coerceAtLeast(1) }.getOrDefault(0f); delay(200) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Mic, null) },
        title = { Text(file.name.substringAfter('_'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (player == null) Text(stringResource(R.string.error_generic))
            else Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (playing) { player.pause(); playing = false } else { player.start(); playing = true } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.an_pause else R.string.an_play))
                }
                Slider(pos, { v -> pos = v; runCatching { player.seekTo((v * player.duration).toInt()) } }, Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotSheet(state: ReaderState, snap: Snapshot2, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch { runCatching { MediaIntents.writeTo(ctx, snap.file, uri) }.onFailure { state.error = it.message } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.an_snapshot_copied), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Image(snap.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
            Text("${snap.bitmap.width} × ${snap.bitmap.height}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
            Spacer(Modifier.height(12.dp))
            // tabs audit CM-23: the buttons wrap on narrow phones / long Arabic labels
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { MediaIntents.copyImage(ctx, state.app, snap.file) }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ae_copy)) }
                OutlinedButton(onClick = { MediaIntents.share(ctx, state.app, snap.file, "image/png") }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share)) }
                Button(onClick = { save.launch("snapshot_" + java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US).format(java.util.Date()) + ".png") }) {
                    Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.an_save_png))
                }
            }
            // nav-batch: Foxit "Snapshot → Print" (print a portion of a page)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                // tabs audit CM-12: printing a snapshot needs the print permission
                if (state.guard.check(com.smartdocs.editor.core.security.DocAction.PRINT)) scope.launch {
                    runCatching {
                        val pdf = com.smartdocs.editor.core.tools5.ToPdf.convert(ctx, snap.file, state.app.documents.newTemp("snapshot"))
                        com.smartdocs.editor.core.util.PdfPrint.print(ctx, pdf, "snapshot", 1)
                    }.onFailure { state.error = it.message }
                }
            }) { Icon(Icons.Default.Print, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.print)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
