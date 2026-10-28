package com.smartdocs.editor.ui.selmenu

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.ImageEdit5
import com.smartdocs.editor.core.edit.ImageItem
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.selmenu.ImageFacts
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelCtx
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.core.selmenu.SelMenus
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.edit.EditController
import com.smartdocs.editor.ui.edit.EditMode
import com.smartdocs.editor.ui.edit.ImgSheet5
import com.smartdocs.editor.ui.edit.saveToPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * fix/selmenu-rest: the page-content picture menu of the EDIT tab's content-edit screen (registry IMAGE context).
 * Long-press on a picture, shape or shading selects it (switching to Image mode) and opens the floating bar at the
 * finger; every item runs the handler of the content-edit image bars (ImageActionBar, ImageTools5).
 */
class SelEditImage private constructor() {
    /** Press point in the page view's px and the object the menu was opened for. */
    var at by mutableStateOf<Offset?>(null)
    var forImage by mutableStateOf<ImageItem?>(null)
    var moreOpen by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)

    fun close() { at = null; forImage = null; moreOpen = false }

    companion object {
        private val map = WeakHashMap<EditController, SelEditImage>()
        fun of(c: EditController): SelEditImage = synchronized(map) { map.getOrPut(c) { SelEditImage() } }

        /** EditPageView long-press at display point ([x], [y]) / page px [px]: true when an object was under it. */
        fun longPress(c: EditController, x: Float, y: Float, px: Offset): Boolean {
            val m = c.model ?: return false
            if (c.busy) return false
            val t = c.tools5
            val o = t.visibleObjects().filter { x in it.box[0]..it.box[2] && y in it.box[1]..it.box[3] }.minByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) }
            val item = o?.let { t.itemOf(it) } ?: m.images.lastOrNull { x in it.rect.left..it.rect.right && y in it.rect.bottom..it.rect.top } ?: return false
            if (c.mode != EditMode.IMAGE) { c.clearSelection(); c.mode = EditMode.IMAGE }
            c.selectImageNve(item)
            val s = of(c); s.forImage = item; s.at = px; s.moreOpen = false
            return true
        }

        /** A tap on the page closes the menu (the tap then selects / deselects as usual). */
        fun dismiss(c: EditController) { synchronized(map) { map[c] }?.close() }
    }
}

/** Hook at the end of EditPageView's page Box: the floating bar of the long-pressed object, its More sheet and dialogs. */
@Composable
fun SelEditImageMenu(c: EditController) {
    val s = SelEditImage.of(c)
    val at = s.at
    val im = c.image
    if (at == null || im == null || s.forImage != im) { if (at != null && im == null) s.close(); return }
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val t = c.tools5
    val tablet = LocalConfiguration.current.screenWidthDp >= 600
    val ai = com.smartdocs.editor.LocalSettings.current.aiEnabled
    val vector = t.isVector(im)
    val facts = ImageFacts(vector = vector, shading = vector && t.kindOf(im.op) == ImageEdit5.Kind.SHADING)
    val model = SelMenus.build(SelMenuState.of(c.state).env(SelCtx.IMAGE, "", ai, image = facts), if (tablet) SelMenus.BAR_TABLET else SelMenus.BAR_PHONE)
    val pickReplace = rememberImagePicker(multiple = false) { files ->
        val f = files.firstOrNull() ?: return@rememberImagePicker
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { PdfOps.decodeScaled(f, 2400) }
            if (bmp != null) c.replaceImage(bmp) else c.message = ctx.getString(R.string.ed_img_unreadable)
        }
    }
    fun runItem(item: SelItem) {
        if (!item.enabled) {
            c.message = item.why?.let { ctx.getString(SelBindings.why(it)) } ?: ctx.getString(R.string.sm_unavailable); return
        }
        s.moreOpen = false
        val keep = item.action == SelAction.I_DELETE
        if (!keep) s.close()
        when (item.action) {
            SelAction.I_COPY -> scope.launch { c.message = ctx.getString(if (t.copy()) R.string.ni_copied else R.string.ed_img_export_failed) }
            SelAction.I_CUT -> scope.launch { if (!t.cut()) c.message = ctx.getString(R.string.ed_img_export_failed) }
            SelAction.I_SAVE -> scope.launch {
                val bmp = c.imageBitmap()
                val ok = bmp != null && saveToPictures(ctx, bmp, "SmartDocs_" + System.currentTimeMillis())
                c.message = ctx.getString(if (ok) R.string.ed_img_exported else R.string.ed_img_export_failed)
            }
            SelAction.I_SHARE -> scope.launch {
                val bmp = c.imageBitmap() ?: run { c.message = ctx.getString(R.string.ed_img_export_failed); return@launch }
                val f = withContext(Dispatchers.IO) {
                    app.documents.newTemp("image", "png").also { out -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), "image/png"), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { c.message = ctx.getString(R.string.ed_failed, it.message ?: "") }
            }
            SelAction.I_REPLACE -> pickReplace()
            SelAction.I_CROP -> t.sheet = ImgSheet5.CROP
            SelAction.I_ROTATE_LEFT -> c.rotateImage(clockwise = false)
            SelAction.I_ROTATE_RIGHT -> c.rotateImage(clockwise = true)
            SelAction.I_FLIP_H -> t.flip(true)
            SelAction.I_FLIP_V -> t.flip(false)
            SelAction.I_EDITOR -> t.sheet = ImgSheet5.EDITOR
            SelAction.I_PROPS -> t.sheet = ImgSheet5.PROPS
            SelAction.I_MORE_TOOLS -> t.sheet = ImgSheet5.MORE
            SelAction.I_DELETE -> s.confirmDelete = true
            SelAction.I_FRONT -> scope.launch { t.arrange(ImageEdit5.Arrange.FRONT) { c.message = ctx.getString(R.string.ni_already) } }
            SelAction.I_BACK -> scope.launch { t.arrange(ImageEdit5.Arrange.BACK) { c.message = ctx.getString(R.string.ni_already) } }
            SelAction.I_DUPLICATE -> t.duplicate()
            else -> {}
        }
    }
    if (!s.moreOpen && !s.confirmDelete && model.bar.isNotEmpty()) FloatingPlacement(Rect(at, at), handles = false) {
        SelBar(model, tablet, onItem = { runItem(it) }, onMore = { s.moreOpen = true })
    }
    if (s.moreOpen) SelMoreSheet(model, null, tablet, onItem = { runItem(it) }) { s.moreOpen = false }
    if (s.confirmDelete) AlertDialog(
        onDismissRequest = { s.confirmDelete = false },
        title = { Text(stringResource(R.string.ed_img_delete_title)) },
        text = { Text(stringResource(R.string.ed_img_delete_msg)) },
        confirmButton = { TextButton(onClick = { s.confirmDelete = false; s.close(); if (vector) t.delete() else c.deleteImage() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { s.confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
