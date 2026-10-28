package com.smartdocs.editor.ui.nve.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.PdfRepair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Try to repair" on the reader's could-not-open screen: PDFBox's lenient parser rebuilds a broken xref / trailer and
 * the document is re-saved as "<name> (repaired).pdf" in the documents folder, then [onRepaired] opens it.
 */
@Composable
fun NveRepairButton(file: File?, password: String? = null, onRepaired: (File) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val f = file?.takeIf { it.exists() } ?: return
    OutlinedButton(onClick = {
        busy = true; failed = false
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val name = PdfRepair.repairedName(f.name).removeSuffix(".pdf")
                    val target = app.documents.uniqueFile(app.documents.docsDir, name)
                    PdfRepair.repair(f, target, password).out
                }.getOrNull()
            }
            busy = false
            if (out != null) { runCatching { app.documents.touchRecent(out) }; onRepaired(out) } else failed = true
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Build, null)
        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nl_repair))
    }
    if (failed) Text(stringResource(R.string.nl_repair_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
}
