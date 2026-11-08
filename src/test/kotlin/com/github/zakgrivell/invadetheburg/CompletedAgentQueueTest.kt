package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import org.junit.Assert.*
import org.junit.Test

class CompletedAgentQueueTest {
    private fun pane(id: String, state: String) = QueuePane(PaneActivity(id, state == "PROCESSING", state), "Agent $id")
    private fun started(): CompletedAgentQueue = CompletedAgentQueue().apply {
        observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 0)
        observe(listOf(pane("a", "PROCESSING"), pane("b", "PROCESSING")), "a", 100)
    }
    private fun completed(): CompletedAgentQueue = started().apply {
        observe(listOf(pane("a", "READY"), pane("b", "PROCESSING")), "a", 1100)
        observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 2100)
    }
    @Test fun `completion order and per-prompt duration survive polling and tab switching`() {
        val q = started()
        q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 1100)
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "b", 2100)
        repeat(10) { q.observe(listOf(pane("b", "READY"), pane("a", "READY")), if (it % 2 == 0) "b" else "a", 3100) }
        assertEquals(listOf("b", "a"), q.entries.map { it.thread })
        assertEquals(listOf(1000L, 2000L), q.entries.map { it.elapsedMs })
        assertTrue(q.entries.none { it.partial })
        val ids = q.entries.map { it.id }
        q.observe(listOf(pane("a", "READY"), QueuePane(pane("b", "READY").activity, "Renamed agent")), "a", 4000)
        assertEquals(ids, q.entries.map { it.id })
        assertEquals("Renamed agent", q.entries.first().name)
    }
    @Test fun `a prompt finishing in the current tab can advance naturally without a review click`() {
        val q = completed()
        assertEquals("b", q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 3000))
    }
    @Test fun `fast manual tab changes between polls still disarm automatic advancement`() {
        val q = completed(); q.reviewNext()
        q.selectionChanged("b"); q.selectionChanged("a")
        assertNull(q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 3000))
    }
    @Test fun `review flow advances only after new prompt in the actively reviewed thread`() {
        val q = completed()
        assertEquals("a", q.reviewNext())
        assertEquals("b", q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 3000))
        assertEquals(listOf("b"), q.entries.map { it.thread })
        assertNull(q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "b", 3200))
    }
    @Test fun `manual tab switching disarms advance even when returning later`() {
        val q = completed(); q.reviewNext()
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "b", 2300)
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 2400)
        assertNull(q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 2500))
        assertEquals(listOf("b"), q.entries.map { it.thread })
    }
    @Test fun `background completion never steals focus and background restart only removes itself`() {
        val q = started()
        assertNull(q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 1000))
        q.reviewNext()
        assertNull(q.observe(listOf(pane("a", "PROCESSING"), pane("b", "PROCESSING")), "a", 1200))
        assertTrue(q.entries.isEmpty())
    }
    @Test fun `removed completion stays removed until the next completed prompt`() {
        val q = completed(); val oldId = q.entries.first().id
        q.remove(oldId, "b")
        repeat(3) { q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "b", 2500) }
        assertEquals(listOf("b"), q.entries.map { it.thread })
        q.observe(listOf(pane("a", "PROCESSING"), pane("b", "READY")), "a", 3000)
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 5000)
        assertEquals(listOf("b", "a"), q.entries.map { it.thread })
        assertNotEquals(oldId, q.entries.last().id)
        assertEquals(2000L, q.entries.last().elapsedMs)
        q.remove(oldId, "a")
        assertEquals(2, q.entries.size)
    }
    @Test fun `permission waits retain prompt duration without enqueuing early`() {
        val q = started()
        q.observe(listOf(pane("a", "NEEDS_INPUT"), pane("b", "PROCESSING")), "a", 1000)
        assertTrue(q.entries.isEmpty())
        q.observe(listOf(pane("a", "PROCESSING"), pane("b", "PROCESSING")), "a", 4000)
        q.observe(listOf(pane("a", "READY"), pane("b", "PROCESSING")), "a", 5000)
        assertEquals(4900L, q.entries.single().elapsedMs)
    }
    @Test fun `startup does not enqueue old results and mid-flight timings are marked approximate`() {
        val q = CompletedAgentQueue()
        q.observe(listOf(pane("a", "READY"), pane("b", "PROCESSING")), null, 1000)
        assertTrue(q.entries.isEmpty())
        q.observe(listOf(pane("a", "READY"), pane("b", "UNREAD")), null, 2000)
        assertTrue(q.entries.single().partial)
        assertEquals("~1s", promptDuration(q.entries.single()))
    }
    @Test fun `closing a thread removes its run and completion without reopening it`() {
        val q = completed(); q.reviewNext()
        assertNull(q.observe(listOf(pane("b", "READY")), "b", 3000))
        assertEquals(listOf("b"), q.entries.map { it.thread })
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 4000)
        assertEquals(1, q.entries.size)
    }
    @Test fun `reordering is stable across new completions and selected removal`() {
        val q = completed(); val b = q.entries.last()
        q.move(b.id, -1)
        assertEquals("b", q.reviewNext())
        assertEquals("a", q.remove(b.id, "b"))
        q.move("stale", 1)
        q.move(q.entries.single().id, -100)
        assertEquals(listOf("a"), q.entries.map { it.thread })
    }
    @Test fun `permission wait first seen mid-prompt does not claim a complete duration`() {
        val q = CompletedAgentQueue()
        q.observe(listOf(pane("a", "NEEDS_INPUT")), "a", 0)
        q.observe(listOf(pane("a", "PROCESSING")), "a", 100)
        q.observe(listOf(pane("a", "READY")), "a", 1000)
        assertTrue(q.entries.single().partial)
    }
    @Test fun `explicitly marking the current thread done advances even after manual tab browsing`() {
        val q = completed()
        q.selectionChanged("b"); q.selectionChanged("a")
        assertEquals("b", q.remove(q.entries.first().id, "a"))
    }
    @Test fun `cancelled or unknown runs do not become successful queued completions`() {
        val q = started()
        q.observe(listOf(pane("a", "DRAFT"), pane("b", "UNKNOWN")), "a", 1000)
        q.observe(listOf(pane("a", "READY"), pane("b", "READY")), "a", 2000)
        assertTrue(q.entries.isEmpty())
    }
}
