package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.RecoveryNames
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SHL-10 auto recovery: while a document has unsaved changes, a recovery copy (the file plus pending annotations /
 * form values, [ReaderState.exportWithAnnots]) is written every N minutes into filesDir/recovery. A normal close
 * saves or discards and the copy goes; after a crash it is offered when the document is opened again.
 */
object NveRecovery {
    private fun dir(ctx: Context) = File(ctx.filesDir, "recovery").apply { mkdirs() }
    private fun fileFor(ctx: Context, doc: File) = File(dir(ctx), RecoveryNames.fileName(doc.absolutePath))

    /** Write the recovery copy of [state] now (no-op when nothing is pending). */
    suspend fun snapshot(state: ReaderState) {
        val doc = state.file ?: return
        if (!state.dirty) return
        val ctx = state.context
        val target = fileFor(ctx, doc)
        val tmp = File(dir(ctx), target.name + ".tmp")
        runCatching {
            state.exportWithAnnots(tmp)
            withContext(Dispatchers.IO) { if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() } }
        }.onFailure { tmp.delete() }
        prune(ctx)
    }

    /** The recovery copy of [doc] when it is newer than the document itself (unsaved work after a crash). */
    fun pending(ctx: Context, doc: File): File? = fileFor(ctx, doc).takeIf { it.exists() && it.length() > 0 && it.lastModified() > doc.lastModified() }

    fun discard(ctx: Context, doc: File) { fileFor(ctx, doc).delete() }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".pdf") } ?: return
        RecoveryNames.expired(files.map { it.name to it.lastModified() }, System.currentTimeMillis()).forEach { File(dir(ctx), it).delete() }
    }

    /** Copy the recovered version to the documents folder as "<name> (Recovered).pdf". */
    suspend fun restoreCopy(state: ReaderState): File? = withContext(Dispatchers.IO) {
        val doc = state.file ?: return@withContext null
        val rec = pending(state.context, doc) ?: return@withContext null
        val name = RecoveryNames.recoveredName(doc.name).removeSuffix(".pdf")
        val out = state.app.documents.uniqueFile(state.app.documents.docsDir, name)
        rec.copyTo(out, overwrite = true)
        rec.delete()
        runCatching { state.app.documents.touchRecent(out) }
        out
    }

    /**
     * "Revert to saved": drop every pending change and show the last saved version. The reader is closed without
     * saving ([ReaderState.discardChanges]) and [reopen] opens the same file again (e.g. pop + navigate).
     */
    fun revert(state: ReaderState, reopen: () -> Unit) {
        state.file?.let { discard(state.context, it) }
        state.discardChanges()
        reopen()
    }
}

/** Periodic recovery snapshots while the reader shows [state] (hook once in the reader). */
@Composable
fun NveRecoveryEffect(state: ReaderState) {
    val ctx = LocalContext.current
    val minutes = remember(NveShellPrefs.version.intValue) { NveShellPrefs.recoveryMinutes(ctx) }
    LaunchedEffect(state, minutes) {
        if (minutes <= 0) return@LaunchedEffect
        while (isActive) {
            delay(minutes * 60_000L)
            if (state.dirty && !state.saving) NveRecovery.snapshot(state)
        }
    }
    // saved (no longer dirty): the copy is obsolete
    // (only on a dirty -> clean transition: on open the crash copy must survive until the banner was answered)
    LaunchedEffect(state) { var was = state.dirty; snapshotFlow { state.dirty }.collect { d -> if (was && !d) state.file?.let { NveRecovery.discard(ctx, it) }; was = d } }
    DisposableEffect(state) { onDispose { if (state.discarded) state.file?.let { NveRecovery.discard(ctx, it) } } }
    // the crash reporter can offer to attach the document that was open
    DisposableEffect(state.file) {
        com.smartdocs.editor.core.util.CrashLog.currentDocument = state.file?.absolutePath
        onDispose { com.smartdocs.editor.core.util.CrashLog.currentDocument = null }
    }
}

/** Offered after a crash: open the recovered copy or discard it (hook at the top of the page area). */
@Composable
fun NveRecoveryBanner(state: ReaderState, onOpen: (File) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val doc = state.file ?: return
    var rec by remember(doc, state.session) { mutableStateOf(NveRecovery.pending(ctx, doc)) }
    var busy by remember { mutableStateOf(false) }
    val r = rec ?: return
    Surface(modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Restore, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nl_recovery_found, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.lastModified()))),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { NveRecovery.discard(ctx, doc); rec = null }, enabled = !busy) { Text(stringResource(R.string.nl_discard)) }
            val scope = rememberCoroutineScope()
            Button(onClick = { busy = true; scope.launch { NveRecovery.restoreCopy(state)?.let(onOpen); rec = null; busy = false } }, enabled = !busy) { Text(stringResource(R.string.nl_open_recovered)) }
        }
    }
}
