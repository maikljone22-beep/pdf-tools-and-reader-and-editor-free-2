package com.smartdocs.editor.ui.common

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository.WriteBack
import com.smartdocs.editor.core.files.OriginCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pushes an edited working copy back into the document it was opened from, asking the user for write access
 * when the system requires it. Returns a function to call after every save.
 */
@Composable
fun rememberWriteBack(): (File) -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf<File?>(null) }

    fun handle(f: File, r: WriteBack, onPermission: (IntentSenderRequest) -> Unit) {
        when (r) {
            WriteBack.Done -> Toast.makeText(ctx, ctx.getString(R.string.wb_saved_original), Toast.LENGTH_SHORT).show()
            WriteBack.NotNeeded -> Unit
            is WriteBack.NeedsPermission -> { pending = f; onPermission(IntentSenderRequest.Builder(r.sender).build()) }
            is WriteBack.Failed -> failed = f
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val f = pending; pending = null
        if (f != null && res.resultCode == Activity.RESULT_OK) scope.launch {
            val r = app.documents.writeBack(f)
            if (r is WriteBack.Done) Toast.makeText(ctx, ctx.getString(R.string.wb_saved_original), Toast.LENGTH_SHORT).show() else failed = f
        } else if (f != null) failed = f
    }
    // a picked "original" that does not look like the imported one: confirm before overwriting it (audit DI-13)
    var confirmPick by remember { mutableStateOf<Triple<File, android.net.Uri, String>?>(null) }
    fun writeInto(f: File, uri: android.net.Uri) = scope.launch {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = app.documents.displayName(uri)
        app.documents.setOrigin(f, uri)
        name?.let { app.documents.setImportedName(f, it) }
        handle(f, app.documents.writeBack(f)) { permission.launch(it) }
    }
    // "pick the original again": the picker grants write access to exactly that document
    val regrant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = failed; failed = null
        if (f != null && uri != null) scope.launch {
            val picked = withContext(Dispatchers.IO) { app.documents.displayName(uri) }
            val same = withContext(Dispatchers.IO) {
                OriginCheck.looksLikeOriginal(app.documents.importedNameOf(f), app.documents.syncedSourceSizeOf(f), picked, app.documents.sourceSize(uri))
            }
            if (same) writeInto(f, uri) else confirmPick = Triple(f, uri, picked ?: "?")
        }
    }
    val onCopyCreated: (android.net.Uri?) -> Unit = { uri ->
        val f = failed; failed = null
        if (f != null && uri != null) scope.launch {
            // audit DI-29: an IO error or a missing stream is reported, never a crash or a false "saved"
            val r = runCatching { app.documents.exportTo(f, uri) }
            if (r.isFailure) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) } }
                Toast.makeText(ctx, ctx.getString(R.string.fx_save_failed, r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                return@launch
            }
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val name = app.documents.displayName(uri)
            app.documents.setOrigin(f, uri)     // from now on this copy is "the original"
            name?.let { app.documents.setImportedName(f, it) }
            withContext(Dispatchers.IO) { runCatching { app.documents.markSynced(f, uri) } }
            Toast.makeText(ctx, ctx.getString(R.string.wb_saved_original), Toast.LENGTH_SHORT).show()
        }
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onCopyCreated)
    val saveCopyAny = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*"), onCopyCreated)   // office5: non-PDF files

    confirmPick?.let { (f, uri, picked) ->
        AlertDialog(
            onDismissRequest = { confirmPick = null },
            title = { Text(stringResource(R.string.fx_pick_mismatch_title)) },
            text = { Text(stringResource(R.string.fx_pick_mismatch_text, picked, f.name)) },
            confirmButton = { TextButton(onClick = { confirmPick = null; writeInto(f, uri) }) { Text(stringResource(R.string.fx_replace_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmPick = null; failed = f }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    failed?.let { f ->
        val isPdf = f.extension.equals("pdf", true)
        AlertDialog(
            onDismissRequest = { failed = null },
            title = { Text(stringResource(R.string.wb_title)) },
            text = { Text(stringResource(R.string.wb_text, f.name)) },
            confirmButton = { TextButton(onClick = { regrant.launch(if (isPdf) arrayOf("application/pdf") else arrayOf("*/*")) }) { Text(stringResource(R.string.wb_pick_original)) } },
            dismissButton = { TextButton(onClick = { if (isPdf) saveCopy.launch(f.name) else saveCopyAny.launch(f.name) }) { Text(stringResource(R.string.wb_save_copy)) } },
        )
    }

    return remember { { f: File -> scope.launch { handle(f, app.documents.writeBack(f)) { permission.launch(it) } } } }
}

/** The document a tool was opened on (from the reader), so results can replace it in place. */
val LocalToolSource = staticCompositionLocalOf<File?> { null }
