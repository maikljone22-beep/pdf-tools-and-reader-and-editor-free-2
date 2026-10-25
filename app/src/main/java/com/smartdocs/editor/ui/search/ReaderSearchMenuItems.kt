package com.smartdocs.editor.ui.search

import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.search.NormOptions
import com.smartdocs.editor.core.search.SearchPrefs

/** Arabic search switches for the reader's search-bar menu. [onChanged] re-runs the search. */
@Composable
fun ReaderSearchMenuItems(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var o by remember { mutableStateOf(SearchPrefs.load(ctx)) }
    fun set(n: NormOptions) { o = n; SearchPrefs.save(ctx, n); onChanged() }
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.sr_ignore_diacritics)) }, leadingIcon = { Checkbox(o.ignoreDiacritics, null) },
        onClick = { set(o.copy(ignoreDiacritics = !o.ignoreDiacritics)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.sr_unify_letters)) }, leadingIcon = { Checkbox(o.unifyLetters, null) },
        onClick = { set(o.copy(unifyLetters = !o.unifyLetters)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.sr_ta_marbuta)) }, leadingIcon = { Checkbox(o.taMarbuta, null) },
        onClick = { set(o.copy(taMarbuta = !o.taMarbuta)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.sr_unify_digits)) }, leadingIcon = { Checkbox(o.unifyDigits, null) },
        onClick = { set(o.copy(unifyDigits = !o.unifyDigits)) })
}
