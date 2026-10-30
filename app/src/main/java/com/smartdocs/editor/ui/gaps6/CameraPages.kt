package com.smartdocs.editor.ui.gaps6

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.view.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Captures pages with the ML Kit document scanner (edge detection, crop, filters) and hands the JPEGs (temp
 * files) to [onPages] — Organize inserts them at the chosen position. Returns the launch function.
 */
@Composable
fun rememberCameraPages(pageLimit: Int = 0, onPages: (List<File>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.g6_camera_unavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uris: List<Uri> = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri } ?: emptyList()
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, u ->
                    runCatching {
                        val f = app.documents.newTemp("cam_$i", "jpg")
                        ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } } ?: return@runCatching null
                        f
                    }.getOrNull()
                }
            }
            if (files.isNotEmpty()) onPages(files)
        }
    }
    return launch@{
        val act = ctx.findActivity() ?: run { Toast.makeText(ctx, unavailable, Toast.LENGTH_LONG).show(); return@launch }
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .apply { if (pageLimit > 0) setPageLimit(pageLimit) }   // 0 = no page limit
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(act)
                .addOnSuccessListener { sender -> runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { Toast.makeText(ctx, unavailable, Toast.LENGTH_LONG).show() } }
                .addOnFailureListener { Toast.makeText(ctx, unavailable, Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(ctx, unavailable, Toast.LENGTH_LONG).show() }
    }
}
