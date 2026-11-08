package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import com.github.zakgrivell.invadetheburg.ui.BrowserInputGate
import com.github.zakgrivell.invadetheburg.ui.requestChatFocus
import org.junit.Assert.*
import org.junit.Test
import javax.swing.*

class BrowserViewStateTest {
    private val running = PaneActivity("a", true, "PROCESSING")
    private val done = PaneActivity("a", false, "READY")
    @Test fun `navigating to another running thread suspends until explicit resume`() {
        val state = BrowserViewState()
        assertTrue(state.showBrowser(running))
        state.viewChanged(true)
        repeat(5) { assertFalse(state.showBrowser(running)) }
        state.resume()
        assertTrue(state.showBrowser(running))
        state.viewChanged(true)
        assertFalse(state.showBrowser(running))
    }
    @Test fun `new draft stays editable until its prompt actually starts`() {
        val state = BrowserViewState()
        state.showBrowser(running)
        state.viewChanged(false)
        repeat(5) { assertFalse(state.showBrowser(PaneActivity("new", false, "DRAFT"))) }
        assertTrue(state.showBrowser(PaneActivity("new", true, "PROCESSING")))
    }
    @Test fun `manual scrolling from completed chat ends on next prompt completion`() {
        val state = BrowserViewState()
        state.viewChanged(false)
        state.resume()
        assertTrue(state.showBrowser(done))
        assertTrue(state.showBrowser(running))
        assertFalse(state.showBrowser(done))
        assertFalse(state.showBrowser(null))
    }
    @Test fun `dismiss current scrolling stays paused until next prompt or Resume`() {
        val state = BrowserViewState()
        state.showBrowser(running)
        state.suspend()
        assertFalse(state.showBrowser(running))
        state.showBrowser(done)
        assertTrue(state.showBrowser(running))
    }
    @Test fun `sidebar is limited to visible chats and recaps not browser scrolling`() {
        for (target in listOf("chat", "recap")) assertTrue(queueVisibleFor(target, done, true))
        for (target in listOf("break", "completion")) assertFalse(queueVisibleFor(target, done, true))
        assertFalse(queueVisibleFor("chat", null, true))
        assertFalse(queueVisibleFor("recap", done, false))
    }
    @Test fun `focus handoff chooses the composer instead of read-only transcript or panel`() {
        SwingUtilities.invokeAndWait {
            val root = JPanel()
            val history = object : JTextArea() { override fun isShowing() = true }.apply { isEditable = false }
            val composer = object : JTextArea() { override fun isShowing() = true }
            root.add(history); root.add(composer)
            assertSame(composer, com.github.zakgrivell.invadetheburg.ui.chatFocusTarget(root, root))
            composer.isEnabled = false
            assertNull(com.github.zakgrivell.invadetheburg.ui.chatFocusTarget(null, root))
        }
    }
    @Test fun `hidden retained browser cannot accept focus and original control states restore`() {
        SwingUtilities.invokeAndWait {
            val gate = BrowserInputGate()
            val panel = JPanel()
            val input = JTextField()
            val disabled = JButton().apply { isEnabled = false; isFocusable = false }
            panel.add(input); panel.add(disabled)
            gate.setActive(panel, false)
            assertFalse(input.isEnabled)
            assertFalse(input.isFocusable)
            gate.setActive(panel, false)
            val late = JTextField().also { panel.add(it) }
            gate.setActive(panel, false)
            assertFalse(late.isFocusable)
            gate.setActive(panel, true)
            assertTrue(input.isEnabled)
            assertTrue(input.isFocusable)
            assertTrue(late.isFocusable)
            assertFalse(disabled.isEnabled)
            assertFalse(disabled.isFocusable)
        }
    }
    @Test fun `returning from browser resolves composer after hide instead of focusing a removed control`() {
        SwingUtilities.invokeAndWait {
            val root = JPanel()
            val old = object : JTextArea() { override fun isShowing() = false }
            val composer = object : JTextArea() { override fun isShowing() = true }
            val tasks = mutableListOf<() -> Unit>()
            val focused = mutableListOf<java.awt.Component>()
            requestChatFocus(root, { old }, { true }, { focused.add(it) }, { tasks.add(it) })
            assertTrue(focused.isEmpty())
            root.add(composer) // Chat only becomes editable after the overlay is hidden.
            tasks.single().invoke()
            assertEquals(listOf(composer), focused)
        }
    }
    @Test fun `pending composer focus cannot steal input after a pane change or new dialog`() {
        SwingUtilities.invokeAndWait {
            val composer = object : JTextArea() { override fun isShowing() = true }
            val tasks = mutableListOf<() -> Unit>()
            var stillCurrent = true
            requestChatFocus(composer, { fail("Stale editor must not be inspected"); null }, { stillCurrent }, { fail("Stale callback stole focus") }, { tasks.add(it) })
            stillCurrent = false
            tasks.single().invoke()
        }
    }
}
