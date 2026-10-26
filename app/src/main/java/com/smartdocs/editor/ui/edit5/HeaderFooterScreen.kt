package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.HFSpec
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.edit5.TokenContext
import com.smartdocs.editor.core.edit5.Tokens
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold

internal val TEXT_COLORS = listOf(0x212121, 0x616161, 0x9E9E9E, 0x1565C0, 0xC62828, 0x2E7D32)

/** Presets: (label, spec transform). */
private data class HFPreset(val label: Int, val apply: (HFSpec, String) -> HFSpec)

private val PRESETS = listOf(
    HFPreset(R.string.e5_hf_p_page) { it, _ -> it.copy(topLeft = "", topCenter = "", topRight = "", bottomLeft = "", bottomCenter = "{page}", bottomRight = "") },
    HFPreset(R.string.e5_hf_p_page_of) { it, pageOf -> it.copy(bottomCenter = pageOf, bottomLeft = "", bottomRight = "") },
    HFPreset(R.string.e5_hf_p_title_date) { it, _ -> it.copy(topRight = "{title}", topLeft = "{date}", bottomCenter = "{page}") },
    HFPreset(R.string.e5_hf_p_hijri) { it, _ -> it.copy(topRight = "{hijri}", topLeft = "{date}", bottomCenter = "{page}") },
    HFPreset(R.string.e5_hf_p_book) { it, _ -> it.copy(topCenter = "{title}", bottomRight = "{page}", bottomCenter = "", mirrorEven = true) },
)

/** Header & footer: six cells with tokens, fonts, margins, Hijri dates; update or remove later (tagged content). */
@Composable
fun HeaderFooterScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var spec by remember { mutableStateOf(HFSpec(user = settings.authorName)) }
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var page by remember { mutableIntStateOf(0) }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work) {
        val w = doc.work ?: return@LaunchedEffect
        HeaderFooterOps.stored(w, doc.password)?.let { spec = it.copy(user = settings.authorName) }
        existing = runCatching { HeaderFooterOps.detect(w, doc.password) }.getOrDefault(emptySet())
    }
    ToolScaffold(stringResource(R.string.e5_hf_title), nav, runner) {
        E5FileRow(doc)
        if (existing.isNotEmpty()) Text(stringResource(if ("hf" in existing) R.string.e5_hf_found_ours else R.string.e5_hf_found_other), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        E5TwoPane(options = {
            E5Section(stringResource(R.string.e5_presets)) {
                val pageOf = stringResource(R.string.e5_hf_tpl_page_of)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PRESETS.forEach { p -> AssistChip(onClick = { spec = p.apply(spec, pageOf) }, label = { Text(stringResource(p.label)) }) } }
            }
            E5Section(stringResource(R.string.e5_hf_header)) {
                CellRow(spec.topRight, spec.topCenter, spec.topLeft) { r, c, l -> spec = spec.copy(topRight = r, topCenter = c, topLeft = l) }
            }
            E5Section(stringResource(R.string.e5_hf_footer)) {
                CellRow(spec.bottomRight, spec.bottomCenter, spec.bottomLeft) { r, c, l -> spec = spec.copy(bottomRight = r, bottomCenter = c, bottomLeft = l) }
            }
            TokenHelp()
            E5Section(stringResource(R.string.e5_text_style)) {
                FontChoice(spec.style.font) { spec = spec.copy(style = spec.style.copy(font = it)) }
                Text(stringResource(R.string.e5_font_size, spec.style.size.toInt()))
                Slider(spec.style.size, { spec = spec.copy(style = spec.style.copy(size = it)) }, valueRange = 6f..24f)
                ColorChips(TEXT_COLORS, spec.style.color) { spec = spec.copy(style = spec.style.copy(color = it)) }
                SwitchRow(stringResource(R.string.e5_bold), spec.style.bold) { spec = spec.copy(style = spec.style.copy(bold = it)) }
                SwitchRow(stringResource(R.string.e5_underline), spec.style.underline) { spec = spec.copy(style = spec.style.copy(underline = it)) }
            }
            E5Section(stringResource(R.string.e5_layout)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatField(spec.marginTop, stringResource(R.string.e5_margin_top), Modifier.weight(1f)) { spec = spec.copy(marginTop = it) }
                    FloatField(spec.marginBottom, stringResource(R.string.e5_margin_bottom), Modifier.weight(1f)) { spec = spec.copy(marginBottom = it) }
                    FloatField(spec.marginSide, stringResource(R.string.e5_margin_side), Modifier.weight(1f)) { spec = spec.copy(marginSide = it) }
                }
                FloatField(spec.startNumber.toFloat(), stringResource(R.string.e5_start_number)) { spec = spec.copy(startNumber = it.toInt().coerceAtLeast(0)) }
                SwitchRow(stringResource(R.string.e5_arabic_digits), spec.arabicDigits) { spec = spec.copy(arabicDigits = it) }
                SwitchRow(stringResource(R.string.e5_month_names_ar), spec.arabicNames) { spec = spec.copy(arabicNames = it) }
                SwitchRow(stringResource(R.string.e5_umm_al_qura), spec.ummAlQura) { spec = spec.copy(ummAlQura = it) }
                SwitchRow(stringResource(R.string.e5_mirror_even), spec.mirrorEven) { spec = spec.copy(mirrorEven = it) }
                Text(stringResource(R.string.e5_shrink, (spec.shrink * 100).toInt()))
                Slider(spec.shrink, { spec = spec.copy(shrink = it) }, valueRange = 0f..0.2f)
                RangeSubset(spec.range, { spec = spec.copy(range = it) }, spec.subset) { spec = spec.copy(subset = it) }
            }
        }, preview = {
            val info = doc.infos.getOrNull(page)
            if (doc.work != null && info != null) {
                PageStepper(page, doc.pageCount) { page = it }
                val tctx = TokenContext(page = spec.startNumber + page, pages = doc.pageCount + spec.startNumber - 1, file = doc.source?.name ?: "", user = spec.user, arabic = spec.arabicNames, ummAlQura = spec.ummAlQura)
                Box {
                    E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth()) { map ->
                        val s = map(android.graphics.RectF(0f, info.displayH, info.displayW, 0f)).width / info.displayW
                        val top = spec.marginTop * s; val bottom = size.height - spec.marginBottom * s
                        drawLine(Color(0x552196F3), androidx.compose.ui.geometry.Offset(0f, top + spec.style.size * s), androidx.compose.ui.geometry.Offset(size.width, top + spec.style.size * s))
                        drawLine(Color(0x552196F3), androidx.compose.ui.geometry.Offset(0f, bottom - spec.style.size * s), androidx.compose.ui.geometry.Offset(size.width, bottom - spec.style.size * s))
                    }
                }
                listOf(spec.topRight, spec.topCenter, spec.topLeft, spec.bottomRight, spec.bottomCenter, spec.bottomLeft).filter { it.isNotBlank() }.forEach {
                    Text("• " + Tokens.expand(it, tctx, spec.arabicDigits), style = MaterialTheme.typography.bodySmall)
                }
            }
        })
        val w = doc.work
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runner.run(processing) {
                    doc.apply(app.documents.tempDir) { s, o, p -> HeaderFooterOps.remove(s, o, p, others = true) }
                    doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "no_header")
                }
            }, enabled = w != null && existing.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_remove)) }
        }
        PrimaryButton(stringResource(if ("hf" in existing) R.string.e5_update else R.string.apply), enabled = w != null, icon = Icons.Default.ViewHeadline) {
            val sp = spec
            runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, p -> HeaderFooterOps.apply(ctx, s, o, p, sp, replace = true) }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "header")
            }
        }
    }
}

/** Right / centre / left cells (Arabic reading order: right first). */
@Composable
private fun CellRow(right: String, center: String, left: String, onChange: (String, String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(right, { onChange(it, center, left) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_right)) })
        OutlinedTextField(center, { onChange(right, it, left) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_center)) })
        OutlinedTextField(left, { onChange(right, center, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_left)) })
    }
}

@Composable
internal fun TokenHelp() {
    Text(stringResource(R.string.e5_tokens_help) + "  " + Tokens.ALL.joinToString(" ") + "  {date:yyyy-MM-dd} {hijri:d MMMM yyyy}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

