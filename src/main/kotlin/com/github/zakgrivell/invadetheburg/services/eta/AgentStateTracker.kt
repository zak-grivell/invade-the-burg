package com.github.zakgrivell.invadetheburg.services.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turns successive pane observations into session-scoped state transitions. Read-only: it never
 * influences the overlay. Time is passed in (epoch millis) and ids are injected so it is deterministic.
 *
 * - A session opens on the first WORKING of a pane and closes on DONE or IDLE.
 * - WORKING <-> NEEDS_PERMISSION stays inside the session and counts as a work/wait switch.
 * - Only transitions that belong to a session are emitted; state changes outside a session are tracked silently.
 * - Losing the pane (switch, no Air pane, [close]) emits `-> UNOBSERVED` then an ABANDONED end.
 * - A session whose start was not seen (first sighting, or after being unobserved) ends as PARTIAL.
 */
internal class AgentStateTracker(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class Session(val id: String, val startMs: Long, val startObserved: Boolean) {
        var workWaitSwitches = 0
    }

    private val listeners = CopyOnWriteArrayList<AgentStateListener>()
    private var identity: String? = null
    private var state = AgentState.UNOBSERVED
    private var session: Session? = null
    private var stretchStartMs = 0L
    private var lastTransitionMs = 0L

    fun addListener(l: AgentStateListener) { listeners.add(l) }
    fun removeListener(l: AgentStateListener) { listeners.remove(l) }

    fun observe(pane: PaneActivity?, nowMs: Long) {
        val id = pane?.identity
        if (id != identity) {
            abandonOpenSession(nowMs)
            identity = id
            state = AgentState.UNOBSERVED
        }
        val next = agentStateOf(pane)
        if (next != state) transitionTo(next, nowMs)
    }

    /** Ends any open session as abandoned; call when the owning service is disposed. */
    fun close(nowMs: Long) {
        abandonOpenSession(nowMs)
        identity = null
        state = AgentState.UNOBSERVED
    }

    /** Features for the current WORKING stretch, or null when no session is in a WORKING stretch. */
    fun features(nowMs: Long, zone: ZoneId): EtaFeatures? {
        val s = session ?: return null
        if (state != AgentState.WORKING) return null
        return EtaFeatures(
            stretchElapsedS = secondsBetween(stretchStartMs, nowMs),
            sessionElapsedS = secondsBetween(s.startMs, nowMs),
            workWaitSwitches = s.workWaitSwitches,
            sinceLastTransitionS = secondsBetween(lastTransitionMs, nowMs),
            hourOfDay = Instant.ofEpochMilli(nowMs).atZone(zone).hour,
        )
    }

    private fun transitionTo(next: AgentState, nowMs: Long) {
        val from = state
        var s = session
        if (s == null) {
            if (next == AgentState.WORKING) {
                // The start is only "observed" if we saw the agent at rest immediately before.
                val startObserved = from == AgentState.DONE || from == AgentState.IDLE
                s = Session(newId(), nowMs, startObserved)
                session = s
            }
        } else if (isWorkWaitSwitch(from, next)) {
            s.workWaitSwitches++
        }
        state = next
        if (s == null) return
        if (next == AgentState.WORKING) stretchStartMs = nowMs
        lastTransitionMs = nowMs
        emit(StateTransition(s.id, nowMs, from, next))
        if (next == AgentState.DONE || next == AgentState.IDLE) {
            val outcome = if (s.startObserved) SessionOutcome.DONE else SessionOutcome.PARTIAL
            endSession(s, nowMs, outcome)
        }
    }

    private fun abandonOpenSession(nowMs: Long) {
        val s = session ?: return
        emit(StateTransition(s.id, nowMs, state, AgentState.UNOBSERVED))
        endSession(s, nowMs, SessionOutcome.ABANDONED)
    }

    private fun endSession(s: Session, nowMs: Long, outcome: SessionOutcome) {
        session = null
        val end = SessionEnd(s.id, nowMs, outcome, secondsBetween(s.startMs, nowMs))
        listeners.forEach { runCatching { it.onSessionEnd(end) } }
    }

    private fun emit(t: StateTransition) {
        listeners.forEach { runCatching { it.onTransition(t) } }
    }

    private fun isWorkWaitSwitch(from: AgentState, to: AgentState) =
        (from == AgentState.WORKING && to == AgentState.NEEDS_PERMISSION) ||
            (from == AgentState.NEEDS_PERMISSION && to == AgentState.WORKING)

    private fun secondsBetween(fromMs: Long, toMs: Long) = (toMs - fromMs) / 1000.0
}
