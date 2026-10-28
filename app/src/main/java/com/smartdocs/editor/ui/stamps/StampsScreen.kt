package com.smartdocs.editor.ui.stamps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.CustomStamp
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.StampRenderer
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.signature.ColorDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Helpers shared by the manager and the picker. The border style of a text stamp is packed into the
 * upper bits of [CustomStamp.color] (lower 32 bits = ARGB) because the entity has no dedicated column.
 */
object CustomStamps {
    fun stampsDir(context: Context): File = File(context.filesDir, "stamps").apply { mkdirs() }
    fun argb(stamp: CustomStamp): Int = (stamp.color and 0xFFFFFFFFL).toInt()
    fun border(stamp: CustomStamp): StampRenderer.Border = StampRenderer.Border.entries.getOrElse(((stamp.color shr 32) and 0xF).toInt()) { StampRenderer.Border.ROUNDED }
    fun pack(argb: Int, border: StampRenderer.Border): Long = (argb.toLong() and 0xFFFFFFFFL) or (border.ordinal.toLong() shl 32)

    /** Render a custom stamp (text → canvas; image → decode). */
    fun render(stamp: CustomStamp, widthPx: Int = 600): Bitmap? = when {
        !stamp.text.isNullOrBlank() -> StampRenderer.renderTextStamp(stamp.text, null, argb(stamp), widthPx, border(stamp))
        stamp.imagePath != null -> runCatching { BitmapFactory.decodeFile(stamp.imagePath) }.getOrNull()
        else -> null
    }
}

/** Manage custom stamps: create text or image stamps, preview, delete. */
@Composable
fun StampsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stamps by app.db.stamps().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var showText by rememberSaveable { mutableStateOf(false) }

    val pickImage = rememberImagePicker(multiple = false) { files ->
        files.firstOrNull()?.let { f ->
            scope.launch(Dispatchers.IO) {
                val bmp = PdfOps.decodeScaled(f, 1200) ?: return@launch
                val out = app.documents.uniqueFile(CustomStamps.stampsDir(ctx), "stamp_${System.currentTimeMillis()}", "png")
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                app.db.stamps().insert(CustomStamp(text = null, imagePath = out.absolutePath, color = CustomStamps.pack(StampRenderer.BLUE, StampRenderer.Border.NONE)))
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.sa_stamps_title), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = pickImage) { Icon(Icons.Default.AddPhotoAlternate, stringResource(R.string.sa_stamp_new_image)) }
                ExtendedFloatingActionButton(onClick = { showText = true }, icon = { Icon(Icons.Default.TextFields, null) }, text = { Text(stringResource(R.string.sa_stamp_new_text)) })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (stamps.isEmpty()) EmptyState(Icons.Default.Approval, stringResource(R.string.sa_stamps_empty))
            else BoxWithConstraints {
                val cols = if (maxWidth > 700.dp) 3 else 2
                LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(stamps, key = { it.id }) { s ->
                        val bmp by produceState<Bitmap?>(null, s) { value = withContext(Dispatchers.Default) { CustomStamps.render(s, 500) } }
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Box(Modifier.fillMaxWidth().height(100.dp).background(Color.White, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).padding(10.dp), contentAlignment = Alignment.Center) {
                                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            }
                            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.text ?: stringResource(R.string.sig_image), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                // §41: stamps of a locked shared set are removed only as a whole set (stamp picker)
                                if (!com.smartdocs.editor.ui.view5.StampMeta.custom(ctx, s.id).locked) IconButton(onClick = {
                                    scope.launch { withContext(Dispatchers.IO) { s.imagePath?.let { File(it).delete() }; app.db.stamps().delete(s) } }
                                }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                                else Icon(Icons.Default.Lock, stringResource(R.string.cmr_locked), Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showText) TextStampDialog(onDismiss = { showText = false }) { text, color, border ->
        scope.launch { app.db.stamps().insert(CustomStamp(text = text, imagePath = null, color = CustomStamps.pack(color, border))) }
        showText = false
    }
}

@Composable
private fun TextStampDialog(onDismiss: () -> Unit, onCreate: (String, Int, StampRenderer.Border) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(StampRenderer.RED) }
    var border by rememberSaveable { mutableStateOf(StampRenderer.Border.ROUNDED) }
    val preview by produceState<Bitmap?>(null, text, color, border) {
        value = if (text.isBlank()) null else withContext(Dispatchers.Default) { StampRenderer.renderTextStamp(text.trim(), null, color, 500, border) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sa_stamp_new_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.stamp_text)) }, singleLine = true)
                Text(stringResource(R.string.sa_stamp_color), style = MaterialTheme.typography.labelMedium)
                Row { StampRenderer.palette.forEach { c -> ColorDot(c, color == c) { color = c } } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(border == StampRenderer.Border.ROUNDED, { border = StampRenderer.Border.ROUNDED }, label = { Text(stringResource(R.string.sa_stamp_border_rounded)) })
                    FilterChip(border == StampRenderer.Border.PLAIN, { border = StampRenderer.Border.PLAIN }, label = { Text(stringResource(R.string.sa_stamp_border_plain)) })
                }
                FilterChip(border == StampRenderer.Border.NONE, { border = StampRenderer.Border.NONE }, label = { Text(stringResource(R.string.sa_stamp_border_none)) })
                Box(Modifier.fillMaxWidth().height(80.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), stringResource(R.string.sa_stamp_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onCreate(text.trim(), color, border) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
