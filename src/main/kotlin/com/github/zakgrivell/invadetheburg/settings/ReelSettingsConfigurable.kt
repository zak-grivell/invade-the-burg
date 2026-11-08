package com.github.zakgrivell.invadetheburg.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import com.github.zakgrivell.invadetheburg.ui.ReelSetupDialog
import com.github.zakgrivell.invadetheburg.ui.MultiAgentPreviewDialog
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

class ReelSettingsConfigurable(private val settingsProvider: () -> ReelSettings = { service<ReelSettings>() }) : SearchableConfigurable {
    private var presetPicker: javax.swing.JComboBox<String>? = null
    private var recapMode: javax.swing.JComboBox<RecapMode>? = null
    private var voiceId: JBTextField? = null
    private var urlField: JBTextField? = null
    private var etaCheckBox: JCheckBox? = null
    private var favoritesTable: JTable? = null
    private var favoritesModel: DefaultTableModel? = null
    override fun getId() = "dev.reelbreak.settings"
    override fun getDisplayName() = "Reel Break"
    override fun createComponent(): JComponent {
        val settings = settingsProvider()
        val input = JBTextField(settings.url).also { urlField = it }
        val presets = websitePresets()
        val preset = javax.swing.JComboBox((listOf("Custom website") + presets.map { it.name }).toTypedArray())
        presetPicker = preset
        preset.selectedIndex = presets.indexOfFirst { it.url == settings.url } + 1
        preset.addActionListener { if (preset.selectedIndex > 0) input.text = presets[preset.selectedIndex - 1].url }
        fun signIn(parent: JComponent, url: String) {
            try { ReelSetupDialog(parent, normalizeBreakUrl(url)).show() }
            catch (error: IllegalArgumentException) { Messages.showErrorDialog(parent, error.message ?: "Invalid URL", "Reel Break Setup") }
        }
        val websiteForm = form().apply {
            row("Preset:", preset)
            row("Website URL:", JPanel(BorderLayout(8, 0)).apply {
                add(input, BorderLayout.CENTER)
                add(JButton("Sign in…").apply { addActionListener { signIn(input, input.text) } }, BorderLayout.EAST)
            }, input)
        }
        val model = object : DefaultTableModel(arrayOf("Name", "Website URL"), 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
        }
        favoritesModel = model
        settings.favorites.forEach { model.addRow(arrayOf(it.name, it.url)) }
        val table = JTable(model).apply {
            putClientProperty("terminateEditOnFocusLost", true)
            fillsViewportHeight = true
            selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            preferredScrollableViewportSize = java.awt.Dimension(440, rowHeight * 6)
        }
        favoritesTable = table
        val remove = JButton("Remove").apply {
            isEnabled = false
            addActionListener {
                table.cellEditor?.stopCellEditing()
                if (table.selectedRow >= 0) model.removeRow(table.selectedRow)
            }
        }
        val signInSelected = JButton("Sign in…").apply {
            isEnabled = false
            addActionListener {
                table.cellEditor?.stopCellEditing()
                if (table.selectedRow >= 0) signIn(table, model.getValueAt(table.selectedRow, 1)?.toString().orEmpty())
            }
        }
        table.selectionModel.addListSelectionListener {
            remove.isEnabled = table.selectedRow >= 0
            signInSelected.isEnabled = table.selectedRow >= 0
        }
        val favorites = JPanel(BorderLayout(0, 8)).apply {
            border = javax.swing.BorderFactory.createTitledBorder("Favourites")
            add(JBLabel("Presets are always available. Add your own websites here."), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                add(JButton("Add website").apply { addActionListener {
                    table.cellEditor?.stopCellEditing()
                    model.addRow(arrayOf("", "https://"))
                    table.setRowSelectionInterval(model.rowCount - 1, model.rowCount - 1)
                    table.editCellAt(model.rowCount - 1, 0)
                    table.editorComponent?.requestFocusInWindow()
                } })
                add(remove)
                add(signInSelected)
            }, BorderLayout.SOUTH)
        }
        val mode = javax.swing.JComboBox(RecapMode.entries.toTypedArray()).also { recapMode = it; it.selectedItem = settings.recapMode }
        val voice = JBTextField(settings.narrationVoiceId).also { voiceId = it }
        val recapForm = form().apply {
            row("Show recap:", mode)
            row("", JBLabel("<html>Ask offers a recap after each turn. Always opens it automatically.<br>Never turns recaps off.</html>"))
            row("Voice ID:", voice)
            row("", JBLabel("<html>Uses the hosted narration service; no local API key is needed.<br>Completed responses are sent through the service to ElevenLabs.</html>"))
        }
        fun updateRecapFields() {
            val enabled = mode.selectedItem != RecapMode.NEVER
            voice.isEnabled = enabled
        }
        mode.addActionListener { updateRecapFields() }
        updateRecapFields()
        val eta = JCheckBox("Use ETA to time breaks", settings.useEtaToTimeBreaks).also { etaCheckBox = it }
        val behavior = form().apply {
            row("", eta)
            row("", JBLabel("<html>Skip very short waits and show an estimate during longer breaks.</html>"))
            row("", JButton("Preview multiple agents…").apply { addActionListener { MultiAgentPreviewDialog(this).show() } })
        }
        fun page(content: JComponent): JComponent = JScrollPane(JPanel(BorderLayout()).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(16, 12, 16, 12)
            add(content, BorderLayout.NORTH)
        }).apply {
            border = javax.swing.BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        return JPanel(BorderLayout(0, 12)).apply {
            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                for ((title, section) in listOf("Website" to websiteForm, "Chat recaps" to recapForm, "Break behaviour" to behavior, "" to favorites)) {
                    section.alignmentX = 0f
                    if (title.isNotEmpty()) section.border = javax.swing.BorderFactory.createTitledBorder(title)
                    section.maximumSize = java.awt.Dimension(Int.MAX_VALUE, section.preferredSize.height)
                    add(section)
                    add(javax.swing.Box.createVerticalStrut(12))
                }
            }
            add(page(content), BorderLayout.CENTER)
            add(JBLabel("Changes are saved when you click Apply or OK."), BorderLayout.SOUTH)
        }
    }

    private fun form() = JPanel(java.awt.GridBagLayout())
    private fun JPanel.row(text: String, control: JComponent, focus: JComponent = control) {
        val y = componentCount / 2
        add(JBLabel(text).apply { labelFor = focus }, java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = y; anchor = java.awt.GridBagConstraints.NORTHWEST
            insets = java.awt.Insets(5, 0, 8, 12)
        })
        add(control, java.awt.GridBagConstraints().apply {
            gridx = 1; gridy = y; weightx = 1.0; fill = java.awt.GridBagConstraints.HORIZONTAL
            anchor = java.awt.GridBagConstraints.NORTHWEST; insets = java.awt.Insets(0, 0, 8, 0)
        })
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
        val settings = settingsProvider()
        return etaCheckBox?.isSelected != settings.useEtaToTimeBreaks || recapMode?.selectedItem != settings.recapMode || voiceId?.text?.trim() != settings.narrationVoiceId || urlField?.text?.trim() != settings.url || readFavorites() != settings.favorites || favoritesTable?.isEditing == true
    }
    override fun reset() {
        favoritesTable?.cellEditor?.cancelCellEditing()
        val settings = settingsProvider()
        recapMode?.selectedItem = settings.recapMode
        voiceId?.text = settings.narrationVoiceId
        urlField?.text = settings.url
        presetPicker?.selectedIndex = websitePresets().indexOfFirst { it.url == settings.url } + 1
        etaCheckBox?.isSelected = settings.useEtaToTimeBreaks
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
            settingsProvider().let { etaCheckBox?.let { box -> it.useEtaToTimeBreaks = box.isSelected }; it.url = url; it.favorites = favorites; it.recapMode = recapMode?.selectedItem as? RecapMode ?: RecapMode.ASK; it.narrationVoiceId = voiceId?.text.orEmpty() }
        }
        catch (error: IllegalArgumentException) { throw ConfigurationException(error.message ?: "Invalid URL") }
    }
    override fun disposeUIResources() { presetPicker = null; etaCheckBox = null; recapMode = null; voiceId = null; urlField = null; favoritesTable = null; favoritesModel = null }
}
