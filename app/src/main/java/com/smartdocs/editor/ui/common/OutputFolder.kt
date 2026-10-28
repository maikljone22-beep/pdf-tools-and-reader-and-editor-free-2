package com.smartdocs.editor.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import java.io.File

/**
 * FINAL_CHECK_2 #2 (Split §23, Combine, batch): "save the outputs to this folder" — a row that shows the chosen folder
 * (SAF tree, permission kept) or "Documents only", with Choose / clear. [tree] is the tree URI ("" = none).
 */
@Composable
fun OutputFolderRow(tree: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            onChange(u.toString())
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val name = tree.takeIf { it.isNotBlank() }?.let { runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name }.getOrNull() }
        Text(name?.let { stringResource(R.string.cv9_out_folder, it) } ?: stringResource(R.string.cv9_out_documents), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (tree.isNotBlank()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        TextButton(onClick = { launcher.launch(null) }) { Text(stringResource(R.string.cv9_out_choose)) }
    }
}

/** Copy [files] into the folder [tree] (when chosen); returns how many were copied, or -1 when copying failed. */
suspend fun copyToOutputFolder(ctx: Context, tree: String, files: List<File>): Int {
    if (tree.isBlank() || files.isEmpty()) return 0
    return runCatching { com.smartdocs.editor.ui.convert6.Cv6Save.toTree(ctx, Uri.parse(tree), files.map { it to it.name }) }.getOrDefault(-1)
}
