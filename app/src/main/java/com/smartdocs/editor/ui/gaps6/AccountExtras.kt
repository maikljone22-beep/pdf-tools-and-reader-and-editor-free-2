package com.smartdocs.editor.ui.gaps6

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.gaps6.ActivatedDevices
import com.smartdocs.editor.core.gaps6.GoogleAccount
import com.smartdocs.editor.core.gaps6.Referral
import com.smartdocs.editor.core.gaps6.ReferralStore
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.launch

/** "Sign in with Google" card for the Account tab (name, e-mail, photo; sign out). */
@Composable
fun GoogleAccountCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by GoogleAccount.profile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { GoogleAccount.load(ctx) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.g6_google_failed)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            runCatching { GoogleAccount.finish(ctx, res.data) }.onFailure { error = failed }
            busy = false
        }
    }
    fun signIn() {
        busy = true; error = null
        scope.launch {
            runCatching { GoogleAccount.begin(ctx) }
                .onSuccess { step ->
                    when (step) {
                        is GoogleAccount.Step.SignedIn -> busy = false
                        is GoogleAccount.Step.NeedsUi -> runCatching { consent.launch(IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()) }
                            .onFailure { busy = false; error = failed }
                    }
                }
                .onFailure { busy = false; error = failed + (it.message?.let { m -> "\n$m" } ?: "") }
        }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            val p = profile
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    if (p?.photoUrl != null) AsyncImage(model = p.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else if (p != null) Text(p.name.firstOrNull()?.uppercase() ?: "G", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    else Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (p == null) {
                        Text(stringResource(R.string.g6_google_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.g6_google_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(p.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else if (p == null) Button(onClick = { signIn() }, shape = PillShape) { Text(stringResource(R.string.g6_google_sign_in)) }
                else TextButton(onClick = { scope.launch { GoogleAccount.signOut(ctx) } }) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.g6_google_sign_out))
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Activated devices (local list kept with the activation-code record; see core/gaps6/AccountLocal.kt). */
@Composable
fun ActivatedDevicesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf(ActivatedDevices.load(ctx)) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.g6_devices_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.g6_devices_note), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (devices.isEmpty()) {
                Text(stringResource(R.string.g6_devices_empty), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            } else GroupCard(null) {
                devices.forEachIndexed { i, d ->
                    if (i > 0) GroupDivider()
                    GroupRow(
                        d.name + if (d.thisDevice) " · " + stringResource(R.string.g6_this_device) else "",
                        stringResource(R.string.g6_device_line, d.plan.ifBlank { "-" }, d.codeTail, DocumentRepository.formatDate(d.activatedAt)),
                        icon = if (d.thisDevice) Icons.Default.Smartphone else Icons.Default.Devices,
                        trailing = { IconButton(onClick = { ActivatedDevices.remove(ctx, d.id); devices = ActivatedDevices.load(ctx) }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) } },
                    )
                }
            }
        }
    }
}

/** Invite friends: share the referral link + local counter. */
@Composable
fun InviteScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val code = remember { ReferralStore.ownCode(ctx) }
    val link = Referral.link(code)
    var sent by remember { mutableIntStateOf(ReferralStore.invitesSent(ctx)) }
    var redeemed by remember { mutableStateOf(ReferralStore.redeemed(ctx)) }
    var friend by remember { mutableStateOf("") }
    var friendError by remember { mutableStateOf(false) }
    val shareText = stringResource(R.string.g6_invite_message, link)
    Scaffold(topBar = { AppTopBar(stringResource(R.string.g6_invite_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.CardGiftcard, null, Modifier.size(56.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.g6_invite_sub), style = MaterialTheme.typography.bodyMedium)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.g6_your_code), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace) }
                    SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }
                    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }.onSuccess { ReferralStore.recordInvite(ctx); sent = ReferralStore.invitesSent(ctx) }
                }, shape = PillShape) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.g6_invite_share)) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)) }, shape = PillShape) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.g6_copy_link)) }
            }
            GroupCard(stringResource(R.string.g6_referrals)) {
                GroupRow(stringResource(R.string.g6_invites_sent), trailing = { Text("$sent", style = MaterialTheme.typography.titleMedium) }, icon = Icons.Default.Send)
                GroupDivider()
                GroupRow(stringResource(R.string.g6_friends_joined), stringResource(R.string.g6_needs_server), icon = Icons.Default.Group, trailing = { Text("—", style = MaterialTheme.typography.titleMedium) })
            }
            Text(stringResource(R.string.g6_redeem_title), style = MaterialTheme.typography.titleSmall)
            val r = redeemed
            if (r != null) Text(stringResource(R.string.g6_redeemed, r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            else {
                OutlinedTextField(friend, { friend = it; friendError = false }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    label = { Text(stringResource(R.string.g6_friend_code)) }, isError = friendError,
                    supportingText = { if (friendError) Text(stringResource(R.string.g6_code_invalid)) })
                Button(onClick = { if (ReferralStore.redeem(ctx, friend)) redeemed = ReferralStore.redeemed(ctx) else friendError = true },
                    enabled = friend.isNotBlank(), shape = PillShape) { Text(stringResource(R.string.g6_redeem)) }
            }
        }
    }
}
