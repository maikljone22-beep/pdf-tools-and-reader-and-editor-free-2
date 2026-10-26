package com.smartdocs.editor.ui.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.Attachment
import com.smartdocs.editor.core.pdf.AttachmentOps
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.launch
import java.io.File

/** List, extract and add embedded file attachments. */
@Composable
fun AttachmentsTool(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val file = rememberInputFile(uri)
    val pick = rememberPdfPicker { f -> file.value = f.firstOrNull() }
    var items by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var extracted by remember { mutableStateOf<File?>(null) }

    fun reload() { val f = file.value ?: return; scope.launch { loading = true; items = runCatching { AttachmentOps.list(f) }.getOrDefault(emptyList()); loading = false } }
    LaunchedEffect(file.value) { reload() }

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        val f = file.value ?: return@rememberLauncherForActivityResult
        if (u != null) {
            val name = app.documents.displayName(u) ?: "attachment"
            val tmp = File(app.documents.tempDir, name.replace(Regex("""[\/:*?"<>|]"""), "_"))
            val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: "application/octet-stream"
            runner.run(ctx.getString(R.string.processing)) {
                // copy on IO (inside the runner), not on the main thread: big attachments froze the UI (ANR)
                ctx.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: error("cannot open")
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_att")
                AttachmentOps.add(f, tmp, out, mime = mime); out
            }
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { file.value = it } }

    ToolScaffold(stringResource(R.string.panel_attachments), nav, runner, actions = {
        if (file.value != null) IconButton(onClick = { addPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, stringResource(R.string.at_add)) }
    }) {
        FilePickRow(file.value, stringResource(R.string.open_file), pick)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (file.value != null && items.isEmpty() && !loading) EmptyState(Icons.Default.AttachFile, stringResource(R.string.at_none), action = { Button(onClick = { addPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.at_add)) } })
        items.forEach { a ->
            ListItem(
                modifier = Modifier.clickable {
                    val f = file.value ?: return@clickable
                    scope.launch { extracted = runCatching { AttachmentOps.extract(f, a, File(app.documents.docsDir, "Attachments")) }.getOrNull() }
                },
                leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                headlineContent = { Text(a.name) },
                supportingContent = { Text(listOfNotNull(DocumentRepository.formatSize(a.size), if (a.page >= 0) stringResource(R.string.page_of, a.page + 1, 0).substringBefore(" من") else null, a.description.ifBlank { null }).joinToString(" · ")) },
                trailingContent = { Icon(Icons.Default.Download, null) },
            )
        }
    }
    extracted?.let { f ->
        AlertDialog(onDismissRequest = { extracted = null }, title = { Text(stringResource(R.string.success)) }, text = { Text(stringResource(R.string.saved_to, f.absolutePath)) },
            confirmButton = { TextButton(onClick = { extracted = null; runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), "*/*"), null)) } }) { Text(stringResource(R.string.share)) } },
            dismissButton = { TextButton(onClick = { extracted = null }) { Text(stringResource(R.string.ok)) } })
    }
}
