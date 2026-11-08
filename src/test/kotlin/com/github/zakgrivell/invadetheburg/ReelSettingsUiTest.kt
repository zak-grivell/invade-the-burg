package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.settings.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.*

class ReelSettingsUiTest {
    private fun components(root: Component): List<Component> = listOf(root) +
        if (root is Container) root.components.flatMap(::components) else emptyList()

    @Test fun `quick settings precede favourites and edits apply and reset correctly`() {
        SwingUtilities.invokeAndWait {
            val saved = ReelSettings()
            val ui = ReelSettingsConfigurable { saved }
            try {
                val controls = components(ui.createComponent())
                val table = controls.filterIsInstance<JTable>().single()
                fun field(label: String) = controls.filterIsInstance<JLabel>().single { it.text == label }.labelFor as JTextField
                val mode = controls.filterIsInstance<JComboBox<*>>().single { it.selectedItem is RecapMode }
                assertTrue(controls.indexOf(mode) < controls.indexOf(table))
                assertTrue(controls.indexOf(field("Website URL:")) < controls.indexOf(table))
                assertFalse(ui.isModified)
                val presets = controls.filterIsInstance<JComboBox<*>>().single { it !== mode }
                presets.selectedIndex = 5
                assertEquals("https://www.duolingo.com/learn", field("Website URL:").text)
                assertTrue(ui.isModified)
                mode.selectedItem = RecapMode.NEVER
                assertFalse(field("Voice ID:").isEnabled)
                assertTrue("Bundled gameplay has no source field", controls.filterIsInstance<JLabel>().none { it.text == "Background video:" })
                ui.apply()
                assertEquals(RecapMode.NEVER, saved.recapMode)
                assertEquals("https://www.duolingo.com/learn", saved.url)
                assertFalse(ui.isModified)
                field("Website URL:").text = "https://example.com/"
                ui.reset()
                assertEquals(saved.url, field("Website URL:").text)
                assertFalse(ui.isModified)
                val remove = controls.filterIsInstance<JButton>().single { it.text == "Remove" }
                assertFalse(remove.isEnabled)
                table.setRowSelectionInterval(0, 0)
                assertTrue(remove.isEnabled)
            } finally { ui.disposeUIResources() }
        }
    }
}
