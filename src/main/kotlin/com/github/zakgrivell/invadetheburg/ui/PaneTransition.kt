package com.github.zakgrivell.invadetheburg.ui

import com.intellij.openapi.Disposable
import java.awt.Color
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import javax.swing.Timer

internal const val TRANSITION_HALF_MS = 90f
internal const val COMPLETION_HOLD_MS = 600L

/** Fade through a short curtain: native browser peers cannot be alpha-painted by Swing. */
internal class PaneTransition(private val now: () -> Long = System::nanoTime) : Disposable {
    private var layer: JLayeredPane? = null
    private var key: Any? = null
    private var pending: (() -> Unit)? = null
    private var covering = true
    private var opacity = 0f
    private var lastFrame = 0L
    private val curtain = object : JComponent() {
        override fun paintComponent(g: Graphics) {
            val color = javax.swing.UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
            g.color = Color(color.red, color.green, color.blue, (opacity * 255).toInt().coerceIn(0, 255))
            g.fillRect(0, 0, width, height)
        }
    }
    private val timer: Timer = Timer(16) { advance() }

    internal fun advance() {
        val now = now()
        val step = (now - lastFrame) / 1_000_000f / TRANSITION_HALF_MS
        lastFrame = now
        opacity = (opacity + if (covering) step else -step).coerceIn(0f, 1f)
        if (covering && opacity >= 1f) {
            covering = false
            val action = pending
            pending = null
            action?.invoke()
        }
        layer?.repaint(curtain.bounds)
        if (!covering && opacity <= 0f) {
            timer.stop()
            curtain.isVisible = false
        }
    }

    fun show(nextKey: Any, editor: JComponent?, render: () -> Unit) {
        val target = editor?.let(SwingUtilities::getRootPane)?.layeredPane
        if (target == null || !editor.isShowing) {
            dispose()
            key = nextKey
            render()
            return
        }
        if (layer !== target) {
            dispose()
            layer = target
            target.add(curtain, Integer.valueOf(450), 0)
        }
        curtain.bounds = SwingUtilities.convertRectangle(editor, editor.visibleRect, target)
        if (key == nextKey) {
            if (pending != null) pending = render
            else render()
            return
        }
        val firstRender = key == null
        key = nextKey
        if (firstRender && nextKey == "chat") { render(); return }
        pending = render
        covering = true
        curtain.isVisible = true
        lastFrame = now()
        if (!timer.isRunning) timer.start()
    }

    override fun dispose() {
        timer.stop()
        pending = null
        opacity = 0f
        key = null
        layer?.remove(curtain)
        layer?.repaint()
        layer = null
    }
}
