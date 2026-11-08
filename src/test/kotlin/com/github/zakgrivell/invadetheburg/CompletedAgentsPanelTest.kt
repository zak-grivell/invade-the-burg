package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.CompletedAgent
import com.github.zakgrivell.invadetheburg.ui.CompletedAgentsPanel
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.*

class CompletedAgentsPanelTest {
    private fun all(component: Component): List<Component> = listOf(component) + if (component is Container) component.components.flatMap(::all) else emptyList()
    @Test fun `rows open on click and their cross only removes their own completion`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val panel = CompletedAgentsPanel({ calls.add("review:$it") }, { calls.add("remove:$it") })
            val a = CompletedAgent("a:1", "a", "Agent A", 62000, false)
            val b = CompletedAgent("b:1", "b", "Agent B", 3000, false)
            panel.update(listOf(a, b), "a")
            fun buttons() = all(panel).filterIsInstance<JButton>()
            val openB = buttons().single { it.text.startsWith("Agent B") }
            val closeB = buttons().single { it.accessibleContext.accessibleName == "Remove Agent B from queue" }
            assertTrue(buttons().single { it.text.startsWith("Agent A") }.text.contains("Current"))
            assertTrue(buttons().none { it.text in listOf("Review", "Review next", "Move up", "Move down", "Resume scrolling", "✓ Done", "Remove from queue") })
            openB.doClick()
            assertEquals(listOf("review:b:1"), calls)
            val c = CompletedAgent("c:1", "c", "Agent C", 2000, true)
            panel.update(listOf(b, a, c), "b")
            assertSame(openB, buttons().first { it.text.startsWith("Agent B") })
            assertTrue(openB.text.contains("Current"))
            assertEquals(listOf("review:b:1"), calls) // Model refresh never opens/removes a thread.
            closeB.doClick()
            assertEquals(listOf("review:b:1", "remove:b:1"), calls)
            panel.update(listOf(a, c), "a")
            assertTrue(buttons().none { it.text.startsWith("Agent B") })
            panel.update(emptyList())
            assertTrue(buttons().isEmpty())
        }
    }
}
