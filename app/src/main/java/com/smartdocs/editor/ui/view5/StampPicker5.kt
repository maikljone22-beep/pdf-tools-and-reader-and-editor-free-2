package com.smartdocs.editor.ui.view5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.CustomStamp
import com.smartdocs.editor.core.pdf.StampRenderer
import com.smartdocs.editor.core.view5.StampTokens
import com.smartdocs.editor.ui.stamps.CustomStamps
import com.smartdocs.editor.ui.stamps.presetStamps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Sign here" family: flag-shaped stamps pointing at the place to sign. */
enum class SignHere(val ar: Int, val en: String, val color: Int) {
    SIGN_HERE(R.string.v5_sh_sign_here, "SIGN HERE", 0xFFEF6C00.toInt()),
    INITIAL_HERE(R.string.v5_sh_initial_here, "INITIAL HERE", 0xFF1565C0.toInt()),
    ACCEPTED(R.string.v5_sh_accepted, "ACCEPTED", 0xFF2E7D32.toInt()),
    REJECTED(R.string.v5_sh_rejected, "REJECTED", 0xFFD32F2F.toInt()),
    WITNESS(R.string.v5_sh_witness, "WITNESS", 0xFF6A1B9A.toInt()),
}

/** Identity used by dynamic stamps (name / title / organisation), kept in prefs "view5". */
object StampIdentity {
    fun load(ctx: Context, fallbackName: String): StampTokens.Identity {
        val p = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE)
        return StampTokens.Identity(p.getString("id_name", null)?.takeIf { it.isNotBlank() } ?: fallbackName, p.getString("id_title", "") ?: "", p.getString("id_org", "") ?: "",
            p.getString("id_unit", "") ?: "", p.getString("id_email", "") ?: "")
    }
    fun save(ctx: Context, id: StampTokens.Identity) {
        ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).edit().putString("id_name", id.name).putString("id_title", id.title).putString("id_org", id.org).putString("id_unit", id.unit).putString("id_email", id.email).apply()
    }
    fun templates(ctx: Context): List<String> {
        val saved = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).getString("stamp_tpl", null)
        return (saved?.split("\u001E")?.filter { it.isNotBlank() } ?: DEFAULT_TEMPLATES).distinct()   // templates are grid keys: duplicates crash
    }
    fun saveTemplates(ctx: Context, list: List<String>) { ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).edit().putString("stamp_tpl", list.joinToString("\u001E")).apply() }
    fun hijriAdjust(ctx: Context) = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).getInt("hijri_adj", 0)
    val DEFAULT_TEMPLATES = listOf(
        "معتمد\n{name} • {date}", "تمت المراجعة\n{name} • {hijri}", "استلمت\n{datetime}", "APPROVED\n{name} • {date}",
        "{name}\n{title} — {org}", "{hijri}\n{date}",
    )
}

object SignHereRenderer {
    /** Flag-shaped stamp with the arrow on the reading-start side (right for Arabic). */
    fun render(text: String, sub: String, color: Int, widthPx: Int = 600, pointRight: Boolean = true): Bitmap {
        val h = (widthPx * 0.30f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tip = h * 0.45f
        val path = Path().apply {
            if (pointRight) { moveTo(0f, 0f); lineTo(widthPx - tip, 0f); lineTo(widthPx.toFloat(), h / 2f); lineTo(widthPx - tip, h.toFloat()); lineTo(0f, h.toFloat()) }
            else { moveTo(tip, 0f); lineTo(widthPx.toFloat(), 0f); lineTo(widthPx.toFloat(), h.toFloat()); lineTo(tip, h.toFloat()); lineTo(0f, h / 2f) }
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = h * 0.36f }
        val avail = widthPx - tip - h * 0.2f
        while (p.textSize > 10f && p.measureText(text) > avail) p.textSize *= 0.92f
        val cx = if (pointRight) (widthPx - tip) / 2f else tip + (widthPx - tip) / 2f
        c.drawText(text, cx, h * 0.52f, p)
        val ps = Paint(p).apply { textSize = h * 0.17f; typeface = Typeface.DEFAULT }
        while (ps.textSize > 8f && ps.measureText(sub) > avail) ps.textSize *= 0.92f
        if (sub.isNotBlank()) c.drawText(sub, cx, h * 0.82f, ps)
        return bmp
    }
}

/**
 * Stamp picker with ready stamps, the full "sign here" set, dynamic stamps (templates with {name}, {title},
 * {org}, {date}, {time}, {datetime}, {hijri}, {hijri_num}) and custom stamps. Same contract as the reader's
 * StampPickerSheet: [onPicked] gets the bitmap to place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun V5StampPicker(onPicked: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val custom by app.db.stamps().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var identity by remember { mutableStateOf(StampIdentity.load(ctx, settings.authorName)) }
    var templates by remember { mutableStateOf(StampIdentity.templates(ctx)) }
    var editId by remember { mutableStateOf(false) }
    var newTpl by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(StampMeta.favourites(ctx)) }
    var recents by remember { mutableStateOf(StampMeta.recents(ctx)) }
    var metaVersion by remember { mutableIntStateOf(0) }
    var editCustom by remember { mutableStateOf<CustomStamp?>(null) }
    var styleTpl by remember { mutableStateOf<String?>(null) }
    var askTpl by remember { mutableStateOf<String?>(null) }
    var pdfFile by remember { mutableStateOf<java.io.File?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    val arLabels = presetStamps.map { stringResource(it.arabicRes) }
    val shLabels = SignHere.entries.map { stringResource(it.ar) }
    val now = remember { System.currentTimeMillis() }
    val adj = remember { StampIdentity.hijriAdjust(ctx) }
    val noClip = stringResource(R.string.cx_no_clip_image)
    var info by remember { mutableStateOf<String?>(null) }

    fun renderTpl(t: String): Bitmap {
        val st = StampMeta.tplStyle(ctx, t)
        val filled = StampTokens.fill(t, identity, now, hijriAdjust = adj)
        val lines = filled.split('\n')
        return StampRenderer.renderTextStamp(lines.first(), lines.drop(1).joinToString(" ").ifBlank { null }, st.color, 500,
            StampRenderer.Border.entries.getOrElse(st.border) { StampRenderer.Border.ROUNDED }, StampMeta.typeface(st.font))
    }
    fun renderCustom(s: CustomStamp): Bitmap? {
        val m = StampMeta.custom(ctx, s.id)
        return CustomStamps.render(s, 500)?.let { StampProcess.apply(it, m.opacity, m.whiteTransparent) }
    }
    /** Every stamp has a key (favourites / recent): p:, s:, t:, c:, sh:. */
    fun renderKey(k: String): Bitmap? = when {
        k.startsWith("p:") -> presetStamps.indexOfFirst { it.english == k.removePrefix("p:") }.takeIf { it >= 0 }?.let { i -> StampRenderer.renderBilingual(arLabels[i], presetStamps[i].english, presetStamps[i].color, 500) }
        k.startsWith("s:") -> k.removePrefix("s:").split(':').let { (n, d) ->
            SignHere.entries.firstOrNull { it.name == n }?.let { e -> SignHereRenderer.render(shLabels[e.ordinal], if (d == "1") StampTokens.fill("{name} • {date}", identity, now, hijriAdjust = adj) else e.en, e.color, 500) }
        }
        k.startsWith("t:") -> renderTpl(k.removePrefix("t:"))
        k.startsWith("c:") -> custom.firstOrNull { it.id.toString() == k.removePrefix("c:") }?.let { renderCustom(it) }
        k.startsWith("sh:") -> k.removePrefix("sh:").split(':').let { (n, c) -> StampShape.entries.firstOrNull { it.name == n }?.render(c.toInt(), 400) }
        else -> null
    }
    fun pick(key: String, bmp: Bitmap) {
        StampMeta.pushRecent(ctx, key); recents = StampMeta.recents(ctx)
        // a template with {ask:...} values asks for them before the stamp is placed
        if (key.startsWith("t:") && StampMeta.askTokens(key.removePrefix("t:")).isNotEmpty()) { askTpl = key.removePrefix("t:"); return }
        onPicked(bmp)
    }
    fun fav(key: String) { StampMeta.toggleFavourite(ctx, key); favs = StampMeta.favourites(ctx) }

    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) {
                val out = java.io.File(ctx.cacheDir, "stamp_src_${System.nanoTime()}.pdf")
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }; out }.getOrNull()
            }
            pdfFile = f
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Text(stringResource(R.string.sa_stamp_pick_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            ScrollableTabRow(tab, edgePadding = 8.dp) {
                listOf(R.string.cx_stamp_tab_favourites, R.string.sa_stamp_tab_ready, R.string.v5_stamp_sign_here, R.string.sa_stamp_tab_dynamic,
                    R.string.cx_stamp_tab_shapes, R.string.sa_stamp_tab_custom).forEachIndexed { i, r ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(r)) })
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                when (tab) {
                    0 -> Column {
                        // favourites first, then recently used (24.65 / 7.41)
                        val keys = (favs.toList() + recents.filter { it !in favs }).distinct()
                        if (keys.isEmpty()) Text(stringResource(R.string.cx_stamp_no_recent), Modifier.padding(24.dp))
                        else Grid(keys, { "r$it" }, { k -> renderKey(k) }, { k, b -> pick(k, b) }, fav = { it in favs }, onFav = { fav(it) })
                    }
                    1 -> Grid(presetStamps.indices.toList(), { "p$it" }, { i -> StampRenderer.renderBilingual(arLabels[i], presetStamps[i].english, presetStamps[i].color, 500) },
                        { i, b -> pick("p:" + presetStamps[i].english, b) }, fav = { "p:" + presetStamps[it].english in favs }, onFav = { fav("p:" + presetStamps[it].english) })
                    2 -> Grid(SignHere.entries.flatMap { listOf(it to true, it to false) }, { "s${it.first}${it.second}" }, { (e, dated) ->
                        val sub = if (dated) StampTokens.fill("{name} • {date}", identity, now, hijriAdjust = adj) else e.en
                        SignHereRenderer.render(shLabels[e.ordinal], sub, e.color, 500)
                    }, { (e, d), b -> pick("s:${e.name}:${if (d) 1 else 0}", b) }, fav = { (e, d) -> "s:${e.name}:${if (d) 1 else 0}" in favs }, onFav = { (e, d) -> fav("s:${e.name}:${if (d) 1 else 0}") })
                    3 -> Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(identity.name.ifBlank { stringResource(R.string.v5_identity_missing) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editId = true }) { Icon(Icons.Default.Badge, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.v5_identity)) }
                            TextButton(onClick = { newTpl = "" }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.v5_new_template)) }
                        }
                        Text(stringResource(R.string.cx_stamp_tpl_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        key(metaVersion) {
                            Grid(templates, { "t$it" }, { t -> renderTpl(t) }, { t, b -> pick("t:$t", b) }, onLong = { t -> styleTpl = t },
                                fav = { "t:$it" in favs }, onFav = { fav("t:$it") })
                        }
                    }
                    4 -> Grid(StampShape.entries.flatMap { s -> StampRenderer.palette.map { s to it } }, { "sh${it.first}${it.second}" }, { (s, c) -> s.render(c, 400) },
                        { (s, c), b -> pick("sh:${s.name}:$c", b) }, fav = { (s, c) -> "sh:${s.name}:$c" in favs }, onFav = { (s, c) -> fav("sh:${s.name}:$c") })
                    else -> Column {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, label = { Text(stringResource(R.string.cx_stamp_from_pdf)) },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { scope.launch { val b = StampSources.fromClipboard(ctx); if (b != null) onPicked(b) else info = noClip } },
                                label = { Text(stringResource(R.string.cx_stamp_from_clip)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp)) })
                            // user categories of custom stamps (24.52 / 24.53)
                            val cats = remember(custom, metaVersion) { custom.map { StampMeta.custom(ctx, it.id).category }.filter { it.isNotBlank() }.distinct() }
                            if (cats.isNotEmpty()) {
                                FilterChip(category == null, { category = null }, label = { Text(stringResource(R.string.cx_stamp_all)) })
                                cats.forEach { c -> FilterChip(category == c, { category = c }, label = { Text(c) }) }
                            }
                        }
                        val shown = remember(custom, category, metaVersion) { custom.filter { category == null || StampMeta.custom(ctx, it.id).category == category } }
                        if (shown.isEmpty()) Text(stringResource(R.string.sa_stamps_empty), Modifier.padding(24.dp))
                        else key(metaVersion) {
                            Grid(shown, { "c${it.id}" }, { s: CustomStamp -> renderCustom(s) }, { s, b -> pick("c:${s.id}", b) }, onLong = { editCustom = it },
                                fav = { "c:${it.id}" in favs }, onFav = { fav("c:${it.id}") }, label = { StampMeta.custom(ctx, it.id).name })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    info?.let { m -> AlertDialog(onDismissRequest = { info = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.ok)) } }) }
    if (editId) {
        var n by remember { mutableStateOf(identity.name) }; var t by remember { mutableStateOf(identity.title) }; var o by remember { mutableStateOf(identity.org) }
        var u by remember { mutableStateOf(identity.unit) }; var e by remember { mutableStateOf(identity.email) }
        AlertDialog(onDismissRequest = { editId = false }, title = { Text(stringResource(R.string.v5_identity)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.v5_id_name)) }, singleLine = true)
                OutlinedTextField(t, { t = it }, label = { Text(stringResource(R.string.v5_id_title)) }, singleLine = true)
                OutlinedTextField(o, { o = it }, label = { Text(stringResource(R.string.v5_id_org)) }, singleLine = true)
                OutlinedTextField(u, { u = it }, label = { Text(stringResource(R.string.cx_id_unit)) }, singleLine = true)
                OutlinedTextField(e, { e = it }, label = { Text(stringResource(R.string.cx_id_email)) }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { identity = StampTokens.Identity(n, t, o, u, e); StampIdentity.save(ctx, identity); editId = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editId = false }) { Text(stringResource(R.string.cancel)) } })
    }
    newTpl?.let { cur ->
        var txt by remember { mutableStateOf(cur) }
        AlertDialog(onDismissRequest = { newTpl = null }, title = { Text(stringResource(R.string.v5_new_template)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(txt, { txt = it }, minLines = 2, label = { Text(stringResource(R.string.v5_template)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.v5_template_tokens), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("{name}", "{title}", "{org}", "{unit}", "{email}", "{date}", "{time}", "{hijri}", "{ask:" + stringResource(R.string.cx_stamp_ask_default) + "}").forEach { tk -> AssistChip(onClick = { txt += tk }, label = { Text(tk) }) }
                }
                Text(StampTokens.fill(txt, identity, now, hijriAdjust = adj), style = MaterialTheme.typography.bodyMedium)
            } },
            confirmButton = { TextButton(enabled = txt.isNotBlank(), onClick = { templates = (templates + txt).distinct(); StampIdentity.saveTemplates(ctx, templates); newTpl = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { newTpl = null }) { Text(stringResource(R.string.cancel)) } })
    }
    // dynamic template style: colour, border, font (24.56); delete
    styleTpl?.let { t ->
        var st by remember(t) { mutableStateOf(StampMeta.tplStyle(ctx, t)) }
        AlertDialog(onDismissRequest = { styleTpl = null }, title = { Text(stringResource(R.string.cx_stamp_style)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StampRenderer.palette.forEach { c -> com.smartdocs.editor.ui.reader.ColorDot(c and 0xFFFFFF, st.color == c) { st = st.copy(color = c) } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(R.string.sa_stamp_border_rounded, R.string.sa_stamp_border_plain, R.string.sa_stamp_border_none).forEachIndexed { i, r ->
                        FilterChip(st.border == i, { st = st.copy(border = i) }, label = { Text(stringResource(r)) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StampMeta.FONTS.forEachIndexed { i, r -> FilterChip(st.font == i, { st = st.copy(font = i) }, label = { Text(stringResource(r)) }) }
                }
            } },
            confirmButton = { TextButton(onClick = { StampMeta.saveTplStyle(ctx, t, st); metaVersion++; styleTpl = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { Row {
                TextButton(onClick = { templates = templates - t; StampIdentity.saveTemplates(ctx, templates); styleTpl = null }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = { styleTpl = null }) { Text(stringResource(R.string.cancel)) }
            } })
    }
    // prompted custom values of a dynamic stamp (24.57)
    askTpl?.let { t ->
        val names = remember(t) { StampMeta.askTokens(t) }
        val values = remember(t) { mutableStateMapOf<String, String>() }
        AlertDialog(onDismissRequest = { askTpl = null }, title = { Text(stringResource(R.string.cx_stamp_values)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { n -> OutlinedTextField(values[n] ?: "", { values[n] = it }, label = { Text(n) }, singleLine = true) }
            } },
            confirmButton = { TextButton(onClick = { val filled = StampMeta.fillAsk(t, values); askTpl = null; onPicked(renderTpl(filled)) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { askTpl = null }) { Text(stringResource(R.string.cancel)) } })
    }
    // custom stamp: name, category, opacity, white transparent, delete (24.52 / 24.58)
    editCustom?.let { s ->
        var m by remember(s.id) { mutableStateOf(StampMeta.custom(ctx, s.id)) }
        AlertDialog(onDismissRequest = { editCustom = null }, title = { Text(stringResource(R.string.cx_stamp_edit)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(m.name, { m = m.copy(name = it) }, label = { Text(stringResource(R.string.cx_name)) }, singleLine = true)
                OutlinedTextField(m.category, { m = m.copy(category = it) }, label = { Text(stringResource(R.string.cx_stamp_category)) }, singleLine = true)
                Text(stringResource(R.string.cx_stamp_opacity, (m.opacity * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                Slider(m.opacity, { m = m.copy(opacity = it) }, valueRange = 0.1f..1f)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(m.whiteTransparent, { m = m.copy(whiteTransparent = it) }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cx_stamp_white)) }
            } },
            confirmButton = { TextButton(onClick = { StampMeta.saveCustom(ctx, s.id, m); metaVersion++; editCustom = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { Row {
                TextButton(onClick = { editCustom = null; scope.launch { withContext(Dispatchers.IO) { s.imagePath?.let { java.io.File(it).delete() }; app.db.stamps().delete(s) } } }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = { editCustom = null }) { Text(stringResource(R.string.cancel)) }
            } })
    }
    // stamp from a PDF page (7.38 / 24.51): choose the page, render it and keep it as a custom image stamp
    pdfFile?.let { f ->
        var pageText by remember(f) { mutableStateOf("1") }
        var busy by remember(f) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { pdfFile = null; f.delete() }, title = { Text(stringResource(R.string.cx_stamp_from_pdf)) },
            text = { Column {
                OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(stringResource(R.string.cx_stamp_page)) }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        val bmp = StampSources.fromPdfPage(ctx, f, (pageText.toIntOrNull() ?: 1) - 1)
                        val out = java.io.File(CustomStamps.stampsDir(ctx), "pdf_${System.currentTimeMillis()}.png")
                        withContext(Dispatchers.IO) { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        val id = app.db.stamps().insert(CustomStamp(text = null, imagePath = out.absolutePath, color = CustomStamps.pack(StampRenderer.BLUE, StampRenderer.Border.NONE)))
                        StampMeta.saveCustom(ctx, id, StampMeta.Custom(name = f.nameWithoutExtension, category = "PDF"))
                    }.onFailure { info = it.message ?: it.toString() }
                    withContext(Dispatchers.IO) { f.delete() }
                    busy = false; pdfFile = null; metaVersion++
                }
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pdfFile = null; f.delete() }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> Grid(items: List<T>, key: (T) -> Any, render: (T) -> Bitmap?, onPicked: (T, Bitmap) -> Unit, onLong: ((T) -> Unit)? = null,
                     fav: ((T) -> Boolean)? = null, onFav: ((T) -> Unit)? = null, label: ((T) -> String)? = null) {
    BoxWithConstraints {
        val cols = if (maxWidth > 700.dp) 4 else 2
        LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = key) { item ->
                val bmp by produceState<Bitmap?>(null, item) { value = withContext(Dispatchers.Default) { runCatching { render(item) }.getOrNull() } }
                Box(Modifier.fillMaxWidth().height(90.dp).background(Color.White, RoundedCornerShape(12.dp))
                    .combinedClickable(enabled = bmp != null, onClick = { bmp?.let { onPicked(item, it) } }, onLongClick = onLong?.let { f -> { f(item) } }).padding(8.dp),
                    contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    label?.invoke(item)?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelSmall, color = Color.DarkGray, maxLines = 1) }
                    if (fav != null && onFav != null) {
                        val on = fav(item)
                        IconButton(onClick = { onFav(item) }, Modifier.align(Alignment.TopEnd).size(28.dp)) {
                            Icon(if (on) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.cx_stamp_favourite), tint = if (on) Color(0xFFFFA000) else Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
