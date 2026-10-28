package com.smartdocs.editor.ui.convert6

import java.io.File

/**
 * fc-sync (old plan 16b): files handed from the Files multi-select to the Convert hub. The hub takes them once when it
 * opens; a batch-capable conversion ([com.smartdocs.editor.core.convert6.Conv.multi]) then starts with the selected
 * files that it accepts, without a file picker.
 */
object Cv6Batch {
    @Volatile private var files: List<File> = emptyList()

    fun hand(list: List<File>) { files = list.filter { it.isFile } }

    fun take(): List<File> = files.also { files = emptyList() }
}
