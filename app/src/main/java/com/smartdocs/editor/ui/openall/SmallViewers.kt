package com.smartdocs.editor.ui.openall

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.openall.*
import com.smartdocs.editor.core.tools5.HtmlBits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- fonts

/** Font preview (open-all): TTF / OTF / TTC directly, WOFF after conversion; Latin and Arabic samples at several sizes. */
@Composable
fun FontViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val loaded by produceState<Result<Pair<Typeface, Map<Int, String>>>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                require(f.length() <= 64L * 1024 * 1024) { "font too large" }
                val bytes = f.readBytes()
                val sfnt = if (v.type == FType.WOFF) Woff.toSfnt(bytes) else bytes
                val file = if (v.type == FType.WOFF) File(OpenAll.workDir(ctx, "fonts"), f.nameWithoutExtension + ".ttf").also { it.writeBytes(sfnt) } else f
                Typeface.createFromFile(file) to SfntNames.read(sfnt)
            }
        }
    }
    val tf = loaded?.getOrNull()
    ViewerScaffold(nav, v, tf?.second?.get(4) ?: v.name) { pad ->
        when {
            loaded == null -> Box(Modifier.padding(pad)) { CenteredProgress() }
            tf == null -> Box(Modifier.padding(pad)) { ErrorPane(loaded?.exceptionOrNull()?.message) }
            else -> {
                val family = remember(tf) { FontFamily(tf.first) }
                LazyColumn(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
                    item {
                        listOf(1, 2, 5, 0).forEach { id -> tf.second[id]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        Spacer(Modifier.height(12.dp))
                    }
                    items(listOf(14, 18, 24, 32, 48)) { s ->
                        Text(stringResource(R.string.oa_font_preview_en), fontFamily = family, fontSize = s.sp, modifier = Modifier.padding(vertical = 4.dp))
                        Text(stringResource(R.string.oa_font_preview_ar), fontFamily = family, fontSize = s.sp, modifier = Modifier.padding(vertical = 4.dp), style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl))
                        HorizontalDivider()
                    }
                    item {
                        Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789 !?&@#%()[]{}", fontFamily = family, fontSize = 22.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Text("ا ب ت ث ج ح خ د ذ ر ز س ش ص ض ط ظ ع غ ف ق ك ل م ن ه و ي ء ٠١٢٣٤٥٦٧٨٩", fontFamily = family, fontSize = 22.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.oa_font_install_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- DXF

object DxfRender {
    fun read(f: File): DxfDrawing {
        require(f.length() <= 80L * 1024 * 1024) { "drawing too large" }
        val head = FileSniffer.readHead(f, 32)
        // fix/files-index: binary DXF is read too
        if (DxfReader.isBinary(head)) return DxfReader.read(DxfReader.binaryPairs(f.readBytes()))
        return DxfReader.read(TextDecode.readFile(f).text)
    }

    /** Draws [d] into a [w]×[h] box (y up in the drawing, down on screen), fitted with a margin. */
    fun draw(c: android.graphics.Canvas, d: DxfDrawing, w: Float, h: Float, dark: Boolean = false) {
        val dw = (d.maxX - d.minX).coerceAtLeast(1e-9); val dh = (d.maxY - d.minY).coerceAtLeast(1e-9)
        val s = (minOf(w / dw, h / dh) * 0.94).toFloat()
        val ox = (w - dw * s) / 2f; val oy = (h - dh * s) / 2f
        fun x(v: Double) = (ox + (v - d.minX) * s).toFloat()
        fun y(v: Double) = (h - oy - (v - d.minY) * s).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (sh in d.shapes) {
            var col = sh.color.toInt()
            if (dark && sh.color == 0xFF212121) col = 0xFFE0E0E0.toInt()
            p.color = col; tp.color = col
            when (sh) {
                is DxfShape.Line -> c.drawLine(x(sh.x1), y(sh.y1), x(sh.x2), y(sh.y2), p)
                is DxfShape.Poly -> {
                    val path = android.graphics.Path(); var i = 0
                    while (i + 1 < sh.pts.size) { if (i == 0) path.moveTo(x(sh.pts[0]), y(sh.pts[1])) else path.lineTo(x(sh.pts[i]), y(sh.pts[i + 1])); i += 2 }
                    if (sh.closed) path.close()
                    c.drawPath(path, p)
                }
                is DxfShape.Circle -> if (sh.r <= 0) c.drawPoint(x(sh.x), y(sh.y), p) else c.drawCircle(x(sh.x), y(sh.y), (sh.r * s).toFloat(), p)
                is DxfShape.Arc -> {
                    var sweep = sh.a2 - sh.a1; while (sweep < 0) sweep += 360.0
                    val r = (sh.r * s).toFloat()
                    // screen y is flipped: angles run clockwise on screen
                    c.drawArc(x(sh.x) - r, y(sh.y) - r, x(sh.x) + r, y(sh.y) + r, (-sh.a1 - sweep).toFloat(), sweep.toFloat(), false, p)
                }
                is DxfShape.Hatch -> {
                    // fix/files-index: solid fill, or the pattern's line families clipped to the boundary
                    val path = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
                    sh.loops.forEach { l -> var i = 0; while (i + 1 < l.size) { if (i == 0) path.moveTo(x(l[0]), y(l[1])) else path.lineTo(x(l[i]), y(l[i + 1])); i += 2 }; path.close() }
                    if (sh.solid) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL })
                    else {
                        val b = android.graphics.RectF(); path.computeBounds(b, true)
                        val gap = (sh.spacing * s).toFloat().coerceAtLeast(4f)
                        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 0.8f }
                        val half = kotlin.math.hypot(b.width(), b.height()) / 2f + gap
                        c.save(); c.clipPath(path)
                        for (a in sh.angles) {
                            c.save(); c.rotate((-a).toFloat(), b.centerX(), b.centerY())
                            var t = -half
                            var n = 0
                            while (t <= half && n++ < 4000) { c.drawLine(b.centerX() - half, b.centerY() + t, b.centerX() + half, b.centerY() + t, lp); t += gap }
                            c.restore()
                        }
                        c.restore()
                        c.drawPath(path, p)
                    }
                }
                is DxfShape.Text -> {
                    tp.textSize = (sh.h * s).toFloat().coerceAtLeast(1f)
                    c.save(); c.rotate((-sh.rot).toFloat(), x(sh.x), y(sh.y))
                    sh.text.split('\n').forEachIndexed { k, line -> c.drawText(line, x(sh.x), y(sh.y) + k * tp.textSize * 1.25f, tp) }
                    c.restore()
                }
            }
        }
    }

    suspend fun toPdf(f: File, out: File): File {
        val d = withContext(Dispatchers.IO) { read(f) }
        val ratio = ((d.maxX - d.minX) / (d.maxY - d.minY).coerceAtLeast(1e-9)).toFloat().coerceIn(0.2f, 5f)
        val (w, h) = if (ratio >= 1f) 842f to 842f / ratio else 595f * ratio to 595f
        return canvasToPdf(out, listOf(w.coerceAtLeast(200f) to h.coerceAtLeast(200f))) { c, _ -> draw(c, d, w.coerceAtLeast(200f), h.coerceAtLeast(200f)) }
    }
}

/** DXF drawing viewer with pinch zoom / pan (open-all). DWG goes to the info screen. */
@Composable
fun DxfViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val d by produceState<Result<DxfDrawing>?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { DxfRender.read(f) } } }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    ViewerScaffold(nav, v, v.name, convert = { out -> ViewerConvert.toPdf(ctx, v, out) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(if (dark) Color(0xFF121212) else Color.White)) {
            val dr = d?.getOrNull()
            when {
                d == null -> CenteredProgress()
                dr == null -> ErrorPane(d?.exceptionOrNull()?.message)
                else -> ZoomBox(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) { DxfRender.draw(drawContext.canvas.nativeCanvas, dr, size.width, size.height, dark) }
                }
            }
            Text(stringResource(R.string.oa_dxf_hint), Modifier.align(Alignment.BottomCenter).padding(8.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

// ---------------------------------------------------------------- vCard / iCalendar

private fun fieldLabel(ctx: Context, key: String): String {
    val base = key.substringBefore('|')
    val id = when (base) {
        "tel" -> R.string.oa_f_tel; "email" -> R.string.oa_f_email; "adr" -> R.string.oa_f_adr; "org" -> R.string.oa_f_org; "title" -> R.string.oa_f_title
        "url" -> R.string.oa_f_url; "bday" -> R.string.oa_f_bday; "note" -> R.string.oa_f_note; "nickname" -> R.string.oa_f_nickname; else -> null
    }
    val label = id?.let { ctx.getString(it) } ?: base
    return key.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { "$label ($it)" } ?: label
}

object CardsHtml {
    fun page(ctx: Context, f: File, t: FType): String {
        val text = TextDecode.readFile(f).text
        val body = StringBuilder()
        if (t == FType.VCF) for (c in CardReaders.contacts(text)) {
            body.append("<h2 dir=\"auto\">").append(HtmlBits.esc(c.name.ifBlank { "—" })).append("</h2><table class=\"hdr\">")
            c.fields.forEach { (k, value) -> body.append("<tr><td><b>").append(HtmlBits.esc(fieldLabel(ctx, k))).append("</b></td><td dir=\"auto\">").append(HtmlBits.esc(value).replace("\n", "<br>")).append("</td></tr>") }
            body.append("</table>")
        } else for (e in CardReaders.events(text)) {
            body.append("<h2 dir=\"auto\">").append(HtmlBits.esc(e.summary.ifBlank { "—" })).append("</h2><table class=\"hdr\">")
            listOf(R.string.oa_ev_when to listOf(e.start, e.end).filter { it.isNotBlank() }.joinToString(" → "), R.string.oa_ev_where to e.location, R.string.oa_ev_repeat to e.rule, R.string.oa_f_note to e.description)
                .filter { it.second.isNotBlank() }.forEach { (k, value) -> body.append("<tr><td><b>").append(HtmlBits.esc(ctx.getString(k))).append("</b></td><td dir=\"auto\">").append(HtmlBits.esc(value).replace("\n", "<br>")).append("</td></tr>") }
            body.append("</table>")
        }
        return HtmlBits.page(f.nameWithoutExtension, body.toString(), HtmlBits.rtlDominant(text.take(4000)))
    }
}

/** Contacts (.vcf) and calendar (.ics) viewer with "add to contacts / calendar" (open-all). */
@Composable
fun CardsViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val data by produceState<Result<Pair<List<Contact>, List<CalEvent>>>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                require(f.length() <= 50L * 1024 * 1024) { "file too large" }
                val t = TextDecode.readFile(f).text
                if (v.type == FType.VCF) CardReaders.contacts(t) to emptyList<CalEvent>() else emptyList<Contact>() to CardReaders.events(t)
            }
        }
    }
    ViewerScaffold(nav, v, v.name, convert = { out -> ViewerConvert.toPdf(ctx, v, out) }) { pad ->
        val r = data?.getOrNull()
        when {
            data == null -> Box(Modifier.padding(pad)) { CenteredProgress() }
            r == null -> Box(Modifier.padding(pad)) { ErrorPane(data?.exceptionOrNull()?.message) }
            else -> SelectionContainer(Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(if (v.type == FType.VCF) stringResource(R.string.oa_contacts, r.first.size) else stringResource(R.string.oa_events, r.second.size), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
                    items(r.first) { c ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val photo = remember(c) { c.photo?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
                                    if (photo != null) Image(photo.asImageBitmap(), null, Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                    else Icon(Icons.Default.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.name.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    IconButton(onClick = {
                                        val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                            .putExtra(ContactsContract.Intents.Insert.NAME, c.name)
                                        c.fields.firstOrNull { it.first.startsWith("tel") }?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it.second) }
                                        c.fields.firstOrNull { it.first.startsWith("email") }?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it.second) }
                                        c.fields.firstOrNull { it.first.startsWith("org") }?.let { i.putExtra(ContactsContract.Intents.Insert.COMPANY, it.second) }
                                        runCatching { ctx.startActivity(i) }
                                    }) { Icon(Icons.Default.PersonAdd, stringResource(R.string.oa_add_contact)) }
                                }
                                c.fields.forEach { (k, value) ->
                                    Row(Modifier.padding(top = 4.dp)) {
                                        Text(fieldLabel(ctx, k), Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(value, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface))
                                    }
                                }
                            }
                        }
                    }
                    items(r.second) { e ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (e.kind == "VTODO") Icons.Default.CheckCircle else Icons.Default.Event, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(e.summary.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    if (e.kind == "VEVENT") IconButton(onClick = {
                                        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                                            .putExtra(CalendarContract.Events.TITLE, e.summary).putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
                                            .putExtra(CalendarContract.Events.DESCRIPTION, e.description)
                                        runCatching { ctx.startActivity(i) }
                                    }) { Icon(Icons.Default.EventAvailable, stringResource(R.string.oa_add_event)) }
                                }
                                listOf(R.string.oa_ev_when to listOf(e.start, e.end).filter { it.isNotBlank() }.joinToString(" → "), R.string.oa_ev_where to e.location, R.string.oa_ev_repeat to e.rule, R.string.oa_f_note to e.description)
                                    .filter { it.second.isNotBlank() }.forEach { (k, value) ->
                                        Row(Modifier.padding(top = 4.dp)) {
                                            Text(stringResource(k), Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(value, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface))
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}
