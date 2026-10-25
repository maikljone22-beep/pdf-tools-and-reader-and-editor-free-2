package com.smartdocs.editor.ui.edit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.FontFamily
import com.smartdocs.editor.core.edit.TextAlign
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EDIT_COLORS = listOf(0x000000, 0x424242, 0x9E9E9E, 0xFFFFFF, 0xD32F2F, 0xF57C00, 0xFBC02D, 0x388E3C, 0x1976D2, 0x303F9F, 0x7B1FA2, 0xC2185B)
private val EDIT_SIZES = listOf(6f, 8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 48f, 64f, 72f, 96f)

@Composable
private fun BarIcon(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (selected) FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    else IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
}

/** Formatting bar shown while a text block is being edited (above the keyboard). */
@Composable
internal fun TextFormatBar(c: EditController) {
    val rtlUi = LocalLayoutDirection.current == LayoutDirection.Rtl
    val st = c.style
    var sizeMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // font substitution notice (the original font is embedded as a subset or cannot draw these characters)
            val b = c.block
            if (b != null) {
                val substituted = !b.fontReusable || ArabicShaper.isArabic(c.text) || st.bold != b.bold || st.italic != b.italic || st.family != b.family
                val note = when {
                    b.lossy -> stringResource(R.string.ed_lossy)
                    substituted -> stringResource(R.string.ed_font_substituted, b.fontName.ifBlank { "?" })
                    else -> stringResource(R.string.ed_font_kept, b.fontName)
                }
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c.style = st.copy(size = (st.size - 1f).coerceAtLeast(4f)) }) { Icon(Icons.Default.TextDecrease, stringResource(R.string.ed_smaller)) }
                Box {
                    TextButton(onClick = { sizeMenu = true }) { Text(stringResource(R.string.ed_size_pt, st.size.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() })) }
                    DropdownMenu(sizeMenu, { sizeMenu = false }) {
                        EDIT_SIZES.forEach { s -> DropdownMenuItem(text = { Text(s.toInt().toString()) }, onClick = { c.style = st.copy(size = s); sizeMenu = false }) }
                    }
                }
                IconButton(onClick = { c.style = st.copy(size = (st.size + 1f).coerceAtMost(200f)) }) { Icon(Icons.Default.TextIncrease, stringResource(R.string.ed_bigger)) }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
                BarIcon(Icons.Default.FormatBold, stringResource(R.string.ed_bold), st.bold) { c.style = st.copy(bold = !st.bold) }
                BarIcon(Icons.Default.FormatItalic, stringResource(R.string.ed_italic), st.italic) { c.style = st.copy(italic = !st.italic) }
                TextButton(onClick = {
                    c.style = st.copy(family = FontFamily.entries[(st.family.ordinal + 1) % FontFamily.entries.size])
                }) {
                    Text(stringResource(when (st.family) { FontFamily.SANS -> R.string.ed_family_sans; FontFamily.SERIF -> R.string.ed_family_serif; FontFamily.MONO -> R.string.ed_family_mono }))
                }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
                BarIcon(Icons.Default.FormatAlignLeft, stringResource(R.string.ed_align_left), st.align == TextAlign.LEFT) { c.style = st.copy(align = TextAlign.LEFT); c.alignTouched = true }
                BarIcon(Icons.Default.FormatAlignCenter, stringResource(R.string.ed_align_center), st.align == TextAlign.CENTER) { c.style = st.copy(align = TextAlign.CENTER); c.alignTouched = true }
                BarIcon(Icons.Default.FormatAlignRight, stringResource(R.string.ed_align_right), st.align == TextAlign.RIGHT) { c.style = st.copy(align = TextAlign.RIGHT); c.alignTouched = true }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
                EDIT_COLORS.forEach { col ->
                    val sel = (st.color and 0xFFFFFF) == col
                    Box(
                        Modifier.padding(3.dp).size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(Color(0xFF000000 or col.toLong()))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { c.style = st.copy(color = col) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.block != null) TextButton(onClick = { c.deleteBlock() }, enabled = !c.busy) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ed_delete_block))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { c.clearSelection() }, enabled = !c.busy) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { c.commitText(rtlUi) }, enabled = !c.busy) { Text(stringResource(R.string.done), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Actions for the selected image (replace, export, share, rotate, delete, apply move/resize). */
@Composable
internal fun ImageActionBar(c: EditController) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val pickReplace = rememberImagePicker(multiple = false) { files ->
        val f = files.firstOrNull() ?: return@rememberImagePicker
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { PdfOps.decodeScaled(f, 2400) }
            if (bmp != null) c.replaceImage(bmp) else c.message = ctx.getString(R.string.ed_img_unreadable)
        }
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            c.image?.let { im ->
                Text(stringResource(R.string.ed_img_info, im.pixelW, im.pixelH) + if (c.transformPending) " · " + stringResource(R.string.ed_img_pending) else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionChip(Icons.Default.FindReplace, stringResource(R.string.ed_img_replace), !c.busy) { pickReplace() }
                ActionChip(Icons.Default.SaveAlt, stringResource(R.string.ed_img_export), !c.busy) {
                    scope.launch {
                        val bmp = c.imageBitmap()
                        val ok = bmp != null && saveToPictures(ctx, bmp, "SmartDocs_" + System.currentTimeMillis())
                        c.message = ctx.getString(if (ok) R.string.ed_img_exported else R.string.ed_img_export_failed)
                    }
                }
                ActionChip(Icons.Default.Share, stringResource(R.string.ed_img_share), !c.busy) {
                    scope.launch {
                        val bmp = c.imageBitmap() ?: run { c.message = ctx.getString(R.string.ed_img_export_failed); return@launch }
                        val f = withContext(Dispatchers.IO) {
                            app.documents.newTemp("image", "png").also { out -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        }
                        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), "image/png"), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { c.message = ctx.getString(R.string.ed_failed, it.message ?: "") }
                    }
                }
                ActionChip(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.ed_img_rotate_left), !c.busy) { c.rotateImage(clockwise = false) }
                ActionChip(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.ed_img_rotate_right), !c.busy) { c.rotateImage(clockwise = true) }
                ActionChip(Icons.Default.Delete, stringResource(R.string.delete), !c.busy) { confirmDelete = true }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_img_drag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (c.transformPending) TextButton(onClick = { c.resetTransform() }, enabled = !c.busy) { Text(stringResource(R.string.ed_reset)) }
                Button(onClick = { c.applyTransform() }, enabled = !c.busy && c.transformPending) { Text(stringResource(R.string.ed_apply)) }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.ed_img_delete_title)) },
        text = { Text(stringResource(R.string.ed_img_delete_msg)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; c.deleteImage() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Bar shown in image mode after tapping an empty spot: insert a picture there as page content. */
@Composable
internal fun InsertImageBar(c: EditController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberImagePicker(multiple = false) { files ->
        val f = files.firstOrNull() ?: return@rememberImagePicker
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { PdfOps.decodeScaled(f, 2400) }
            if (bmp != null) c.insertImage(bmp) else c.message = ctx.getString(R.string.ed_img_unreadable)
        }
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ed_img_insert_here), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { c.insertAt = null }) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { pick() }, enabled = !c.busy) { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ed_img_pick)) }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        modifier = Modifier.padding(horizontal = 3.dp))
}

/** Save [bmp] as PNG into Pictures/SmartDocs (MediaStore). */
internal suspend fun saveToPictures(ctx: Context, bmp: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDocs"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        val ok = cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (Build.VERSION.SDK_INT >= 29) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        if (!ok) cr.delete(uri, null, null)
        ok
    }.getOrDefault(false)
}
