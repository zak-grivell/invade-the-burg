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
    @Test fun `sidebar actions preserve selected identity when other agents finish or move`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val panel = CompletedAgentsPanel({ calls.add("review:$it") }, { calls.add("remove:$it") }, { id, direction -> calls.add("move:$id:$direction") }, { calls.add("next") })
            val components = all(panel)
            val list = components.filterIsInstance<JList<*>>().single()
            fun button(text: String) = components.filterIsInstance<JButton>().single { it.text == text }
            assertFalse(button("Review next").isEnabled)
            val a = CompletedAgent("a:1", "a", "Agent A", 62000, false)
            val b = CompletedAgent("b:1", "b", "Agent B", 3000, false)
            panel.update(listOf(a, b)); list.selectedIndex = 1
            val c = CompletedAgent("c:1", "c", "Agent C", 2000, true)
            panel.update(listOf(c, a, b))
            assertEquals(b, list.selectedValue)
            button("Review").doClick(); button("Move up").doClick(); button("✓ Done").doClick(); button("Remove from queue").doClick(); button("Review next").doClick()
            assertEquals(listOf("review:b:1", "move:b:1:-1", "remove:b:1", "remove:b:1", "next"), calls)
            panel.update(emptyList())
            assertFalse(button("Review").isEnabled)
            assertFalse(button("Move down").isEnabled)
            assertFalse(button("Review next").isEnabled)
        }
    }
}
