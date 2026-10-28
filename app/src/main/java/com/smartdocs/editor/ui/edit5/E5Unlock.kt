package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.LockState
import com.smartdocs.editor.core.edit5.E5Locks
import com.smartdocs.editor.core.edit5.OutProtection
import com.smartdocs.editor.core.edit5.PasswordBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Asks for the password of locked input files of one operation (Bates files, the impose source, the second file of
 * replace/copy/interleave) — once per file: answers are kept in [book] for the lifetime of the screen. Render
 * [E5UnlockDialog] once in the screen.
 */
class E5Unlocker {
    class Request(val file: File, val wrong: Boolean, val answer: CompletableDeferred<String?>)

    val book = PasswordBook()
    private val lock = Mutex()
    var request by mutableStateOf<Request?>(null); private set
    /** Files whose unlock was cancelled in the last [unlockAll]. */
    var cancelled by mutableStateOf<List<File>>(emptyList()); private set

    /** Bumped on every change of [book], so composables reading [has]/[isLocked] recompose. */
    var version by mutableIntStateOf(0); private set

    fun password(f: File): String? = book.get(f)

    /** True when [f] was probed and is ready (open, or its password is known). */
    fun has(f: File): Boolean { version; return book.has(f) }

    /** True when [f] is encrypted and its password is known. */
    fun isLocked(f: File): Boolean { version; return book.get(f) != null }

    fun forget(f: File) { book.forget(f); owner.remove(f.absolutePath); version++ }

    /** audit 5 E-3: files (by path) whose known password gives owner access. */
    private val owner = HashMap<String, Boolean>()

    /** True when "remove protection" may be offered for [f]: not encrypted, or opened with owner access. */
    fun canRemove(f: File): Boolean { version; return owner[f.absolutePath] ?: false }

    /**
     * Password of [file] (null = not encrypted), asking when needed and again after a wrong one. Returns false when
     * the user cancelled. Errors other than password ones propagate.
     */
    suspend fun unlock(file: File): Boolean = lock.withLock { unlockLocked(file) }

    private suspend fun unlockLocked(file: File): Boolean {
        if (book.has(file)) return true
        var pw: String? = null
        var wrong = false
        while (true) {
            when (E5Locks.probe(file, pw)) {
                LockState.OPEN -> { book.put(file, pw); owner[file.absolutePath] = true; version++; return true }
                LockState.UNLOCKED -> {
                    owner[file.absolutePath] = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.edit5.E5Owner.ownerAccess(file, pw) }
                    book.put(file, pw); version++; return true
                }
                LockState.WRONG_PASSWORD -> wrong = true
                LockState.NEEDS_PASSWORD -> Unit
            }
            val req = Request(file, wrong, CompletableDeferred())
            request = req
            val typed = try { req.answer.await() } finally { if (request === req) request = null }
            if (typed == null) return false
            pw = typed
        }
    }

    /** Unlock every file of [files] in order; returns the files the user cancelled (empty = all ready). */
    suspend fun unlockAll(files: List<File>): List<File> {
        val skipped = ArrayList<File>()
        lock.withLock { for (f in book.pending(files)) if (!unlockLocked(f)) skipped += f }
        cancelled = skipped
        return skipped
    }

    fun answer(password: String?) { request?.answer?.complete(password) }
}

@Composable
fun rememberE5Unlocker(): E5Unlocker = remember { E5Unlocker() }

/** Password prompt for the file [E5Unlocker.request] names; "wrong password" after a failed try. */
@Composable
fun E5UnlockDialog(u: E5Unlocker) {
    val req = u.request ?: return
    var pw by remember(req) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { u.answer(null) },
        title = { Text(stringResource(R.string.password_enter)) },
        text = {
            Column {
                Text(stringResource(R.string.e5_lock_file, req.file.name), style = MaterialTheme.typography.bodyMedium)
                if (req.wrong) Text(stringResource(R.string.e5_lock_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { Button(onClick = { u.answer(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { u.answer(null) }) { Text(stringResource(R.string.cancel)) } })
}

/**
 * "Keep protection" / "remove protection" choice for outputs derived from locked files. audit 5 E-3: "remove" is
 * offered only when [canRemove] (owner access to every locked source, [E5Locks.canRemove]); otherwise the output
 * keeps the protection and the choice is forced back to KEEP.
 */
@Composable
fun ProtectionChoice(value: OutProtection, canRemove: Boolean, onChange: (OutProtection) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(canRemove, value) { if (!canRemove && value == OutProtection.REMOVE) onChange(OutProtection.KEEP) }
    if (canRemove) SwitchRow(stringResource(R.string.e5_lock_remove_out), value == OutProtection.REMOVE) { onChange(if (it) OutProtection.REMOVE else OutProtection.KEEP) }
    Text(stringResource(if (canRemove && value == OutProtection.REMOVE) R.string.e5_lock_out_plain else R.string.e5_lock_out_keep),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!canRemove) Text(stringResource(R.string.tte5_remove_needs_owner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
