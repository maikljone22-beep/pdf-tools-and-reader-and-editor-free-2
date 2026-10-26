package com.smartdocs.editor.ui.cloud

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabLive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One-line reader glue (share6, live review comments). In ReaderScreen:
 *
 *     com.smartdocs.editor.ui.cloud.CollabLiveHost(state.file, snack) { op -> state.applyInPlace(state.password, op) }
 *
 * Attaches the open document when it is a shared-review copy, polls other reviewers' comments every 30 s and shows
 * "N new comments" with a "show" action that merges them into the document now (the reader reopens at the same page).
 * Otherwise they are merged into the next save (ReaderState hook → [CollabLive.beforeSave]).
 */
@Composable
fun CollabLiveHost(file: File?, snack: SnackbarHostState, applyInPlace: suspend (suspend (File, File, String?) -> Unit) -> Any?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (file == null) return
    DisposableEffect(file.absolutePath) {
        CollabLive.attach(ctx, file)
        onDispose { CollabLive.detach(ctx, file) }
    }
    LaunchedEffect(file.absolutePath) {
        CollabLive.arrivals.collect { n ->
            if (!CollabLive.isAttached(file)) return@collect
            val msg = ctx.resources.getQuantityString(R.plurals.s6_new_comments, n, n)
            val r = snack.showSnackbar(msg, actionLabel = ctx.getString(R.string.s6_show), withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) scope.launch {
                runCatching { applyInPlace { src, out, pw -> CollabLive.mergeOp(ctx, src, out, pw) } }
                    .onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            }
        }
    }
}
