package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.github.zakgrivell.invadetheburg.services.eta.AgentState
import com.github.zakgrivell.invadetheburg.services.eta.agentStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStateTest {
    private fun pane(activity: String?) = PaneActivity("session", activity == "PROCESSING", activity)

    @Test fun `maps known Air activities`() {
        assertEquals(AgentState.WORKING, agentStateOf(pane("PROCESSING")))
        assertEquals(AgentState.NEEDS_PERMISSION, agentStateOf(pane("NEEDS_INPUT")))
        assertEquals(AgentState.DONE, agentStateOf(pane("READY")))
        assertEquals(AgentState.DONE, agentStateOf(pane("REVIEWING")))
        assertEquals(AgentState.DONE, agentStateOf(pane("UNREAD")))
        assertEquals(AgentState.IDLE, agentStateOf(pane("DRAFT")))
    }

    @Test fun `unknown or missing activity name is UNKNOWN`() {
        assertEquals(AgentState.UNKNOWN, agentStateOf(pane("SOMETHING_NEW")))
        assertEquals(AgentState.UNKNOWN, agentStateOf(pane(null)))
    }

    @Test fun `no pane is UNOBSERVED`() {
        assertEquals(AgentState.UNOBSERVED, agentStateOf(null))
    }
}
