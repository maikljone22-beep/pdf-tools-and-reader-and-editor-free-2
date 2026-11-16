package com.smartdocs.editor.ui.tools5

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.ShareImport
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.tools.*
import java.io.File

internal val SharedFileListSaver = androidx.compose.runtime.saveable.Saver<List<File>, List<String>>(
    save = { it.map { f -> f.absolutePath } }, restore = { it.map(::File).filter(File::isFile) })

/** Receive first, then let the user choose an operation and its settings before any transformation. */
@Composable
fun SharedActionScreen(nav: NavHostController) {
    val payload = rememberSaveable(saver = SharePayloadSaver) { mutableStateOf(ShareImport.pending.also { ShareImport.pending = null }) }.value
    val files = payload?.files.orEmpty()
    val allPdf = files.isNotEmpty() && files.all { it.extension.equals("pdf", true) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    fun choose(id: String) {
        if (files.size == 1) nav.navigate(Routes.tool(id, Uri.fromFile(files.single()).toString()))
        else {
            nav.currentBackStackEntry?.savedStateHandle?.set("bulk_files", ArrayList(files.map { it.absolutePath }))
            nav.navigate(Routes.tool("bulk_$id"))
        }
    }
    ToolScaffold(stringResource(R.string.shared_choose_title), nav) {
        Text(stringResource(R.string.shared_choose_hint))
        if ((payload?.failedFiles ?: 0) > 0) Text(stringResource(R.string.shared_failed, payload!!.failedFiles), color = MaterialTheme.colorScheme.error)
        if (files.isEmpty()) { Text(stringResource(R.string.t5_share_empty)); return@ToolScaffold }
        Text(stringResource(R.string.t5_shared_files, files.size), style = MaterialTheme.typography.titleMedium)
        files.forEach { file ->
            Row(Modifier.fillMaxWidth()) {
                com.smartdocs.editor.ui.home.ThumbBox(com.smartdocs.editor.ui.home.rememberFileThumb(file), 42.dp, 54.dp, file = file)
                Text(file.name, Modifier.weight(1f).padding(8.dp))
                IconButton(onClick = {
                    nav.navigate(if (file.extension.lowercase() in setOf("xfdf", "fdf")) com.smartdocs.editor.ui.share5.Share5Routes.returned(Uri.fromFile(file).toString()) else Routes.reader(Uri.fromFile(file).toString()))
                }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.open)) }
            }
        }
        OutlinedButton(onClick = {
            ShareImport.pending = payload
            nav.navigate(Routes.tool("share_to_pdf"))
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_combine)) }
        val quick = if (allPdf) listOf("protect", "protect_advanced", "unlock", "optimize", "rotate", "convert_hub", "ocr", "flatten", "redact", "sanitize", "e5_links") else listOf("convert_hub")
        quick.mapNotNull(::toolDef).forEach { tool ->
            OutlinedButton(onClick = { choose(tool.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(tool.icon, null); Spacer(Modifier.width(8.dp)); Text(stringResource(tool.label))
            }
        }
        if (files.size > 1) OutlinedButton(onClick = { choose("choose") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bulk_choose_operation)) }
        else {
            OutlinedButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_all_tools)) }
            if (showAll) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shared_search_tools)) }, singleLine = true)
                allTools.filter { it.id !in quick }.forEach { tool ->
                    val title = stringResource(tool.label)
                    if (search.isBlank() || title.contains(search, true)) TextButton(onClick = { choose(tool.id) }) { Icon(tool.icon, null); Spacer(Modifier.width(8.dp)); Text(title) }
                }
            }
        }
    }
}
