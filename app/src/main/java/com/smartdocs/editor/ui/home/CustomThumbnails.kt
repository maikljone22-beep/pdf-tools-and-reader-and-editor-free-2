package com.smartdocs.editor.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.common.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** User choices live outside the disposable render cache and never modify document bytes. */
object CustomThumbnails {
    var version by mutableIntStateOf(0); private set
    private fun dir(c: Context) = File(c.filesDir, "custom_thumbnails").apply { mkdirs() }
    private fun key(f: File) = MessageDigest.getInstance("SHA-256").digest(f.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun store(c: Context) = com.smartdocs.editor.core.files.ThumbnailChoices(dir(c))
    fun choice(c: Context, f: File): String? = runCatching { store(c).choice(f) }.getOrNull()
    fun relocate(c: Context, from: File, to: File) { store(c).relocate(from, to); version++ }
    suspend fun reset(c: Context, f: File, hide: Boolean) {
        withContext(Dispatchers.IO) { if (hide) store(c).hide(f) else store(c).reset(f) }
        version++
    }
    suspend fun replace(c: Context, f: File, image: File) {
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }))
            val out = File.createTempFile(key(f), ".jpg", dir(c))
            try {
                out.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                store(c).replace(f, out)
            } catch (e: Throwable) { out.delete(); throw e }
            finally { bitmap.recycle() }
        }
        version++
    }
}

@Composable
fun ThumbnailEditor(file: File, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberImagePicker(multiple = false) { images ->
        val image = images.firstOrNull()
        if (image != null) {
            busy = true
            scope.launch {
                try { CustomThumbnails.replace(ctx, file, image); onDismiss() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.thumb_edit)) },
        text = { Column {
            Text(file.name)
            TextButton(onClick = pick, enabled = !busy) { Text(stringResource(R.string.thumb_replace)) }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { try { CustomThumbnails.reset(ctx, file, true); onDismiss() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true } finally { busy = false } }
            }) { Text(stringResource(R.string.thumb_hide)) }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { try { CustomThumbnails.reset(ctx, file, false); onDismiss() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true } finally { busy = false } }
            }) { Text(stringResource(R.string.thumb_auto)) }
            if (busy) LinearProgressIndicator()
            if (error) Text(stringResource(R.string.error_generic), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

fun thumbnailFile(uri: String): File? = runCatching {
    android.net.Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.isFile }
}.getOrNull()
