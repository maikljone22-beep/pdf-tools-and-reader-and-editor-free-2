package com.smartdocs.editor.ui.selmenu

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SignatureMetaStore
import com.smartdocs.editor.core.fillsign2.FsGeom
import com.smartdocs.editor.ui.reader.Placement
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderIntents
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.SignatureThumb
import com.smartdocs.editor.ui.reader.pageBoxOf
import com.smartdocs.editor.ui.reader.toPdfRect
import com.smartdocs.editor.ui.signature.SignatureGate
import kotlinx.coroutines.launch
import java.io.File

/**
 * fix/selmenu-rest: "Sign" and "Insert image" of the empty-area menu put the picked signature / picture at the
 * pressed point. They open the reader's own placement frame (move / resize / rotate, then Done) on the tool the
 * Sign tab and the image tool use, so saving, "Recently used" and the default signature size work as usual.
 */
fun ReaderState.placeAtPoint(tool: ReaderTool, page: Int, x: Float, y: Float, img: Bitmap, sigPath: String? = null) {
    if (tool == ReaderTool.SIGNATURE) {
        // tab audit D27: through selectTool (tool memory, edit selection and measure cleared), like the Sign tab
        if (this.tool != ReaderTool.SIGNATURE) selectTool(ReaderTool.SIGNATURE)
        if (this.tool != ReaderTool.SIGNATURE) return
        pendingText = sigPath.orEmpty()
    } else this.tool = tool
    placement = null; pendingImage = img; selection = null
    // tab audit D9: crop box + /Rotate; the image keeps its upright (displayed) size and stays on the page
    val box = pageBoxOf(page)
    val pref = if (tool == ReaderTool.SIGNATURE && !sigPath.isNullOrBlank()) SignatureMetaStore.get(context, sigPath).widthPt else 0f
    val w = if (pref > 0f) pref.coerceIn(8f, box.dispW * 0.95f) else (box.dispW * 0.35f).coerceAtLeast(40f)
    val h = w * img.height / img.width.coerceAtLeast(1)
    placement = Placement(page, FsGeom.stampBoxInside(box, x, y, w, h).toPdfRect(), img)
}

/** Picks a saved signature for "Sign here" ([SelMenuState.signAt]); a locked one asks for its PIN through [gate]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelSignChooser(state: ReaderState, actions: ReaderActions, gate: SignatureGate) {
    val m = SelMenuState.of(state)
    val at = m.signAt ?: return
    val app = com.smartdocs.editor.LocalApp.current
    val sigs by app.db.signatures().all().collectAsState(initial = emptyList())
    val meta by remember { SignatureMetaStore.flow(app) }.collectAsState()
    // nitro-quicksign order: recently used first, then the Manage Signatures palette order
    val recent = SignatureMetaStore.recent(meta, sigs.map { it.path }, max = 3)
    val palette = SignatureMetaStore.ordered(meta, sigs.map { it.path })
    val ordered = (recent + palette).distinct().mapNotNull { p -> sigs.firstOrNull { it.path == p } }
    val close = { m.signAt = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.smr_sign_pick)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ordered.isEmpty()) Text(stringResource(R.string.rd_no_signatures), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ordered.forEachIndexed { i, s ->
                        SignatureThumb(s, i, meta[s.path]?.locked == true,
                            Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .clickable {
                                    close()
                                    gate.request(s.path) {
                                        state.scope.launch {
                                            ReaderIntents.decode(File(s.path))?.let { bmp -> state.placeAtPoint(ReaderTool.SIGNATURE, at.page, at.x, at.y, bmp, s.path) }
                                        }
                                    }
                                })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close(); actions.navigateTool("signatures") }) { Text(stringResource(R.string.sig_create)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}
