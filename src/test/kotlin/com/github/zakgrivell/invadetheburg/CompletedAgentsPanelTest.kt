package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.CompletedAgent
import com.github.zakgrivell.invadetheburg.ui.CompletedAgentsPanel
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.*

class CompletedAgentsPanelTest {
    private fun all(component: Component): List<Component> = listOf(component) + if (component is Container) component.components.flatMap(::all) else emptyList()
    @Test fun `rows open on click and their cross only removes their own completion`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val panel = CompletedAgentsPanel({ calls.add("review:$it") }, { calls.add("remove:$it") }, { _, _, _ -> fail("Click must not reorder") })
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
    private fun layout(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layout)
    }
    private fun mouse(button: JButton, event: Int, point: Point = Point(20, button.height / 2)) {
        button.dispatchEvent(MouseEvent(button, event, System.currentTimeMillis(),
            if (event == MouseEvent.MOUSE_RELEASED) 0 else InputEvent.BUTTON1_DOWN_MASK,
            point.x, point.y, 1, false, if (event == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1))
    }
    @Test fun `dragging names reorders above or below a row without opening or removing threads`() {
        SwingUtilities.invokeAndWait {
            val moves = mutableListOf<Triple<String, String, Boolean>>()
            val panel = CompletedAgentsPanel({ fail("Drag opened $it") }, { fail("Drag removed $it") }, { id, target, after -> moves.add(Triple(id, target, after)) })
            val entries = listOf("a", "b", "c").map { CompletedAgent("$it:1", it, "Agent $it", 1000, false) }
            panel.update(entries)
            panel.setSize(420, 240); layout(panel)
            assertFalse(panel.actionMap.get("cancelDrag").isEnabled) // Leave normal IDE Escape handling alone.
            fun open(id: String) = all(panel).filterIsInstance<JButton>().single { it.text.startsWith("Agent $id") }
            val a = open("a"); val c = open("c")
            fun dragTo(source: JButton, target: JButton, y: Int) {
                mouse(source, MouseEvent.MOUSE_PRESSED)
                val p = SwingUtilities.convertPoint(target, Point(20, y), source)
                mouse(source, MouseEvent.MOUSE_DRAGGED, p)
                mouse(source, MouseEvent.MOUSE_RELEASED, p)
            }
            dragTo(a, c, c.height - 1)
            dragTo(c, a, 1)
            assertEquals(listOf(Triple("a:1", "c:1", true), Triple("c:1", "a:1", false)), moves)
            // Arriving completions do not change which target a drop names.
            mouse(a, MouseEvent.MOUSE_PRESSED)
            val p = SwingUtilities.convertPoint(c, Point(20, c.height - 1), a)
            mouse(a, MouseEvent.MOUSE_DRAGGED, p)
            panel.update(entries + CompletedAgent("d:1", "d", "Agent d", 500, false))
            layout(panel)
            mouse(a, MouseEvent.MOUSE_RELEASED, p)
            assertEquals(Triple("a:1", "c:1", true), moves.last())
            // Cancelling or losing the source cannot trigger a reorder or open action.
            mouse(a, MouseEvent.MOUSE_PRESSED); mouse(a, MouseEvent.MOUSE_DRAGGED, p)
            assertTrue(panel.actionMap.get("cancelDrag").isEnabled)
            panel.actionMap.get("cancelDrag").actionPerformed(null)
            assertFalse(panel.actionMap.get("cancelDrag").isEnabled)
            mouse(a, MouseEvent.MOUSE_RELEASED, p)
            mouse(a, MouseEvent.MOUSE_PRESSED); mouse(a, MouseEvent.MOUSE_DRAGGED, p)
            panel.update(entries.drop(1), "b"); layout(panel)
            mouse(a, MouseEvent.MOUSE_RELEASED, p)
            assertEquals(3, moves.size)
        }
    }
    @Test fun `dragging respects pinned current and cancels when the editor pane changes`() {
        SwingUtilities.invokeAndWait {
            val moves = mutableListOf<Triple<String, String, Boolean>>()
            val panel = CompletedAgentsPanel({ fail("Drag opened $it") }, { fail("Drag removed $it") }, { id, target, after -> moves.add(Triple(id, target, after)) })
            val entries = listOf("a", "b", "c").map { CompletedAgent("$it:1", it, "Agent $it", 1000, false) }
            panel.update(entries, "a"); panel.setSize(420, 240); layout(panel)
            fun open(id: String) = all(panel).filterIsInstance<JButton>().single { it.text.startsWith("Agent $id") }
            val a = open("a"); val c = open("c")
            val aboveA = SwingUtilities.convertPoint(a, Point(20, 1), c)
            mouse(c, MouseEvent.MOUSE_PRESSED); mouse(c, MouseEvent.MOUSE_DRAGGED, aboveA); mouse(c, MouseEvent.MOUSE_RELEASED, aboveA)
            assertEquals(listOf(Triple("c:1", "a:1", true)), moves)
            val belowC = SwingUtilities.convertPoint(c, Point(20, c.height - 1), a)
            mouse(a, MouseEvent.MOUSE_PRESSED); mouse(a, MouseEvent.MOUSE_DRAGGED, belowC); mouse(a, MouseEvent.MOUSE_RELEASED, belowC)
            assertEquals(1, moves.size)
            mouse(c, MouseEvent.MOUSE_PRESSED); mouse(c, MouseEvent.MOUSE_DRAGGED, aboveA)
            panel.update(listOf(entries[1], entries[0], entries[2]), "b"); layout(panel)
            mouse(c, MouseEvent.MOUSE_RELEASED, aboveA)
            assertEquals(1, moves.size)
        }
    }
}
