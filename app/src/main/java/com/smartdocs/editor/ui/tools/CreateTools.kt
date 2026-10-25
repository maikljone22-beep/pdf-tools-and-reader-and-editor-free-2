package com.smartdocs.editor.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

private val pageSizes = listOf("A4" to PDRectangle.A4, "A5" to PDRectangle.A5, "Letter" to PDRectangle.LETTER, "Legal" to PDRectangle.LEGAL, "A3" to PDRectangle.A3)
private val pageColors = listOf(0xFFFFFF, 0xFFF8E1, 0xFFF9C4, 0xE3F2FD, 0xE8F5E9, 0xEEEEEE)

/** Tool "blank": create a new PDF with blank / lined / grid / dotted pages. */
@Composable
fun BlankPdfScreen(nav: NavHostController) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") }
    var size by remember { mutableStateOf("A4") }
    var landscape by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(PdfOps.PageStyle.BLANK) }
    var color by remember { mutableIntStateOf(0xFFFFFF) }
    val processing = stringResource(R.string.processing)

    ToolScaffold(stringResource(R.string.tool_blank), nav, runner) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true, placeholder = { Text(stringResource(R.string.tl_default_name)) })
        NumberField(count, stringResource(R.string.page_count), { count = it }, Modifier.fillMaxWidth(), maxLen = 2)
        FieldLabel(stringResource(R.string.page_size))
        ChoiceChips(pageSizes.map { it.first }, size, { it }) { size = it }
        FieldLabel(stringResource(R.string.orientation))
        ChoiceChips(listOf(false, true), landscape, { if (it) stringResource(R.string.landscape) else stringResource(R.string.portrait) }) { landscape = it }
        FieldLabel(stringResource(R.string.page_style))
        ChoiceChips(PdfOps.PageStyle.entries, style, { styleLabel(it) }) { style = it }
        FieldLabel(stringResource(R.string.tl_page_color))
        ColorChips(pageColors, color) { color = it }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.tl_create), icon = Icons.Default.NoteAdd) {
            val n = (count.toIntOrNull() ?: 1).coerceIn(1, 50)
            val rect = pageSizes.first { it.first == size }.second
            val st = style; val ls = landscape; val col = color
            val base = name.ifBlank { "new_document" }
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, base)
                PdfOps.createBlank(out, n, rect, ls, st, col)
                out
            }
        }
    }
}

@Composable
fun styleLabel(s: PdfOps.PageStyle): String = when (s) {
    PdfOps.PageStyle.BLANK -> stringResource(R.string.style_blank)
    PdfOps.PageStyle.LINED -> stringResource(R.string.style_lined)
    PdfOps.PageStyle.GRID -> stringResource(R.string.style_grid)
    PdfOps.PageStyle.DOTS -> stringResource(R.string.style_dots)
}

/** Tool "images_to_pdf": pick photos, order them and build a PDF. */
@Composable
fun ImagesToPdfScreen(nav: NavHostController) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var images by remember { mutableStateOf(listOf<java.io.File>()) }
    var fitA4 by remember { mutableStateOf(true) }
    var quality by remember { mutableFloatStateOf(85f) }
    var name by remember { mutableStateOf("") }
    val pick = rememberImagePicker(multiple = true) { images = images + it }
    val processing = stringResource(R.string.processing)

    ToolScaffold(stringResource(R.string.tool_images_to_pdf), nav, runner, scrollable = false, actions = {
        IconButton(onClick = pick) { Icon(Icons.Default.AddPhotoAlternate, stringResource(R.string.tl_add_images)) }
    }) {
        if (images.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.Collections, stringResource(R.string.tl_no_images)) {
                    Button(onClick = pick) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.tl_add_images)) }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(images, key = { i, f -> "${f.absolutePath}#$i" }) { i, f ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column {
                            Box {
                                AsyncImage(model = f, contentDescription = null, Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)), contentScale = ContentScale.Crop)
                                Surface(Modifier.padding(6.dp).align(Alignment.TopStart), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                                    Text("${i + 1}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = { images = images.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.tl_move_up)) }
                                IconButton(onClick = { images = images.move(i, i + 1) }, enabled = i < images.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.tl_move_down)) }
                                IconButton(onClick = { images = images.toMutableList().also { it.removeAt(i) } }) { Icon(Icons.Default.Close, stringResource(R.string.tl_remove), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tl_fit_a4), Modifier.weight(1f)); Switch(fitA4, { fitA4 = it })
        }
        LabeledSlider(stringResource(R.string.tl_image_quality), quality, 30f..100f, { quality = it }) { "${it.toInt()}%" }
        PrimaryButton(stringResource(R.string.tl_create), enabled = images.isNotEmpty(), icon = Icons.Default.PictureAsPdf) {
            val list = images.toList(); val fit = fitA4; val q = quality.toInt()
            val base = name.ifBlank { "images_${System.currentTimeMillis() / 1000}" }
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, base)
                PdfOps.imagesToPdf(list, out, fit, q)
                out
            }
        }
    }
}

/** Move element [from] to [to] (no-op when out of range). */
fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices || from !in indices) return this
    return toMutableList().also { val e = it.removeAt(from); it.add(to, e) }
}
