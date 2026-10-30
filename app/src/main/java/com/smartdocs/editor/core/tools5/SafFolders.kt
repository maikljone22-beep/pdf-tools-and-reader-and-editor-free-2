package com.smartdocs.editor.core.tools5

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Folders picked with ACTION_OPEN_DOCUMENT_TREE (Storage Access Framework): list the documents of a folder
 * (optionally with its sub-folders) to batch them, copy them in, and write batch outputs into a folder with the
 * recipe's name-conflict rule. Every folder is addressed by a document URI built on its tree, so a sub-folder found
 * while listing can receive outputs too ("same folder as the input").
 */
object SafFolders {

    /** A document found in a picked folder: its URI, display name and the folder it is in. */
    data class Entry(val uri: Uri, val name: String, val folder: Uri)

    /** Keep read/write access to [tree] across restarts (the batch runs later in WorkManager). */
    fun persist(ctx: Context, tree: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** The folder document of a picked tree. */
    fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Display name of a picked tree (for the destination row). */
    fun name(ctx: Context, tree: Uri): String = runCatching {
        ctx.contentResolver.query(root(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment.orEmpty().substringAfterLast(':')

    private data class Child(val uri: Uri, val name: String, val dir: Boolean)

    private fun children(ctx: Context, folder: Uri): List<Child> {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val out = ArrayList<Child>()
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Child(DocumentsContract.buildDocumentUriUsingTree(folder, id), c.getString(1).orEmpty(), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    /** Documents of [tree] accepted by [accept] (by name), sub-folders too when [recursive]; sorted by path. */
    fun list(ctx: Context, tree: Uri, recursive: Boolean, accept: (String) -> Boolean, limit: Int = 2000, skipDir: (String) -> Boolean = { false }): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(folder: Uri, depth: Int) {
            val kids = runCatching { children(ctx, folder) }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
            kids.filter { !it.dir && accept(it.name) }.forEach { if (out.size < limit) out += Entry(it.uri, it.name, folder) }
            if (recursive && depth < 12) kids.filter { it.dir && !skipDir(it.name) }.forEach { walk(it.uri, depth + 1) }
        }
        walk(root(tree), 0)
        return out
    }

    /** Size and last-modified time of a document (0 when the provider does not say). */
    fun stat(ctx: Context, doc: Uri): Pair<Long, Long> = runCatching {
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) 0L else c.getLong(0)) to (if (c.isNull(1)) 0L else c.getLong(1)) else null
        }
    }.getOrNull() ?: (0L to 0L)

    /** The sub-folder [name] of [folder], or null when there is none. */
    fun childDir(ctx: Context, folder: Uri, name: String): Uri? = runCatching { children(ctx, folder).firstOrNull { it.dir && it.name == name }?.uri }.getOrNull()

    /** Names of the files in [folder] (to check output names before a run). */
    fun names(ctx: Context, folder: Uri): Set<String> = runCatching { children(ctx, folder).filter { !it.dir }.map { it.name }.toSet() }.getOrDefault(emptySet())

    /** Copy [e] into [dir] (a unique local name), for the batch to read. */
    fun copyIn(ctx: Context, e: Entry, dir: File): File {
        dir.mkdirs()
        val base = TextNorm.safeFileName(e.name.substringBeforeLast('.'), 100).ifBlank { "file" }
        val ext = e.name.substringAfterLast('.', "pdf").lowercase()
        var f = File(dir, "$base.$ext"); var n = 2
        while (f.exists()) f = File(dir, "${base}_${n++}.$ext")
        ctx.contentResolver.openInputStream(e.uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: error("cannot read ${e.name}")
        return f
    }

    fun mime(ext: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: if (ext.equals("pdf", true)) "application/pdf" else "application/octet-stream"

    /**
     * Write [file] into [folder] under its own name, settling a taken name with [conflict]. Returns the name written,
     * or null when the file was skipped because the name is taken.
     */
    fun export(ctx: Context, folder: Uri, file: File, conflict: Conflict): String? {
        val existing = runCatching { children(ctx, folder) }.getOrDefault(emptyList()).filter { !it.dir }.associateBy { it.name }
        val name = RecipeOps.resolveName(file.nameWithoutExtension, file.extension.ifBlank { "pdf" }, conflict) { it in existing } ?: return null
        val target = existing[name]?.uri
            ?: DocumentsContract.createDocument(ctx.contentResolver, folder, mime(file.extension), name)
            ?: error("cannot create $name")
        ctx.contentResolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("cannot write $name")
        return name
    }
}
