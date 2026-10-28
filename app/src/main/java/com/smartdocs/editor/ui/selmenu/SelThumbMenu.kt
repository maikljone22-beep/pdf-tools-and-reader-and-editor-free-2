package com.smartdocs.editor.ui.selmenu

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.selmenu.Need
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelCtx
import com.smartdocs.editor.core.selmenu.SelEnv
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.core.selmenu.SelTab
import com.smartdocs.editor.core.selmenu.SelThumbs
import com.smartdocs.editor.core.selmenu.Why

/**
 * fix/thumbs-panel: the long-press menu of a page thumbnail (registry context [SelCtx.THUMB]), drawn as a dropdown
 * anchored to the pressed cell. Used by the Organize grid and the reader's thumbnails panel; each screen runs the
 * chosen [SelAction] on [SelThumbs.pages] itself. Disabled items show their reason, like the reader's right-click menu.
 */
@Composable
fun ThumbSelMenu(env: SelEnv, pressed: Int, selected: Set<Int>, pageCount: Int, onDismiss: () -> Unit, onItem: (SelAction, List<Int>) -> Unit) {
    val ctx = LocalContext.current
    val pages = SelThumbs.pages(pressed, selected)
    val items: List<SelItem> = SelThumbs.items(env, pressed, selected, pageCount)
    DropdownMenu(true, onDismiss) {
        Text(if (pages.size == 1) stringResource(R.string.tpn_menu_page, pages[0] + 1) else stringResource(R.string.tpn_menu_pages, pages.size),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 180.dp).padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider()
        items.forEach { item ->
            val reason = item.why
            DropdownMenuItem(
                text = { Text(stringResource(SelBindings.label(item.action)), Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                leadingIcon = { Icon(SelBindings.icon(item.action), null, Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                trailingIcon = if (reason == null) null else ({
                    Text(stringResource(SelBindings.why(reason)), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                }),
                onClick = {
                    // a disabled item says why (as in the reader's right-click menu)
                    if (!item.enabled) Toast.makeText(ctx, reason?.let { ctx.getString(SelBindings.why(it)) } ?: ctx.getString(R.string.sm_unavailable), Toast.LENGTH_SHORT).show()
                    else { onDismiss(); onItem(item.action, pages) }
                },
            )
        }
    }
}

/** Registry environment of the Organize grid: its own permission check; the organizer edits a working copy, never a signed original. */
fun organizeThumbEnv(allows: (DocAction) -> Boolean, aiEnabled: Boolean): SelEnv {
    val blocked = HashMap<Need, Why>()
    for ((need, act) in listOf(Need.COPY to DocAction.COPY, Need.MODIFY to DocAction.MODIFY, Need.PRINT to DocAction.PRINT)) if (!allows(act)) blocked[need] = Why.AUTHOR
    return SelEnv(SelTab.PAGES, SelCtx.THUMB, blocked = blocked, aiEnabled = aiEnabled)
}

/** Registry environment of the reader's thumbnails panel: the reader's guard and signed state ([SelMenuState.env]). */
fun readerThumbEnv(state: com.smartdocs.editor.ui.reader.ReaderState, aiEnabled: Boolean): SelEnv =
    SelMenuState.of(state).env(SelCtx.THUMB, "", aiEnabled)
