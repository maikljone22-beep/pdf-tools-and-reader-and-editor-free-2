package com.smartdocs.editor.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Tabs audit CM-16 / CM-23 / CM-35: a bitmap this composable owns (never one from a shared cache). A replaced bitmap
 * is recycled two frames after the new one is shown (no frame still draws it); the last one when the composable
 * leaves the composition.
 */
@Composable
fun rememberOwnedBitmap(vararg keys: Any?, produce: suspend () -> Bitmap?): Bitmap? {
    var current by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(*keys) {
        val b = runCatching { produce() }.getOrNull()
        val old = current
        current = b
        if (old != null && old !== b) { withFrameNanos { }; withFrameNanos { }; if (!old.isRecycled) old.recycle() }
    }
    DisposableEffect(Unit) { onDispose { current?.let { if (!it.isRecycled) it.recycle() }; current = null } }
    return current
}

/** Recycle [old] once two frames have passed (it may still be drawn in the current one). */
suspend fun recycleLater(old: Bitmap?) {
    if (old == null || old.isRecycled) return
    withFrameNanos { }; withFrameNanos { }
    if (!old.isRecycled) old.recycle()
}
