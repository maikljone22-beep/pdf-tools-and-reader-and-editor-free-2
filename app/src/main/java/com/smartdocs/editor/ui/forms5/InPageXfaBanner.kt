package com.smartdocs.editor.ui.forms5

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.forms5.XfaGenerated
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.launch
import java.io.File

/**
 * Banner for XFA documents in the reader (hook: in ReaderScreen's page area, aligned top).
 *  - dynamic XFA: "open as fillable form" (generated AcroForm PDF, opened through [onOpen]) and
 *    "convert to normal PDF" (the generated form flattened);
 *  - a form generated from XFA: "save into the original XFA" (datasets write-back).
 * Static XFA needs no banner: its AcroForm fields are filled on the page and the XFA packet is dropped on save.
 */
@Composable
fun InPageXfaBanner(reader: ReaderState, onOpen: (File) -> Unit, modifier: Modifier = Modifier) {
    val fs = remember(reader) { FormFill.of(reader) }
    val m = fs.model
    val dynamic = m.xfa == XfaSupport.Kind.DYNAMIC
    val generated = m.generatedFromXfa
    var hidden by remember(reader.file) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (hidden || (!dynamic && !generated)) return
    val app = reader.app
    val failed = stringResource(R.string.f5_xfa_failed)

    fun generate(flat: Boolean) {
        val src = reader.file ?: return
        scope.launch {
            working = true
            try {
                val base = src.nameWithoutExtension + if (flat) "_xfa_pdf" else "_xfa_form"
                val out = app.documents.uniqueFile(app.documents.docsDir, base)
                if (flat) {
                    val tmp = app.documents.newTemp("xfa")
                    try { XfaGenerated.generate(reader.context, src, reader.password, tmp); PdfOps.flattenForms(tmp, out) } finally { tmp.delete() }
                } else XfaGenerated.generate(reader.context, src, reader.password, out)
                onOpen(out)
            } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; result = failed + ": " + (e.message ?: e.toString()) } finally { working = false }
        }
    }

    // nav-prefs (XFA preferences): open the fillable form of a dynamic XFA document automatically (the generated
    // copy is reused while it is newer than the original), and hide the banner
    androidx.compose.runtime.LaunchedEffect(dynamic, reader.file) {
        val src = reader.file ?: return@LaunchedEffect
        if (!dynamic || !com.smartdocs.editor.core.data.NpfPrefs.xfaAutoOpen) return@LaunchedEffect
        val prev = File(app.documents.docsDir, src.nameWithoutExtension + "_xfa_form.pdf")
        if (prev.isFile && prev.lastModified() >= src.lastModified()) onOpen(prev) else generate(false)
    }
    if (!com.smartdocs.editor.core.data.NpfPrefs.xfaBanner && !working && result == null) return
    Card(modifier.padding(horizontal = 12.dp).widthIn(max = 640.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EditNote, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (dynamic) R.string.f5_xfa_dynamic_title else R.string.f5_xfa_generated_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { hidden = true }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
            Text(stringResource(if (dynamic) R.string.f5_xfa_dynamic_body else R.string.f5_xfa_generated_body), style = MaterialTheme.typography.bodySmall)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (working) CircularProgressIndicator(Modifier.size(24.dp))
                else if (dynamic) {
                    Button(onClick = { generate(false) }) { Text(stringResource(R.string.f5_xfa_open_form)) }
                    OutlinedButton(onClick = { generate(true) }) { Text(stringResource(R.string.f5_xfa_to_pdf)) }
                } else {
                    val okMsg = stringResource(R.string.f5_xfa_written)
                    Button(onClick = {
                        val f = reader.file ?: return@Button
                        scope.launch {
                            working = true
                            try {
                                // pending values first go into this generated file, then into the original's datasets
                                if (reader.dirty && !reader.save()) throw IllegalStateException(reader.error ?: "save")
                                val (target, n) = XfaGenerated.writeBack(f, null)
                                result = okMsg.format(n, target.name)
                                runCatching { app.documents.writeBack(target) }
                            } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; result = failed + ": " + (e.message ?: e.toString()) } finally { working = false }
                        }
                    }) { Text(stringResource(R.string.f5_xfa_write_back)) }
                }
            }
        }
    }
}
