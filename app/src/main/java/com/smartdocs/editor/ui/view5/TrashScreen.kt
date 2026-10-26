package com.smartdocs.editor.ui.view5

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.view5.Trash
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Android entry points of the 30-day trash (files deleted from Files are moved here). */
object Trash5 {
    fun of(ctx: Context) = Trash(File(ctx.applicationContext.filesDir, "trash"))

    /** Move [f] to the trash; false when that failed (the caller may then delete it for good). */
    fun moveToTrash(ctx: Context, f: File): Boolean = runCatching { of(ctx).moveToTrash(f) != null }.getOrDefault(false)

    fun purge(ctx: Context): Int = of(ctx).purgeExpired()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val trash = remember { Trash5.of(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<Trash.Entry>(), version) { value = withContext(Dispatchers.IO) { trash.purgeExpired(); trash.list() } }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Trash.Entry?>(null) }
    val snack = remember { SnackbarHostState() }
    val now = System.currentTimeMillis()
    val docsDir = (ctx.applicationContext as com.smartdocs.editor.SmartDocsApp).documents.docsDir

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.v5_trash), onBack = { nav.popBackStack() }) {
                if (entries.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.v5_empty_trash)) }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(stringResource(R.string.v5_trash_note, Trash.DAYS) + "  ·  " + DocumentRepository.formatSize(entries.sumOf { it.size }),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entries.isEmpty()) EmptyState(Icons.Default.DeleteOutline, stringResource(R.string.v5_trash_empty))
            else LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.name) },
                        supportingContent = {
                            Text(stringResource(R.string.v5_trash_left, e.daysLeft(now)) + " · " + DocumentRepository.formatSize(e.size) + " · " + DocumentRepository.formatDate(e.deletedAt))
                        },
                        leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.Description, null) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { trash.restore(e, docsDir) }
                                        version++
                                        snack.showSnackbar(if (r != null) ctx.getString(R.string.v5_restored, r.name) else ctx.getString(R.string.error_generic))
                                    }
                                }) { Icon(Icons.Default.RestoreFromTrash, stringResource(R.string.v5_restore)) }
                                IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Default.DeleteForever, stringResource(R.string.v5_delete_forever)) }
                            }
                        })
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false }, title = { Text(stringResource(R.string.v5_empty_trash)) },
        text = { Text(stringResource(R.string.v5_empty_trash_confirm)) },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; scope.launch { withContext(Dispatchers.IO) { trash.empty() }; version++ } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } })
    confirmDelete?.let { e ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.v5_delete_forever)) }, text = { Text(e.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; scope.launch { withContext(Dispatchers.IO) { trash.deleteForever(e) }; version++ } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
