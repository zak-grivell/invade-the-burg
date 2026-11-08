package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import org.junit.Assert.*
import org.junit.Test

class QueueDrainResumeTest {
    private fun pane(id: String, status: String) = PaneActivity(id, status == "PROCESSING", status)
    private fun panes(vararg states: Pair<String, String>) = states.map { (id, status) -> QueuePane(pane(id, status), id) }
    private fun completed(vararg ids: String) = CompletedAgentQueue().apply {
        observe(panes(*ids.map { it to "READY" }.toTypedArray()), ids.first(), 0)
        observe(panes(*ids.map { it to "PROCESSING" }.toTypedArray()), ids.first(), 100)
        observe(panes(*ids.map { it to "READY" }.toTypedArray()), ids.first(), 1100)
    }

    @Test fun `submitting the final queued thread resumes suspended reels and its next completion returns to chat`() {
        val q = completed("a")
        val view = BrowserViewState().apply { viewChanged(true) }
        val running = pane("a", "PROCESSING")
        assertFalse(view.showBrowser(running))
        val previous = q.entries
        assertNull(q.observe(panes("a" to "PROCESSING"), "a", 2000))
        assertTrue(view.resumeAfterQueueChange(previous, q.entries, running))
        assertTrue(view.showBrowser(running))
        assertFalse(queueVisibleFor("break", running, true))
        q.observe(panes("a" to "READY"), "a", 3000)
        assertEquals(1, q.entries.size)
        assertFalse(view.showBrowser(pane("a", "READY")))
        assertTrue(queueVisibleFor("chat", pane("a", "READY"), true))
    }

    @Test fun `last remove cross resumes idle reels once without empty polls undoing a later dismissal`() {
        for (status in listOf("READY", "UNREAD")) {
            val q = completed("a")
            val view = BrowserViewState().apply { viewChanged(false) }
            val previous = q.entries
            assertNull(q.remove(previous.single().id, "a"))
            assertTrue(view.resumeAfterQueueChange(previous, q.entries, pane("a", status), explicitRemoval = true))
            assertTrue(view.showBrowser(pane("a", status)))
            view.suspend()
            repeat(5) {
                assertFalse(view.resumeAfterQueueChange(q.entries, q.entries, pane("a", status), explicitRemoval = true))
                assertFalse(view.showBrowser(pane("a", status)))
            }
        }
    }

    @Test fun `remaining results hand off to review before the final prompt resumes reels`() {
        val q = completed("a", "b")
        val view = BrowserViewState().apply { viewChanged(false) }
        val previous = q.entries
        assertEquals("b", q.observe(panes("a" to "PROCESSING", "b" to "READY"), "a", 2000))
        assertFalse(view.resumeAfterQueueChange(previous, q.entries, pane("a", "PROCESSING")))
        view.viewChanged(false)
        assertFalse(view.showBrowser(pane("b", "READY")))
        val last = q.entries
        assertNull(q.observe(panes("a" to "PROCESSING", "b" to "PROCESSING"), "b", 3000))
        assertTrue(view.resumeAfterQueueChange(last, q.entries, pane("b", "PROCESSING")))
        assertTrue(view.showBrowser(pane("b", "PROCESSING")))
    }

    @Test fun `startup background restarts and thread closure cannot steal focus from another pane`() {
        val previous = completed("a").entries
        val view = BrowserViewState().apply { viewChanged(false) }
        assertFalse(view.resumeAfterQueueChange(emptyList(), emptyList(), pane("a", "PROCESSING")))
        assertFalse(view.resumeAfterQueueChange(previous, emptyList(), pane("other", "PROCESSING")))
        assertFalse(view.resumeAfterQueueChange(previous, emptyList(), pane("other", "READY")))
        for (selected in listOf(null, pane("new", "DRAFT"), pane("a", "NEEDS_INPUT"), pane("a", "UNKNOWN"))) {
            assertFalse(view.resumeAfterQueueChange(previous, emptyList(), selected, explicitRemoval = true))
            assertFalse(view.showBrowser(selected))
        }
        // Removing the last background result explicitly may resume an already-working selected thread.
        assertTrue(view.resumeAfterQueueChange(previous, emptyList(), pane("other", "PROCESSING"), explicitRemoval = true))
        assertTrue(view.showBrowser(pane("other", "PROCESSING")))
    }
}
