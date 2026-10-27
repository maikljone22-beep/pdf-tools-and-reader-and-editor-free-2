package com.smartdocs.editor.ui.nve.tts

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.NveReadOrder
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.search.ArabicTextFix
import com.smartdocs.editor.core.tts.ReadAloudController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Read Out Loud preferences (prefs "nve_tts"): what is read besides the page text, and in which order. */
object NveReadPrefs {
    private const val FILE = "nve_tts"
    fun load(ctx: Context): NveReadOrder.Prefs {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return NveReadOrder.Prefs(
            comments = p.getBoolean("comments", false), fields = p.getBoolean("fields", false), alt = p.getBoolean("alt", true),
            order = NveReadOrder.Order.entries.getOrElse(p.getInt("order", 0)) { NveReadOrder.Order.CONTENT },
        )
    }
    fun save(ctx: Context, v: NveReadOrder.Prefs) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("comments", v.comments).putBoolean("fields", v.fields)
            .putBoolean("alt", v.alt).putInt("order", v.order.ordinal).apply()
    }
}

/** Page text for Read Out Loud with comments, form fields and figure alt text in the chosen reading order. */
object NveReadText {
    /**
     * [base]: the page text the reader already extracted (PDFium, Arabic fixed); null = extract it here with PDFBox.
     * Returns [base] unchanged when no extra item is enabled or the file cannot be read.
     */
    suspend fun pageText(context: Context, file: File, password: String?, page: Int, prefs: NveReadOrder.Prefs = NveReadPrefs.load(context), base: String? = null): String =
        withContext(Dispatchers.IO) {
            if (!prefs.any && base != null) return@withContext base
            runCatching {
                PdfOps.load(file, password).use { doc ->
                    val text = base ?: runCatching {
                        val st = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = page + 1; endPage = page + 1; sortByPosition = true }
                        ArabicTextFix.fix(st.getText(doc))
                    }.getOrDefault("")
                    NveReadOrder.merge(text, NveReadOrder.extras(doc, page, prefs, labels(context)), prefs.order)
                }
            }.getOrElse { base ?: "" }
        }

    fun labels(ctx: Context) = NveReadOrder.Labels(
        comment = ctx.getString(R.string.ns_tts_lbl_comment), by = ctx.getString(R.string.ns_tts_lbl_by), field = ctx.getString(R.string.ns_tts_lbl_field),
        checked = ctx.getString(R.string.ns_tts_lbl_checked), unchecked = ctx.getString(R.string.ns_tts_lbl_unchecked),
        empty = ctx.getString(R.string.ns_tts_lbl_empty), figure = ctx.getString(R.string.ns_tts_lbl_figure),
    )
}

/**
 * Reading / speech preferences: pitch (optional), read comments, read form fields, read alt text, reading order.
 * Drop-in for Settings and the reader's read-aloud options sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NveReadAloudPrefs(modifier: Modifier = Modifier, showPitch: Boolean = true) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(NveReadPrefs.load(ctx)) }
    fun set(v: NveReadOrder.Prefs) { p = v; NveReadPrefs.save(ctx, v) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showPitch) {
            var pitch by remember(settings.ttsPitch) { mutableFloatStateOf(settings.ttsPitch) }
            Text(stringResource(R.string.ns_tts_pitch) + ": " + "%.1f".format(java.util.Locale.US, pitch), style = MaterialTheme.typography.labelLarge)
            Slider(pitch, { pitch = it }, valueRange = 0.5f..2f, onValueChangeFinished = {
                ReadAloudController.setPitch(pitch)
                scope.launch { app.settings.update { it.copy(ttsPitch = pitch) } }
            })
        }
        Text(stringResource(R.string.ns_tts_read_extra), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(p.comments, { set(p.copy(comments = it)) }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_tts_read_comments)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(p.fields, { set(p.copy(fields = it)) }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_tts_read_fields)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(p.alt, { set(p.copy(alt = it)) }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_tts_read_alt)) }
        Text(stringResource(R.string.ns_tts_order), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(NveReadOrder.Order.CONTENT to R.string.ns_tts_order_content, NveReadOrder.Order.STRUCTURE to R.string.ns_tts_order_structure,
                NveReadOrder.Order.TEXT_THEN_EXTRAS to R.string.ns_tts_order_text_first).forEach { (o, l) ->
                FilterChip(p.order == o, { set(p.copy(order = o)) }, label = { Text(stringResource(l)) })
            }
        }
    }
}
