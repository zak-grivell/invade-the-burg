package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.ReelOverlay
import org.junit.Assert.*
import org.junit.Test
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities

class ReelReturnButtonTest {
    @Test fun `return control stays at top right when the chat resizes and still resumes`() {
        SwingUtilities.invokeAndWait {
            val root = JRootPane()
            val editor = object : JPanel() {
                override fun isShowing() = true
                override fun getVisibleRect() = Rectangle(0, 0, width, height)
            }
            root.contentPane.layout = null
            root.contentPane.add(editor)
            var resumed = 0
            val overlay = ReelOverlay({}, { resumed++ }, {})
            try {
                var top: Int? = null
                for (width in listOf(800, 500)) {
                    root.setSize(width, 600)
                    root.doLayout()
                    editor.setBounds(0, 0, width, 600)
                    overlay.showReturnButton(editor)
                    val button = root.layeredPane.components.filterIsInstance<JButton>().single()
                    assertTrue(button.isVisible)
                    assertEquals("Back to Reels", button.text)
                    assertEquals(width - 5, button.x + button.width)
                    assertTrue("Return control belongs in the header", button.y in 0..20)
                    top?.let { assertEquals(it, button.y) }
                    top = button.y
                    button.doClick()
                }
                assertEquals(2, resumed)
                overlay.hide()
                assertTrue(root.layeredPane.components.filterIsInstance<JButton>().none { it.isVisible })
            } finally { overlay.dispose() }
        }
    }
}
