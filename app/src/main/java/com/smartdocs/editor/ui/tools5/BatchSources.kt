package com.smartdocs.editor.ui.tools5

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.SafFolders
import com.smartdocs.editor.core.tools5.SourceKind
import com.smartdocs.editor.core.tools5.ToPdf
import com.smartdocs.editor.ui.reader.view.ReaderTabs
import kotlinx.coroutines.launch
import java.io.File

/** A batch input: the local file, the name it had in its folder, and that folder (SAF document URI) when picked from one. */
data class SourceFile(val file: File, val name: String = file.name, val folder: String = "")

/** Documents open in the reader tabs (Nitro / Foxit "Add Open Files"). */
fun openTabFiles(ctx: Context): List<File> {
    ReaderTabs.load(ctx)
    return ReaderTabs.tabs.mapNotNull { t -> runCatching { Uri.parse(t.uri).path?.let(::File) }.getOrNull()?.takeIf { it.exists() } }
}

/**
 * "Add folder" (ACTION_OPEN_DOCUMENT_TREE) with an "include sub-folders" choice: the documents [accept]ed by name are
 * copied into [dir] and handed to [onPicked]. Returns the launcher action.
 */
@Composable
fun rememberFolderSource(dir: () -> File, accept: (String) -> Boolean = { ToPdf.kindOf(it) == SourceKind.PDF }, onBusy: (Boolean) -> Unit = {}, onPicked: (List<SourceFile>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) { SafFolders.persist(ctx, u); tree = u } }
    tree?.let { t ->
        var recursive by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { tree = null },
            title = { Text(SafFolders.name(ctx, t)) },
            text = { SwitchRow(stringResource(R.string.nbt_folder_recursive), recursive) { recursive = it } },
            confirmButton = {
                TextButton(onClick = {
                    tree = null
                    onBusy(true)
                    scope.launch {
                        val out = io {
                            val target = dir()
                            SafFolders.list(ctx, t, recursive, accept).mapNotNull { e -> runCatching { SourceFile(SafFolders.copyIn(ctx, e, target), e.name, e.folder.toString()) }.getOrNull() }
                        }
                        onBusy(false)
                        onPicked(out)
                    }
                }) { Text(stringResource(R.string.nbt_folder_add)) }
            },
            dismissButton = { TextButton(onClick = { tree = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { runCatching { launcher.launch(null) } }
}

/** "Add folder" and "Add open files" buttons under the file picker of a batch screen. */
@Composable
fun BatchSourceButtons(onFolder: () -> Unit, onOpenTabs: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onFolder, Modifier.weight(1f)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbt_add_folder), maxLines = 1) }
        OutlinedButton(onClick = onOpenTabs, Modifier.weight(1f)) { Icon(Icons.Default.Tab, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbt_add_open), maxLines = 1) }
    }
}
