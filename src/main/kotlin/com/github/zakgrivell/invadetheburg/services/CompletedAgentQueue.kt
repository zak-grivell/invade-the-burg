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
    val entries: List<CompletedAgent> get() = pending.toList()

    /** Returns the next waiting thread when the selected queued thread starts its next prompt. */
    fun observe(panes: List<QueuePane>, selected: String?, now: Long): String? {
        val live = panes.map { it.activity.identity }.toSet()
        var advance = false
        pending.removeAll { it.thread !in live }
        runs.keys.retainAll(live)
        for ((activity, name) in panes) {
            val id = activity.identity
            val queued = pending.indexOfFirst { it.thread == id }
            if (queued >= 0 && pending[queued].name != name) pending[queued] = pending[queued].copy(name = name)
            if (activity.processing) {
                if (pending.removeAll { it.thread == id }) {
                    if (selected == id) advance = true
                }
                runs.getOrPut(id) { Running(now, observed[id] !in setOf("READY", "UNREAD", "DRAFT")) }
            } else if (activity.completed) {
                runs.remove(id)?.let { run ->
                    pending.add(CompletedAgent("$id:${++serial}", id, name, (now - run.since).coerceAtLeast(0), run.partial))
                }
            } else if (activity.activity != "NEEDS_INPUT") {
                // Draft/cancel/unknown is not a successful completion. Permission waits retain timing.
                runs.remove(id)
            }
        }
        observed = panes.associate { it.activity.identity to it.activity.activity }
        return if (advance) reviewNext() else null
    }

    fun reviewNext(): String? = pending.firstOrNull()?.thread
    fun review(id: String): String? {
        val index = pending.indexOfFirst { it.id == id }
        if (index < 0) return null
        val entry = pending.removeAt(index)
        pending.add(0, entry)
        return entry.thread
    }
    fun remove(id: String, selected: String?): String? {
        val entry = pending.find { it.id == id } ?: return null
        pending.remove(entry)
        val advance = selected == entry.thread
        return if (advance) reviewNext() else null
    }
}

internal fun promptDuration(entry: CompletedAgent): String {
    val seconds = entry.elapsedMs / 1000
    val time = if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m" else if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    return (if (entry.partial) "~" else "") + time
}
