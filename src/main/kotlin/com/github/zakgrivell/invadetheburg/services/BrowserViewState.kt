package com.github.zakgrivell.invadetheburg.services

/** A navigation suspends the old browser; only a new run or explicit Resume may reveal it. */
internal class BrowserViewState {
    private var suspended = false
    private var manual = false
    private var wasProcessing = false
    fun viewChanged(processing: Boolean) {
        suspended = true
        manual = false
        wasProcessing = processing
    }
    fun suspend() { suspended = true; manual = false }
    fun resume() { suspended = false; manual = true }
    fun showBrowser(pane: PaneActivity?): Boolean {
        if (pane == null) { suspend(); wasProcessing = false; return false }
        if (pane.processing && !wasProcessing) suspended = false
        if (!pane.processing && wasProcessing) manual = false
        wasProcessing = pane.processing
        return manual || (pane.processing && !suspended)
    }
}

internal data class QueueView(val entries: List<CompletedAgent>, val canResume: Boolean)

internal fun queueVisibleFor(target: String, pane: PaneActivity?, editorShowing: Boolean): Boolean =
    target in setOf("chat", "recap") && pane != null && editorShowing
