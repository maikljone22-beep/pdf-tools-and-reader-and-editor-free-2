package com.smartdocs.editor.ui.ai6

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.Space
import com.smartdocs.editor.core.ai6.Craft
import com.smartdocs.editor.core.ai6.CraftKind
import com.smartdocs.editor.core.ai6.CraftLabels
import com.smartdocs.editor.core.ai6.CraftRender
import com.smartdocs.editor.core.ai6.Crafts
import com.smartdocs.editor.core.ai6.SpaceCraft
import com.smartdocs.editor.core.ai6.SpaceCrafts
import com.smartdocs.editor.ui.ai5.LanguagePicker
import com.smartdocs.editor.ui.common.ResultSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ aor (§35 / §41): Workspace crafts

/**
 * aor: generate an infographic or a web page from [files] (one document or a Space) and export it as a PNG image,
 * a self-contained HTML page or a PDF. [sourceName] goes into the footer; [onExported] gets every exported file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CraftSheet(
    nav: NavHostController, files: List<File>, kind: CraftKind, onDismiss: () -> Unit, passwords: Map<String, String> = emptyMap(),
    sourceName: String = files.firstOrNull()?.name.orEmpty(), onExported: ((File) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var focus by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("") }
    var craft by remember { mutableStateOf<Craft?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var other by remember { mutableStateOf<File?>(null) }
    val working = stringResource(R.string.a5_working)
    val labels = CraftLabels(stringResource(R.string.aor_key_figures), stringResource(R.string.aor_takeaway), stringResource(R.string.aor_contents))
    val footer = if (sourceName.isBlank()) "" else stringResource(R.string.aor_made_from, sourceName)
    val base = (files.firstOrNull()?.nameWithoutExtension ?: "craft") + if (kind == CraftKind.INFOGRAPHIC) "_infographic" else "_web"

    fun export(ext: String) {
        val c = craft ?: return
        busy = working
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    app.documents.uniqueFile(app.documents.docsDir, base, ext).also { f ->
                        when (ext) {
                            "png" -> CraftRender.png(c, labels, footer, f)
                            "html" -> f.writeText(Crafts.html(c, labels, footer))
                            else -> CraftRender.pdf(c, labels, footer, f)
                        }
                    }
                }
                if (ext == "pdf") pdf = f else other = f
                onExported?.invoke(f)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message ?: e.toString() } finally { busy = null }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (kind == CraftKind.INFOGRAPHIC) R.string.aor_infographic else R.string.aor_webpage), style = MaterialTheme.typography.titleMedium)
            val c = craft
            if (c == null) {
                Text(stringResource(if (kind == CraftKind.INFOGRAPHIC) R.string.aor_infographic_sub else R.string.aor_webpage_sub), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(focus, { focus = it }, label = { Text(stringResource(R.string.ai6t_focus_optional)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.a5_target_language)); LanguagePicker(lang, { lang = it })
                }
                Button(enabled = busy == null && files.isNotEmpty() && settings.aiEnabled, onClick = {
                    busy = working; error = null
                    scope.launch {
                        try {
                            val idx = indexWithPasswords(ctx, files, passwords)
                            val made = Crafts.generate(ctx, client, kind, idx, lang, focus)
                            val bmp = withContext(Dispatchers.Default) { CraftRender.bitmap(made, labels, footer, 720) }
                            preview = bmp
                            craft = made
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = null }
                    }
                }) { Text(stringResource(R.string.ai6t_generate)) }
            } else {
                Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                preview?.let { b ->
                    Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        Image(b.asImageBitmap(), stringResource(R.string.aor_preview), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = busy == null, onClick = { export("png") }) { Text(stringResource(R.string.aor_png)) }
                    FilledTonalButton(enabled = busy == null, onClick = { export("html") }) { Text("HTML") }
                    FilledTonalButton(enabled = busy == null, onClick = { export("pdf") }) { Text("PDF") }
                    TextButton(enabled = busy == null, onClick = { craft = null }) { Text(stringResource(R.string.ai6t_again)) }
                }
                other?.let { TFileActions(it) }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(it) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    pdf?.let { f -> ResultSheet(f, nav, onDismiss = { pdf = null }) }
}

/**
 * aor (B13 / §35): the crafts of a Space. Makes slides, an infographic or a web page from the Space's files and
 * lists every output made here (newest first) to open, share or remove again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceCraftsSheet(nav: NavHostController, space: Space, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val store = remember { SpaceCrafts(File(ctx.filesDir, "ai6/crafts")) }
    var items by remember(space.id) { mutableStateOf(store.list(space.id)) }
    var making by remember { mutableStateOf<String?>(null) }
    var openPdf by remember { mutableStateOf<File?>(null) }
    var openOther by remember { mutableStateOf<File?>(null) }
    val files = remember(space.files) { space.files.map { File(it) }.filter { it.exists() } }
    val name = space.name.ifBlank { "…" }
    fun added(kind: String): (File) -> Unit = { f -> store.add(space.id, kind, f); items = store.list(space.id) }
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.aor_crafts_title, name), style = MaterialTheme.typography.titleMedium)
            val canMake = settings.aiEnabled && AiClient.available(settings) && files.isNotEmpty()
            if (!settings.aiEnabled) Text(stringResource(R.string.ai6_off), color = MaterialTheme.colorScheme.error)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(enabled = canMake, onClick = { making = SpaceCraft.SLIDES }) {
                    Icon(Icons.Default.Slideshow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai6t_slides))
                }
                FilledTonalButton(enabled = canMake, onClick = { making = SpaceCraft.INFOGRAPHIC }) {
                    Icon(Icons.Default.Insights, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.aor_infographic))
                }
                FilledTonalButton(enabled = canMake, onClick = { making = SpaceCraft.WEB_PAGE }) {
                    Icon(Icons.Default.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.aor_webpage))
                }
            }
            HorizontalDivider()
            if (items.isEmpty()) Text(stringResource(R.string.aor_crafts_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items, key = { it.path }) { c ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (c.file.extension.lowercase() in setOf("pdf", "pptx")) openPdf = c.file else openOther = c.file
                        },
                        leadingContent = {
                            Icon(when (c.kind) { SpaceCraft.INFOGRAPHIC -> Icons.Default.Insights; SpaceCraft.WEB_PAGE -> Icons.Default.Language; else -> Icons.Default.Slideshow }, null)
                        },
                        headlineContent = { Text(c.file.name, maxLines = 1) },
                        supportingContent = { Text(dateFmt.format(Date(c.created)), style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            IconButton(onClick = { store.remove(space.id, c.path); items = store.list(space.id) }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.aor_craft_remove))
                            }
                        },
                    )
                }
            }
        }
    }
    when (making) {
        SpaceCraft.SLIDES -> SlidesSheet(nav, files, onDismiss = { making = null }, onExported = added(SpaceCraft.SLIDES))
        SpaceCraft.INFOGRAPHIC -> CraftSheet(nav, files, CraftKind.INFOGRAPHIC, onDismiss = { making = null }, sourceName = name, onExported = added(SpaceCraft.INFOGRAPHIC))
        SpaceCraft.WEB_PAGE -> CraftSheet(nav, files, CraftKind.WEB_PAGE, onDismiss = { making = null }, sourceName = name, onExported = added(SpaceCraft.WEB_PAGE))
    }
    openPdf?.let { f -> ResultSheet(f, nav, onDismiss = { openPdf = null }) }
    openOther?.let { f ->
        AlertDialog(onDismissRequest = { openOther = null }, text = { TFileActions(f) },
            confirmButton = { TextButton(onClick = { openOther = null }) { Text(stringResource(R.string.a5_close)) } })
    }
}
