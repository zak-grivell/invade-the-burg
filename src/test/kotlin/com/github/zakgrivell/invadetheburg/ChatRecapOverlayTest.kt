package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.*

class ChatRecapOverlayTest {
    private class VisiblePane : JPanel() {
        override fun isShowing() = true
        override fun getVisibleRect() = java.awt.Rectangle(0, 0, width, height)
    }
    @Test fun `recap mounts only over originating editor and pauses when hidden`() {
        SwingUtilities.invokeAndWait {
            val root = JRootPane().apply { setSize(1000, 700); doLayout(); contentPane.layout = null }
            root.layeredPane.setBounds(0, 0, 1000, 700)
            root.contentPane.setBounds(0, 0, 1000, 700)
            val editor = VisiblePane().apply { setBounds(500, 0, 500, 700) }
            root.contentPane.add(editor)
            var completions = 0
            var finished: () -> Unit = {}
            var activations = 0
            var active = false
            var disposed = false
            val fake = object : BreakPage {
                override fun onFinished(callback: () -> Unit) { finished = callback }
                override val ready = false // A native browser must be mounted before it can load.
                override val component = JPanel()
                override fun loadUrl(url: String) {}
                override fun setActive(value: Boolean) { active = value; if (value) activations++ }
                override fun dispose() { disposed = true }
            }
            val media = RecapMedia("Response", ByteArray(20))
            val overlay = ChatRecapOverlay(media, { completions++ }, { fake })
            try {
                overlay.showOver(editor)
                val views = root.layeredPane.getComponentsInLayer(JLayeredPane.MODAL_LAYER)
                assertEquals("root=${editor.rootPane === root} showing=${editor.isShowing} visible=${editor.visibleRect} layer=${root.layeredPane.bounds} children=${root.layeredPane.components.map { it.javaClass.simpleName + ":" + root.layeredPane.getLayer(it) + ":" + it.bounds }} active=$active", 1, views.size)
                assertEquals(500, views[0].x)
                assertEquals(500, views[0].width)
                assertTrue(active)
                repeat(20) { overlay.showOver(editor) }
                assertEquals("Polling must not repeatedly resume narration", 1, activations)
                overlay.hide()
                assertFalse(active)
                assertFalse(views[0].isVisible)
                overlay.showOver(editor)
                assertEquals("Returning to the recap resumes exactly once", 2, activations)
                finished(); finished()
                assertEquals("Playback completion dismisses the overlay exactly once", 1, completions)
                assertFalse(active)
                overlay.showOver(editor)
                assertEquals("Finished recap cannot autoplay again", 2, activations)
            } finally { overlay.dispose() }
            assertTrue(disposed)
            assertEquals(0, root.layeredPane.getComponentsInLayer(JLayeredPane.MODAL_LAYER).size)
        }
    }
    @Test fun `async recap stays uncreated until its own chat is selected and opens no windows`() {
        SwingUtilities.invokeAndWait {
            val windowsBefore = java.awt.Window.getWindows().toSet()
            val root = JRootPane().apply { setSize(800, 600); doLayout() }
            val editor = VisiblePane().apply { setBounds(0, 0, 800, 600) }
            root.contentPane.add(editor)
            var created = 0
            var activePages = 0
            fun recap() = ChatRecapOverlay(RecapMedia("Response", ByteArray(20)), {}, {
                created++
                object : BreakPage {
                    override val component = JPanel()
                    private var active = false
                    override fun loadUrl(url: String) {}
                    override fun setActive(active: Boolean) {
                        if (this.active != active) activePages += if (active) 1 else -1
                        this.active = active
                    }
                    override fun dispose() {}
                }
            })
            val first = recap()
            val background = recap()
            val recaps = listOf(first, background)
            try {
                renderSelectedRecap(recaps, null, editor)
                assertEquals(0, created)
                renderSelectedRecap(recaps, first, editor)
                repeat(20) { renderSelectedRecap(recaps, first, editor) }
                assertEquals("Background async result must not create or start a browser", 1, created)
                assertEquals(1, activePages)
                renderSelectedRecap(recaps, background, editor)
                assertEquals(2, created)
                assertEquals("Switching chats pauses the first recap", 1, activePages)
                renderSelectedRecap(recaps, null, editor)
                assertEquals(0, activePages)
                assertEquals(windowsBefore, java.awt.Window.getWindows().toSet())
            } finally { first.dispose(); background.dispose() }
        }
    }

}
