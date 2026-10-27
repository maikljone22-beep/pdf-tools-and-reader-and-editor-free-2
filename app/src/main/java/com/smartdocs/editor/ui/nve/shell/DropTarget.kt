package com.smartdocs.editor.ui.nve.shell

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.view.DragEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import kotlinx.coroutines.launch
import java.io.File

/** Mime types accepted by drag and drop onto the app window. */
private val DROP_MIMES = setOf("application/pdf", "image/", "application/vnd.openxmlformats-officedocument", "application/msword", "text/")

/**
 * Drag and drop a PDF (or an Office / image file) onto the app window (desktop mode, Samsung DeX, split screen):
 * the dropped document is copied into the app (DragAndDropPermissions held during the copy) and [onFile] opens it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NveDropRoot(onFile: (File) -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var hovering by remember { mutableStateOf(false) }
    val activity = remember(ctx) { var c = ctx; while (c is ContextWrapper && c !is Activity) c = c.baseContext; c as? Activity }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val e: DragEvent = event.toAndroidDragEvent()
                val clip = e.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                val perms = activity?.requestDragAndDropPermissions(e)
                scope.launch {
                    try {
                        uris.firstOrNull()?.let { u: Uri -> runCatching { app.documents.localFile(u) }.getOrNull()?.let(onFile) }
                    } finally { perms?.release() }
                }
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize()
        .dragAndDropTarget(shouldStartDragAndDrop = { ev -> ev.mimeTypes().any { m -> DROP_MIMES.any { m.startsWith(it) } } }, target = target)
        .then(if (hovering) Modifier.border(4.dp, androidx.compose.material3.MaterialTheme.colorScheme.primary) else Modifier)) {
        content()
    }
}
