package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.ImageAnnotProps5
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.launch

/**
 * NVE-img: properties of a stored image annotation (image stamp): hidden / printable / locked, border width, and how
 * the image fits its box (scale always / when bigger / when smaller / never, proportional, position). One undoable
 * file step. Hook: show it for a stored annotation [e] whose appearance holds an image, e.g. from the annotation
 * menu: `ImageAnnotPropsDialog5(state, existing) { show = false }`.
 */
@Composable
fun ImageAnnotPropsDialog5(state: ReaderState, e: AnnotationWriter.Existing, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = state.file ?: return
    var loaded by remember { mutableStateOf(false) }
    var props by remember { mutableStateOf<ImageAnnotProps5.Props?>(null) }
    LaunchedEffect(e) { props = runCatching { ImageAnnotProps5.readFile(f, state.password, e.page, e.index) }.getOrNull(); loaded = true }
    val p = props
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ni_ia_title)) },
        confirmButton = {
            TextButton(enabled = p != null, onClick = {
                val v = p ?: return@TextButton
                onDismiss()
                scope.launch {
                    runCatching { state.applyInPlace(label = { ctx.getString(R.string.ni_ia_title) }) { src, out, pw -> ImageAnnotProps5.applyFile(src, out, pw, e.page, e.index, v) } }
                        .onFailure { state.error = it.message ?: it.toString() }
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            when {
                !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                p == null -> Text(stringResource(R.string.ni_ia_no_image))
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    @Composable fun check(label: Int, on: Boolean, set: (Boolean) -> Unit) =
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(on, set); Text(stringResource(label)) }
                    check(R.string.ni_ia_hidden, p.hidden) { props = p.copy(hidden = it) }
                    check(R.string.ni_ia_print, p.printable) { props = p.copy(printable = it) }
                    check(R.string.ni_ia_locked, p.locked) { props = p.copy(locked = it) }
                    Text(stringResource(R.string.ni_ia_border, p.border.toInt()))
                    Slider(p.border, { props = p.copy(border = Math.round(it).toFloat()) }, valueRange = 0f..8f, steps = 7)
                    HorizontalDivider()
                    Text(stringResource(R.string.ni_ia_scale_when), style = MaterialTheme.typography.labelLarge)
                    listOf(ImageAnnotProps5.ScaleWhen.ALWAYS to R.string.ni_ia_sw_always, ImageAnnotProps5.ScaleWhen.BIGGER to R.string.ni_ia_sw_bigger,
                        ImageAnnotProps5.ScaleWhen.SMALLER to R.string.ni_ia_sw_smaller, ImageAnnotProps5.ScaleWhen.NEVER to R.string.ni_ia_sw_never).forEach { (sw, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(p.scaleWhen == sw, { props = p.copy(scaleWhen = sw) }); Text(stringResource(l)) }
                    }
                    check(R.string.ni_ia_proportional, p.proportional) { props = p.copy(proportional = it) }
                    Text(stringResource(R.string.ni_ia_pos_x, Math.round(p.posX * 100)))
                    Slider(p.posX, { props = p.copy(posX = it) })
                    Text(stringResource(R.string.ni_ia_pos_y, Math.round(p.posY * 100)))
                    Slider(p.posY, { props = p.copy(posY = it) })
                }
            }
        })
}
