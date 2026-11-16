package com.smartdocs.editor.ui.updates

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.R
import com.smartdocs.editor.core.updates.*
import com.smartdocs.editor.ui.common.AppTopBar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

const val UPDATES_ROUTE = "app_updates"
enum class UpdatePhase { IDLE, CANCELLING, CHECKING, AVAILABLE, CURRENT, UNPUBLISHED, DOWNLOADING, READY, VERIFYING, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val offer: UpdateOffer? = null, val file: File? = null,
                       val received: Long = 0, val total: Long = 0, val problem: UpdateProblem? = null)

class UpdatesViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    val url get() = UpdatePreferences.url(getApplication())
    private fun failure(e: Throwable) { mutable.update { it.copy(phase = UpdatePhase.ERROR, problem = (e as? UpdateException)?.problem ?: UpdateProblem.DOWNLOAD) } }
    fun check(url: String) {
        if (mutable.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.CANCELLING)) return
        task = viewModelScope.launch {
            mutable.value = UpdateState(UpdatePhase.CHECKING)
            try {
                UpdatePreferences.saveUrl(getApplication(), url)
                val offer = UpdateService.check(getApplication(), url)
                mutable.value = UpdateState(if (offer == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, offer)
            } catch (e: CancellationException) { throw e }
            catch (_: UpdateChannelUnavailable) { mutable.value = UpdateState(UpdatePhase.UNPUBLISHED) }
            catch (e: Exception) { failure(e) }
        }
    }
    fun download() {
        val offer = mutable.value.offer ?: return
        if (mutable.value.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        task = viewModelScope.launch {
            mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, received = 0, total = offer.artifact.size, problem = null) }
            try {
                val file = UpdateService.download(getApplication(), offer) { received, total -> mutable.update { it.copy(received = received, total = total) } }
                mutable.update { it.copy(phase = UpdatePhase.READY, file = file) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
        }
    }
    fun install(onReady: (File) -> Unit) {
        val current = mutable.value
        val offer = current.offer ?: return
        val file = current.file ?: return
        if (current.phase != UpdatePhase.READY) return
        task = viewModelScope.launch {
            mutable.update { it.copy(phase = UpdatePhase.VERIFYING, problem = null) }
            try {
                // Verify again: the file may have changed since the download completed.
                UpdateService.verify(getApplication(), file, offer)
                mutable.update { it.copy(phase = UpdatePhase.READY) }
                onReady(file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
        }
    }
    fun installFailed() { failure(UpdateException(UpdateProblem.INSTALL)) }
    fun cancel() {
        val previous = task
        if (previous?.isActive != true) return
        mutable.update { it.copy(phase = UpdatePhase.CANCELLING) }
        previous.cancel()
        viewModelScope.launch {
            previous.join()
            mutable.update { it.copy(phase = when { it.file != null -> UpdatePhase.READY; it.offer != null -> UpdatePhase.AVAILABLE; else -> UpdatePhase.IDLE }) }
        }
    }
}

@Composable
private fun problemText(problem: UpdateProblem?): String = stringResource(when (problem) {
    UpdateProblem.INVALID_FEED -> R.string.update_invalid_feed
    UpdateProblem.UNSAFE_URL -> R.string.update_https
    UpdateProblem.WRONG_PACKAGE -> R.string.update_wrong_package
    UpdateProblem.INCOMPATIBLE -> R.string.update_incompatible
    UpdateProblem.CHECKSUM -> R.string.update_checksum
    UpdateProblem.SIGNATURE -> R.string.update_signature
    UpdateProblem.NOT_NEWER -> R.string.update_bad_version
    UpdateProblem.INSTALL -> R.string.update_install_failed
    else -> R.string.update_connection_failed
})

@Composable
fun UpdatesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val vm: UpdatesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.state.collectAsState()
    var source by rememberSaveable { mutableStateOf(vm.url) }
    var editSource by rememberSaveable { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(UpdatePreferences.automatic(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var allowedToInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowedToInstall = context.packageManager.canRequestPackageInstalls()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var permissionError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.phase == UpdatePhase.IDLE) vm.check(source) }
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.CANCELLING)
    Scaffold(topBar = { AppTopBar(stringResource(R.string.update_title), onBack = { vm.cancel(); nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.titleMedium)
            Row { Text(stringResource(R.string.update_auto), Modifier.weight(1f)); Switch(automatic, { automatic = it; UpdatePreferences.setAutomatic(context, it) }) }
            when (state.phase) {
                UpdatePhase.CANCELLING -> { Text(stringResource(R.string.update_cancelling)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                UpdatePhase.CHECKING -> { Text(stringResource(R.string.update_checking)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                UpdatePhase.CURRENT -> Text(stringResource(R.string.update_current))
                UpdatePhase.UNPUBLISHED -> Text(stringResource(R.string.update_unpublished))
                UpdatePhase.ERROR -> Text(problemText(state.problem), color = MaterialTheme.colorScheme.error)
                UpdatePhase.VERIFYING -> { Text(stringResource(R.string.update_verifying)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> Unit
            }
            state.offer?.let { offer ->
                Text(stringResource(R.string.update_available, offer.versionName), style = MaterialTheme.typography.titleLarge)
                if (offer.notes.isNotBlank()) Text(offer.notes)
                Text(stringResource(R.string.update_size, (offer.artifact.size / 1024 / 1024).toInt()))
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (state.received.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_progress, (100 * state.received / state.total.coerceAtLeast(1)).toInt()))
                }
                if (state.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) Button(onClick = { vm.download() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_download)) }
                if (state.phase == UpdatePhase.READY) {
                    Text(stringResource(R.string.update_verified))
                    val canInstall = allowedToInstall
                    Button(onClick = {
                        permissionError = false
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                            catch (_: Exception) { permissionError = true }
                        } else vm.install { file ->
                            try {
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) { vm.installFailed() }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (canInstall) R.string.update_install else R.string.update_allow_install)) }
                    Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (permissionError) Text(stringResource(R.string.update_install_failed), color = MaterialTheme.colorScheme.error)
            if (busy) OutlinedButton(onClick = { vm.cancel() }, enabled = state.phase != UpdatePhase.CANCELLING) { Text(stringResource(R.string.cancel)) }
            else OutlinedButton(onClick = { vm.check(source) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_check)) }
            TextButton(onClick = { editSource = !editSource }, enabled = !busy) { Text(stringResource(R.string.update_source)) }
            if (editSource) {
                OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.update_source)) }, enabled = !busy)
                TextButton(onClick = { source = UpdatePreferences.defaultUrl() }, enabled = !busy) { Text(stringResource(R.string.update_default_source)) }
            }
        }
    }
}

/** A nonblocking daily check; network failures never interrupt document work. */
@Composable
fun UpdatesPromptHost(nav: NavHostController) {
    val context = LocalContext.current.applicationContext
    var offer by remember { mutableStateOf<UpdateOffer?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (UpdatePreferences.shouldCheck(context, now)) {
            UpdatePreferences.markAttempt(context, now)
            try { offer = UpdateService.check(context, UpdatePreferences.url(context)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    val update = offer
    if (update != null) AlertDialog(onDismissRequest = { offer = null }, title = { Text(stringResource(R.string.update_available, update.versionName)) },
        text = { Text(stringResource(R.string.update_prompt)) },
        confirmButton = { TextButton(onClick = { offer = null; nav.navigate(UPDATES_ROUTE) }) { Text(stringResource(R.string.update_title)) } },
        dismissButton = { TextButton(onClick = { offer = null }) { Text(stringResource(R.string.update_later)) } })
}
