package com.smartdocs.editor.ui.account

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.billing.Pro
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.review.AcctPrefs
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.Primary
import com.smartdocs.editor.core.theme.Teal
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Account tab. The account is local (name + optional photo); Google is used only to authorize Drive access.
 * Shows the Pro status, cloud/sharing entry points and support links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val sigs by app.db.signatures().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val recents by app.db.docs().recents().collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by app.db.docs().favorites().collectAsStateWithLifecycle(initialValue = emptyList())
    val pro by Pro.state.collectAsStateWithLifecycle()
    val proActive by Pro.isActiveFlow.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf(AcctPrefs.profilePhoto(ctx)?.takeIf { File(it).exists() }) }
    val tiles = Brand.tiles
    LaunchedEffect(Unit) { Pro.load(ctx) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val dest = File(ctx.filesDir, "profile_${System.currentTimeMillis()}.jpg")
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false) }
            if (ok) { photo?.let { File(it).delete() }; AcctPrefs.setProfilePhoto(ctx, dest.absolutePath); photo = dest.absolutePath }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_account), style = MaterialTheme.typography.headlineSmall) },
            actions = { IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // ---- header card ----
            val name = settings.authorName.ifBlank { stringResource(R.string.ds_local_profile) }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Primary, Teal))).padding(20.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))
                                .clickable { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            val p = photo
                            val initial = settings.authorName.trim().firstOrNull()?.uppercase()
                            when {
                                p != null -> AsyncImage(model = File(p), contentDescription = stringResource(R.string.ac_photo), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                initial != null -> Text(initial, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                                else -> Icon(Icons.Default.AddAPhoto, stringResource(R.string.ac_photo), Modifier.size(28.dp), tint = Color.White)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                            Text(stringResource(R.string.ds_offline_note), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                        }
                        FilledIconButton(onClick = { editName = true }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.settings_author))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat(recents.size, stringResource(R.string.ds_stat_files), Modifier.weight(1f))
                        Stat(favorites.size, stringResource(R.string.favorites), Modifier.weight(1f))
                        Stat(sigs.size, stringResource(R.string.ds_stat_signatures), Modifier.weight(1f))
                    }
                }
            }

            // ---- Pro card ----
            Surface(
                onClick = { nav.navigate(AcctRoutes.PAYWALL) },
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WorkspacePremium, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (proActive) R.string.ac_pro_active else R.string.ac_pro_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            when {
                                proActive && pro.source == Pro.Source.CODE -> stringResource(R.string.ac_pro_via_code)
                                proActive -> stringResource(R.string.ac_pro_via_play)
                                !Pro.ENFORCE -> stringResource(R.string.ac_pro_all_free)
                                else -> stringResource(R.string.ac_pro_upgrade_sub)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                    }
                    if (!proActive) Surface(shape = PillShape, color = MaterialTheme.colorScheme.primary) {
                        Text(stringResource(R.string.ac_upgrade), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }

            GroupCard(stringResource(R.string.ds_my_content)) {
                GroupRow(stringResource(R.string.sig_saved), icon = Icons.Default.Draw, tile = tiles.blue, onClick = { nav.navigate(Routes.tool("signatures")) })
                GroupDivider()
                GroupRow(stringResource(R.string.fill_sign_profile), icon = Icons.Default.ContactPage, tile = tiles.teal, onClick = { nav.navigate(Routes.tool("fill_profile")) })
                GroupDivider()
                GroupRow(stringResource(R.string.stamp_custom), icon = Icons.Default.Approval, tile = tiles.amber, onClick = { nav.navigate(Routes.tool("stamps")) })
            }
            GroupCard(stringResource(R.string.ac_cloud_sharing)) {
                val drive = remember { GoogleDrive.get(ctx) }
                GroupRow("Google Drive", if (drive.isConnected()) drive.accountEmail ?: stringResource(R.string.ac_connected) else stringResource(R.string.ac_not_connected),
                    icon = Icons.Default.AddToDrive, tile = tiles.green, onClick = { nav.navigate(Routes.CLOUD) })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_sync_title), stringResource(R.string.ac_sync_sub), icon = Icons.Default.Sync, tile = tiles.blue, onClick = { nav.navigate(AcctRoutes.SYNC) })
                GroupDivider()
                val reviews by CollabStore.reviews.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { CollabStore.load(ctx) }
                GroupRow(stringResource(R.string.ac_review_title),
                    if (CollabConfig.enabled) stringResource(R.string.ac_review_count, reviews.size) else stringResource(R.string.ac_not_configured),
                    icon = Icons.Default.RateReview, tile = tiles.purple, onClick = if (CollabConfig.enabled) ({ nav.navigate(AcctRoutes.review()) }) else null)
                GroupDivider()
                GroupRow(stringResource(R.string.wifi_transfer), stringResource(R.string.ac_wifi_sub), icon = Icons.Default.Wifi, tile = tiles.teal, onClick = { nav.navigate(AcctRoutes.WIFI) })
            }
            GroupCard(stringResource(R.string.ds_app_section)) {
                GroupRow(stringResource(R.string.settings), icon = Icons.Default.Settings, tile = tiles.purple, onClick = { nav.navigate(Routes.SETTINGS) })
                GroupDivider()
                GroupRow(stringResource(R.string.settings_ai), icon = Icons.Default.AutoAwesome, tile = tiles.rose, onClick = { nav.navigate(Routes.tool("ai_settings")) })
            }
            GroupCard(stringResource(R.string.ds_support_section)) {
                GroupRow(stringResource(R.string.ac_help_title), icon = Icons.Default.HelpOutline, tile = tiles.green, onClick = { nav.navigate(AcctRoutes.HELP) })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_privacy_policy), icon = Icons.Default.Policy, tile = tiles.teal, onClick = { nav.navigate(AcctRoutes.PRIVACY) })
                GroupDivider()
                GroupRow(stringResource(R.string.settings_about), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), icon = Icons.Default.Info, tile = tiles.blue)
            }
            if (photo != null) TextButton(onClick = { photo?.let { File(it).delete() }; AcctPrefs.setProfilePhoto(ctx, null); photo = null }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Default.HideImage, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_remove_photo))
            }
        }
    }
    if (editName) {
        var name by remember { mutableStateOf(settings.authorName) }
        AlertDialog(onDismissRequest = { editName = false }, title = { Text(stringResource(R.string.settings_author)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = { TextButton(onClick = {
                val n = name.trim()
                scope.launch { app.settings.update { it.copy(authorName = n) } }
                CollabStore.setAuthorName(ctx, n)
                editName = false
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.16f)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
        }
    }
}
