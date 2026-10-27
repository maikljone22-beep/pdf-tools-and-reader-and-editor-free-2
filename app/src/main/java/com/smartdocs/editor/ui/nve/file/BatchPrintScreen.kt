package com.smartdocs.editor.ui.nve.file

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.PrintOptions
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch
import java.io.File

/**
 * NVE-file (PRN-3): print many PDF files with the same options, as one combined print job or one job per file.
 * Tool id "print_batch". Password-protected inputs ask for their password.
 */
@Composable
fun BatchPrintScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>() }
    val passwords = remember { mutableStateMapOf<File, String>() }
    var opts by remember { mutableStateOf(PrintOptions()) }
    var combined by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askPw by remember { mutableStateOf<File?>(null) }
    val pick = rememberPdfPicker(multiple = true) { list -> list.forEach { if (it !in files) files += it } }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); error = null } }

    fun locked(f: File): Boolean = runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).close(); false }
        .getOrElse { it is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException }

    ToolScaffold(stringResource(R.string.nf_batch_print), nav, snackbar = snack) {
        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_add_files)) }
        if (files.isEmpty()) Text(stringResource(R.string.nf_batch_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (passwords.containsKey(f)) Icons.Default.LockOpen else Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(8.dp))
                Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { if (i > 0) { files.removeAt(i); files.add(i - 1, f) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.nf_move_up)) }
                IconButton(onClick = { files.removeAt(i); passwords.remove(f) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
            }
        }
        HorizontalDivider()
        SwitchLine(stringResource(R.string.nf_batch_combined), combined) { combined = it }
        NvePresetRow(opts) { opts = it }
        NvePrintOptionsEditor(opts) { opts = it }
        PrimaryButton(stringResource(R.string.print), enabled = files.isNotEmpty() && !busy, icon = Icons.Default.Print) {
            busy = true
            scope.launch {
                runCatching {
                    // ask the password of the first locked file without one; printing resumes after it is entered
                    val need = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.firstOrNull { !passwords.containsKey(it) && locked(it) } }
                    if (need != null) { askPw = need; return@runCatching }
                    NvePrint.printMany(ctx, files.map { it to passwords[it] }, opts, combined, ctx.getString(R.string.nf_batch_print))
                }.onFailure { error = it.message ?: ctx.getString(R.string.error_generic) }
                busy = false
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    askPw?.let { f ->
        var pw by remember(f) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askPw = null; files.remove(f) },
            title = { Text(f.name) },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.nf_password_for)) }) },
            confirmButton = { TextButton(onClick = { passwords[f] = pw; askPw = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { askPw = null; files.remove(f) }) { Text(stringResource(R.string.nf_skip_file)) } })
    }
}
