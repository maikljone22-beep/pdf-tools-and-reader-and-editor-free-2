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
import kotlinx.coroutines.launch
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
    val currentKind = remember(current) { current?.let { FileKind6.of(it) } }
    var query by rememberSaveable { mutableStateOf("") }
    var recentVersion by remember { mutableIntStateOf(0) }
    val recent = remember(recentVersion) { Cv6Recent.get(ctx).mapNotNull { Cv6Catalog.byId(it) } }
    var conv by remember { mutableStateOf<Conv?>(null) }
    var inputs by remember { mutableStateOf<List<File>>(emptyList()) }
    var opts by remember { mutableStateOf(Cv6Opts()) }
    var sheet by remember { mutableStateOf(false) }
    var needPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var multiResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var multiInfo by remember { mutableStateOf<String?>(null) }
    val job = rememberT5Job()
    val labels = remember { Cv6Catalog.all.associate { it.id to it.title(ctx) } }
    val ocrLabel = stringResource(R.string.cv6_running_ocr)
    val pwMsg = stringResource(R.string.cv_password_needed)

    var pickTarget by remember { mutableStateOf<Conv?>(null) }
    var appendPick by remember { mutableStateOf(false) }
    fun onPicked(files: List<File>) {
        val c = pickTarget ?: return
        val ok = files.filter { f -> FileKind6.of(f).let { k -> k in c.inputs || (k == Kind6.UNKNOWN && c.inputs.size > 8) } }
        if (ok.size < files.size) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.cv6_skipped, files.size - ok.size)) }
        inputs = if (appendPick) (inputs + ok).distinct() else ok
        if (inputs.isNotEmpty()) { conv = c; sheet = true }
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
        conv = c; opts = defaultsFor(c); needPassword = false; password = ""
        when {
            c.inputs.isEmpty() -> { inputs = emptyList(); sheet = true }
            current != null && currentKind in c.inputs -> { inputs = listOf(current); sheet = true }
            else -> pick(c, append = false)
        }
    }

    // NVE-shell: the "Convert to Word / Excel / PowerPoint / Text" share targets open with that conversion preselected
    LaunchedEffect(uri) { com.smartdocs.editor.ui.nve.shell.NveConvertTargets.take()?.let { id -> Cv6Catalog.byId(id)?.let { open(it) } } }

    fun start() {
        val c = conv ?: return
        val ins = inputs; val o = opts; val pw = password.ifBlank { null }
        sheet = false
        job.launch(ctx.getString(R.string.cv6_converting, labels[c.id].orEmpty())) {
            val base = message
            try {
                val r = Cv6Engine(ctx, app.documents).run(c, ins, o, pw) { stage, d, t -> message = if (stage == Stage.OCR) ocrLabel else base; step(d, t) }
                if (o.attachSource && c.to == com.smartdocs.editor.core.convert6.Fmt.PDF) {   // nav-attach: embed the originals
                    val desc = ctx.getString(R.string.nat_source_desc)
                    com.smartdocs.editor.core.pdf.AttachmentOps.pairSources(r.files.filter { it.extension.equals("pdf", true) }, ins)
                        .forEach { (pdf, src) -> com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(pdf, src, desc) }
                }
                needPassword = false
                info = r.info
                when {
                    r.files.size == 1 -> result = r.files.first()
                    r.files.isEmpty() -> error = ctx.getString(R.string.cv6_nothing)
                    else -> { multiInfo = r.info; multiResults = r.files }
                }
                r.files.firstOrNull()?.let { f -> runCatching { app.documents.touchRecent(f) } }
            } catch (e: ConvertEngine.PasswordNeeded) { needPassword = true; error = pwMsg; sheet = true }
            catch (e: PdfSession.Companion.PasswordRequiredException) { needPassword = true; error = pwMsg; sheet = true }
            catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { needPassword = true; error = pwMsg; sheet = true }
            catch (e: UnsupportedSource) { error = ctx.getString(R.string.cv6_err_unsupported, e.ext) }
            catch (e: UnsupportedImage) { error = if (e.reason == "heic") ctx.getString(R.string.cv6_err_heic) else ctx.getString(R.string.cv6_err_image, e.reason) }
            catch (e: LegacyEncrypted) { error = ctx.getString(R.string.cv6_err_encrypted) }
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

    val c = conv
    if (sheet && c != null) {
        Cv6OptionsSheet(c, inputs, { inputs = it }, { pick(c, append = true) }, opts, { opts = it }, needPassword, password, { password = it },
            onDismiss = { sheet = false }, onConvert = { start() })
    }
    T5JobUi(job, nav, snackbar, mime = "*/*")
    if (multiResults.isNotEmpty()) Cv6ResultsSheet(multiResults, multiInfo, nav, onDismiss = { multiResults = emptyList() }, onZip = { z -> multiResults = emptyList(); job.info = null; job.result = z })
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
