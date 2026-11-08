package com.github.zakgrivell.invadetheburg.services.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity

/** Coarse agent state derived from Air's pane activity; [UNOBSERVED] means no Air pane is selected. */
internal enum class AgentState { WORKING, NEEDS_PERMISSION, DONE, IDLE, UNKNOWN, UNOBSERVED }

internal fun agentStateOf(pane: PaneActivity?): AgentState = when (pane?.activity) {
    null -> if (pane == null) AgentState.UNOBSERVED else AgentState.UNKNOWN
    "PROCESSING" -> AgentState.WORKING
    "NEEDS_INPUT" -> AgentState.NEEDS_PERMISSION
    "READY", "REVIEWING", "UNREAD" -> AgentState.DONE
    "DRAFT" -> AgentState.IDLE
    else -> AgentState.UNKNOWN
}

internal data class StateTransition(val sessionId: String, val tsMs: Long, val from: AgentState, val to: AgentState)

internal enum class SessionOutcome { DONE, PARTIAL, ABANDONED }

internal data class SessionEnd(val sessionId: String, val tsMs: Long, val outcome: SessionOutcome, val totalDurationS: Double)

internal interface AgentStateListener {
    fun onTransition(t: StateTransition)
    fun onSessionEnd(e: SessionEnd)
}
