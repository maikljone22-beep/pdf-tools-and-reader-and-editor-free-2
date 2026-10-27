package com.smartdocs.editor.core.files

import com.smartdocs.editor.core.data.BookmarkDao
import com.smartdocs.editor.core.data.DocDao
import com.smartdocs.editor.core.data.TagDao
import java.io.File

/**
 * What follows a document when it is renamed, moved or deleted in Files (audit DI-22 / DI-03): the Room rows
 * keyed by its `file://` URI (recents with last page and favourite, tags, reading bookmarks). The origin keys and
 * the search index are moved by [DocumentRepository], which runs this inside one Room transaction.
 * [uriOf] is `Uri.fromFile(f).toString()` in the app (a plain function so this is testable on the JVM).
 */
internal class DocMetadata(
    private val docs: DocDao,
    private val tags: TagDao,
    private val bookmarks: BookmarkDao,
    private val uriOf: (File) -> String,
) {
    /** Re-key every (old, new) pair of [moves] (see [pairs]). */
    suspend fun moved(moves: List<Pair<File, File>>) {
        for ((a, b) in moves) {
            val from = uriOf(a); val to = uriOf(b)
            if (from == to) continue
            docs.moveUri(from, to, b.name)
            tags.moveUri(from, to); tags.removeDoc(from)
            bookmarks.moveUri(from, to)
        }
    }

    /**
     * [files] were deleted. Their recents rows always go (no dead entries). A file moved to the trash keeps its tags
     * and bookmarks ([keepUserData]) so a restore to the same path finds them again; a permanent delete drops them.
     */
    suspend fun deleted(files: List<File>, keepUserData: Boolean) {
        for (f in files) {
            val uri = uriOf(f)
            docs.remove(uri)
            if (!keepUserData) { tags.removeDoc(uri); bookmarks.removeDoc(uri) }
        }
    }

    companion object {
        /** Files below [root] (or [root] itself), relative to it. Call before the move. */
        fun contents(root: File): List<String> =
            if (root.isDirectory) root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList() else listOf("")

        /** (old, new) file pairs when [from] became [to], for the [contents] listed before the move. */
        fun pairs(from: File, to: File, contents: List<String>): List<Pair<File, File>> =
            contents.map { rel -> if (rel.isEmpty()) from to to else File(from, rel) to File(to, rel) }
    }
}
