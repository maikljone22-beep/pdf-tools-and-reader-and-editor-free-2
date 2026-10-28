package com.smartdocs.editor.ui.rpf

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.core.rpf.RpfPrefs
import com.smartdocs.editor.ui.forms5.FormFillState
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.flow.distinctUntilChanged

/** §39: Tab / Shift+Tab move between form fields with a hardware keyboard (hook first in the reader's key chain). */
object RpfKeys {
    fun onKey(s: ReaderState, e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_TAB || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val fs = s.forms5
        if (!fs.enabled || fs.order.isEmpty()) return false
        fs.step(if (e.isShiftPressed) -1 else 1)
        return true
    }
}

/** What the field speaker says for [w]: label, kind, value, required (pure apart from the strings). */
internal fun describeField(ctx: Context, fs: FormFillState, w: FWidget): String {
    val kind = ctx.getString(when (w.kind) {
        WKind.TEXT -> R.string.rpf_ft_text; WKind.CHECK -> R.string.rpf_ft_check; WKind.RADIO -> R.string.rpf_ft_radio
        WKind.COMBO -> R.string.rpf_ft_combo; WKind.LIST -> R.string.rpf_ft_list; WKind.SIGNATURE -> R.string.rpf_ft_signature
        WKind.BUTTON -> R.string.rpf_ft_button; WKind.IMAGE -> R.string.rpf_ft_image
    })
    val raw = fs.value(w.name)
    val value = when (w.kind) {
        WKind.CHECK, WKind.RADIO -> ctx.getString(if (raw.isNotEmpty() && raw != "Off" && (w.onValue == null || raw == w.onValue)) R.string.rpf_ft_checked else R.string.rpf_ft_unchecked)
        WKind.BUTTON, WKind.SIGNATURE, WKind.IMAGE -> ""
        else -> if (w.password) "" else raw.ifEmpty { ctx.getString(R.string.rpf_ft_empty) }
    }
    return listOf(w.label.ifBlank { w.name }, kind, value, if (w.required) ctx.getString(R.string.rpf_ft_required) else "")
        .filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * §10 Tab-through reading: while the preference is on, each form field that gets the focus (tap, Next, Tab) is read
 * aloud — its label, kind, value and whether it is required.
 */
@Composable
fun RpfFieldSpeaker(s: ReaderState) {
    if (!RpfPrefs.fieldTts) return
    val ctx = LocalContext.current
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx.applicationContext) { }
        onDispose { tts[0]?.stop(); tts[0]?.shutdown(); tts[0] = null }
    }
    LaunchedEffect(s) {
        val fs = s.forms5
        snapshotFlow { fs.activeId }.distinctUntilChanged().collect { id ->
            val w = fs.active ?: return@collect
            if (id == null) return@collect
            tts[0]?.speak(describeField(ctx, fs, w), TextToSpeech.QUEUE_FLUSH, null, "rpf_field")
        }
    }
}

/** Rows for the read-aloud options sheet (hook in ReadAloudOptionsSheet). */
@Composable
fun RpfReadAloudRows(s: ReaderState, close: () -> Unit) {
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(RpfPrefs.fieldTts, { RpfPrefs.setBool(RpfPrefs.K.FIELD_TTS, it) })
        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.rpf_field_tts), Modifier.weight(1f))
    }
    Text(stringResource(R.string.rpf_field_tts_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fs = s.forms5
    if (fs.order.isNotEmpty()) OutlinedButton(onClick = {
        if (!RpfPrefs.fieldTts) RpfPrefs.setBool(RpfPrefs.K.FIELD_TTS, true)
        close(); fs.focus(fs.order.first())
    }) { Text(stringResource(R.string.rpf_field_tts_start)) }
}
