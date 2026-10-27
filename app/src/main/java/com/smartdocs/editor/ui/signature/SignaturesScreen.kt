package com.smartdocs.editor.ui.signature

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.data.SignatureLock
import com.smartdocs.editor.core.data.SignatureMeta
import com.smartdocs.editor.core.data.SignatureMetaStore
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Signature manager: saved signatures / initials grid, creation sheet, and shortcuts to sign a document. */
@Composable
fun SignaturesScreen(nav: NavHostController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val all by app.db.signatures().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 signatures, 2 initials
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<SavedSignature?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val savedMsg = stringResource(R.string.sa_signature_saved)
    // nitro-quicksign: lock / recently used metadata, black-and-white copies
    val meta by remember { SignatureMetaStore.flow(app) }.collectAsStateWithLifecycle()
    val gate = rememberSignatureGate()
    var lockFor by remember { mutableStateOf<SavedSignature?>(null) }
    val bwMsg = stringResource(R.string.nq_bw_done)

    val pickPdf = rememberPdfPicker { files -> files.firstOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }

    // nitro-fillsign2: palette order (move up / down) from the sidecar, then the newest
    val orderedAll = SignatureMetaStore.ordered(meta, all.map { it.path }).mapNotNull { p -> all.firstOrNull { it.path == p } }
    val shown = when (filter) { 1 -> orderedAll.filter { !it.isInitials }; 2 -> orderedAll.filter { it.isInitials }; else -> orderedAll }
    var editFor by remember { mutableStateOf<SavedSignature?>(null) }

    /** Moves [sig] one place up (-1) or down (+1) among the signatures shown, keeping the others' relative order. */
    fun move(sig: SavedSignature, delta: Int) {
        val full = orderedAll.map { it.path }
        val order = if (filter == 0) SignatureMetaStore.move(full, full.indexOf(sig.path), delta) else {
            val other = shown.getOrNull(shown.indexOf(sig) + delta)?.path ?: return
            full.toMutableList().apply { val a = indexOf(sig.path); val b = indexOf(other); this[a] = other; this[b] = sig.path }
        }
        SignatureMetaStore.setOrder(app, order)
    }
    val recent = SignatureMetaStore.recent(meta, shown.map { it.path }, max = 4).mapNotNull { p -> shown.firstOrNull { it.path == p } }

    fun bwCopy(sig: SavedSignature) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val src = BitmapFactory.decodeFile(sig.path) ?: error("decode")
                    val f = app.documents.uniqueFile(app.documents.signaturesDir, "sig_bw_${System.currentTimeMillis()}", "png")
                    SignatureImage.savePng(SignatureImage.toBlackAndWhite(src), f)
                    SignatureMetaStore.copyLock(app, sig.path, f.absolutePath)
                    app.db.signatures().insert(SavedSignature(path = f.absolutePath, isInitials = sig.isInitials))
                }.isSuccess
            }
            if (ok) snackbar.showSnackbar(bwMsg)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.sa_signatures_title), onBack = { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate(Routes.tool("digital_sign")) }) { Icon(Icons.Default.VerifiedUser, stringResource(R.string.sig_digital)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showCreate = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.sig_create)) })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == 0, { filter = 0 }, label = { Text(stringResource(R.string.sa_filter_all)) })
                FilterChip(filter == 1, { filter = 1 }, label = { Text(stringResource(R.string.sa_filter_signatures)) })
                FilterChip(filter == 2, { filter = 2 }, label = { Text(stringResource(R.string.sig_initials)) })
            }
            OutlinedButton(onClick = pickPdf, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Draw, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_sign_document))
            }
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                EmptyState(Icons.Default.HistoryEdu, stringResource(R.string.sa_no_signatures), action = {
                    Button(onClick = { showCreate = true }) { Text(stringResource(R.string.sig_create)) }
                })
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cols = if (maxWidth > 700.dp) 4 else 2
                    val card: @Composable (SavedSignature) -> Unit = { sig ->
                        val pos = shown.indexOf(sig)
                        SignatureCard(sig, meta[sig.path] ?: SignatureMeta(),
                            canUp = pos > 0, canDown = pos in 0 until shown.size - 1,
                            onUp = { move(sig, -1) }, onDown = { move(sig, 1) },
                            onEdit = { gate.request(sig.path) { editFor = sig } },
                            onDelete = { gate.request(sig.path) { toDelete = sig } },
                            onBlackWhite = { gate.request(sig.path) { bwCopy(sig) } },
                            onLock = { if (meta[sig.path]?.locked == true) gate.request(sig.path) { lockFor = sig } else lockFor = sig },
                            onRemoveLock = { gate.request(sig.path) { SignatureLock.clear(app, sig.path) } })
                    }
                    LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // nitro-quicksign: Recently used first
                        if (recent.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h_recent") { SectionHeader(stringResource(R.string.nq_recent)) }
                            items(recent, key = { "r" + it.id }) { sig -> card(sig) }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h_all") { SectionHeader(stringResource(R.string.nq_all_signatures)) }
                        }
                        items(shown, key = { it.id }) { sig -> card(sig) }
                    }
                }
            }
        }
    }

    if (showCreate) {
        SignatureCreateSheet(
            onDismiss = { showCreate = false },
            onSaved = { showCreate = false; scope.launch { snackbar.showSnackbar(savedMsg) } },
        )
    }

    toDelete?.let { sig ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.sa_delete_signature)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { withContext(Dispatchers.IO) { runCatching { File(sig.path).delete(); com.smartdocs.editor.core.data.LockedSignatureFiles.blobOf(File(sig.path)).delete() }; app.db.signatures().delete(sig) }; SignatureMetaStore.remove(app, sig.path) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    lockFor?.let { sig -> SetSignatureLockDialog(sig.path, onDismiss = { lockFor = null }) { lockFor = null } }
    editFor?.let { sig -> EditSignatureDialog(sig, meta[sig.path] ?: SignatureMeta(), onDismiss = { editFor = null }) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SignatureCard(sig: SavedSignature, meta: SignatureMeta, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onEdit: () -> Unit,
                          onDelete: () -> Unit, onBlackWhite: () -> Unit, onLock: () -> Unit, onRemoveLock: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val bmp by produceState<android.graphics.Bitmap?>(null, sig.path, meta.rev) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(sig.path) }.getOrNull() }
    }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(110.dp).background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                // nitro-fillsign2: an imported locked signature has no image until it is unlocked once
                else if (meta.locked) Icon(Icons.Default.Lock, null, Modifier.size(32.dp), tint = Color.LightGray)
            }
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta.locked) Icon(Icons.Default.Lock, stringResource(R.string.nq_lock_locked), Modifier.padding(end = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                if (meta.temporary) Icon(Icons.Default.Timer, stringResource(R.string.nf2_temporary_badge), Modifier.padding(end = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(meta.name.ifBlank { stringResource(if (sig.isInitials) R.string.sig_initials else R.string.sa_filter_signatures) }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.nq_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        // nitro-fillsign2: edit (name, colour, image) and palette order
                        DropdownMenuItem(text = { Text(stringResource(R.string.nf2_edit)) }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nf2_move_up)) }, onClick = { menu = false; onUp() }, enabled = canUp, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nf2_move_down)) }, onClick = { menu = false; onDown() }, enabled = canDown, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nq_bw_copy)) }, onClick = { menu = false; onBlackWhite() }, leadingIcon = { Icon(Icons.Default.Contrast, null) })
                        DropdownMenuItem(text = { Text(stringResource(if (meta.locked) R.string.nq_lock_change else R.string.nq_lock_set)) }, onClick = { menu = false; onLock() },
                            leadingIcon = { Icon(Icons.Default.Lock, null) })
                        if (meta.locked) DropdownMenuItem(text = { Text(stringResource(R.string.nq_lock_remove)) }, onClick = { menu = false; onRemoveLock() },
                            leadingIcon = { Icon(Icons.Default.LockOpen, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                    }
                }
            }
        }
    }
}
