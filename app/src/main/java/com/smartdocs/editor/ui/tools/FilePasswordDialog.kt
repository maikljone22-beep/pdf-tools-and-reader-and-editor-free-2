package com.smartdocs.editor.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.LockedPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Asks for the password of one locked input file (merge, insert pages from file). The password is checked before it
 * is accepted: a wrong one keeps the dialog open with an error. "Remove file" drops the file from the operation.
 * audit 5 C-4: single-input tools pass their own [message] / [removeLabel] (the defaults speak of adding a file).
 */
@Composable
fun FilePasswordDialog(file: File, onUnlocked: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit = onRemove,
                       message: String? = null, removeLabel: String? = null) {
    val scope = rememberCoroutineScope()
    var pw by remember(file) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var wrong by remember(file) { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    fun submit() {
        if (pw.isEmpty() || checking) return
        checking = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { LockedPdf.checkPassword(file, pw) }
            checking = false
            if (ok) onUnlocked(pw) else wrong = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.fd_locked_title)) },
        text = {
            Column {
                Text(message ?: stringResource(R.string.fd_locked_msg, file.name), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it; wrong = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.password)) }, isError = wrong,
                    supportingText = { if (wrong) Text(stringResource(R.string.fd_wrong_password)) },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.fd_show_password))
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = pw.isNotEmpty() && !checking) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.fd_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onRemove) { Text(removeLabel ?: stringResource(R.string.fd_remove_file)) } },
    )
}
