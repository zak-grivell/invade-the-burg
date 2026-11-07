package org.jetbrains.plugins.template.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import org.jetbrains.plugins.template.settings.ReelSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/** Interactive design preview. No real agent connections or browser sessions are created. */
internal class MultiAgentPreviewDialog(parent: JComponent) : DialogWrapper(parent, true) {
    private enum class Status(private val label: String) {
        WORKING("Working"), NEEDS_INPUT("Needs input"), DONE("Done");
        override fun toString() = label
    }
    private data class DemoAgent(val name: String, val task: String, var status: Status) {
        override fun toString() = "$name · $status"
    }
    private val agents = listOf(
        DemoAgent("Codex", "Build the website switcher", Status.WORKING),
        DemoAgent("Claude", "Review the settings screen", Status.WORKING),
        DemoAgent("Junie", "Check the plugin tests", Status.NEEDS_INPUT)
    )
    private val listModel = DefaultListModel<DemoAgent>().apply { agents.forEach { addElement(it) } }
    private val agentList = JList(listModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val statusPicker = JComboBox(Status.entries.toTypedArray())
    private val summary = JBLabel()
    private val content = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val breakButton = JButton("Open Reels")
    private var showingBreak = false
    private var updating = false
    private val websites = service<ReelSettings>().favorites.ifEmpty {
        listOf(org.jetbrains.plugins.template.settings.FavoriteWebsite("Current website", service<ReelSettings>().url))
    }
    private val websitePicker = JComboBox(websites.toTypedArray())

    init {
        title = "Multiple Agents — Preview"
        setOKButtonText("Close preview")
        init()
        agentList.selectedIndex = 0
        refresh()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(12, 12)).apply {
        preferredSize = Dimension(850, 520)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(JBLabel("Demo only · sample agents and messages"))
            add(summary)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(220, 400)
            add(JBLabel("Agents"), BorderLayout.NORTH)
            add(JScrollPane(agentList), BorderLayout.CENTER)
        }, BorderLayout.WEST)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JPanel().apply {
                add(JBLabel("Simulate status:"))
                add(statusPicker)
            }, BorderLayout.NORTH)
            add(JScrollPane(content), BorderLayout.CENTER)
            add(JPanel().apply {
                add(websitePicker)
                add(breakButton)
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(JBLabel("Select an agent to view its chat. Change its status to try completion and permission requests."), BorderLayout.SOUTH)
        agentList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) { showingBreak = false; refresh() }
        }
        statusPicker.addActionListener {
            if (!updating) {
                agentList.selectedValue?.status = statusPicker.selectedItem as Status
                if (agentList.selectedValue?.status != Status.WORKING) showingBreak = false
                refresh()
            }
        }
        websitePicker.addActionListener { refresh() }
        breakButton.addActionListener { showingBreak = !showingBreak; refresh() }
    }

    private fun refresh() {
        val agent = agentList.selectedValue ?: return
        updating = true
        try { statusPicker.selectedItem = agent.status } finally { updating = false }
        val working = agents.count { it.status == Status.WORKING }
        val waiting = agents.count { it.status == Status.NEEDS_INPUT }
        summary.text = "$working working · $waiting need input · ${agents.count { it.status == Status.DONE }} done"
        agentList.repaint()
        breakButton.isEnabled = agent.status == Status.WORKING
        breakButton.text = if (showingBreak) "Back to chat" else "Open Reels"
        websitePicker.isEnabled = showingBreak
        content.text = if (showingBreak) {
            val website = websites[websitePicker.selectedIndex.coerceAtLeast(0)]
            "BREAK PREVIEW\n\n${website.name}\n${website.url}\n\nYour website would appear here while ${agent.name} works.\n\nOther agents stay visible in the list. Select one to check its chat.\n\nThis preview does not open websites or change your settings."
        } else {
            val message = when (agent.status) {
                Status.WORKING -> "I'm working on this task. You can open a break and come back to check my progress."
                Status.NEEDS_INPUT -> "I need your permission before continuing. Your chat is visible so you can respond."
                Status.DONE -> "This task is complete. Your chat is visible so you can review the result."
            }
            "${agent.name}\n${agent.task}\n\nSample message:\n$message"
        }
        content.caretPosition = 0
    }
}
