package com.github.zakgrivell.invadetheburg.services

import com.github.zakgrivell.invadetheburg.services.tags.AgentTag

/** Navigation suspends the old browser; new runs, explicit Resume and queue handoffs may reveal it. */
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
    /** Empty startup/polls and background activity must not take focus from an unrelated chat. */
    fun resumeAfterQueueChange(previous: List<CompletedAgent>, remaining: List<CompletedAgent>, pane: PaneActivity?, explicitRemoval: Boolean = false): Boolean {
        if (previous.isEmpty() || remaining.isNotEmpty() || pane == null) return false
        val submittedCurrent = pane.processing && previous.any { it.thread == pane.identity }
        val dismissedLast = explicitRemoval && (pane.processing || pane.completed)
        if (!submittedCurrent && !dismissedLast) return false
        resume()
        return true
    }
    fun showBrowser(pane: PaneActivity?): Boolean {
        if (pane == null) { suspend(); wasProcessing = false; return false }
        if (pane.processing && !wasProcessing) suspended = false
        if (!pane.processing && wasProcessing) manual = false
        wasProcessing = pane.processing
        return manual || (pane.processing && !suspended)
    }
}

/** [tags] maps a thread to its latest valid agent tag; untagged threads are absent. */
internal data class QueueView(val entries: List<CompletedAgent>, val activeThread: String?, val tags: Map<String, AgentTag> = emptyMap())

internal fun queueVisibleFor(target: String, pane: PaneActivity?, editorShowing: Boolean): Boolean =
    target in setOf("chat", "recap") && pane != null && editorShowing
