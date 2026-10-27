package com.smartdocs.editor.core.history

/** Rules deciding whether an undo / redo may run (audit AN-04). Pure, unit tested. */
object HistoryGuards {
    /**
     * True when [steps] (what the undo / redo would apply) include a file version while stored annotations are taken
     * out of the file for editing ([conversionPending]): restoring a file version then would bring the original
     * annotation back next to its pending copy.
     */
    fun blocksFileSteps(steps: List<HistoryStep>, conversionPending: Boolean): Boolean =
        conversionPending && steps.any { it is HistoryStep.FileSnapshot }
}
