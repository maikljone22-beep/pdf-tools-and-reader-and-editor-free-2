package com.smartdocs.editor.ui.attachments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.Attachment
import com.smartdocs.editor.core.pdf.AttachmentOps
import com.smartdocs.editor.core.tools5.Portfolio
import com.smartdocs.editor.ui.reader.ReaderState
import java.io.File

/** Attachments of the open document (null while loading); reloaded after every change of the file. */
@Composable
fun rememberReaderAttachments(state: ReaderState): List<Attachment>? {
    val v by produceState<List<Attachment>?>(null, state.file, state.renderVersion) {
        val f = state.file
        value = if (f == null) emptyList() else runCatching { AttachmentOps.list(f, state.password) }.getOrDefault(emptyList())
    }
    return v
}

/**
 * Reader side panel "Attachments": every document-level and page-level attachment of the open document. Changes
 * (add, delete, description) go through [ReaderState.applyInPlace], so they are one undo step, saved back to the
 * source and appended incrementally on signed files.
 */
@Composable
fun ReaderAttachmentsPanel(state: ReaderState, onJump: (Int) -> Unit, onOpenPdf: ((File) -> Unit)?) {
    val items = rememberReaderAttachments(state)
    AttachmentsBrowser(
        items = items,
        source = { state.file?.let { it to state.password } },
        change = { label, op -> state.applyInPlace(state.password, label = { label }, op = op) },
        onOpenPdf = onOpenPdf,
        onJump = onJump,
        scrollable = true,
    )
}

/**
 * Shown once per document when the open PDF is a portfolio (/Collection): offers to open it in the portfolio
 * view (parts list, extract, open). "Keep reading" is remembered for that file.
 */
@Composable
fun PortfolioPrompt(state: ReaderState, onOpenPortfolio: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.applicationContext.getSharedPreferences("nav_att", android.content.Context.MODE_PRIVATE) }
    var parts by remember { mutableStateOf<Int?>(null) }
    var show by remember { mutableStateOf(false) }
    val f = state.file
    LaunchedEffect(f?.absolutePath, state.session != null) {
        if (f == null || state.session == null) return@LaunchedEffect
        val key = "pf_seen:" + f.absolutePath
        if (prefs.getBoolean(key, false)) return@LaunchedEffect
        if (Portfolio.isPortfolio(f, state.password)) {
            parts = runCatching { Portfolio.parts(f, state.password).size }.getOrNull()
            show = true
        }
    }
    if (!show || f == null) return
    fun dismiss() { show = false; prefs.edit().putBoolean("pf_seen:" + f.absolutePath, true).apply() }
    AlertDialog(onDismissRequest = { dismiss() },
        icon = { Icon(Icons.Default.FolderZip, null) },
        title = { Text(stringResource(R.string.nat_portfolio_title)) },
        text = { Text(stringResource(R.string.nat_portfolio_text, parts ?: 0)) },
        confirmButton = { TextButton(onClick = { dismiss(); onOpenPortfolio() }) { Text(stringResource(R.string.nat_portfolio_open)) } },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.nat_keep_reading)) } })
}
