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
        return StampTokens.Identity(p.getString("id_name", null)?.takeIf { it.isNotBlank() } ?: fallbackName, p.getString("id_title", "") ?: "", p.getString("id_org", "") ?: "")
    }
    fun save(ctx: Context, id: StampTokens.Identity) {
        ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).edit().putString("id_name", id.name).putString("id_title", id.title).putString("id_org", id.org).apply()
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val custom by app.db.stamps().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var identity by remember { mutableStateOf(StampIdentity.load(ctx, settings.authorName)) }
    var templates by remember { mutableStateOf(StampIdentity.templates(ctx)) }
    var editId by remember { mutableStateOf(false) }
    var newTpl by remember { mutableStateOf<String?>(null) }
    val arLabels = presetStamps.map { stringResource(it.arabicRes) }
    val shLabels = SignHere.entries.map { stringResource(it.ar) }
    val now = remember { System.currentTimeMillis() }
    val adj = remember { StampIdentity.hijriAdjust(ctx) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Text(stringResource(R.string.sa_stamp_pick_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            ScrollableTabRow(tab, edgePadding = 8.dp) {
                listOf(R.string.sa_stamp_tab_ready, R.string.v5_stamp_sign_here, R.string.sa_stamp_tab_dynamic, R.string.sa_stamp_tab_custom).forEachIndexed { i, r ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(r)) })
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                when (tab) {
                    0 -> Grid(presetStamps.indices.toList(), { "p$it" }, { i -> StampRenderer.renderBilingual(arLabels[i], presetStamps[i].english, presetStamps[i].color, 500) }, onPicked)
                    1 -> Grid(SignHere.entries.flatMap { listOf(it to true, it to false) }, { "s${it.first}${it.second}" }, { (e, dated) ->
                        val sub = if (dated) StampTokens.fill("{name} • {date}", identity, now, hijriAdjust = adj) else e.en
                        SignHereRenderer.render(shLabels[e.ordinal], sub, e.color, 500)
                    }, onPicked)
                    2 -> Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(identity.name.ifBlank { stringResource(R.string.v5_identity_missing) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editId = true }) { Icon(Icons.Default.Badge, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.v5_identity)) }
                            TextButton(onClick = { newTpl = "" }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.v5_new_template)) }
                        }
                        Grid(templates, { "t$it" }, { t ->
                            val filled = StampTokens.fill(t, identity, now, hijriAdjust = adj)
                            val lines = filled.split('\n')
                            StampRenderer.renderTextStamp(lines.first(), lines.drop(1).joinToString(" ").ifBlank { null }, StampRenderer.BLUE, 500)
                        }, onPicked, onLong = { t -> templates = templates - t; StampIdentity.saveTemplates(ctx, templates) })
                    }
                    else -> if (custom.isEmpty()) Text(stringResource(R.string.sa_stamps_empty), Modifier.padding(24.dp))
                    else Grid(custom, { "c${it.id}" }, { s: CustomStamp -> CustomStamps.render(s, 500) }, onPicked)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (editId) {
        var n by remember { mutableStateOf(identity.name) }; var t by remember { mutableStateOf(identity.title) }; var o by remember { mutableStateOf(identity.org) }
        AlertDialog(onDismissRequest = { editId = false }, title = { Text(stringResource(R.string.v5_identity)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.v5_id_name)) }, singleLine = true)
                OutlinedTextField(t, { t = it }, label = { Text(stringResource(R.string.v5_id_title)) }, singleLine = true)
                OutlinedTextField(o, { o = it }, label = { Text(stringResource(R.string.v5_id_org)) }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { identity = StampTokens.Identity(n, t, o); StampIdentity.save(ctx, identity); editId = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editId = false }) { Text(stringResource(R.string.cancel)) } })
    }
    newTpl?.let { cur ->
        var txt by remember { mutableStateOf(cur) }
        AlertDialog(onDismissRequest = { newTpl = null }, title = { Text(stringResource(R.string.v5_new_template)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(txt, { txt = it }, minLines = 2, label = { Text(stringResource(R.string.v5_template)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.v5_template_tokens), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("{name}", "{title}", "{org}", "{date}", "{time}", "{hijri}").forEach { tk -> AssistChip(onClick = { txt += tk }, label = { Text(tk) }) }
                }
                Text(StampTokens.fill(txt, identity, now, hijriAdjust = adj), style = MaterialTheme.typography.bodyMedium)
            } },
            confirmButton = { TextButton(enabled = txt.isNotBlank(), onClick = { templates = (templates + txt).distinct(); StampIdentity.saveTemplates(ctx, templates); newTpl = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { newTpl = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> Grid(items: List<T>, key: (T) -> Any, render: (T) -> Bitmap?, onPicked: (Bitmap) -> Unit, onLong: ((T) -> Unit)? = null) {
    BoxWithConstraints {
        val cols = if (maxWidth > 700.dp) 4 else 2
        LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = key) { item ->
                val bmp by produceState<Bitmap?>(null, item) { value = withContext(Dispatchers.Default) { runCatching { render(item) }.getOrNull() } }
                Box(Modifier.fillMaxWidth().height(90.dp).background(Color.White, RoundedCornerShape(12.dp))
                    .combinedClickable(enabled = bmp != null, onClick = { bmp?.let(onPicked) }, onLongClick = onLong?.let { f -> { f(item) } }).padding(8.dp),
                    contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
