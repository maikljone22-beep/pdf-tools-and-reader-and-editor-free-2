package com.smartdocs.editor.core.batch

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class FileTaskStatus { WAITING, RUNNING, COMPLETED, FAILED, STOPPED }
data class FileTask(val input: File, val status: FileTaskStatus = FileTaskStatus.WAITING, val outputs: List<File> = emptyList(), val error: String? = null)
data class FileBatchState(val files: List<FileTask> = emptyList(), val running: Boolean = false)

/** One file at a time. Retry preserves completed outputs and uses the same operation/settings. */
class FileBatch(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(FileBatchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var operation: (suspend (File) -> List<File>)? = null
    var errorMessage: (Throwable) -> String = { it.message ?: it.javaClass.simpleName }

    fun start(files: List<File>, op: suspend (File) -> List<File>): Job? {
        if (mutable.value.running) return null
        operation = op
        mutable.value = FileBatchState(files.distinctBy { it.absolutePath }.map(::FileTask))
        return runIndexes(mutable.value.files.indices.toList())
    }
    fun retry(index: Int): Job? = if (mutable.value.files.getOrNull(index)?.status in setOf(FileTaskStatus.FAILED, FileTaskStatus.STOPPED)) runIndexes(listOf(index)) else null
    fun retryFailed(): Job? = runIndexes(mutable.value.files.indices.filter { mutable.value.files[it].status == FileTaskStatus.FAILED })
    fun resume(): Job? = runIndexes(mutable.value.files.indices.filter { mutable.value.files[it].status in setOf(FileTaskStatus.WAITING, FileTaskStatus.STOPPED) })
    fun stop() { job?.cancel() }
    private fun update(index: Int, f: (FileTask) -> FileTask) {
        mutable.value = mutable.value.copy(files = mutable.value.files.mapIndexed { i, item -> if (i == index) f(item) else item })
    }
    private fun runIndexes(indexes: List<Int>): Job? {
        if (mutable.value.running || indexes.isEmpty()) return null
        val op = operation ?: return null
        mutable.value = mutable.value.copy(running = true)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (i in indexes) {
                    ensureActive()
                    update(i) { it.copy(status = FileTaskStatus.RUNNING, error = null) }
                    try {
                        val outputs = withContext(Dispatchers.IO) { op(mutable.value.files[i].input) }
                        require(outputs.isNotEmpty() && outputs.all { it.isFile && it.length() > 0 }) { "No output was produced" }
                        update(i) { it.copy(status = FileTaskStatus.COMPLETED, outputs = outputs) }
                    } catch (e: CancellationException) {
                        update(i) { it.copy(status = FileTaskStatus.STOPPED) }
                        throw e
                    } catch (e: Throwable) {
                        update(i) { it.copy(status = FileTaskStatus.FAILED, error = errorMessage(e)) }
                    }
                }
            } finally { mutable.value = mutable.value.copy(running = false) }
        }.also { job = it }
    }
}
