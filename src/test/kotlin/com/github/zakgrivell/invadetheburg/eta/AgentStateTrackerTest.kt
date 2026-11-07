package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.github.zakgrivell.invadetheburg.services.eta.AgentState.*
import com.github.zakgrivell.invadetheburg.services.eta.AgentStateListener
import com.github.zakgrivell.invadetheburg.services.eta.AgentStateTracker
import com.github.zakgrivell.invadetheburg.services.eta.SessionEnd
import com.github.zakgrivell.invadetheburg.services.eta.SessionOutcome
import com.github.zakgrivell.invadetheburg.services.eta.StateTransition
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AgentStateTrackerTest {
    private class Recorder : AgentStateListener {
        val transitions = mutableListOf<StateTransition>()
        val ends = mutableListOf<SessionEnd>()
        override fun onTransition(t: StateTransition) { transitions += t }
        override fun onSessionEnd(e: SessionEnd) { ends += e }
        fun pairs() = transitions.map { it.from to it.to }
    }

    private var ids = 0
    private fun tracker() = AgentStateTracker { "s${++ids}" }.also { ids = 0 }
    private fun pane(activity: String, identity: String = "a") = PaneActivity(identity, activity == "PROCESSING", activity)
    private fun AgentStateTracker.recorded() = Recorder().also { addListener(it) }

    @Test fun `permission round trip gives four transitions, a done end and two switches`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("READY"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.observe(pane("NEEDS_INPUT"), 11_000)
        t.observe(pane("PROCESSING"), 21_000)
        val f = t.features(30_000, ZoneOffset.UTC)!!
        assertEquals(2, f.workWaitSwitches)
        t.observe(pane("READY"), 61_000)

        assertEquals(listOf(DONE to WORKING, WORKING to NEEDS_PERMISSION, NEEDS_PERMISSION to WORKING, WORKING to DONE), r.pairs())
        assertEquals(setOf("s1"), r.transitions.map { it.sessionId }.toSet())
        assertEquals(listOf(SessionEnd("s1", 61_000, SessionOutcome.DONE, 60.0)), r.ends)
    }

    @Test fun `repeated identical polls emit nothing`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("READY"), 0)
        repeat(5) { t.observe(pane("PROCESSING"), 1_000L + it * 200) }
        assertEquals(1, r.transitions.size)
        assertTrue(r.ends.isEmpty())
    }

    @Test fun `first sighting mid-working is a partial session`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("PROCESSING"), 0)
        t.observe(pane("READY"), 5_000)
        assertEquals(listOf(UNOBSERVED to WORKING, WORKING to DONE), r.pairs())
        assertEquals(SessionOutcome.PARTIAL, r.ends.single().outcome)
    }

    @Test fun `switching panes abandons the open session`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("READY"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.observe(pane("READY", identity = "b"), 4_000)
        assertEquals(listOf(DONE to WORKING, WORKING to UNOBSERVED), r.pairs())
        assertEquals(SessionEnd("s1", 4_000, SessionOutcome.ABANDONED, 3.0), r.ends.single())
    }

    @Test fun `losing the pane abandons the open session and coming back is partial`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("READY"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.observe(null, 2_000)
        t.observe(pane("PROCESSING"), 3_000)
        assertEquals(SessionOutcome.ABANDONED, r.ends.single().outcome)
        t.observe(pane("READY"), 4_000)
        assertEquals(listOf(SessionOutcome.ABANDONED, SessionOutcome.PARTIAL), r.ends.map { it.outcome })
        assertEquals(listOf("s1", "s2"), r.ends.map { it.sessionId })
    }

    @Test fun `close abandons the open session and is a no-op otherwise`() {
        val t = tracker(); val r = t.recorded()
        t.close(0)
        assertTrue(r.transitions.isEmpty() && r.ends.isEmpty())
        t.observe(pane("READY"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.close(6_000)
        assertEquals(listOf(DONE to WORKING, WORKING to UNOBSERVED), r.pairs())
        assertEquals(SessionEnd("s1", 6_000, SessionOutcome.ABANDONED, 5.0), r.ends.single())
    }

    @Test fun `states outside a session emit nothing`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("READY"), 0)
        t.observe(pane("DRAFT"), 1_000)
        t.observe(pane("NEEDS_INPUT"), 2_000)
        t.observe(pane("SOMETHING_NEW"), 3_000)
        assertTrue(r.transitions.isEmpty() && r.ends.isEmpty())
    }

    @Test fun `working after needing input without seeing the start is partial`() {
        val t = tracker(); val r = t.recorded()
        t.observe(pane("NEEDS_INPUT"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.observe(pane("READY"), 2_000)
        assertEquals(SessionOutcome.PARTIAL, r.ends.single().outcome)
    }

    @Test fun `features measure the current stretch after a permission round trip`() {
        val t = tracker()
        val base = Instant.parse("2026-11-07T14:00:00Z").toEpochMilli()
        assertNull(t.features(base, ZoneOffset.UTC))
        t.observe(pane("READY"), base)
        assertNull(t.features(base, ZoneOffset.UTC))
        t.observe(pane("PROCESSING"), base + 10_000)
        t.observe(pane("NEEDS_INPUT"), base + 40_000)
        assertNull(t.features(base + 41_000, ZoneOffset.UTC))
        t.observe(pane("PROCESSING"), base + 100_000)

        val f = t.features(base + 130_000, ZoneOffset.UTC)!!
        assertEquals(30.0, f.stretchElapsedS, 0.0)
        assertEquals(120.0, f.sessionElapsedS, 0.0)
        assertEquals(2, f.workWaitSwitches)
        assertEquals(30.0, f.sinceLastTransitionS, 0.0)
        assertEquals(14, f.hourOfDay)
        assertEquals(15, t.features(base + 130_000, ZoneOffset.ofHours(1))!!.hourOfDay)

        t.observe(pane("READY"), base + 140_000)
        assertNull(t.features(base + 141_000, ZoneOffset.UTC))
    }

    @Test fun `a throwing listener does not stop others`() {
        val t = tracker()
        t.addListener(object : AgentStateListener {
            override fun onTransition(t: StateTransition) = error("boom")
            override fun onSessionEnd(e: SessionEnd) = error("boom")
        })
        val r = t.recorded()
        t.observe(pane("READY"), 0)
        t.observe(pane("PROCESSING"), 1_000)
        t.observe(pane("READY"), 2_000)
        assertEquals(2, r.transitions.size)
        assertEquals(1, r.ends.size)
    }
}
