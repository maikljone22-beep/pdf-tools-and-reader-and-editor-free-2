package com.smartdocs.editor.ui.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sign.CertFormat
import com.smartdocs.editor.core.sign.SigAppearanceSpec
import com.smartdocs.editor.ui.common.rememberImagePicker
import java.io.File

/** Designer state, persisted in "psf_sign" (same prefs as the advanced options). */
@Stable
class SigAppearanceState(private val context: Context) {
    private val prefs = context.getSharedPreferences("psf_sign", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("g6_appearance_on", false))
    var spec by mutableStateOf(SigAppearanceSpec.decode(prefs.getString("g6_appearance", null)))
    fun persist() { prefs.edit().putBoolean("g6_appearance_on", enabled).putString("g6_appearance", spec.encode()).apply() }

    fun labels(): SigAppearanceSpec.Labels = SigAppearanceSpec.Labels(
        context.getString(R.string.g6_ap_signed_by), context.getString(R.string.sa_sign_reason), context.getString(R.string.sa_sign_location),
        context.getString(R.string.sa_sign_date), context.getString(R.string.g6_ap_dn),
    )
}

@Composable
fun rememberSigAppearanceState(): SigAppearanceState {
    val ctx = LocalContext.current
    return remember { SigAppearanceState(ctx.applicationContext) }
}

/** Draws the appearance as a bitmap (transparent background) for DigitalSignature.VisibleSignature.image. */
object SigAppearanceRenderer {
    const val W = 900

    fun render(spec: SigAppearanceSpec, lines: List<String>, dn: String): Bitmap {
        val logo = spec.logoPath?.takeIf { File(it).exists() }?.let { decode(it, 600) }
        val hasLogo = logo != null
        val textLines = lines.ifEmpty { listOf(CertFormat.dnValue(dn, "CN") ?: " ") }
        val lineH = 64f
        val textH = (textLines.size * lineH + 24f).coerceAtLeast(160f)
        val h = when { hasLogo && spec.layout == SigAppearanceSpec.Layout.LOGO_TOP -> (textH + 220f).toInt(); else -> textH.toInt() }
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val textRect: RectF
        if (logo != null) {
            val logoRect = when (spec.layout) {
                SigAppearanceSpec.Layout.LOGO_LEFT -> RectF(0f, 0f, W * 0.32f, h.toFloat())
                SigAppearanceSpec.Layout.LOGO_RIGHT -> RectF(W * 0.68f, 0f, W.toFloat(), h.toFloat())
                SigAppearanceSpec.Layout.LOGO_TOP -> RectF(0f, 0f, W.toFloat(), 200f)
            }
            textRect = when (spec.layout) {
                SigAppearanceSpec.Layout.LOGO_LEFT -> RectF(W * 0.35f, 0f, W.toFloat(), h.toFloat())
                SigAppearanceSpec.Layout.LOGO_RIGHT -> RectF(0f, 0f, W * 0.65f, h.toFloat())
                SigAppearanceSpec.Layout.LOGO_TOP -> RectF(0f, 210f, W.toFloat(), h.toFloat())
            }
            val s = minOf(logoRect.width() / logo.width, logoRect.height() / logo.height)
            val lw = logo.width * s; val lh = logo.height * s
            val l = logoRect.centerX() - lw / 2; val t = logoRect.centerY() - lh / 2
            c.drawBitmap(logo, null, RectF(l, t, l + lw, t + lh), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 })
        } else textRect = RectF(0f, 0f, W.toFloat(), h.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 35, 60) }
        var y = textRect.top + (textRect.height() - textLines.size * lineH) / 2 + lineH * 0.78f
        textLines.forEachIndexed { i, line ->
            paint.typeface = if (i == 0 && spec.showName) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var size = if (i == 0 && spec.showName) 52f else 40f
            paint.textSize = size
            while (paint.measureText(line) > textRect.width() - 16f && size > 18f) { size -= 2f; paint.textSize = size }
            c.drawText(line, textRect.left + 8f, y, paint)
            y += lineH
        }
        return bmp
    }

    private fun decode(path: String, max: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > max * 2) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** Collapsible designer card with a live preview. [preview] supplies (name, reason, location, date, dn). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SigAppearanceDesigner(state: SigAppearanceState, name: String, reason: String, location: String, date: String, dn: String) {
    var open by remember { mutableStateOf(false) }
    val pickLogo = rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { state.spec = state.spec.copy(logoPath = it.absolutePath); state.persist() } }
    fun set(s: SigAppearanceSpec) { state.spec = s; state.persist() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DesignServices, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.g6_ap_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.g6_ap_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.enabled, { state.enabled = it; state.persist(); if (it) open = true })
            IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
        }
        AnimatedVisibility(open && state.enabled) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = state.spec
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(s.showName, { set(s.copy(showName = !s.showName)) }, label = { Text(stringResource(R.string.sa_sign_name)) })
                    FilterChip(s.showReason, { set(s.copy(showReason = !s.showReason)) }, label = { Text(stringResource(R.string.sa_sign_reason)) })
                    FilterChip(s.showLocation, { set(s.copy(showLocation = !s.showLocation)) }, label = { Text(stringResource(R.string.sa_sign_location)) })
                    FilterChip(s.showDate, { set(s.copy(showDate = !s.showDate)) }, label = { Text(stringResource(R.string.sa_sign_date)) })
                    FilterChip(s.showDn, { set(s.copy(showDn = !s.showDn)) }, label = { Text(stringResource(R.string.g6_ap_dn)) })
                    FilterChip(s.showLabels, { set(s.copy(showLabels = !s.showLabels)) }, label = { Text(stringResource(R.string.g6_ap_labels)) })
                }
                Text(stringResource(R.string.g6_ap_layout), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SigAppearanceSpec.Layout.LOGO_LEFT to R.string.g6_ap_logo_left, SigAppearanceSpec.Layout.LOGO_RIGHT to R.string.g6_ap_logo_right,
                        SigAppearanceSpec.Layout.LOGO_TOP to R.string.g6_ap_logo_top).forEach { (l, res) ->
                        FilterChip(s.layout == l, { set(s.copy(layout = l)) }, label = { Text(stringResource(res)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = pickLogo, label = { Text(stringResource(R.string.g6_ap_logo)) }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)) })
                    if (s.logoPath != null) AssistChip(onClick = { set(s.copy(logoPath = null)) }, label = { Text(stringResource(R.string.g6_ap_logo_remove)) }, leadingIcon = { Icon(Icons.Default.HideImage, null, Modifier.size(18.dp)) })
                }
                val lines = s.lines(name, reason, location, date, dn, state.labels())
                val preview = remember(s, lines, dn) { runCatching { SigAppearanceRenderer.render(s, lines, dn) }.getOrNull() }
                if (preview != null) Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Image(preview.asImageBitmap(), stringResource(R.string.g6_ap_preview), Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height))
                }
            }
        }
    }
}
