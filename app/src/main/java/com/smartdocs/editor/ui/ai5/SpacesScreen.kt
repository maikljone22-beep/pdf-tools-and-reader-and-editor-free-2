package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.Space
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.core.ai6.SpaceBundle
import com.smartdocs.editor.core.ai6.SpaceSources
import com.smartdocs.editor.core.ai6.TextSource
import com.smartdocs.editor.core.ai6.WebText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spaces (route `tool/ai_spaces`): named sets of PDFs questioned together, each with optional instructions and
 * its own saved chats and study creations. Opening a Space goes to the hub with `space:<id>`.
 */
@Composable
fun SpacesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val store = remember { AiStore(ctx) }
    var spaces by remember { mutableStateOf(store.spaces()) }
    var editing by remember { mutableStateOf<Space?>(null) }
    val app = com.smartdocs.editor.LocalApp.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    // ai6 (B9): import a shared Space (.sdspace)
    val importBundle = rememberPdfPicker(mimes = arrayOf("application/zip", "application/octet-stream", "*/*")) { picked ->
        val f = picked.firstOrNull()
        if (f != null) scope.launch {
            val id = AiStore.newId()
            val sp = withContext(Dispatchers.IO) { runCatching { SpaceBundle.import(f, File(File(ctx.filesDir, "ai6/spaces"), id), id) }.getOrNull() }
            if (sp == null) notice = ctx.getString(R.string.ai6h_space_bad) else { store.saveSpace(sp); spaces = store.spaces(); notice = ctx.getString(R.string.ai6h_space_imported, sp.name) }
        }
    }
    fun shareSpace(s: Space) {
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { app.documents.uniqueFile(app.documents.docsDir, s.name.ifBlank { "space" }.take(40), SpaceBundle.EXT).also { SpaceBundle.export(s, it) } }.getOrNull() }
            if (out == null) notice = ctx.getString(R.string.error_generic)
            else runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(out), SpaceBundle.MIME), null)) }
        }
    }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a5_spaces), onBack = { nav.popBackStack() }) {
            IconButton(onClick = importBundle) { Icon(Icons.Default.FileOpen, stringResource(R.string.ai6h_space_import)) }   // ai6 (B9)
        } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Space(AiStore.newId(), "", emptyList()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.a5_new_space)) })
        },
    ) { pad ->
        if (spaces.isEmpty()) EmptyState(Icons.Default.Workspaces, stringResource(R.string.a5_spaces_empty), Modifier.padding(pad).fillMaxSize())
        else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(spaces, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.tool("ai", "space:" + s.id)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { "…" }, style = MaterialTheme.typography.titleSmall)
                            Text(s.files.joinToString("، ") { sourceLabel(it) }, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { shareSpace(s) }) { Icon(Icons.Default.Share, stringResource(R.string.ai6h_space_share)) }   // ai6 (B9)
                        IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, stringResource(R.string.a5_edit)) }
                        IconButton(onClick = { store.deleteSpace(s.id); spaces = store.spaces() }) { Icon(Icons.Default.Delete, stringResource(R.string.a5_delete)) }
                    }
                }
            }
        }
    }
    editing?.let { sp -> SpaceEditor(sp, onSave = { store.saveSpace(it); spaces = store.spaces(); editing = null }, onDismiss = { editing = null }) }
    notice?.let { m -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.a5_close)) } }) }
}

/** ai6: a file of a Space as shown in lists: web / text sources by their title. */
private fun sourceLabel(path: String): String =
    if (SpaceSources.isSource(path)) runCatching { SpaceSources.read(File(path)).title }.getOrNull()?.ifBlank { null } ?: File(path).name.removeSuffix(SpaceSources.EXT)
    else File(path).nameWithoutExtension

@Composable
private fun SpaceEditor(initial: Space, onSave: (Space) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var persona by remember { mutableStateOf(initial.persona) }
    var files by remember { mutableStateOf(initial.files) }
    val pick = rememberPdfPicker(multiple = true) { picked -> files = (files + picked.map { it.absolutePath }).distinct().take(20) }
    // ai6 (B9): web links and pasted text as sources
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var addKind by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val srcDir = remember { File(ctx.filesDir, "ai6/sources") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.name.isBlank()) R.string.a5_new_space else R.string.a5_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.a5_space_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(persona, { persona = it }, label = { Text(stringResource(R.string.a5_space_persona)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                files.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sourceLabel(p), Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { files = files - p }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                    }
                }
                OutlinedButton(onClick = pick) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_add_files)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {   // ai6 (B9)
                    OutlinedButton(onClick = { addKind = TextSource.URL }, enabled = !fetching) { Icon(Icons.Default.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai6h_add_link)) }
                    OutlinedButton(onClick = { addKind = TextSource.TEXT }, enabled = !fetching) { Icon(Icons.Default.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai6h_add_text)) }
                }
                if (fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
                fetchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(initial.copy(name = name.trim(), persona = persona.trim(), files = files)) }, enabled = name.isNotBlank() && files.isNotEmpty()) { Text(stringResource(R.string.a5_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.a5_cancel)) } })
    addKind?.let { kind ->
        var title by remember(kind) { mutableStateOf("") }
        var body by remember(kind) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addKind = null },
            title = { Text(stringResource(if (kind == TextSource.URL) R.string.ai6h_add_link else R.string.ai6h_add_text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == TextSource.URL) OutlinedTextField(body, { body = it.trim() }, label = { Text(stringResource(R.string.ai6h_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else {
                        OutlinedTextField(title, { title = it.take(80) }, label = { Text(stringResource(R.string.ai6h_text_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.ai6h_text_body)) }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = body.isNotBlank(), onClick = {
                    addKind = null
                    if (kind == TextSource.TEXT) {
                        val f = SpaceSources.write(srcDir, TextSource(TextSource.TEXT, title.ifBlank { body.trim().lineSequence().first().take(40) }, "", body))
                        files = (files + f.absolutePath).distinct().take(20)
                    } else {
                        val url = WebText.normalizeUrl(body)
                        if (url == null) fetchError = ctx.getString(R.string.ai6h_bad_url)
                        else {
                            fetching = true; fetchError = null
                            scope.launch {
                                try {
                                    val page = WebText.fetch(url)
                                    if (page.text.length < 40) fetchError = ctx.getString(R.string.ai6h_fetch_empty)
                                    else {
                                        val f = withContext(Dispatchers.IO) { SpaceSources.write(srcDir, TextSource(TextSource.URL, page.title, page.url, page.text)) }
                                        files = (files + f.absolutePath).distinct().take(20)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                                } catch (e: Exception) { fetchError = ctx.getString(R.string.ai6h_fetch_failed, e.message ?: e.javaClass.simpleName)
                                } finally { fetching = false }
                            }
                        }
                    }
                }) { Text(stringResource(R.string.ai6h_add)) }
            },
            dismissButton = { TextButton(onClick = { addKind = null }) { Text(stringResource(R.string.a5_cancel)) } })
    }
}
