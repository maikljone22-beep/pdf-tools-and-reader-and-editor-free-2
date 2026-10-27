package com.smartdocs.editor.ui.esign

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.EsignBrand
import com.smartdocs.editor.core.esign.EsignBrandStore
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun brandColor(hex: String, fallback: Color): Color = EsignBrand.argb(hex)?.let { Color(it) } ?: fallback

/**
 * What a signer sees: the branded header of the web signing page / e-mail (logo, sender name, theme colour) and the button.
 * [logo] = local logo file (null = none); [version] forces the image to reload after it changed.
 */
@Composable
fun BrandPreview(brand: EsignBrand?, logo: File?, title: String, sender: String, version: Int = 0, onEdit: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val main = brandColor(brand?.color.orEmpty(), Color(0xFF1E3A8A))
    val button = brandColor(brand?.color.orEmpty(), Color(0xFF2563EB))
    val ink = brandColor(EsignBrand.onColor(brand?.color?.ifBlank { null } ?: "#1e3a8a"), Color.White)
    val inkButton = brandColor(EsignBrand.onColor(brand?.color?.ifBlank { null } ?: "#2563eb"), Color.White)
    val who = brand?.name?.ifBlank { null } ?: sender
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ne2_brand_preview), style = MaterialTheme.typography.titleSmall)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column {
                Column(Modifier.fillMaxWidth().background(main).padding(14.dp)) {
                    if (logo != null && logo.exists()) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(logo).memoryCacheKey("${logo.path}#$version").diskCacheKey("${logo.path}#$version").build(),
                            contentDescription = stringResource(R.string.ne2_brand_logo), contentScale = ContentScale.Fit,
                            modifier = Modifier.heightIn(max = 40.dp).widthIn(max = 160.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(2.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(title, color = ink, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (who.isNotBlank()) Text(who, color = ink.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ne2_brand_asks, who.ifBlank { "…" }, title), style = MaterialTheme.typography.bodySmall)
                    Surface(shape = RoundedCornerShape(10.dp), color = button) {
                        Text(stringResource(R.string.ne2_brand_review_btn), color = inkButton, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                }
            }
        }
        if (brand == null) Text(stringResource(R.string.ne2_brand_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onEdit?.let { TextButton(onClick = it) { Icon(Icons.Default.Palette, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_brand_edit)) } }
    }
}

/** Route "esign/brand": sender name, theme colour and logo used on signing pages and e-mails. */
@Composable
fun EsignBrandScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(EsignBrandStore.enabled(ctx)) }
    var name by remember { mutableStateOf(EsignBrandStore.name(ctx).ifBlank { settings.authorName }) }
    var color by remember { mutableStateOf(EsignBrandStore.color(ctx)) }
    var hasLogo by remember { mutableStateOf(EsignBrandStore.logoFile(ctx).exists()) }
    var version by remember { mutableIntStateOf(0) }
    val failed = stringResource(R.string.ne2_brand_logo_failed)
    val saved = stringResource(R.string.ne2_brand_saved)
    val badColor = color.isNotBlank() && EsignBrand.normalizeColor(color).isEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { EsignBrandStore.setLogo(ctx, uri) } }
                .onSuccess { hasLogo = true; version++ }
                .onFailure { Toast.makeText(ctx, failed, Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ne2_brand_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ne2_brand_on), Modifier.weight(1f))
                Switch(on, { on = it })
            }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.ne2_brand_name)) }, singleLine = true, enabled = on,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Text(stringResource(R.string.ne2_brand_color), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EsignBrand.PRESETS.forEach { hex ->
                    val selected = EsignBrand.normalizeColor(color) == hex
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(EsignBrand.argb(hex)!!))
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable(enabled = on) { color = hex }, contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Default.Check, hex, tint = Color(EsignBrand.argb(EsignBrand.onColor(hex))!!))
                    }
                }
            }
            OutlinedTextField(color, { color = it.take(7) }, label = { Text(stringResource(R.string.ne2_brand_hex)) }, singleLine = true, enabled = on, isError = badColor,
                supportingText = { if (badColor) Text(stringResource(R.string.ne2_brand_bad_color)) },
                trailingIcon = { if (color.isNotEmpty()) IconButton(onClick = { color = "" }) { Icon(Icons.Default.Close, stringResource(R.string.cn_delete)) } },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Text(stringResource(R.string.ne2_brand_logo), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }, enabled = on, shape = PillShape) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_brand_pick_logo))
                }
                if (hasLogo) TextButton(onClick = { EsignBrandStore.clearLogo(ctx); hasLogo = false; version++ }, enabled = on) { Text(stringResource(R.string.ne2_brand_remove_logo)) }
            }
            NoteCard(stringResource(R.string.ne2_brand_note))
            val preview = if (on) EsignBrand(name.trim(), EsignBrand.normalizeColor(color), hasLogo = hasLogo).takeIf { !it.isEmpty } else null
            BrandPreview(preview, if (on && hasLogo) EsignBrandStore.logoFile(ctx) else null, stringResource(R.string.ne2_brand_sample_title), name.trim(), version)
            Button(onClick = {
                EsignBrandStore.save(ctx, on, name, color)
                Toast.makeText(ctx, saved, Toast.LENGTH_SHORT).show()
                nav.popBackStack()
            }, enabled = !badColor, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ne2_brand_save)) }
        }
    }
}
