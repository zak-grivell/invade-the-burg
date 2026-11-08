package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.*

class CompletionAnimationTest {
    private class VisiblePane : JPanel() {
        override fun isShowing() = true
        override fun getVisibleRect() = java.awt.Rectangle(0, 0, width, height)
    }
    @Test fun `short transition covers swaps once and ignores repeated polling`() {
        SwingUtilities.invokeAndWait {
            val root = JRootPane().apply { setSize(600, 400); doLayout() }
            val editor = VisiblePane().apply { setBounds(0, 0, 600, 400) }
            root.contentPane.add(editor)
            var clock = 0L
            var shown = "chat"
            val transition = PaneTransition { clock }
            try {
                transition.show("break", editor) { shown = "break" }
                assertEquals("chat", shown)
                clock = 90_000_000
                transition.advance()
                assertEquals("break", shown)
                clock = 180_000_000
                transition.advance()
                transition.show("break", editor) { shown = "updated break" }
                assertEquals("updated break", shown)
                assertTrue(root.layeredPane.components.filter { root.layeredPane.getLayer(it) == 450 }.none { it.isVisible })
            } finally { transition.dispose() }
        }
    }
    @Test fun `new turn cancels pending recap without showing stale video`() {
        SwingUtilities.invokeAndWait {
            val root = JRootPane().apply { setSize(600, 400); doLayout() }
            val editor = VisiblePane().apply { setBounds(0, 0, 600, 400) }
            root.contentPane.add(editor)
            var clock = 0L
            var shown = "chat"
            val transition = PaneTransition { clock }
            try {
                transition.show("recap", editor) { fail("Stale recap must never appear") }
                clock = 40_000_000
                transition.advance()
                transition.show("break", editor) { shown = "break" }
                clock = 130_000_000
                transition.advance()
                assertEquals("break", shown)
                transition.dispose()
                clock = 500_000_000
                transition.advance()
            } finally { transition.dispose() }
        }
    }
}
