package com.smartdocs.editor.ui.signature

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.TsaClient
import com.smartdocs.editor.core.sign.TsaServer
import com.smartdocs.editor.core.sign.TsaServers
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberImagePicker
import java.io.File

/** Advanced digital-signature options (time-stamp, PAdES, LTV, certification level, paging seal); persisted per device. */
@Stable
class AdvancedSignState(private val prefs: SharedPreferences, private val context: Context? = null) {
    var tsa by mutableStateOf(prefs.getBoolean("tsa", false))
    var tsaUrl by mutableStateOf(prefs.getString("tsa_url", null) ?: TsaClient.DEFAULT_URL)

    /** nitro-quicksign: saved time-stamp servers (built-ins + user servers) and the default one. */
    var tsaServers by mutableStateOf(context?.let { TsaServers.load(it) } ?: TsaServers.builtIns())
        private set
    var tsaDefault by mutableStateOf(context?.let { TsaServers.defaultId(it) })
        private set
    var pades by mutableStateOf(prefs.getBoolean("pades", true))
    var ltv by mutableStateOf(prefs.getBoolean("ltv", false))
    var docTs by mutableStateOf(prefs.getBoolean("doc_ts", false))
    var certify by mutableIntStateOf(0)
    var seal by mutableStateOf(false)
    var sealPath by mutableStateOf<String?>(prefs.getString("seal_path", null))
    var sealSize by mutableFloatStateOf(prefs.getFloat("seal_size", 110f))
    var sealY by mutableFloatStateOf(prefs.getFloat("seal_y", 0.5f))
    var skipWarning by mutableStateOf(prefs.getBoolean("presign_dont_show", false))

    init {
        context?.let { ctx ->
            TsaServers.install(ctx)
            // the single custom URL of older versions becomes a saved server
            val legacy = tsaUrl.trim()
            if (legacy.isNotEmpty() && TsaServers.find(tsaServers, legacy) == null) {
                tsaServers = TsaServers.upsert(tsaServers, TsaServer(TsaServers.newId(), TsaServers.hostOf(legacy), legacy))
                TsaServers.save(ctx, tsaServers)
            }
            tsaServers.firstOrNull { it.id == tsaDefault }?.let { tsaUrl = it.url }
        }
    }

    fun hasTsaPassword(id: String): Boolean = context?.let { TsaServers.hasPassword(it, id) } ?: false

    /** Adds or edits a server. [password]: null keeps the stored one, "" removes it. */
    fun saveTsaServer(server: TsaServer, password: String?) {
        val old = tsaServers.firstOrNull { it.id == server.id }
        tsaServers = TsaServers.upsert(tsaServers, server)
        context?.let { ctx ->
            TsaServers.save(ctx, tsaServers)
            if (server.username.isBlank()) TsaServers.setPassword(ctx, server.id, null)
            else if (password != null) TsaServers.setPassword(ctx, server.id, password)
        }
        if (old != null && TsaServers.normalize(old.url) == TsaServers.normalize(tsaUrl)) tsaUrl = tsaServers.first { it.id == server.id }.url
    }

    fun deleteTsaServer(id: String) {
        val gone = tsaServers.firstOrNull { it.id == id && !it.builtIn } ?: return
        tsaServers = TsaServers.delete(tsaServers, id)
        if (tsaDefault == id) setDefaultTsa(null)
        context?.let { TsaServers.save(it, tsaServers) }
        if (TsaServers.normalize(gone.url) == TsaServers.normalize(tsaUrl)) tsaUrl = (tsaServers.firstOrNull { it.id == tsaDefault } ?: tsaServers.first()).url
    }

    fun setDefaultTsa(id: String?) {
        tsaDefault = id
        context?.let { TsaServers.setDefault(it, id) }
        tsaServers.firstOrNull { it.id == id }?.let { tsaUrl = it.url }
    }

    fun persist() {
        prefs.edit().putBoolean("tsa", tsa).putString("tsa_url", tsaUrl.trim()).putBoolean("pades", pades).putBoolean("ltv", ltv)
            .putBoolean("doc_ts", docTs).putString("seal_path", sealPath).putFloat("seal_size", sealSize).putFloat("seal_y", sealY)
            .putBoolean("presign_dont_show", skipWarning).apply()
    }

    fun options(base: DigitalSignature.SignOptions): DigitalSignature.SignOptions = base.copy(
        tsaUrl = if (tsa && tsaUrl.isNotBlank()) tsaUrl.trim() else null,
        pades = pades,
        certifyLevel = certify,
        ltv = ltv,
        docTimestamp = tsa && docTs,
    )

    /** Decoded seal image (≤ 1200 px) when the paging seal is enabled. */
    fun sealOptions(): DigitalSignature.PagingSealOptions? {
        if (!seal) return null
        val path = sealPath ?: return null
        val bmp = decode(path) ?: return null
        return DigitalSignature.PagingSealOptions(bmp, sealSize, sealY)
    }

    private fun decode(path: String): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1200) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
fun rememberAdvancedSignState(): AdvancedSignState {
    val ctx = LocalContext.current
    return remember { AdvancedSignState(ctx.getSharedPreferences("psf_sign", Context.MODE_PRIVATE), ctx.applicationContext) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedSignOptions(state: AdvancedSignState, signatures: List<SavedSignature>, canCertify: Boolean) {
    var open by remember { mutableStateOf(false) }
    val pickSeal = rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { state.sealPath = it.absolutePath; state.seal = true } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ps_sign_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(if (open) R.string.pc_cd_collapse else R.string.pc_cd_expand)) }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // signature type / certification
                Text(stringResource(R.string.ps_sign_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(state.certify == 0, { state.certify = 0 }, label = { Text(stringResource(R.string.ps_sign_approval)) })
                    listOf(1 to R.string.ps_certify_p1, 2 to R.string.ps_certify_p2, 3 to R.string.ps_certify_p3).forEach { (lvl, res) ->
                        FilterChip(state.certify == lvl, { state.certify = lvl }, enabled = canCertify, label = { Text(stringResource(res)) })
                    }
                }
                if (!canCertify) Text(stringResource(R.string.ps_certify_not_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(certifyDescription(state.certify)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                SwitchLine(stringResource(R.string.ps_sign_pades), stringResource(R.string.ps_sign_pades_desc), state.pades) { state.pades = it }
                SwitchLine(stringResource(R.string.ps_sign_tsa), stringResource(R.string.ps_sign_tsa_desc), state.tsa) { state.tsa = it }
                if (state.tsa) TsaServerPicker(state)
                SwitchLine(stringResource(R.string.ps_sign_ltv), stringResource(R.string.ps_sign_ltv_desc), state.ltv) { state.ltv = it }
                SwitchLine(stringResource(R.string.ps_sign_doc_ts), stringResource(R.string.ps_sign_doc_ts_desc), state.docTs && state.tsa, enabled = state.tsa) { state.docTs = it }
                HorizontalDivider()
                SwitchLine(stringResource(R.string.ps_sign_paging_seal), stringResource(R.string.ps_sign_paging_seal_desc), state.seal) { state.seal = it }
                if (state.seal) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = pickSeal, label = { Text(stringResource(R.string.ps_sign_seal_pick)) }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)) })
                        signatures.forEach { s ->
                            FilterChip(state.sealPath == s.path, { state.sealPath = s.path }, label = { Text(File(s.path).nameWithoutExtension.takeLast(8)) })
                        }
                    }
                    state.sealPath?.let { Text(File(it).name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        ?: Text(stringResource(R.string.ps_sign_seal_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    LabeledSlider(stringResource(R.string.ps_sign_seal_size), state.sealSize, 60f..200f, { state.sealSize = it }) { "${it.toInt()} pt" }
                    LabeledSlider(stringResource(R.string.ps_sign_seal_position), state.sealY, 0.1f..0.9f, { state.sealY = it }) { "${(it * 100).toInt()}%" }
                }
            }
        }
    }
}

fun certifyDescription(level: Int): Int = when (level) {
    1 -> R.string.ps_after_p1
    2 -> R.string.ps_after_p2
    3 -> R.string.ps_after_p3
    else -> R.string.ps_after_approval
}

@Composable
private fun SwitchLine(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
