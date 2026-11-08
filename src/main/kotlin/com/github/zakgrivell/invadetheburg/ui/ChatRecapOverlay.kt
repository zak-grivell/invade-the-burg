package com.github.zakgrivell.invadetheburg.ui

import com.intellij.openapi.Disposable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Lives in the originating editor's rectangle, not a dialog or a window. */
internal class ChatRecapOverlay(private val media: RecapMedia, private val dismiss: () -> Unit, private val createPage: (String) -> BreakPage = { SystemBreakPage(it, ownsPlaybackControls = true) }) : Disposable {
    private var page: BreakPage? = null
    private var closed = false
    private fun finish() {
        if (closed) return
        closed = true
        activate(false)
        dismiss()
    }
    private var active = false
    private fun activate(value: Boolean) { if (active != value) { active = value; page?.setActive(value) } }
    private var layer: JLayeredPane? = null
    private val view = JPanel(BorderLayout()).apply {
        isVisible = false
        background = Color.BLACK
        add(JPanel(BorderLayout()).apply {
            background = Color.BLACK
            add(JButton("Back to chat").apply { addActionListener { finish() } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
    }
    fun prepare() {
        if (page == null) {
            page = createPage(media.url).also { it.onFinished(::finish) }
            view.add(page!!.component, BorderLayout.CENTER)
        }
    }
    val ready: Boolean get() = page?.ready == true
    fun pause() { activate(false) }
    fun prepareOver(editor: JComponent): Boolean {
        if (closed) return false
        val root = SwingUtilities.getRootPane(editor)
        if (!editor.isShowing || root == null) { hide(); return false }
        val target = root.layeredPane
        val bounds = SwingUtilities.convertRectangle(editor, editor.visibleRect, target)
            .intersection(Rectangle(0, 0, target.width, target.height))
        if (bounds.width <= 0 || bounds.height <= 0) { hide(); return false }
        prepare()
        if (layer !== target) {
            layer?.remove(view); layer?.repaint()
            layer = target
            target.add(view)
            target.setLayer(view, JLayeredPane.MODAL_LAYER)
        }
        val targetBounds = if (active) bounds else Rectangle(-bounds.width - 2, bounds.y, bounds.width, bounds.height)
        if (view.bounds != targetBounds) { view.bounds = targetBounds; view.revalidate(); view.doLayout() }
        view.isVisible = true
        return true
    }
    fun showOver(editor: JComponent) {
        if (!prepareOver(editor)) return
        val target = layer ?: return
        view.bounds = SwingUtilities.convertRectangle(editor, editor.visibleRect, target)
            .intersection(Rectangle(0, 0, target.width, target.height))
        view.revalidate()
        view.isVisible = true
        activate(true)
    }
    fun hide() { if (ready || closed) view.isVisible = false; activate(false) }
    override fun dispose() {
        closed = true
        activate(false); page?.dispose(); page = null; media.close()
        layer?.remove(view); layer?.revalidate(); layer?.repaint(); layer = null
    }
}

/** Only the selected originating pane may create or activate a recap browser. */
internal fun renderSelectedRecap(recaps: Collection<ChatRecapOverlay>, selected: ChatRecapOverlay?, editor: JComponent?) {
    recaps.forEach { recap ->
        if (recap === selected && editor != null) recap.showOver(editor) else recap.hide()
    }
}
