package com.smartdocs.editor.ui.signature

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HistoryEdu
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

    val pickPdf = rememberPdfPicker { files -> files.firstOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }

    val shown = when (filter) { 1 -> all.filter { !it.isInitials }; 2 -> all.filter { it.isInitials }; else -> all }

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
                    LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shown, key = { it.id }) { sig -> SignatureCard(sig, onDelete = { toDelete = sig }) }
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
                    scope.launch { withContext(Dispatchers.IO) { runCatching { File(sig.path).delete() }; app.db.signatures().delete(sig) } }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SignatureCard(sig: SavedSignature, onDelete: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(null, sig.path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(sig.path) }.getOrNull() }
    }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(110.dp).background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (sig.isInitials) R.string.sig_initials else R.string.sa_filter_signatures), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
