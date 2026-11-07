package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import org.junit.Assert.*
import org.junit.Test

class ChatNarratorTest {
    @Test fun `completion fires once and never on first idle observation`() {
        val tracker = CompletionTracker()
        assertFalse(tracker.update(PaneActivity("a", false, true)))
        assertFalse(tracker.update(PaneActivity("a", true)))
        assertTrue(tracker.update(PaneActivity("a", false, true)))
        assertFalse(tracker.update(PaneActivity("a", false, true)))
        assertFalse(tracker.update(PaneActivity("a", true)))
        assertTrue(tracker.update(PaneActivity("a", false, true)))
    }
    @Test fun `permission pause and closed panes do not narrate`() {
        val tracker = CompletionTracker()
        tracker.update(PaneActivity("a", true))
        assertFalse(tracker.update(PaneActivity("a", false, false)))
        assertFalse(tracker.update(PaneActivity("a", false, true)))
        tracker.update(PaneActivity("a", true))
        tracker.retain(emptySet())
        assertFalse(tracker.update(PaneActivity("a", false, true)))
    }
    @Test fun `background panes complete independently`() {
        val tracker = CompletionTracker()
        tracker.update(PaneActivity("a", true))
        tracker.update(PaneActivity("b", true))
        assertTrue(tracker.update(PaneActivity("a", false, true)))
        assertTrue(tracker.update(PaneActivity("b", false, true)))
    }
}
