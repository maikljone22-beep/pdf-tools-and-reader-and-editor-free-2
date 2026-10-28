package com.smartdocs.editor.ui.reader

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.data.NpfPrefs
import com.smartdocs.editor.core.data.NpfText
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * nav-prefs: reader side of "Settings → more preferences". ReaderState, PageCanvas and AnnotEdit call [NpfReader]
 * (one line each); ReaderScreen places [NpfReaderEffects]; AnnotActionBar places [NpfNotePopup].
 */
object NpfReader {
    /** PREF-6: default page layout ("auto" keeps the continuous switch of Settings → PDF view). */
    fun initialMode(continuous: Boolean): ViewMode = when (NpfPrefs.defaultLayout) {
        "single" -> ViewMode.SINGLE
        "continuous" -> ViewMode.CONTINUOUS
        "facing" -> ViewMode.FACING
        "cover" -> ViewMode.COVER
        // tab-view-core V-17: "auto" remembers the layout itself (facing / cover were saved as "not continuous" = single)
        else -> runCatching { ViewMode.valueOf(NpfPrefs.lastLayout) }.getOrNull() ?: if (continuous) ViewMode.CONTINUOUS else ViewMode.SINGLE
    }

    /** PREF-6: default zoom — fit page, or a zoom relative to fit width. */
    fun initialFitPage(): Boolean = NpfPrefs.defaultZoom == "page"
    fun initialZoom(): Float = when (NpfPrefs.defaultZoom) { "z150" -> 1.5f; "z200" -> 2f; else -> 1f }

    private val TYPED = setOf(AnnotKind.NOTE, AnnotKind.FREETEXT, AnnotKind.STAMP_TEXT, AnnotKind.CARET, AnnotKind.REPLACE)

    /** PREF-2: digits of typed comment text (markup copied from a selection keeps the document's digits). */
    fun typed(kind: AnnotKind, text: String): String = if (kind in TYPED) NpfText.digits(text, NpfPrefs.digits) else text

    /** Accessibility: the colour filter that replaces the document colours (null = draw the page as it is). */
    @Composable
    fun pageFilter(night: Boolean): ColorFilter? {
        if (night || !NpfPrefs.a11yOverride) return null
        val bg = NpfPrefs.a11yBackground; val fg = NpfPrefs.a11yText
        return remember(bg, fg) { ColorFilter.colorMatrix(ColorMatrix(NpfText.a11yMatrix(bg, fg))) }
    }

    /** Document /Title per file path, read when "show the document title" is on. */
    val titles = mutableStateMapOf<String, String>()

    /** Reader title: the document title when the preference is on and the PDF has one. */
    fun title(state: ReaderState): String? {
        if (!NpfPrefs.showDocTitle) return null
        val f = state.file ?: return null
        return titles[f.absolutePath]?.takeIf { it.isNotBlank() }
    }

    // ---- recovery copies (SHL-10) ----
    fun recoveryDir(state: ReaderState) = File(state.context.filesDir, "npf_recovery").apply { mkdirs() }
    fun recoveryFile(state: ReaderState, f: File) = File(recoveryDir(state), Integer.toHexString(f.absolutePath.hashCode()) + "_" + f.name.take(60))
}

/**
 * Timers and one-off effects of the reader preferences:
 *  - the panel shown when a document opens (PREF-6) and the document title (PREF-5);
 *  - automatic save every N minutes while there are pending edits (with Settings → autosave on);
 *  - a recovery copy every N minutes (SHL-10): written next to the app data, deleted once the edits are saved or
 *    discarded, and offered as "open the recovered copy" when the same document is opened after a crash.
 */
@Composable
fun NpfReaderEffects(state: ReaderState, settings: AppSettings, nav: NavHostController, onPanel: (ReaderPanel) -> Unit) {
    val opened = state.session != null
    var recovered by remember(state.uriString) { mutableStateOf<File?>(null) }
    var recChecked by remember(state.uriString) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(opened) {
        if (!opened) return@LaunchedEffect
        val f = state.file ?: return@LaunchedEffect
        // tab-view-core V-12: one crash-recovery system (NveRecovery). A copy the old one left is handed over to it
        // (offered by its banner) instead of a second, modal "recovered" prompt.
        val rec = NpfReader.recoveryFile(state, f)
        if (rec.isFile) withContext(Dispatchers.IO) { if (rec.lastModified() > f.lastModified() && rec.length() > 0) com.smartdocs.editor.ui.nve.shell.NveRecovery.adoptLegacy(state.context, f, rec) else rec.delete() }
        recChecked = true
        when (NpfPrefs.panelOnOpen) {
            "outline" -> onPanel(ReaderPanel.OUTLINE)
            "thumbnails" -> onPanel(ReaderPanel.THUMBNAILS)
            "bookmarks" -> onPanel(ReaderPanel.BOOKMARKS)
            "comments" -> onPanel(ReaderPanel.ANNOTATIONS)
        }
        if (NpfPrefs.showDocTitle) runCatching { state.session?.meta()?.title }.getOrNull()?.let { NpfReader.titles[f.absolutePath] = it }
    }
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { if (state.discarded) state.file?.let { NpfReader.recoveryFile(state, it).delete() } }   // "discard" leaves nothing to recover
    }
    // tab-view-core V-12: the periodic recovery copies are written by NveRecoveryEffect only (one interval, one folder)
    val saveMin = NpfPrefs.autoSaveMinutes
    LaunchedEffect(state.dirty, saveMin, settings.autoSave) {
        if (!state.dirty || saveMin <= 0 || !settings.autoSave) return@LaunchedEffect
        while (isActive && state.dirty) {
            delay(saveMin * 60_000L)
            state.saveInBackground()
        }
    }
}

/** Commenting preferences: the selected comment's text in a pop-up with the chosen font, size and opacity. */
@Composable
fun NpfNotePopup(state: ReaderState, annot: Annot?) {
    val a = annot ?: return
    if (a.text.isBlank() || a.kind == AnnotKind.FREETEXT || a.kind == AnnotKind.STAMP_TEXT) return
    val family = when (NpfPrefs.noteFont) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
    Surface(Modifier.fillMaxWidth().padding(4.dp).alpha(state.annot2.cx.noteOpacity.coerceIn(0.3f, 1f)), shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
            if (a.author.isNotBlank()) Text(a.author, style = MaterialTheme.typography.labelSmall)
            Text(a.text, fontFamily = family, fontSize = state.annot2.cx.noteFontSize.sp)
        }
    }
}
