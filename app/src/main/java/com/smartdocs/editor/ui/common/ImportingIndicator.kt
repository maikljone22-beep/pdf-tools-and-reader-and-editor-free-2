package com.smartdocs.editor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import kotlinx.coroutines.delay

/**
 * "Opening the file…" while a document handed over by another app is copied into the app's storage. A large file
 * takes many seconds, and until now nothing on the screen said the app was working. [name] = null: nothing to show.
 */
@Composable
fun ImportingIndicator(name: String?) {
    if (name == null) return
    // a small file is in before anyone could read a box: show it only when the copy takes a moment
    var show by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) { delay(350); show = true }
    if (!show) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Column {
                    Text(stringResource(R.string.imp_opening), style = MaterialTheme.typography.titleMedium)
                    if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.imp_opening_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
