package com.smartdocs.editor.ui.scanner

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.Routes
import java.io.File

/**
 * ai6 (B16): AI right after scanning. Once a scan is saved, this bar offers a summary or questions about it. The AI
 * hub indexes the file first; scanned pages without a text layer are OCRed by its indexer
 * ([com.smartdocs.editor.core.ai5.DocIndexer]) before the summary / chat runs. Hidden when the AI switch is off.
 */
@Composable
fun ScanAiBar(file: File, nav: NavHostController, onClose: () -> Unit) {
    if (!LocalSettings.current.aiEnabled) return
    val uri = Uri.fromFile(file).toString()
    Surface(tonalElevation = 4.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.ai6r_scan_ai, file.name), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = { nav.navigate(Routes.tool("ai", "$uri#tab=summary")) }) {
                Icon(Icons.Default.Summarize, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai6r_scan_summary))
            }
            FilledTonalButton(onClick = { nav.navigate(Routes.tool("ai", "$uri#tab=chat")) }) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai6r_scan_ask))
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
    }
}
