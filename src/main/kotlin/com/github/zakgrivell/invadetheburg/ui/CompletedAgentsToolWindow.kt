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
    override fun init(toolWindow: ToolWindow) { toolWindow.setAvailable(false, null) }
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<ReelService>()
        val panel = CompletedAgentsPanel(service::reviewCompleted, service::removeCompleted)
        val subscription = service.onQueueChanged { view -> panel.update(view.entries, view.activeThread) }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(Disposable { subscription() })
        toolWindow.contentManager.addContent(content)
        // The tool window already has its own heading; a title creates a second tab/header.
        toolWindow.title = ""
        service.start()
    }
}

/** Real per-row buttons support click, keyboard focus and accessibility without a separate toolbar. */
internal class CompletedAgentsPanel(
    private val review: (String) -> Unit,
    private val remove: (String) -> Unit
) : JPanel(BorderLayout()) {
    private inner class Row(val id: String) : JPanel(BorderLayout(6, 0)) {
        val open = JButton().apply {
            putClientProperty("html.disable", true)
            horizontalAlignment = SwingConstants.LEFT
            isContentAreaFilled = false
            isBorderPainted = false
            addActionListener { review(id) }
        }
        val close = JButton("×").apply {
            toolTipText = "Remove this completion from the queue"
            isContentAreaFilled = false
            isBorderPainted = false
            addActionListener { remove(id) }
        }
        init {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(open, BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
            alignmentX = 0f
        }
        fun update(entry: CompletedAgent, active: Boolean) {
            open.text = "${entry.name}  ·  ${promptDuration(entry)}" + if (active) "  (Current)" else ""
            open.toolTipText = entry.name + if (entry.partial) " — observed time; prompt was already running" else " — elapsed prompt time"
            close.accessibleContext.accessibleName = "Remove ${entry.name} from queue"
            background = UIManager.getColor(if (active) "List.selectionBackground" else "List.background")
            open.foreground = UIManager.getColor(if (active) "List.selectionForeground" else "List.foreground")
            close.foreground = open.foreground
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }
    private val rows = linkedMapOf<String, Row>()
    private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val empty = JLabel("No completed prompts waiting.").apply { border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
    init {
        add(empty, BorderLayout.NORTH)
        add(JScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)
    }
    fun update(entries: List<CompletedAgent>, activeThread: String? = null) {
        val live = entries.map { it.id }.toSet()
        rows.keys.filter { it !in live }.forEach { id -> list.remove(rows.remove(id)) }
        entries.forEachIndexed { index, entry ->
            val row = rows.getOrPut(entry.id) { Row(entry.id).also { list.add(it) } }
            row.update(entry, entry.thread == activeThread)
            if (list.getComponentZOrder(row) != index) list.setComponentZOrder(row, index)
        }
        empty.isVisible = entries.isEmpty()
        list.revalidate()
        list.repaint()
    }
}
