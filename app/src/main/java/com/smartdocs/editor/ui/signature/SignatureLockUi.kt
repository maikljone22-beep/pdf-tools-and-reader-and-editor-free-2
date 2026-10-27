package com.smartdocs.editor.ui.signature

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SignatureLock
import kotlinx.coroutines.launch

/**
 * Gate for placing a password-locked QuickSign signature (nitro-quicksign). Use:
 * ```
 * val gate = rememberSignatureGate()
 * gate.request(path) { /* place the signature */ }
 * ```
 * An unlocked signature runs the action at once; a locked one asks for its PIN / password first.
 */
@Stable
class SignatureGate internal constructor(private val context: Context) {
    internal var pending by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun request(path: String, onAllowed: () -> Unit) {
        if (!SignatureLock.isLocked(context, path)) onAllowed() else pending = path to onAllowed
    }
}

@Composable
fun rememberSignatureGate(): SignatureGate {
    val ctx = LocalContext.current
    val gate = remember { SignatureGate(ctx.applicationContext) }
    gate.pending?.let { (path, action) ->
        UnlockSignatureDialog(path, onDismiss = { gate.pending = null }) { gate.pending = null; action() }
    }
    return gate
}

/** Asks for the PIN / password of the signature at [path]; [onUnlocked] after a match. */
@Composable
fun UnlockSignatureDialog(path: String, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.nq_lock_wrong)
    val waitFmt = stringResource(R.string.nq_lock_wait)
    fun check() {
        if (busy || secret.isEmpty()) return
        val wait = SignatureLock.waitMs()
        if (wait > 0) { error = String.format(waitFmt, (wait + 999) / 1000); return }
        busy = true
        scope.launch {
            val chars = secret.toCharArray()
            val ok = SignatureLock.unlock(ctx, path, chars)
            chars.fill('\u0000')
            busy = false
            if (ok) onUnlocked() else { secret = ""; error = SignatureLock.waitMs().takeIf { it > 0 }?.let { String.format(waitFmt, (it + 999) / 1000) } ?: wrong }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.nq_lock_unlock_title)) },
        text = {
            val err = error
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nq_lock_unlock_msg), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(secret, { secret = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, isError = error != null,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.nq_lock_secret)) }, supportingText = if (err != null) ({ Text(err) }) else null)
            }
        },
        confirmButton = {
            TextButton(onClick = { check() }, enabled = !busy && secret.isNotEmpty()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.nq_lock_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Sets (or replaces) the PIN / password of the signature at [path]: entered twice, at least [SignatureLock.MIN_LENGTH] characters. */
@Composable
fun SetSignatureLockDialog(path: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val tooShort = a.isNotEmpty() && a.length < SignatureLock.MIN_LENGTH
    val mismatch = b.isNotEmpty() && a != b
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.nq_lock_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nq_lock_set_msg), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(a, { a = it }, Modifier.fillMaxWidth(), singleLine = true, isError = tooShort,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.nq_lock_secret)) },
                    supportingText = if (tooShort) ({ Text(stringResource(R.string.nq_lock_too_short, SignatureLock.MIN_LENGTH)) }) else null)
                OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth(), singleLine = true, isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.nq_lock_confirm)) },
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.nq_lock_mismatch)) }) else null)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    val chars = a.toCharArray()
                    SignatureLock.lock(ctx, path, chars)
                    chars.fill('\u0000')
                    busy = false
                    onDone()
                }
            }, enabled = !busy && a.length >= SignatureLock.MIN_LENGTH && a == b) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
