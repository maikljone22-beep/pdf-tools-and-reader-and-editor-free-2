package com.smartdocs.editor.ui.share5

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.core.share5.RecipientBook

/**
 * fc-sync: an address field (To / Cc) with suggestions from [RecipientBook] under it; tapping one completes the address
 * being typed and adds a separator. Several addresses are separated by commas.
 */
@Composable
internal fun RecipientField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    val suggestions = remember(field.text) { RecipientBook.suggest(ctx, field.text) }
    Column(modifier) {
        OutlinedTextField(field, { field = it; onChange(it.text) }, Modifier.fillMaxWidth(), label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { s ->
                SuggestionChip(onClick = {
                    val t = RecipientBook.complete(field.text, s)
                    field = TextFieldValue(t, TextRange(t.length)); onChange(t)
                }, label = { Text(s) }, icon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
            }
        }
    }
}
