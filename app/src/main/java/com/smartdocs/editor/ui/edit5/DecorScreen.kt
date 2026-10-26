package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DecorSource
import com.smartdocs.editor.core.edit5.DecorSpec
import com.smartdocs.editor.core.edit5.DecorType
import com.smartdocs.editor.core.edit5.TokenContext
import com.smartdocs.editor.core.edit5.Tokens
import com.smartdocs.editor.core.edit5.WatermarkOps
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold

private val FILLS = listOf(0xFFF8E1, 0xE3F2FD, 0xE8F5E9, 0xFCE4EC, 0xF3E5F5, 0xECEFF1)
private val WM_COLORS = listOf(0x9E9E9E, 0xC62828, 0x1565C0, 0x2E7D32, 0x212121, 0xEF6C00)

/** Watermark / background / security watermark with text (incl. dynamic tokens), image or colour; update/remove. */
@Composable
fun DecorScreen(nav: NavHostController, uri: String?, type: DecorType) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val trace = remember { java.util.UUID.randomUUID().toString().take(8).uppercase() }
    val defaultText = stringResource(if (type == DecorType.WATERMARK) R.string.e5_wm_default else R.string.e5_bg_default)
    var spec by remember(type) {
        mutableStateOf(when (type) {
            DecorType.SECURITY -> DecorSpec.security(settings.authorName.ifBlank { "user" }, trace)
            DecorType.BACKGROUND -> DecorSpec(type = type, source = DecorSource.COLOR, opacity = 1f, rotation = 0f, behind = true, text = "")
            DecorType.WATERMARK -> DecorSpec(type = type, text = defaultText, user = settings.authorName)
        })
    }
    var existing by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    val pickImage = rememberImagePicker(multiple = false) { it.firstOrNull()?.let { f -> spec = spec.copy(source = DecorSource.IMAGE, imagePath = f.absolutePath) } }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work) {
        val w = doc.work ?: return@LaunchedEffect
        WatermarkOps.stored(w, doc.password, type)?.let { spec = it.copy(user = spec.user); existing = true }
    }
    val title = stringResource(when (type) { DecorType.WATERMARK -> R.string.e5_wm_title; DecorType.BACKGROUND -> R.string.e5_bg_title; DecorType.SECURITY -> R.string.e5_sec_title })
    ToolScaffold(title, nav, runner) {
        E5FileRow(doc)
        if (type == DecorType.SECURITY) Text(stringResource(R.string.e5_sec_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        E5TwoPane(options = {
            E5Section(stringResource(R.string.e5_source)) {
                val sources = if (type == DecorType.SECURITY) listOf(DecorSource.TEXT) else DecorSource.values().toList()
                ChoiceChips(sources, spec.source, { stringResource(when (it) { DecorSource.TEXT -> R.string.e5_src_text; DecorSource.IMAGE -> R.string.e5_src_image; DecorSource.COLOR -> R.string.e5_src_color }) }) { spec = spec.copy(source = it) }
                when (spec.source) {
                    DecorSource.TEXT -> {
                        OutlinedTextField(spec.text, { spec = spec.copy(text = it) }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, label = { Text(stringResource(R.string.e5_text)) })
                        TokenHelp()
                        OutlinedTextField(spec.user, { spec = spec.copy(user = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_user_name)) })
                        FontChoice(spec.style.font) { spec = spec.copy(style = spec.style.copy(font = it)) }
                        Text(stringResource(R.string.e5_font_size, spec.style.size.toInt()))
                        Slider(spec.style.size, { spec = spec.copy(style = spec.style.copy(size = it)) }, valueRange = 6f..150f)
                        ColorChips(WM_COLORS, spec.style.color) { spec = spec.copy(style = spec.style.copy(color = it)) }
                        SwitchRow(stringResource(R.string.e5_bold), spec.style.bold) { spec = spec.copy(style = spec.style.copy(bold = it)) }
                        SwitchRow(stringResource(R.string.e5_arabic_digits), spec.arabicDigits) { spec = spec.copy(arabicDigits = it) }
                    }
                    DecorSource.IMAGE -> OutlinedButton(onClick = pickImage, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp))
                        Text(if (spec.imagePath.isBlank()) stringResource(R.string.e5_pick_image) else java.io.File(spec.imagePath).name, maxLines = 1)
                    }
                    DecorSource.COLOR -> ColorChips(FILLS, spec.fill) { spec = spec.copy(fill = it) }
                }
            }
            E5Section(stringResource(R.string.e5_appearance)) {
                Text(stringResource(R.string.e5_opacity, (spec.opacity * 100).toInt()))
                Slider(spec.opacity, { spec = spec.copy(opacity = it) }, valueRange = 0.03f..1f)
                if (spec.source != DecorSource.COLOR) {
                    Text(stringResource(R.string.e5_rotation, spec.rotation.toInt()))
                    Slider(spec.rotation, { spec = spec.copy(rotation = it) }, valueRange = -90f..90f)
                    Text(if (spec.scale <= 0f) stringResource(R.string.e5_scale_auto) else stringResource(R.string.e5_scale, (spec.scale * 100).toInt()))
                    Slider(spec.scale, { spec = spec.copy(scale = it) }, valueRange = 0f..1f)
                    SwitchRow(stringResource(R.string.e5_tile), spec.tile) { spec = spec.copy(tile = it) }
                    if (spec.tile) FloatField(spec.tileGap, stringResource(R.string.e5_tile_gap)) { spec = spec.copy(tileGap = it.coerceAtLeast(0f)) }
                    else {
                        Text(stringResource(R.string.e5_position))
                        AlignGrid(spec.hAlign, spec.vAlign) { h, v -> spec = spec.copy(hAlign = h, vAlign = v) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FloatField(spec.offsetX, stringResource(R.string.e5_offset_x), Modifier.weight(1f)) { spec = spec.copy(offsetX = it) }
                            FloatField(spec.offsetY, stringResource(R.string.e5_offset_y), Modifier.weight(1f)) { spec = spec.copy(offsetY = it) }
                        }
                    }
                }
                if (type != DecorType.BACKGROUND) SwitchRow(stringResource(R.string.e5_behind), spec.behind) { spec = spec.copy(behind = it) }
                SwitchRow(stringResource(R.string.e5_on_screen), spec.onScreen) { spec = spec.copy(onScreen = it) }
                SwitchRow(stringResource(R.string.e5_on_print), spec.onPrint) { spec = spec.copy(onPrint = it) }
                if (type == DecorType.SECURITY) SwitchRow(stringResource(R.string.e5_microtext), spec.microtext) { spec = spec.copy(microtext = it) }
                RangeSubset(spec.range, { spec = spec.copy(range = it) }, spec.subset) { spec = spec.copy(subset = it) }
            }
        }, preview = {
            val info = doc.infos.getOrNull(page)
            if (doc.work != null && info != null) {
                PageStepper(page, doc.pageCount) { page = it }
                val sample = Tokens.expand(spec.text, TokenContext(page = page + 1, pages = doc.pageCount, file = doc.source?.name ?: "", user = spec.user), spec.arabicDigits)
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth()) { _ ->
                    val k = size.width / info.displayW
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = (0xFF000000.toInt() or spec.style.color); alpha = (spec.opacity * 255).toInt(); textSize = spec.style.size * k; textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = spec.style.bold
                    }
                    drawContext.canvas.nativeCanvas.apply {
                        when (spec.source) {
                            DecorSource.COLOR -> drawRect(0f, 0f, size.width, size.height, android.graphics.Paint().apply { color = 0xFF000000.toInt() or spec.fill; alpha = (spec.opacity * 255).toInt() })
                            else -> {
                                val label = if (spec.source == DecorSource.IMAGE) "▣" else sample.lineSequence().firstOrNull().orEmpty()
                                if (spec.scale > 0f && label.isNotEmpty()) { val tw = paint.measureText(label).coerceAtLeast(1f); paint.textSize *= size.width * spec.scale / tw }
                                fun at(x: Float, y: Float) { save(); rotate(-spec.rotation, x, y); drawText(label, x, y + paint.textSize / 3f, paint); restore() }
                                if (spec.tile) {
                                    val step = paint.measureText(label) + spec.tileGap * k; val rowStep = paint.textSize + spec.tileGap * k
                                    var y = 0f; var r = 0
                                    while (y < size.height + rowStep) { var x = if (r % 2 == 1) step / 2 else 0f; while (x < size.width + step) { at(x, y); x += step.coerceAtLeast(20f) }; y += rowStep.coerceAtLeast(10f); r++ }
                                } else {
                                    val cx = when (spec.hAlign) { -1 -> size.width * 0.2f; 1 -> size.width * 0.8f; else -> size.width / 2 } + spec.offsetX * k
                                    val cy = when (spec.vAlign) { -1 -> size.height * 0.85f; 1 -> size.height * 0.15f; else -> size.height / 2 } - spec.offsetY * k
                                    at(cx, cy)
                                }
                            }
                        }
                    }
                }
            }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runner.run(processing) {
                    doc.apply(app.documents.tempDir) { s, o, p -> WatermarkOps.remove(s, o, p, type, others = true) }
                    doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "clean")
                }
            }, enabled = doc.work != null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_remove)) }
        }
        val ready = doc.work != null && when (spec.source) { DecorSource.TEXT -> spec.text.isNotBlank(); DecorSource.IMAGE -> spec.imagePath.isNotBlank(); DecorSource.COLOR -> true }
        PrimaryButton(stringResource(if (existing) R.string.e5_update else R.string.apply), enabled = ready, icon = Icons.Default.BrandingWatermark) {
            val sp = spec
            runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, p -> WatermarkOps.apply(ctx, s, o, p, sp, replace = true) }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, sp.kind)
            }
        }
    }
}

/** 3×3 position picker. h: -1 left … 1 right, v: 1 top … -1 bottom. */
@Composable
private fun AlignGrid(h: Int, v: Int, onPick: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (vv in listOf(1, 0, -1)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (hh in listOf(-1, 0, 1)) {
                val sel = hh == h && vv == v
                Box(Modifier.size(34.dp).border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .clickable { onPick(hh, vv) }, contentAlignment = Alignment.Center) { if (sel) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
            }
        }
    }
}
