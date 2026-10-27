package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R

/** Full-screen "could not open" state of the reader (audit RD-01): what happened and what the user can do. */
@Composable
fun OpenErrorView(error: OpenError, onRetry: () -> Unit, onBack: () -> Unit, onOpenWith: (() -> Unit)?) {
    val (icon, title, text) = when (error) {
        OpenError.MISSING -> Triple(Icons.Default.FindInPage, R.string.fx_open_missing_title, R.string.fx_open_missing_text)
        OpenError.PERMISSION -> Triple(Icons.Default.Lock, R.string.fx_open_permission_title, R.string.fx_open_permission_text)
        OpenError.DAMAGED -> Triple(Icons.Default.BrokenImage, R.string.fx_open_damaged_title, R.string.fx_open_damaged_text)
        OpenError.OTHER -> Triple(Icons.Default.ErrorOutline, R.string.fx_open_other_title, R.string.fx_open_other_text)
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry, Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fx_open_retry)) }
            if (onOpenWith != null) OutlinedButton(onClick = onOpenWith, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fx_open_with))
            }
            TextButton(onClick = onBack, Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.back)) }
        }
    }
}
