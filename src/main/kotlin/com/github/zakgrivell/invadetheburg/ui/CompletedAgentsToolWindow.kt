package com.github.zakgrivell.invadetheburg.ui

import com.github.zakgrivell.invadetheburg.services.CompletedAgent
import com.github.zakgrivell.invadetheburg.services.ReelService
import com.github.zakgrivell.invadetheburg.services.promptDuration
import com.github.zakgrivell.invadetheburg.services.tags.AgentTag
import com.github.zakgrivell.invadetheburg.services.tags.agentTagMessage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.MouseInputAdapter

class CompletedAgentsToolWindow : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = false
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<ReelService>()
        val panel = CompletedAgentsPanel(service::reviewCompleted, service::removeCompleted, service::reorderCompleted)
        val subscription = service.onQueueChanged { view -> panel.update(view.entries, view.activeThread, view.tags) }
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
    private val remove: (String) -> Unit,
    private val reorder: (String, String, Boolean) -> Unit
) : JPanel(BorderLayout()) {
    private data class Drop(val targetId: String, val after: Boolean)
    private data class Drag(val id: String, val start: Point, var moving: Boolean = false)
    private var drag: Drag? = null
    private var drop: Drop? = null
    private var activeId: String? = null
    private fun cancelDrag() {
        drag = null
        drop = null
        rows.values.forEach { it.open.cursor = Cursor.getDefaultCursor() }
        list.repaint()
    }
    private fun destination(point: Point): Drop? {
        if (point.x !in 0 until list.width || point.y < 0 || !list.bounds.contains(point)) return null
        val ordered = list.components.filterIsInstance<Row>()
        val row = ordered.firstOrNull { point.y < it.y + it.height } ?: ordered.lastOrNull() ?: return null
        return Drop(row.id, row.id == activeId || point.y >= row.y + row.height / 2)
    }
    private fun dragListener(id: String) = object : MouseInputAdapter() {
        override fun mousePressed(e: MouseEvent) {
            cancelDrag()
            if (SwingUtilities.isLeftMouseButton(e)) drag = Drag(id, SwingUtilities.convertPoint(e.component, e.point, list))
        }
        override fun mouseDragged(e: MouseEvent) {
            val gesture = drag?.takeIf { it.id == id } ?: return
            val point = SwingUtilities.convertPoint(e.component, e.point, list)
            if (!gesture.moving && gesture.start.distance(point) < 5) return
            gesture.moving = true
            // A drag must never fire the name button's normal click-to-open action on release.
            rows[id]?.open?.let {
                it.model.isArmed = false
                it.model.isPressed = false
                it.cursor = Cursor.getPredefinedCursor(if (id == activeId) Cursor.DEFAULT_CURSOR else Cursor.MOVE_CURSOR)
            }
            list.scrollRectToVisible(Rectangle(point.x, (point.y - 16).coerceAtLeast(0), 1, 32))
            drop = if (id == activeId) null else destination(point)
            list.repaint()
        }
        override fun mouseReleased(e: MouseEvent) {
            val gesture = drag?.takeIf { it.id == id } ?: return
            val target = if (gesture.moving && id != activeId) destination(SwingUtilities.convertPoint(e.component, e.point, list)) else null
            cancelDrag()
            if (target != null) reorder(id, target.targetId, target.after)
        }
    }
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
            val listener = dragListener(id)
            listOf(this, open).forEach { it.addMouseListener(listener); it.addMouseMotionListener(listener) }
        }
        fun update(entry: CompletedAgent, active: Boolean, tag: AgentTag?) {
            open.text = "${entry.name}  ·  ${promptDuration(entry)}" + (if (tag != null) "  [${tag.name.uppercase()}]" else "") + if (active) "  (Current)" else ""
            // Untagged rows keep exactly the previous tooltip; tagged rows lead with the agent's question.
            open.toolTipText = (if (tag != null) agentTagMessage(tag) + "  ·  " else "") +
                entry.name + (if (entry.partial) " — observed time; prompt was already running" else " — elapsed prompt time") + if (active) "; current thread stays first" else "; drag to reorder"
            close.accessibleContext.accessibleName = "Remove ${entry.name} from queue"
            background = UIManager.getColor(if (active) "List.selectionBackground" else "List.background")
            open.foreground = UIManager.getColor(if (active) "List.selectionForeground" else "List.foreground")
            close.foreground = open.foreground
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }
    private val rows = linkedMapOf<String, Row>()
    private val list = object : JPanel() {
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val target = drop?.let { d -> rows[d.targetId]?.let { it to d.after } } ?: return
            g.color = UIManager.getColor("Component.focusColor") ?: Color(0x3574F0)
            val y = (target.first.y + if (target.second) target.first.height else 0).coerceIn(0, (height - 2).coerceAtLeast(0))
            g.fillRect(0, y, width, 2)
        }
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val empty = JLabel("No completed prompts waiting.").apply { border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
    init {
        add(empty, BorderLayout.NORTH)
        add(list, BorderLayout.CENTER)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "cancelDrag")
        actionMap.put("cancelDrag", object : AbstractAction() {
            override fun isEnabled() = drag?.moving == true
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = cancelDrag()
        })
        addHierarchyListener { if (!isShowing) cancelDrag() }
    }
    fun update(entries: List<CompletedAgent>, activeThread: String? = null, tags: Map<String, AgentTag> = emptyMap()) {
        val live = entries.map { it.id }.toSet()
        val nextActive = entries.firstOrNull { it.thread == activeThread }?.id
        if (activeId != nextActive || drag?.id?.let { it !in live } == true) cancelDrag()
        activeId = nextActive
        rows.keys.filter { it !in live }.forEach { id -> list.remove(rows.remove(id)) }
        entries.forEachIndexed { index, entry ->
            val row = rows.getOrPut(entry.id) { Row(entry.id).also { list.add(it) } }
            row.update(entry, entry.thread == activeThread, tags[entry.thread])
            if (list.getComponentZOrder(row) != index) list.setComponentZOrder(row, index)
        }
        empty.isVisible = entries.isEmpty()
        list.revalidate()
        list.repaint()
    }
}
