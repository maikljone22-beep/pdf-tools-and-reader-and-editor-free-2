package com.smartdocs.editor.ui.convert6

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert.ConvertEngine
import com.smartdocs.editor.core.convert6.Conv
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Engine
import com.smartdocs.editor.core.convert6.Cv6Group
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.Kind6
import com.smartdocs.editor.core.convert6.LegacyEncrypted
import com.smartdocs.editor.core.convert6.Stage
import com.smartdocs.editor.core.convert6.UnsupportedImage
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.UnsupportedSource
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.tools5.T5JobUi
import com.smartdocs.editor.ui.tools5.fileOf
import com.smartdocs.editor.ui.tools5.rememberT5Job
import com.smartdocs.editor.core.convert6.Cv6NeedPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Recently used hub conversions (SharedPreferences "cv6"). */
object Cv6Recent {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("cv6", 0)
    fun get(ctx: Context): List<String> = p(ctx).getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun push(ctx: Context, id: String) = p(ctx).edit().putString("recent", Cv6Catalog.pushRecent(get(ctx), id).joinToString(",")).apply()
}

/**
 * Tool `convert_hub` («تحويل»): searchable, grouped conversion cards. A tap opens a one-step flow: pick file(s) (or use the
 * document passed in [uri]), the options sheet, a cancellable progress dialog and the result sheet.
 */
@Composable
fun ConvertHubScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val current = remember(uri) { fileOf(uri) }
    // file-type detection reads ZIP / CFB directories: off the main thread (convert audit C14)
    val currentKind by produceState<Kind6?>(null, current) { value = current?.let { f -> withContext(Dispatchers.IO) { FileKind6.of(f) } } }
    var query by rememberSaveable { mutableStateOf("") }
    var recentVersion by remember { mutableIntStateOf(0) }
    val recent = remember(recentVersion) { Cv6Recent.get(ctx).mapNotNull { Cv6Catalog.byId(it) } }
    // hub state lives in Cv6HubSession: the job and its results survive leaving the screen or a language switch (A-L16 / C11)
    val s = Cv6HubSession
    val job = remember { s.job(ctx) }
    val fresh = rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (fresh.value) { fresh.value = false; if (!job.running && s.multiResults.isEmpty()) s.sheet = false } }
    DisposableEffect(Unit) { s.visible = true; onDispose { s.visible = false } }
    com.smartdocs.editor.ui.tools5.rememberEmlLabels()   // e-mail header labels in the app language
    LaunchedEffect(Unit) { com.smartdocs.editor.core.convert6.Cv6Platform.refreshLabels(ctx) }
    val labels = remember { Cv6Catalog.all.associate { it.id to it.title(ctx) } }
    val ocrLabel = stringResource(R.string.cv6_running_ocr)

    var pickTarget by remember { mutableStateOf<Conv?>(null) }
    var appendPick by remember { mutableStateOf(false) }
    fun onPicked(files: List<File>) {
        val c = pickTarget ?: return
        scope.launch {
            val kinds = withContext(Dispatchers.IO) { files.map { f -> f to (FileKind6.of(f) to FileKind6.isEncryptedOffice(f)) } }
            val ok = kinds.filter { (_, k) -> k.first in c.inputs || (k.first == Kind6.UNKNOWN && !k.second && c.inputs.size > 8) }.map { it.first }
            // a password-protected Word / Excel / PowerPoint file is named as such, not just "skipped" (audit A-M11 / C33)
            val encrypted = kinds.filter { (_, k) -> k.second }.map { it.first.name }
            val skipped = files.size - ok.size - encrypted.size
            if (encrypted.isNotEmpty()) snackbar.showSnackbar(ctx.getString(R.string.cvf_encrypted_files, encrypted.joinToString("، ")))
            else if (skipped > 0) snackbar.showSnackbar(ctx.getString(R.string.cv6_skipped, skipped))
            s.inputs = if (appendPick) (s.inputs + ok).distinct() else ok
            if (s.inputs.isNotEmpty()) { s.conv = c; s.sheet = true }
        }
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { onPicked(uris.mapNotNull { runCatching { app.documents.localFile(it) }.getOrNull() }) }
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { runCatching { app.documents.localFile(u) }.getOrNull()?.let { onPicked(listOf(it)) } }
    }
    fun pick(c: Conv, append: Boolean) {
        pickTarget = c; appendPick = append
        val mimes = FileKind6.mimes(c.inputs)
        runCatching { if (c.multi) multi.launch(mimes) else single.launch(mimes) }
    }

    fun open(c: Conv) {
        Cv6Recent.push(ctx, c.id); recentVersion++
        c.route?.let { r -> nav.navigate(Routes.tool(r, if (currentKind != null && currentKind in c.inputs) uri else null)); return }
        s.reset(c, defaultsFor(c))
        when {
            c.inputs.isEmpty() -> { s.inputs = emptyList(); s.sheet = true }
            current != null && currentKind in c.inputs -> { s.inputs = listOf(current); s.sheet = true }
            else -> pick(c, append = false)
        }
    }

    // NVE-shell: the "Convert to Word / Excel / PowerPoint / Text" share targets open with that conversion preselected
    LaunchedEffect(uri) { com.smartdocs.editor.ui.nve.shell.NveConvertTargets.take()?.let { id -> Cv6Catalog.byId(id)?.let { open(it) } } }

    /** Outputs of the run (all passes) and its failures in the result sheet; every output is added to Recent (B33). */
    fun showResults(j: com.smartdocs.editor.ui.tools5.T5Job) {
        val files = s.done
        val lines = s.doneInfo + listOfNotNull(com.smartdocs.editor.core.convert6.Cv6Batch.failureLines(s.failures) { Cv6ErrorText.of(ctx, it) }
            ?.let { ctx.getString(R.string.cvf_failed_n, s.failures.size) + "\n" + it })
        val text = lines.joinToString("\n").ifBlank { null }
        when {
            files.isEmpty() -> j.error = if (s.failures.isNotEmpty()) text else ctx.getString(R.string.cv6_nothing)
            files.size == 1 && s.failures.isEmpty() -> { j.info = text; j.result = files.first() }
            else -> { s.multiInfo = text; s.multiResults = files }
        }
        scope.launch { files.forEach { f -> runCatching { app.documents.touchRecent(f) } } }
        s.done = emptyList(); s.doneInfo = emptyList(); s.failures = emptyList()
    }

    /**
     * Runs the sheet's conversion. A batch goes on when one input fails; a protected input asks for ITS password
     * (the sheet names the file) and then only the inputs that still need converting run again — earlier outputs are
     * kept (convert audit A-H5 / B02 / B03 / C1 / C3: the password reached only the first input, prompts looped).
     */
    fun start() {
        val c = s.conv ?: return
        val o = s.opts
        // a typed password belongs to the file the sheet asked about
        s.locked?.let { lf -> if (s.password.isNotBlank()) s.passwords = s.passwords + (lf.absolutePath to s.password) }
        // after a password prompt only the inputs that still wait run again (a merge needs them all)
        val mergeRun = o.merge && s.inputs.size > 1 && com.smartdocs.editor.core.convert6.Opt.MERGE in c.opts
        val ins = if (s.pendingLocked.isNotEmpty() && !mergeRun) s.pendingLocked.filter { it in s.inputs } else s.inputs
        if (s.pendingLocked.isEmpty() || mergeRun) { s.done = emptyList(); s.doneInfo = emptyList(); s.failures = emptyList() }
        val pw = if (s.locked == null) s.password.ifBlank { null } else null
        val pws = s.passwords
        s.sheet = false; s.password = ""
        val title = labels[c.id].orEmpty()
        Cv6JobService.start(ctx, title)
        job.launch(ctx.getString(R.string.cv6_converting, title)) {
            val base = message
            try {
                val r = Cv6Engine(ctx, app.documents).run(c, ins, o, pw, pws) { stage, d, t -> message = if (stage == Stage.OCR) ocrLabel else base; step(d, t) }
                if (o.attachSource && c.to == com.smartdocs.editor.core.convert6.Fmt.PDF) {   // nav-attach: embed the originals
                    val desc = ctx.getString(R.string.nat_source_desc)
                    com.smartdocs.editor.core.pdf.AttachmentOps.pairSources(r.files.filter { it.extension.equals("pdf", true) }, ins)
                        .forEach { (pdf, src) -> com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(pdf, src, desc) }
                }
                val locked = r.failures.mapNotNull { it.error as? Cv6NeedPassword }
                s.done = s.done + r.files
                s.doneInfo = s.doneInfo + listOfNotNull(r.info)
                s.failures = s.failures.filter { f -> f.file !in ins } + r.failures.filter { it.error !is Cv6NeedPassword }
                if (locked.isNotEmpty()) {
                    // ask the next protected file's password; the others go on after it
                    s.locked = locked.first().file; s.lockedWrong = locked.first().wrong
                    s.pendingLocked = locked.map { it.file }
                    error = Cv6ErrorText.of(ctx, locked.first())
                    s.sheet = true
                    return@launch
                }
                s.locked = null; s.pendingLocked = emptyList()
                showResults(this)
            } catch (e: Cv6NeedPassword) {
                s.locked = e.file; s.lockedWrong = e.wrong; s.pendingLocked = listOf(e.file)
                error = Cv6ErrorText.of(ctx, e)
                s.sheet = true
            } finally {
                Cv6JobService.stop(ctx)
                if (!s.visible) Cv6JobService.notifyDone(ctx, error == null, error ?: title)
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.cv6_title), onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val q = query.trim()
        val found = remember(q) { if (q.isEmpty()) emptyList() else Cv6Catalog.search(q) { labels[it.id].orEmpty() } }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 4.dp), placeholder = { Text(stringResource(R.string.cv6_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(50),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } })
            }
            if (q.isNotEmpty()) {
                if (found.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                    Text(stringResource(R.string.ds_no_results), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(found, key = { "q_" + it.id }) { c -> ConvCard(c, labels[c.id].orEmpty()) { open(c) } }
                return@LazyVerticalGrid
            }
            if (current != null) item(span = { GridItemSpan(maxLineSpan) }, key = "current") {
                val forIt = remember(currentKind) { currentKind?.let { Cv6Catalog.forKind(it).filter { c -> c.route != "scan" } }.orEmpty() }
                CurrentFileCard(current, forIt, labels) { open(it) }
            }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "recent_t") { GroupTitle(stringResource(R.string.cv6_recent), Icons.Default.History) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { "r_" + it.id }) { c -> ConvCard(c, labels[c.id].orEmpty(), Modifier.width(150.dp)) { open(c) } }
                    }
                }
            }
            Cv6Group.entries.forEach { g ->
                val list = Cv6Catalog.inGroup(g)
                item(span = { GridItemSpan(maxLineSpan) }, key = "g_" + g.name) { GroupTitle(stringResource(g.titleRes()), g.icon()) }
                items(list, key = { g.name + "_" + it.id }) { c -> ConvCard(c, labels[c.id].orEmpty()) { open(c) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "note") {
                Text(stringResource(R.string.cv6_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }

    val c = s.conv
    if (s.sheet && c != null) {
        Cv6OptionsSheet(c, s.inputs, { s.inputs = it }, { pick(c, append = true) }, s.opts, { s.opts = it }, s.locked != null, s.password, { s.password = it },
            onDismiss = {
                s.sheet = false
                // the password prompt was closed: show what is done, the protected files as failures
                if (s.pendingLocked.isNotEmpty()) {
                    s.failures = s.failures + s.pendingLocked.map { com.smartdocs.editor.core.convert6.Cv6Failure(it, Cv6NeedPassword(it, false)) }
                    s.locked = null; s.pendingLocked = emptyList()
                    showResults(job)
                }
            }, onConvert = { start() }, lockedName = s.locked?.name, lockedWrong = s.lockedWrong)
    }
    T5JobUi(job, nav, snackbar, mime = "*/*")
    if (s.multiResults.isNotEmpty()) Cv6ResultsSheet(s.multiResults, s.multiInfo, nav, onDismiss = { s.multiResults = emptyList() }, onZip = { z -> s.multiResults = emptyList(); job.info = null; job.result = z },
        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
}

@Composable
private fun GroupTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** source → target card. */
@Composable
fun ConvCard(c: Conv, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(c.from.icon(), c.from.tile(), size = 36.dp, iconSize = 20.dp)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                IconTile(c.to.icon(), c.to.tile(), size = 36.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (c.multi && c.inputs.isNotEmpty() && c.route == null) Text(stringResource(R.string.cv6_batch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            else Text(" ", style = MaterialTheme.typography.labelSmall, color = Color.Transparent)
        }
    }
}

@Composable
private fun CurrentFileCard(f: File, suggestions: List<Conv>, labels: Map<String, String>, onOpen: (Conv) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cv6_current_file), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions, key = { "s_" + it.id }) { c ->
                        AssistChip(onClick = { onOpen(c) }, label = { Text(labels[c.id].orEmpty(), maxLines = 1) }, leadingIcon = { Icon(c.to.icon(), null, Modifier.size(18.dp)) })
                    }
                }
            }
        }
    }
}

/** Full-width «تحويل» card at the top of the Tools tab. */
@Composable
fun ConvertHubBanner(onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.SwapHoriz, com.smartdocs.editor.core.theme.Brand.tiles.blue, size = 48.dp, iconSize = 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cv6_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.cv6_tools_card_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
