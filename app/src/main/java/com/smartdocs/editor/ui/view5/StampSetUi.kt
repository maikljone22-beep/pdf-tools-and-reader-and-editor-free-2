package com.smartdocs.editor.ui.view5

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.annot.StampSet
import com.smartdocs.editor.core.data.CustomStamp
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.stamps.CustomStamps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 24.58 / §41: custom stamp management beyond the Room row: replace a stamp's image, export the custom stamps as a
 * shared (optionally locked) stamp set and import such a set. Locked stamps keep their name, style and image.
 */
object StampSets {
    /** Replace the image of an image stamp in place (same id, favourites and recents stay). False when locked or not an image. */
    suspend fun replaceImage(ctx: Context, stamp: CustomStamp, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val path = stamp.imagePath ?: return@withContext false
        if (StampMeta.custom(ctx, stamp.id).locked) return@withContext false
        val tmp = File(ctx.cacheDir, "stamp_replace_${System.nanoTime()}")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@withContext false
            val bmp = PdfOps.decodeScaled(tmp, 1200) ?: return@withContext false
            val out = File(path)
            val part = File(out.parentFile, out.name + ".part")
            part.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            part.renameTo(out) || run { part.copyTo(out, overwrite = true); part.delete(); true }
        } finally { tmp.delete() }
    }

    /** Write every custom stamp in [stamps] as a set named [name]; [locked] locks them for everyone who imports it. */
    suspend fun export(ctx: Context, stamps: List<CustomStamp>, name: String, locked: Boolean, uri: Uri): Int = withContext(Dispatchers.IO) {
        val images = LinkedHashMap<String, File>()
        val entries = stamps.mapIndexedNotNull { i, s ->
            val m = StampMeta.custom(ctx, s.id)
            val file = s.imagePath?.let { p -> File(p).takeIf { it.isFile }?.let { f -> "stamp_${i + 1}.png".also { images[it] = f } } }
            if (file == null && s.text.isNullOrBlank()) null
            else StampSet.Entry(file, m.name, m.category, s.text, s.color, m.opacity, m.whiteTransparent, m.locked)
        }
        ctx.contentResolver.openOutputStream(uri, "w")?.use { StampSet.zip(StampSet.Manifest(name, locked, entries), images, it) } ?: error("open")
        entries.size
    }

    /** Import a set; a set imported again replaces its earlier copy. Returns the number of stamps, null when not a set. */
    suspend fun import(ctx: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as SmartDocsApp
        val dir = CustomStamps.stampsDir(ctx)
        val read = ctx.contentResolver.openInputStream(uri)?.use { input ->
            StampSet.unzip(input, dir) { n -> File(dir, "set_${System.nanoTime()}_${n.filter { it.isLetterOrDigit() || it == '.' || it == '_' }}") }
        } ?: return@withContext null
        val (m, files) = read
        val setName = m.name.ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "" }
        if (setName.isNotBlank()) removeSet(ctx, setName)
        var n = 0
        m.stamps.forEach { e ->
            val img = e.file?.let { files[it] }
            if (img == null && e.text.isNullOrBlank()) return@forEach
            val id = app.db.stamps().insert(CustomStamp(text = if (img == null) e.text else null, imagePath = img?.absolutePath, color = e.color))
            StampMeta.saveCustom(ctx, id, StampMeta.Custom(e.name, e.category.ifBlank { setName }, e.opacity, e.whiteTransparent, e.locked, setName))
            n++
        }
        n
    }

    /** Remove every stamp of the set [name] (the only way to take locked stamps away). */
    suspend fun removeSet(ctx: Context, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        val app = ctx.applicationContext as SmartDocsApp
        app.db.stamps().all().first().filter { StampMeta.custom(ctx, it.id).set == name }.forEach { s ->
            s.imagePath?.let { File(it).delete() }
            app.db.stamps().delete(s); StampMeta.forget(ctx, s.id)
        }
    }
}

/** Export dialog: set name and whether the stamps are locked for those who import the set. */
@Composable
fun StampSetExportDialog(onDismiss: () -> Unit, onExport: (name: String, locked: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cmr_set_export)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cmr_set_export_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.cmr_set_name)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(locked, { locked = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cmr_set_lock)) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onExport(name.trim(), locked) }) { Text(stringResource(R.string.cmr_set_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
