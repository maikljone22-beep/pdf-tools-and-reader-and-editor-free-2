package com.smartdocs.editor.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.SectionTitle
import kotlinx.coroutines.launch

private data class Preset(val name: String, val endpoint: String, val model: String)

private val presets = listOf(
    Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    Preset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    Preset("Ollama", "http://10.0.2.2:11434/v1", "llama3.2"),
)

/** Endpoint / model / key configuration with provider presets and a connection test. */
@Composable
fun AiSettingsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val s = LocalSettings.current
    val scope = rememberCoroutineScope()
    var endpoint by remember(s.aiEndpoint) { mutableStateOf(s.aiEndpoint) }
    var model by remember(s.aiModel) { mutableStateOf(s.aiModel) }
    var key by remember(s.aiApiKey) { mutableStateOf(s.aiApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val savedMsg = stringResource(R.string.sa_ai_saved)
    val okMsg = stringResource(R.string.sa_ai_test_ok)
    val failFmt = stringResource(R.string.sa_ai_test_fail)

    fun save() = scope.launch {
        app.settings.update { it.copy(aiEndpoint = endpoint.trim().trimEnd('/'), aiModel = model.trim(), aiApiKey = key.trim()) }
        snackbar.showSnackbar(savedMsg)
    }

    fun test() = scope.launch {
        testing = true; testResult = null
        runCatching { AiClient(endpoint.trim().trimEnd('/'), model.trim(), key.trim()).testConnection() }
            .onSuccess { testOk = true; testResult = okMsg + (if (it.isNotBlank()) "\n$it" else "") }
            .onFailure { testOk = false; testResult = failFmt.format(it.message ?: it.toString()) }
        testing = false
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.sa_ai_settings_title), onBack = { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle(stringResource(R.string.sa_ai_presets))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    FilterChip(selected = endpoint.trimEnd('/') == p.endpoint, onClick = { endpoint = p.endpoint; if (model.isBlank() || presets.any { it.model == model }) model = p.model }, label = { Text(p.name) })
                }
            }
            SectionTitle(stringResource(R.string.settings_ai))
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.ai_endpoint)) }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.ai_model)) }, singleLine = true)
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.ai_api_key)) }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                supportingText = { Text(stringResource(R.string.sa_ai_key_hint)) },
            )
            Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = { test() }, enabled = !testing) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(if (testing) R.string.sa_ai_testing else R.string.sa_ai_test))
                }
            }
            testResult?.let {
                Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp), colors = CardDefaults.cardColors(containerColor = if (testOk) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(16.dp))
                }
            }
            // ai6 (AI-18): today's usage (server counter through the proxy, else local / unlimited)
            val usageClient = remember(s.aiEndpoint, s.aiModel, s.aiApiKey) { com.smartdocs.editor.core.ai.AiClient(s) }
            Box(Modifier.padding(16.dp, 8.dp)) { com.smartdocs.editor.ui.ai6.AiUsageLine(usageClient, detailed = true) }
            // ai6 (AI-6): chat skills
            TextButton(onClick = { nav.navigate(com.smartdocs.editor.ui.Routes.tool("ai_skills")) }, Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.ai6h_skills))
            }
        }
    }
}
