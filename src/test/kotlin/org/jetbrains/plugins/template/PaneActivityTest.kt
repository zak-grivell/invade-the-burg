package org.jetbrains.plugins.template

import org.jetbrains.plugins.template.services.AirPaneActivity
import org.jetbrains.plugins.template.services.BreakState
import org.jetbrains.plugins.template.services.PaneActivity
import org.junit.Assert.*
import org.junit.Test

class PaneActivityTest {
    enum class Activity { PROCESSING, READY, NEEDS_INPUT, REVIEWING, UNREAD, DRAFT }
    class FakePane(private val activity: Activity) {
        fun getThreadIdentity() = "existing-codex-session"
        fun getThreadActivity() = activity
    }
    class BrokenPane {
        fun getThreadIdentity() = "broken"
        fun getThreadActivity(): Activity = error("Air unavailable")
    }
    @Test fun `reads live pane processing state`() {
        assertEquals(PaneActivity("existing-codex-session", true), AirPaneActivity.readKnownPane(FakePane(Activity.PROCESSING)))
        Activity.entries.filter { it != Activity.PROCESSING }.forEach {
            assertEquals(PaneActivity("existing-codex-session", false), AirPaneActivity.readKnownPane(FakePane(it)))
        }
    }
    @Test fun `unrelated and incompatible panes close the break`() {
        assertNull(AirPaneActivity.read(null))
        assertNull(AirPaneActivity.read(Any()))
        assertNull(AirPaneActivity.readKnownPane(Any()))
        assertNull(AirPaneActivity.readKnownPane(BrokenPane()))
    }
    @Test fun `completion cancellation and permission close reels`() {
        val state = BreakState()
        assertTrue(state.update(PaneActivity("session", true)))
        assertFalse(state.update(PaneActivity("session", false)))
        assertTrue(state.update(PaneActivity("session", true)))
        assertFalse(state.update(null))
    }
    @Test fun `dismissal lasts for current turn only`() {
        val state = BreakState()
        assertTrue(state.update(PaneActivity("session", true)))
        state.dismiss()
        assertFalse(state.update(PaneActivity("session", true)))
        assertFalse(state.update(PaneActivity("session", false)))
        assertTrue(state.update(PaneActivity("session", true)))
    }
    @Test fun `return to reels reopens dismissed break and still closes on completion`() {
        val state = BreakState()
        assertTrue(state.update(PaneActivity("session", true)))
        state.dismiss()
        assertFalse(state.update(PaneActivity("session", true)))
        state.resume()
        assertTrue(state.update(PaneActivity("session", true)))
        assertFalse(state.update(PaneActivity("session", false)))
    }
    @Test fun `switching panes follows the newly selected session`() {
        val state = BreakState()
        assertTrue(state.update(PaneActivity("one", true)))
        state.dismiss()
        assertFalse(state.update(PaneActivity("one", true)))
        assertTrue(state.update(PaneActivity("two", true)))
        assertFalse(state.update(PaneActivity("three", false)))
    }
}
