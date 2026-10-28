package com.smartdocs.editor.core.convert6

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** One input of a batch that failed (the others went on). */
class Cv6Failure(val file: File, val error: Throwable)

/**
 * Batch runs of the hub (convert audit A-H5 / B02 / C1): every input is tried; a failure is recorded with its file and the
 * rest go on; a password request of one input does not stop the others ([Cv6NeedPassword] is collected like any
 * failure, so the hub can ask for that file's password and run only the locked files again). Cancellation always
 * stops the whole run. Pure (JVM-tested).
 */
object Cv6Batch {

    class Outcome<T>(val done: List<Pair<File, T>>, val failures: List<Cv6Failure>) {
        val locked: List<Cv6NeedPassword> get() = failures.mapNotNull { it.error as? Cv6NeedPassword }
    }

    /**
     * Runs [block] for each of [inputs] in order. [onFailure] cleans up what the failed input left (its partial outputs).
     * A [CancellationException] (or the coroutine being cancelled) is rethrown at once.
     */
    suspend fun <T> each(inputs: List<File>, onFailure: (File, Throwable) -> Unit = { _, _ -> }, block: suspend (index: Int, file: File) -> T): Outcome<T> {
        val done = ArrayList<Pair<File, T>>()
        val failures = ArrayList<Cv6Failure>()
        inputs.forEachIndexed { k, f ->
            currentCoroutineContext().ensureActive()
            try {
                done += f to block(k, f)
            } catch (e: CancellationException) {
                onFailure(f, e); throw e
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e   // StackOverflow / internal errors stop the run
                onFailure(f, e)
                currentCoroutineContext().ensureActive()                       // a cancel that surfaced as an exception
                failures += Cv6Failure(f, e)
            }
        }
        return Outcome(done, failures)
    }

    /**
     * Info lines of a batch: with several inputs each line is prefixed with its file name (audit B33), duplicates of
     * the same file are dropped. [perFile] = (display name, info or null).
     */
    fun infoLines(perFile: List<Pair<String, String?>>): String? {
        val withInfo = perFile.filter { !it.second.isNullOrBlank() }
        if (withInfo.isEmpty()) return null
        if (perFile.size <= 1) return withInfo.first().second
        return withInfo.flatMap { (name, info) -> info!!.lines().filter { it.isNotBlank() }.map { "$name: $it" } }.distinct().joinToString("\n")
    }

    /** Failure lines "name: message" for the results sheet; [message] gives the localised text of an error. */
    fun failureLines(failures: List<Cv6Failure>, message: (Throwable) -> String): String? =
        failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { "✗ " + it.file.name + ": " + message(it.error) }
}

/**
 * Outputs a run is writing, recorded in a small journal file so that a crash or the process being killed in the
 * middle of a conversion does not leave half-written files in Documents: the next start deletes what is listed
 * (convert audit C2 / C11 / A-M14). Pure.
 */
class OutputJournal(private val journal: File) {
    private val lock = Any()

    fun add(f: File) = synchronized(lock) { runCatching { journal.parentFile?.mkdirs(); journal.appendText(f.absolutePath + "\n") } }

    /** The run is over: [keep] stay, every other recorded file of the run is deleted; the journal is cleared. */
    fun finish(recorded: Collection<File>, keep: Collection<File>) = synchronized(lock) {
        val keepPaths = keep.map { it.absolutePath }.toSet()
        recorded.filter { it.absolutePath !in keepPaths }.forEach { deleteQuietly(it) }
        val rest = pending().filter { p -> recorded.none { it.absolutePath == p } }
        if (rest.isEmpty()) journal.delete() else runCatching { journal.writeText(rest.joinToString("\n", postfix = "\n")) }
    }

    fun pending(): List<String> = runCatching { journal.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** At start-up: delete everything a killed run left behind. Returns how many entries were removed. */
    fun purge(): Int = synchronized(lock) {
        val list = pending()
        list.forEach { deleteQuietly(File(it)) }
        journal.delete()
        list.size
    }

    companion object {
        fun deleteQuietly(f: File) { runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() } }

        /** Temporary files older than [maxAgeMs] in [dir] (shared copies, scanner pictures, converter temps). */
        fun purgeOld(dir: File, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
            var n = 0
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > maxAgeMs) { deleteQuietly(f); n++ }
            }
            return n
        }
    }
}
