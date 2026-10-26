package com.smartdocs.editor.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.billing.ActivationCodes
import com.smartdocs.editor.core.billing.BillingManager
import com.smartdocs.editor.core.billing.Pro
import com.smartdocs.editor.core.billing.ProPlan
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.IconTile
import kotlinx.coroutines.launch

private data class Benefit(val icon: ImageVector, val title: Int, val text: Int)

private val benefits = listOf(
    Benefit(Icons.Default.AutoAwesome, R.string.ac_ben_ai_t, R.string.ac_ben_ai_b),
    Benefit(Icons.Default.Sync, R.string.ac_ben_sync_t, R.string.ac_ben_sync_b),
    Benefit(Icons.Default.RateReview, R.string.ac_ben_review_t, R.string.ac_ben_review_b),
    Benefit(Icons.Default.DocumentScanner, R.string.ac_ben_ocr_t, R.string.ac_ben_ocr_b),
    Benefit(Icons.Default.Favorite, R.string.ac_ben_support_t, R.string.ac_ben_support_b),
)

internal fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    return c as? Activity
}

/** Route "paywall": Pro benefits, Play subscription plans, restore, and the activation-code entry. */
@Composable
fun PaywallScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val billing = remember { BillingManager.get(ctx) }
    val scope = rememberCoroutineScope()
    val plans by billing.plans.collectAsStateWithLifecycle()
    val event by billing.events.collectAsStateWithLifecycle()
    val active by Pro.isActiveFlow.collectAsStateWithLifecycle()
    val ent by Pro.state.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    val tiles = Brand.tiles
    val msgRestored = stringResource(R.string.ac_restored)
    val msgNothing = stringResource(R.string.ac_nothing_to_restore)

    LaunchedEffect(Unit) {
        Pro.load(ctx)
        runCatching { billing.loadPlans() }
        loading = false
    }
    LaunchedEffect(plans) { if (selected == null) selected = plans.firstOrNull { it.productId.contains("year") }?.productId ?: plans.firstOrNull()?.productId }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_pro_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile(Icons.Default.WorkspacePremium, tiles.amber, size = 80.dp, iconSize = 46.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(if (active) R.string.ac_pro_active else R.string.ac_pro_headline), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (active) {
                    val until = if (ent.source == Pro.Source.CODE && ent.expires > 0) stringResource(R.string.ac_until, DocumentRepository.formatDate(ent.expires)) else ""
                    Text(stringResource(if (ent.source == Pro.Source.CODE) R.string.ac_pro_via_code else R.string.ac_pro_via_play) + " " + until,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            if (!Pro.ENFORCE) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Celebration, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ac_pro_all_free_long), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.height(12.dp))
            benefits.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    IconTile(b.icon, tiles.blue, size = 40.dp, iconSize = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Column { Text(stringResource(b.title), style = MaterialTheme.typography.titleSmall); Text(stringResource(b.text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                plans.isEmpty() -> Text(stringResource(R.string.ac_plans_unavailable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                else -> plans.forEach { p -> PlanCard(p, selected == p.productId) { selected = p.productId }; Spacer(Modifier.height(10.dp)) }
            }
            if (plans.isNotEmpty() && !active) {
                Button(
                    onClick = { val p = plans.firstOrNull { it.productId == selected } ?: return@Button; ctx.findActivity()?.let { billing.purchase(it, p) } },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = PillShape,
                ) { Text(stringResource(if (plans.firstOrNull { it.productId == selected }?.hasTrial == true) R.string.ac_start_trial else R.string.ac_subscribe), style = MaterialTheme.typography.titleMedium) }
                Text(stringResource(R.string.ac_renew_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { scope.launch { val ok = runCatching { billing.restore() }.getOrDefault(false); Toast.makeText(ctx, if (ok) msgRestored else msgNothing, Toast.LENGTH_SHORT).show() } }) {
                    Icon(Icons.Default.Restore, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_restore))
                }
                if (ActivationCodes.enabled) TextButton(onClick = { nav.navigate(AcctRoutes.ACTIVATION) }) {
                    Icon(Icons.Default.Key, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_have_code))
                }
            }
        }
    }

    event?.let { e ->
        val text = when (e) {
            BillingManager.PurchaseEvent.Success -> stringResource(R.string.ac_purchase_ok)
            BillingManager.PurchaseEvent.Pending -> stringResource(R.string.ac_purchase_pending)
            BillingManager.PurchaseEvent.Cancelled -> null
            is BillingManager.PurchaseEvent.Error -> stringResource(R.string.ac_purchase_failed, e.code)
        }
        LaunchedEffect(e) { if (text != null) Toast.makeText(ctx, text, Toast.LENGTH_LONG).show(); billing.consumeEvent() }
    }
}

@Composable
private fun PlanCard(p: ProPlan, selected: Boolean, onClick: () -> Unit) {
    val period = when (p.period) { "P1M" -> stringResource(R.string.ac_per_month); "P1Y" -> stringResource(R.string.ac_per_year); else -> p.period }
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                if (p.hasTrial) Text(stringResource(R.string.ac_trial_included), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("${p.price} / $period", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Route "activation": enter / remove an offline activation code. */
@Composable
fun ActivationScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val ent by Pro.state.collectAsStateWithLifecycle()
    val active by Pro.isActiveFlow.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ActivationCodes.Result?>(null) }
    LaunchedEffect(Unit) { Pro.load(ctx) }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_activation), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!ActivationCodes.enabled) {
                Text(stringResource(R.string.ac_activation_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else ActivationForm(ent, active, code, { code = it; result = null }, result, { result = it })
        }
    }
}

@Composable
private fun ActivationForm(ent: Pro.Entitlement, active: Boolean, code: String, onCode: (String) -> Unit, result: ActivationCodes.Result?, onResult: (ActivationCodes.Result?) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (active && ent.source == Pro.Source.CODE) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ac_pro_active), style = MaterialTheme.typography.titleMedium)
                        Text(if (ent.expires > 0) stringResource(R.string.ac_until, DocumentRepository.formatDate(ent.expires)) else stringResource(R.string.ac_lifetime), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { Pro.deactivateCode(ctx); onResult(null) }) { Text(stringResource(R.string.ac_deactivate)) }
                    }
                }
            }
            Text(stringResource(R.string.ac_activation_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                code, { onCode(it.uppercase().take(32)) },
                label = { Text(stringResource(R.string.ac_code_label)) }, placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            )
            Button(onClick = {
                val r = ActivationCodes.activate(ctx, code)
                if (r is ActivationCodes.Result.Valid) com.smartdocs.editor.core.gaps6.ActivatedDevices.recordActivation(ctx, code, r.plan.name.lowercase())   // gaps6
                onResult(r)
            }, enabled = code.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = PillShape) {
                Text(stringResource(R.string.ac_activate))
            }
            result?.let { r ->
                val (msg, ok) = when (r) {
                    is ActivationCodes.Result.Valid -> stringResource(R.string.ac_activation_ok) to true
                    ActivationCodes.Result.Expired -> stringResource(R.string.ac_code_expired) to false
                    ActivationCodes.Result.Disabled -> stringResource(R.string.ac_activation_disabled) to false
                    else -> stringResource(R.string.ac_code_invalid) to false
                }
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.ac_code_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
