package com.smartdocs.editor.ui.esign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.EsignField
import com.smartdocs.editor.core.esign.FieldType
import com.smartdocs.editor.core.esign.PageGeom
import com.smartdocs.editor.core.esign.Signer
import com.smartdocs.editor.core.pdf.PdfSession
import java.util.UUID

/** Distinct colours per signer (fields are tinted by the signer they belong to). */
val SignerColors = listOf(Color(0xFF2563EB), Color(0xFFDB2777), Color(0xFF059669), Color(0xFFD97706), Color(0xFF7C3AED), Color(0xFF0891B2))
fun signerColor(i: Int) = SignerColors[i % SignerColors.size]

@Composable
fun fieldLabel(t: FieldType) = stringResource(when (t) {
    FieldType.SIGNATURE -> R.string.cn_f_signature; FieldType.INITIALS -> R.string.cn_f_initials
    FieldType.DATE -> R.string.cn_f_date; FieldType.TEXT -> R.string.cn_f_text
})

/**
 * Tap the page to add a field of the chosen type for the chosen signer; drag a field to move it; drag its corner
 * handle to resize; tap a field to select it (required switch, delete).
 */
@Composable
fun EsignFieldPlacer(session: PdfSession, signers: List<Signer>, fields: SnapshotStateList<EsignField>) {
    var page by remember { mutableIntStateOf(0) }
    var signer by remember { mutableIntStateOf(0) }
    var type by remember { mutableStateOf(FieldType.SIGNATURE) }
    var selected by remember { mutableStateOf<String?>(null) }
    var aspect by remember { mutableFloatStateOf(0.707f) }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cn_place_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            signers.forEachIndexed { i, s ->
                FilterChip(signer == i, { signer = i }, label = { Text(s.name.ifBlank { "#${i + 1}" }) },
                    leadingIcon = { Box(Modifier.size(12.dp).background(signerColor(i), CircleShape)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldType.entries.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(fieldLabel(t)) }) }
        }
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { // page coordinates are left-to-right
        BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            val hPx = wPx / aspect
            PageBitmap(session, page, wPx.toInt().coerceIn(300, 1600), onSize = { w, h -> aspect = w / h })
            Box(Modifier.matchParentSize().pointerInput(page, signer, type, aspect) {
                detectTapGestures { o ->
                    val (dw, dh) = PageGeom.defaultSize(type, aspect)
                    val c = PageGeom.clampField(o.x / size.width - dw / 2, o.y / size.height - dh / 2, dw, dh)
                    val f = EsignField(UUID.randomUUID().toString().take(8), signer, page, c[0], c[1], c[2], c[3], type)
                    fields += f; selected = f.id
                }
            })
            val density = LocalDensity.current
            fields.filter { it.page == page }.forEach { f ->
                key(f.id) {
                    val color = signerColor(f.signer)
                    val sel = selected == f.id
                    Box(Modifier
                        .absoluteOffset(with(density) { (f.x * wPx).toDp() }, with(density) { (f.y * hPx).toDp() })
                        .size(with(density) { (f.w * wPx).toDp() }, with(density) { (f.h * hPx).toDp() })
                        .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                        .border(if (sel) 2.dp else 1.dp, color, RoundedCornerShape(4.dp))
                        .pointerInput(f.id) {
                            detectTapGestures { selected = f.id }
                        }
                        .pointerInput(f.id, wPx, hPx) {
                            detectDragGestures(onDragStart = { selected = f.id }) { change, d ->
                                change.consume()
                                val i = fields.indexOfFirst { it.id == f.id }; if (i < 0) return@detectDragGestures
                                val cur = fields[i]
                                val c = PageGeom.clampField(cur.x + d.x / wPx, cur.y + d.y / hPx, cur.w, cur.h)
                                fields[i] = cur.copy(x = c[0], y = c[1])
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(fieldLabel(f.type) + if (f.required) " *" else "", color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        if (sel) Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(color, CircleShape).pointerInput(f.id, wPx, hPx) {
                            detectDragGestures { change, d ->
                                change.consume()
                                val i = fields.indexOfFirst { it.id == f.id }; if (i < 0) return@detectDragGestures
                                val cur = fields[i]
                                val c = PageGeom.clampField(cur.x, cur.y, cur.w + d.x / wPx, cur.h + d.y / hPx)
                                fields[i] = cur.copy(w = c[2], h = c[3])
                            }
                        })
                    }
                }
            }
        }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page > 0) { page--; selected = null } }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            Text(stringResource(R.string.cn_page_of, page + 1, session.pageCount), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { if (page < session.pageCount - 1) { page++; selected = null } }, enabled = page < session.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
            Spacer(Modifier.weight(1f))
            val sel = fields.firstOrNull { it.id == selected }
            if (sel != null) {
                Text(stringResource(R.string.cn_required), style = MaterialTheme.typography.labelMedium)
                Switch(sel.required, { v -> val i = fields.indexOf(sel); if (i >= 0) fields[i] = sel.copy(required = v) })
                IconButton(onClick = { fields.remove(sel); selected = null }) { Icon(Icons.Default.Delete, stringResource(R.string.cn_delete)) }
            }
        }
        Text(stringResource(R.string.cn_fields_count, fields.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
