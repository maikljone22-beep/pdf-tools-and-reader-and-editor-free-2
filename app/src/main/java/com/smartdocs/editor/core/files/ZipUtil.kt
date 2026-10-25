package com.smartdocs.editor.core.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtil {
    /** Zip files/folders into [out]. */
    suspend fun zip(items: List<File>, out: File) = withContext(Dispatchers.IO) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            items.forEach { f -> addEntry(z, f, f.name) }
        }
    }

    private fun addEntry(z: ZipOutputStream, f: File, path: String) {
        if (f.isDirectory) {
            z.putNextEntry(ZipEntry("$path/")); z.closeEntry()
            f.listFiles()?.forEach { addEntry(z, it, "$path/${it.name}") }
        } else {
            z.putNextEntry(ZipEntry(path))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }

    /** Extract [zip] into [dir]; returns extracted files. Entries escaping [dir] are skipped. */
    suspend fun unzip(zip: File, dir: File): List<File> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val root = dir.canonicalPath
        val out = ArrayList<File>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val target = File(dir, e.name)
                if (target.canonicalPath.startsWith(root)) {
                    if (e.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { z.copyTo(it) }
                        out += target
                    }
                }
                z.closeEntry(); e = z.nextEntry
            }
        }
        out
    }
}
