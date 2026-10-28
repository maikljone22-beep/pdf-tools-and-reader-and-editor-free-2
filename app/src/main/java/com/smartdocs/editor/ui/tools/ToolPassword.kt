package com.smartdocs.editor.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.LockedPdf
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SessionPasswords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * audit 5 C-4 / C-4b / T-09: the password of a tool's (single) input file. Starts from the reader's password for
 * that file ([SessionPasswords]); a file that needs a user password and has none (or a stale one) is asked for once
 * with [FilePasswordDialog], like Merge does.
 */
class ToolPassword {
    /** The password to open the input with (null: none needed / not known yet). */
    var password by mutableStateOf<String?>(null)
    /** The input cannot be opened without a user password. */
    var locked by mutableStateOf(false)
    /** The check for the current input has finished. */
    var checked by mutableStateOf(false)
    var asking by mutableStateOf(false)
    /** The input can be opened: checked, and unlocked or not locked. */
    val ready: Boolean get() = checked && (!locked || password != null)

    /** Ask again (the password was refused by a later step). */
    fun reask() { if (locked) { password = null; asking = true } }
}

/**
 * Tracks [file]'s password and shows the password dialog while one is needed. [onRemove] is called when the user
 * cancels the dialog (the screen should then drop the file).
 */
@Composable
fun rememberToolPassword(file: File?, onRemove: () -> Unit): ToolPassword {
    val st = remember { ToolPassword() }
    LaunchedEffect(file) {
        st.checked = false; st.asking = false; st.locked = false; st.password = null
        val f = file ?: return@LaunchedEffect
        val session = SessionPasswords.get(f)
        val (needs, valid) = withContext(Dispatchers.IO) {
            val n = LockedPdf.needsPassword(f)
            // a permission-only file opens without one, but the reader's (owner) password still keeps its rights
            n to session?.takeIf { LockedPdf.checkPassword(f, it) }
        }
        st.password = valid
        st.locked = needs
        st.asking = needs && valid == null
        st.checked = true
    }
    val f = file
    if (f != null && st.asking) FilePasswordDialog(
        f,
        onUnlocked = { pw -> st.password = pw; st.asking = false },
        onRemove = { st.asking = false; onRemove() },
        message = stringResource(R.string.ttcx_locked_msg, f.name),
        removeLabel = stringResource(R.string.cancel),
    )
    return st
}

/** True when [e] (or its cause) means a missing or wrong password. */
fun isPasswordError(e: Throwable?): Boolean {
    var t = e; var depth = 0
    while (t != null && depth < 5) {
        if (t is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException || t is PdfSession.Companion.PasswordRequiredException) return true
        t = t.cause; depth++
    }
    return false
}

/** audit 5 C-4 / C-18: a password failure inside a tool job becomes the localized [message] (the runner shows e.message). */
suspend fun <T> withPasswordMessage(message: String, block: suspend () -> T): T =
    try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        if (isPasswordError(e)) throw IllegalStateException(message, e) else throw e
    }
