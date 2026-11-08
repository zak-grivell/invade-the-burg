package com.github.zakgrivell.invadetheburg.ui

import com.github.zakgrivell.invadetheburg.services.CompletedAgent
import com.github.zakgrivell.invadetheburg.services.ReelService
import com.github.zakgrivell.invadetheburg.services.promptDuration
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.*

class CompletedAgentsToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<ReelService>()
        val panel = CompletedAgentsPanel(service::reviewCompleted, service::removeCompleted, service::moveCompleted, service::reviewNextCompleted)
        val subscription = service.onQueueChanged { entries ->
            panel.update(entries)
            toolWindow.title = "Completed agents (${entries.size})"
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(Disposable { subscription() })
        toolWindow.contentManager.addContent(content)
        service.start()
    }
}

/** Identity-based actions remain correct if a completion arrives while the user is selecting a row. */
internal class CompletedAgentsPanel(
    private val review: (String) -> Unit,
    private val remove: (String) -> Unit,
    private val move: (String, Int) -> Unit,
    next: () -> Unit
) : JPanel(BorderLayout(0, 8)) {
    private val model = DefaultListModel<CompletedAgent>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ListCellRenderer { _, item, _, selected, _ ->
            JLabel().apply {
                putClientProperty("html.disable", true)
                text = "${item.name}  ·  ${promptDuration(item)}"
                border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
                isOpaque = true
                background = if (selected) UIManager.getColor("List.selectionBackground") else UIManager.getColor("List.background")
                foreground = if (selected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("List.foreground")
                toolTipText = if (item.partial) "Time observed since this running prompt was first seen" else "Elapsed prompt time, including permission waits"
            }
        }
    }
    private val empty = JLabel("No completed prompts waiting.")
    private val open = JButton("Review").apply { addActionListener { selected()?.let { review(it.id) } } }
    private val done = JButton("✓ Done").apply { addActionListener { selected()?.let { remove(it.id) } } }
    private val discard = JButton("Remove from queue").apply { addActionListener { selected()?.let { remove(it.id) } } }
    private val up = JButton("Move up").apply { addActionListener { selected()?.let { move(it.id, -1) } } }
    private val down = JButton("Move down").apply { addActionListener { selected()?.let { move(it.id, 1) } } }
    private val nextButton = JButton("Review next").apply { addActionListener { next() } }
    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(JPanel(BorderLayout(0, 6)).apply {
            add(nextButton, BorderLayout.NORTH)
            add(JLabel("Finish order · reorder with Move up / Move down"), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply { add(empty, BorderLayout.NORTH); add(JScrollPane(list), BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(JPanel(java.awt.GridLayout(0, 2, 6, 6)).apply { listOf(open, done, discard, up, down).forEach { add(it) } }, BorderLayout.SOUTH)
        list.addListSelectionListener { refreshButtons() }
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "review")
        list.actionMap.put("review", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { open.doClick() } })
        list.inputMap.put(KeyStroke.getKeyStroke("DELETE"), "remove")
        list.actionMap.put("remove", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { discard.doClick() } })
        refreshButtons()
    }
    private fun selected() = list.selectedValue
    fun update(entries: List<CompletedAgent>) {
        if ((0 until model.size()).map { model[it] } == entries) return
        val id = selected()?.id
        val previousIndex = list.selectedIndex
        model.clear()
        entries.forEach(model::addElement)
        val retained = entries.indexOfFirst { it.id == id }
        if (entries.isNotEmpty()) list.selectedIndex = if (retained >= 0) retained else previousIndex.coerceIn(0, entries.lastIndex)
        refreshButtons()
    }
    private fun refreshButtons() {
        empty.isVisible = model.isEmpty
        nextButton.isEnabled = !model.isEmpty
        listOf(open, done, discard).forEach { it.isEnabled = selected() != null }
        up.isEnabled = list.selectedIndex > 0
        down.isEnabled = list.selectedIndex >= 0 && list.selectedIndex < model.size() - 1
    }
}
