package com.github.zakgrivell.invadetheburg.services

internal data class QueuePane(val activity: PaneActivity, val name: String)
internal data class CompletedAgent(val id: String, val thread: String, val name: String, val elapsedMs: Long, val partial: Boolean)

/** EDT-owned. Observing tabs does not acknowledge work; only a new run or explicit removal does. */
internal class CompletedAgentQueue {
    private data class Running(val since: Long, val partial: Boolean)
    private val runs = mutableMapOf<String, Running>()
    private var observed = emptyMap<String, String?>()
    private val pending = mutableListOf<CompletedAgent>()
    private var serial = 0L
    private var reviewing: String? = null
    val entries: List<CompletedAgent> get() = pending.toList()

    fun selectionChanged(selected: String?) { if (reviewing != selected) reviewing = null }

    /** Returns a thread to open only when the actively reviewed entry starts its next prompt. */
    fun observe(panes: List<QueuePane>, selected: String?, now: Long): String? {
        val live = panes.map { it.activity.identity }.toSet()
        var advance = false
        var finishedHere = false
        if (reviewing != selected) reviewing = null
        pending.removeAll { it.thread !in live }
        runs.keys.retainAll(live)
        for ((activity, name) in panes) {
            val id = activity.identity
            val queued = pending.indexOfFirst { it.thread == id }
            if (queued >= 0 && pending[queued].name != name) pending[queued] = pending[queued].copy(name = name)
            if (activity.processing) {
                if (pending.removeAll { it.thread == id }) {
                    if (reviewing == id && selected == id) advance = true
                    if (reviewing == id) reviewing = null
                }
                runs.getOrPut(id) { Running(now, observed[id] !in setOf("READY", "UNREAD", "DRAFT")) }
            } else if (activity.completed) {
                runs.remove(id)?.let { run ->
                    if (id == selected) finishedHere = true
                    pending.add(CompletedAgent("$id:${++serial}", id, name, (now - run.since).coerceAtLeast(0), run.partial))
                }
            } else if (activity.activity != "NEEDS_INPUT") {
                // Draft/cancel/unknown is not a successful completion. Permission waits retain timing.
                runs.remove(id)
            }
        }
        if (finishedHere) reviewing = selected
        observed = panes.associate { it.activity.identity to it.activity.activity }
        if (reviewing !in live) reviewing = null
        return if (advance) reviewNext() else null
    }

    fun reviewNext(): String? = pending.firstOrNull()?.thread?.also { reviewing = it }
    fun review(id: String): String? = pending.find { it.id == id }?.thread?.also { reviewing = it }
    fun remove(id: String, selected: String?): String? {
        val entry = pending.find { it.id == id } ?: return null
        pending.remove(entry)
        val advance = selected == entry.thread
        if (reviewing == entry.thread) reviewing = null
        return if (advance) reviewNext() else null
    }
    fun move(id: String, offset: Int) {
        val from = pending.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, pending.lastIndex)
        if (to != from) pending.add(to, pending.removeAt(from))
    }
}

internal fun promptDuration(entry: CompletedAgent): String {
    val seconds = entry.elapsedMs / 1000
    val time = if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m" else if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    return (if (entry.partial) "~" else "") + time
}
