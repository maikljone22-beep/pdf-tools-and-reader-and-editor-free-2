package com.smartdocs.editor.ui.reader.annot

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.NavOrder
import com.smartdocs.editor.core.annot.NavOrderPrefs

/** §41 pencil preferences: hide the pointer while a stylus draws with the pencil, and pencil strokes over text become highlights. */
object PencilPrefs {
    private const val FILE = "comment_prefs"
    var hideStylusCursor by mutableStateOf(true); private set
    var highlightOverText by mutableStateOf(false); private set
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        hideStylusCursor = p.getBoolean("pencil_hide_cursor", true)
        highlightOverText = p.getBoolean("pencil_highlight_text", false)
        loaded = true
    }

    fun setHideStylusCursor(context: Context, v: Boolean) { hideStylusCursor = v; edit(context) { putBoolean("pencil_hide_cursor", v) } }
    fun setHighlightOverText(context: Context, v: Boolean) { highlightOverText = v; edit(context) { putBoolean("pencil_highlight_text", v) } }

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply(block).apply()

    /** Load every comments-rest preference (pencil, §37 navigation order) once. */
    fun loadAll(context: Context) { load(context); NavOrderPrefs.load(context) }
}

object PencilLogic {
    /**
     * The band a pencil stroke covers when it looks like marking text: a long, thin stroke (along x or y, for rotated
     * pages) of at least [minLen] points. Returns left, bottom, right, top in PDF points (padded by [pad]), else null.
     */
    fun textBand(pts: List<Pair<Float, Float>>, minLen: Float = 12f, pad: Float = 2f): FloatArray? {
        if (pts.size < 2) return null
        val l = pts.minOf { it.first }; val r = pts.maxOf { it.first }
        val b = pts.minOf { it.second }; val t = pts.maxOf { it.second }
        val w = r - l; val h = t - b
        val long = maxOf(w, h); val thin = minOf(w, h)
        if (long < minLen || thin * 3f > long) return null
        return floatArrayOf(l - pad, b - pad, r + pad, t + pad)
    }
}

/** Settings › Comments rows of comments-rest: navigation order (§37) and the pencil options (§41). */
@Composable
fun CommentsRestSettings() {
    val ctx = LocalContext.current
    remember { PencilPrefs.loadAll(ctx) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.cmr_nav_order), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.cmr_nav_order_sub), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(NavOrder.STRUCTURE to R.string.cmr_nav_structure, NavOrder.ROWS to R.string.cmr_nav_rows, NavOrder.COLUMNS to R.string.cmr_nav_columns).forEach { (o, l) ->
                FilterChip(NavOrderPrefs.order == o, { NavOrderPrefs.set(ctx, o) }, label = { Text(stringResource(l)) })
            }
        }
        SwitchRow(stringResource(R.string.cmr_pencil_hide_cursor), PencilPrefs.hideStylusCursor) { PencilPrefs.setHideStylusCursor(ctx, it) }
        SwitchRow(stringResource(R.string.cmr_pencil_highlight), PencilPrefs.highlightOverText, stringResource(R.string.cmr_pencil_highlight_sub)) { PencilPrefs.setHighlightOverText(ctx, it) }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}
