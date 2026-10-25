package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.FillProfileField
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.launch

/** Default profile keys with their Arabic labels. */
val defaultProfileKeys: List<Pair<String, Int>> = listOf(
    "full_name" to R.string.sa_key_full_name,
    "first_name" to R.string.sa_key_first_name,
    "last_name" to R.string.sa_key_last_name,
    "email" to R.string.sa_key_email,
    "phone" to R.string.sa_key_phone,
    "address" to R.string.sa_key_address,
    "city" to R.string.sa_key_city,
    "country" to R.string.sa_key_country,
    "birth_date" to R.string.sa_key_birth_date,
    "id_number" to R.string.sa_key_id_number,
)

/** Editable "my data" list used to fill forms quickly: add / edit / delete / reorder. */
@Composable
fun FillProfileScreen(nav: NavHostController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val fields by app.db.fillProfile().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<FillProfileField?>(null) }
    var adding by remember { mutableStateOf(false) }
    val defaultLabels = defaultProfileKeys.map { (k, r) -> k to stringResource(r) }

    fun addDefaults() = scope.launch {
        val existing = fields.map { it.key }.toSet()
        var order = (fields.maxOfOrNull { it.order } ?: -1) + 1
        defaultLabels.forEach { (k, label) -> if (k !in existing) app.db.fillProfile().upsert(FillProfileField(k, label, "", order++)) }
    }

    fun swap(a: FillProfileField, b: FillProfileField) = scope.launch {
        app.db.fillProfile().upsert(a.copy(order = b.order)); app.db.fillProfile().upsert(b.copy(order = a.order))
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.sa_profile_title), onBack = { nav.popBackStack() }) {
                TextButton(onClick = { addDefaults() }) { Text(stringResource(R.string.sa_profile_defaults)) }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.sa_profile_add)) } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(stringResource(R.string.sa_profile_hint), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fields.isEmpty()) {
                EmptyState(Icons.Default.Person, stringResource(R.string.sa_profile_empty), action = {
                    Button(onClick = { addDefaults() }) { Text(stringResource(R.string.sa_profile_defaults)) }
                })
            } else LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(fields, key = { _, f -> f.key }) { i, f ->
                    Card {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(f.value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
                            }
                            IconButton(onClick = { if (i > 0) swap(f, fields[i - 1]) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.sa_move_up)) }
                            IconButton(onClick = { if (i < fields.size - 1) swap(f, fields[i + 1]) }, enabled = i < fields.size - 1) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.sa_move_down)) }
                            IconButton(onClick = { editing = f }) { Icon(Icons.Default.Edit, null) }
                            IconButton(onClick = { scope.launch { app.db.fillProfile().delete(f) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        val cur = editing
        FieldDialog(
            initialLabel = cur?.label ?: "", initialValue = cur?.value ?: "",
            onDismiss = { adding = false; editing = null },
            onSave = { label, value ->
                scope.launch {
                    if (cur != null) app.db.fillProfile().upsert(cur.copy(label = label, value = value))
                    else {
                        val base = "custom_" + System.currentTimeMillis()
                        app.db.fillProfile().upsert(FillProfileField(base, label, value, (fields.maxOfOrNull { it.order } ?: -1) + 1))
                    }
                }
                adding = false; editing = null
            },
        )
    }
}

@Composable
private fun FieldDialog(initialLabel: String, initialValue: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var label by remember { mutableStateOf(initialLabel) }
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initialLabel.isBlank()) R.string.sa_profile_add else R.string.rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.sa_profile_label)) }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.sa_profile_value)) })
            }
        },
        confirmButton = { TextButton(onClick = { if (label.isNotBlank()) onSave(label.trim(), value.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
