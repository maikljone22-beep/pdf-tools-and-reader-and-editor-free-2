package com.smartdocs.editor.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.util.TextDiff
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.launch
import java.io.File

private val addedBg = Color(0x3343A047)
private val removedBg = Color(0x33E53935)
private val changedBg = Color(0x33FFB300)

/** Tool "compare": text diff of two PDFs. */
@Composable
fun CompareScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var oldFile by rememberInputFile(uri)
    var newFile by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<TextDiff.Result?>(null) }
    var working by remember { mutableStateOf(false) }
    val pickOld = rememberPdfPicker { oldFile = it.firstOrNull(); result = null }
    val pickNew = rememberPdfPicker { newFile = it.firstOrNull(); result = null }
    val needPassword = stringResource(R.string.password_enter)

    ToolScaffold(stringResource(R.string.tool_compare), nav, snackbar = snackbar, scrollable = false) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth > 700.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { FilePickRow(oldFile, stringResource(R.string.compare_old), pickOld) }
                Box(Modifier.weight(1f)) { FilePickRow(newFile, stringResource(R.string.compare_new), pickNew) }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilePickRow(oldFile, stringResource(R.string.compare_old), pickOld)
                FilePickRow(newFile, stringResource(R.string.compare_new), pickNew)
            }
        }
        PrimaryButton(stringResource(R.string.tl_compare), enabled = oldFile != null && newFile != null && !working, icon = Icons.Default.Compare) {
            val a = oldFile ?: return@PrimaryButton; val b = newFile ?: return@PrimaryButton
            working = true
            scope.launch {
                runCatching {
                    val ta = extractAllText(ctx, a).joinToString("\n"); val tb = extractAllText(ctx, b).joinToString("\n")
                    TextDiff.diff(ta, tb)
                }.onSuccess { result = it }
                    .onFailure { snackbar.showSnackbar(if (it is PdfSession.Companion.PasswordRequiredException) needPassword else (it.message ?: it.toString())) }
                working = false
            }
        }
        val r = result
        if (r != null) {
            if (r.identical) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.compare_identical)) }
                }
            } else {
                Legend(r)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    if (maxWidth > 700.dp) SideBySide(r) else Stacked(r)
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
    if (working) ProgressDialog(stringResource(R.string.processing))
}

@Composable
private fun Legend(r: TextDiff.Result) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendChip(addedBg, stringResource(R.string.compare_added), r.added)
        LegendChip(removedBg, stringResource(R.string.compare_removed), r.removed)
        LegendChip(changedBg, stringResource(R.string.compare_changed), r.changed)
    }
}

@Composable
private fun LegendChip(bg: Color, label: String, count: Int) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$count", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun bgFor(kind: TextDiff.Kind): Color = when (kind) {
    TextDiff.Kind.ADDED -> addedBg; TextDiff.Kind.REMOVED -> removedBg; TextDiff.Kind.CHANGED -> changedBg; TextDiff.Kind.SAME -> Color.Transparent
}

@Composable
private fun DiffLineRow(text: String?, kind: TextDiff.Kind, marker: String) {
    Row(Modifier.fillMaxWidth().background(bgFor(kind)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(marker, Modifier.width(18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text ?: "", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Stacked(r: TextDiff.Result) {
    Card(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(r.lines) { _, l ->
                when (l.kind) {
                    TextDiff.Kind.SAME -> DiffLineRow(l.old, l.kind, " ")
                    TextDiff.Kind.ADDED -> DiffLineRow(l.new, l.kind, "+")
                    TextDiff.Kind.REMOVED -> DiffLineRow(l.old, l.kind, "−")
                    TextDiff.Kind.CHANGED -> { DiffLineRow(l.old, TextDiff.Kind.REMOVED, "−"); DiffLineRow(l.new, TextDiff.Kind.ADDED, "+") }
                }
            }
        }
    }
}

@Composable
private fun SideBySide(r: TextDiff.Result) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Text(stringResource(R.string.compare_old), Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(r.lines) { _, l ->
                    if (l.kind == TextDiff.Kind.ADDED) DiffLineRow("", TextDiff.Kind.SAME, " ") else DiffLineRow(l.old, l.kind, if (l.kind == TextDiff.Kind.SAME) " " else "−")
                }
            }
        }
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Text(stringResource(R.string.compare_new), Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(r.lines) { _, l ->
                    if (l.kind == TextDiff.Kind.REMOVED) DiffLineRow("", TextDiff.Kind.SAME, " ") else DiffLineRow(l.new, l.kind, if (l.kind == TextDiff.Kind.SAME) " " else "+")
                }
            }
        }
    }
}
