package com.smartdocs.editor.core.pdf

import java.io.File

/**
 * audit 5 E-1 / C-2: which document a tool result was made FROM. The result sheet offers "Save in original" only
 * when the result was recorded as a transformation of exactly the document the tool screen was opened on; a tool
 * that processed another picked file, or made a report / summary / comparison (never recorded), gets no replace.
 * In memory, keyed by the result's canonical path.
 */
object ToolOutputs {
    private val map = LinkedHashMap<String, String>()

    private fun key(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** [result] is [source] transformed (the same document, changed), so it may replace [source]. Returns [result]. */
    @Synchronized fun record(result: File, source: File?): File {
        if (source != null) {
            map[key(result)] = key(source)
            while (map.size > 64) map.remove(map.keys.first())
        }
        return result
    }

    /** The source [result] was made from, when it was recorded. */
    @Synchronized fun sourceOf(result: File): String? = map[key(result)]

    /** True when [result] may replace [target] (it was recorded as a transformation of [target]). Pure apart from the map. */
    fun replaces(result: File, target: File?): Boolean = target != null && sourceOf(result) == key(target)
}
