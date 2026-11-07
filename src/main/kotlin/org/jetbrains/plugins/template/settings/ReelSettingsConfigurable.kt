package org.jetbrains.plugins.template.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.template.ui.ReelSetupDialog
import org.jetbrains.plugins.template.ui.MultiAgentPreviewDialog
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

class ReelSettingsConfigurable : SearchableConfigurable {
    private var urlField: JBTextField? = null
    private var favoritesTable: JTable? = null
    private var favoritesModel: DefaultTableModel? = null
    override fun getId() = "dev.reelbreak.settings"
    override fun getDisplayName() = "Reel Break"
    override fun createComponent(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        val input = JBTextField(service<ReelSettings>().url)
        urlField = input
        add(JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("URL:"), BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(JButton("Setup").apply {
                toolTipText = "Open this website to sign in and set up your account"
                addActionListener {
                    val url = try { normalizeBreakUrl(input.text) }
                    catch (error: IllegalArgumentException) {
                        Messages.showErrorDialog(input, error.message ?: "Invalid URL", "Reel Break Setup")
                        return@addActionListener
                    }
                    ReelSetupDialog(input, url).show()
                }
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        val model = object : DefaultTableModel(arrayOf("Name", "Website URL"), 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
        }
        favoritesModel = model
        service<ReelSettings>().favorites.forEach { model.addRow(arrayOf(it.name, it.url)) }
        val table = JTable(model).apply {
            putClientProperty("terminateEditOnFocusLost", true)
            fillsViewportHeight = true
            selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        }
        favoritesTable = table
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JBLabel("Favourites — choose these websites from the switcher during a break."), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel().apply {
                add(JButton("Add").apply { addActionListener {
                    table.cellEditor?.stopCellEditing()
                    model.addRow(arrayOf("", "https://"))
                    val row = model.rowCount - 1
                    table.setRowSelectionInterval(row, row)
                    table.editCellAt(row, 0)
                    table.editorComponent?.requestFocusInWindow()
                } })
                add(JButton("Remove").apply { addActionListener {
                    table.cellEditor?.stopCellEditing()
                    val row = table.selectedRow
                    if (row >= 0) model.removeRow(row)
                } })
                add(JButton("Setup selected").apply { addActionListener {
                    table.cellEditor?.stopCellEditing()
                    val row = table.selectedRow
                    if (row < 0) return@addActionListener
                    val url = try { normalizeBreakUrl(model.getValueAt(row, 1)?.toString().orEmpty()) }
                    catch (error: IllegalArgumentException) {
                        Messages.showErrorDialog(table, error.message ?: "Invalid URL", "Reel Break Setup")
                        return@addActionListener
                    }
                    ReelSetupDialog(table, url).show()
                } })
                add(JButton("Preview multiple agents").apply { addActionListener {
                    MultiAgentPreviewDialog(table).show()
                } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(JBLabel("<html>Edit favourite names and URLs in the table. Use complete HTTP or HTTPS addresses.<br>Setup opens a website so you can sign in. Apply or OK saves your changes.<br>Switching websites navigates the current browser; the previous page's position may be lost.</html>"), BorderLayout.SOUTH)
    }
    override fun getPreferredFocusedComponent(): JComponent? = urlField
    private fun readFavorites(): List<FavoriteWebsite> {
        val model = favoritesModel ?: return emptyList()
        return (0 until model.rowCount).map {
            FavoriteWebsite(model.getValueAt(it, 0)?.toString().orEmpty(), model.getValueAt(it, 1)?.toString().orEmpty())
        }
    }
    override fun isModified(): Boolean {
        if (urlField == null) return false
        val settings = service<ReelSettings>()
        return urlField?.text?.trim() != settings.url || readFavorites() != settings.favorites || favoritesTable?.isEditing == true
    }
    override fun reset() {
        favoritesTable?.cellEditor?.cancelCellEditing()
        val settings = service<ReelSettings>()
        urlField?.text = settings.url
        favoritesModel?.let { model ->
            model.rowCount = 0
            settings.favorites.forEach { model.addRow(arrayOf(it.name, it.url)) }
        }
    }
    override fun apply() {
        val value = urlField?.text ?: return
        favoritesTable?.cellEditor?.stopCellEditing()
        try {
            val url = normalizeBreakUrl(value)
            val favorites = normalizeFavorites(readFavorites())
            service<ReelSettings>().let { it.url = url; it.favorites = favorites }
        }
        catch (error: IllegalArgumentException) { throw ConfigurationException(error.message ?: "Invalid URL") }
    }
    override fun disposeUIResources() { urlField = null; favoritesTable = null; favoritesModel = null }
}
