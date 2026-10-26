package com.smartdocs.editor.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R

/** A row of a checkable result list. */
data class HitRow(val id: String, val page: Int, val snippet: String, val label: String = "")

/** Checkable result list with select all / none. [onOpen] (optional) shows the page. */
@Composable
fun HitList(
    rows: List<HitRow>, checked: Set<String>, onToggle: (String) -> Unit, onSetAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier, onOpen: ((HitRow) -> Unit)? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s5_hits_checked, checked.count { id -> rows.any { it.id == id } }, rows.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSetAll(true) }, enabled = rows.isNotEmpty()) { Text(stringResource(R.string.select_all)) }
            TextButton(onClick = { onSetAll(false) }, enabled = checked.isNotEmpty()) { Text(stringResource(R.string.s5_select_none)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { r ->
                val on = r.id in checked
                ListItem(
                    modifier = Modifier.clickable { onToggle(r.id) },
                    leadingContent = { Checkbox(on, { onToggle(r.id) }) },
                    headlineContent = { Text(r.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Text(stringResource(R.string.pg_page_n, r.page + 1) + if (r.label.isNotEmpty()) " · " + r.label else "", style = MaterialTheme.typography.labelSmall)
                    },
                    trailingContent = if (onOpen == null) null else {
                        { IconButton(onClick = { onOpen(r) }) { Icon(Icons.Default.Visibility, stringResource(R.string.s5_show_page)) } }
                    },
                )
            }
        }
    }
}
