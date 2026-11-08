package com.github.zakgrivell.invadetheburg.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.components.service
import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.github.zakgrivell.invadetheburg.settings.FavoriteWebsite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.util.Properties
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Hides the overlay in place so native browser peers keep their document and history. */
internal class ReelOverlay(private val dismiss: () -> Unit, private val resume: () -> Unit, private val settings: () -> Unit) : Disposable {
    companion object {
        private val version: String = try {
            val props = Properties()
            ReelOverlay::class.java.classLoader.getResourceAsStream("reelbreak.properties")?.use { props.load(it) }
            props.getProperty("version", "unknown")
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private var layer: JLayeredPane? = null
    private var returnLayer: JLayeredPane? = null
    private var popupLayer: JLayeredPane? = null
    private val returnButton = JButton("Back to Reels").apply {
        toolTipText = "Return to your break while the agent is working"
        addActionListener { resume() }
    }
    private val workButton = JButton("Back to work").apply { addActionListener { dismiss() } }
    init {
        val size = java.awt.Dimension(
            maxOf(returnButton.preferredSize.width, workButton.preferredSize.width),
            maxOf(returnButton.preferredSize.height, workButton.preferredSize.height)
        )
        returnButton.preferredSize = size
        workButton.preferredSize = size
    }
    private var completionStarted = 0L
    val isFinishing: Boolean get() = popupPanel.isVisible &&
        (System.nanoTime() - completionStarted) / 1_000_000 < COMPLETION_HOLD_MS
    val isVisible: Boolean get() = !warming && view?.isVisible == true
    private val popupPanel = JPanel(java.awt.GridBagLayout()).apply {
        background = javax.swing.UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
        add(JBLabel("All done! Returning to chat…").apply {
            font = font.deriveFont(Font.BOLD, 24f)
            border = javax.swing.BorderFactory.createEmptyBorder(20, 40, 20, 40)
        })
    }
    private var view: JPanel? = null
    private val etaBadge = EtaBadge()
    // Own row under the header buttons so a narrow pane can never squeeze or clip the badge.
    private val etaBadgeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 3)).apply {
        isVisible = false
        add(etaBadge)
    }
    private var updatingSwitcher = false
    private var switcherItems: List<FavoriteWebsite> = emptyList()
    private val websiteSwitcher = JComboBox<FavoriteWebsite>().apply {
        toolTipText = "Switch website — presets and favourites in Settings"
        addActionListener {
            if (!updatingSwitcher) {
                (selectedItem as? FavoriteWebsite)?.let { service<ReelSettings>().url = it.url }
            }
        }
    }

    private fun updateSwitcher(url: String) {
        val favorites = service<ReelSettings>().availableWebsites
        val items = if (favorites.any { it.url == url }) favorites
            else listOf(FavoriteWebsite("Current website", url)) + favorites
        if (items == switcherItems && (websiteSwitcher.selectedItem as? FavoriteWebsite)?.url == url) return
        updatingSwitcher = true
        try {
            if (items != switcherItems) {
                websiteSwitcher.model = DefaultComboBoxModel(items.toTypedArray())
                switcherItems = items
            }
            websiteSwitcher.selectedItem = items.first { it.url == url }
        } finally { updatingSwitcher = false }
    }
    private val page = RetainedPage { url ->
        val platform = BrowserPlatform.current()
        try {
            SystemBreakPage(url)
        } catch (failure: Exception) {
            unavailablePage(platform, failure)
        } catch (failure: LinkageError) {
            unavailablePage(platform, failure)
        }
    }

    private fun unavailablePage(platform: BrowserPlatform?, failure: Throwable): BreakPage = object : BreakPage {
        override val component = JTextArea("Browser unavailable.\n\n${platform?.setup ?: "Supported systems: Windows, macOS, Linux."}\n\n${failure.javaClass.simpleName}: ${failure.message.orEmpty()}").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
        }
        override fun loadUrl(url: String) {}
        override fun setActive(active: Boolean) {}
        override fun dispose() {}
    }

    val isShowing: Boolean get() = isVisible

    /** Null hides the badge row. Safe to call every poll; it only re-lays out on change. */
    fun setEtaBadge(text: String?) {
        val changed = (text != null && etaBadge.text != text) || etaBadgeRow.isVisible != (text != null)
        if (!changed) return
        if (text != null) etaBadge.text = text
        etaBadgeRow.isVisible = text != null
        view?.revalidate()
        view?.repaint()
    }

    private var warming = false
    fun prepare(editor: JComponent?, url: String) {
        page.prepare(url)
        if (editor != null && view == null) showOver(editor, url, preparing = true)
    }

    fun showOver(editor: JComponent, url: String, preparing: Boolean = false): Boolean {
        cancelCompletion()
        hideReturnButton()
        val root = SwingUtilities.getRootPane(editor) ?: return false
        if (!editor.isShowing) return false
        val nextLayer = root.layeredPane
        val visible = SwingUtilities.convertRectangle(editor, editor.visibleRect, nextLayer)
            .intersection(Rectangle(0, 0, nextLayer.width, nextLayer.height))
        if (visible.width <= 0 || visible.height <= 0) return false
        val browser = if (preparing) page.prepare(url) else page.show(url)
        updateSwitcher(url)
        if (view == null) {
            view = JPanel(BorderLayout()).apply {
                isVisible = false
                add(JPanel(BorderLayout()).apply {
                    add(JPanel(BorderLayout()).apply {
                        add(JBLabel("  Reel Break · v$version"), BorderLayout.CENTER)
                        add(JPanel().apply {
                            add(websiteSwitcher)
                            add(JButton("Settings").apply { addActionListener { settings() } })
                            add(workButton)
                        }, BorderLayout.EAST)
                    }, BorderLayout.NORTH)
                    // The native browser below is a heavyweight component that would hide anything drawn over it,
                    // so the badge sits in the header, on its own row, not on the video.
                    add(etaBadgeRow, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(browser.component, BorderLayout.CENTER)
            }
        }
        if (layer !== nextLayer) {
            layer?.remove(view)
            layer?.repaint()
            layer = nextLayer
            // Use the constraints overload: Kotlin otherwise selects add(Component, index).
            nextLayer.add(view, JLayeredPane.PALETTE_LAYER, 0)
        }
        val bounds = if (preparing) Rectangle(-visible.width - 2, visible.y, visible.width, visible.height) else visible
        if (view!!.bounds != bounds) {
            view!!.bounds = bounds
            view!!.revalidate()
            view!!.doLayout()
            view!!.repaint()
        }
        warming = preparing
        view!!.isVisible = true
        return true
    }

    /** A Swing completion card avoids trying to alpha-composite the native browser peer. */
    fun finishOver(editor: JComponent) {
        val root = SwingUtilities.getRootPane(editor) ?: return hide()
        if (!editor.isShowing) return hide()
        val nextLayer = root.layeredPane
        val bounds = SwingUtilities.convertRectangle(editor, editor.visibleRect, nextLayer)
            .intersection(Rectangle(0, 0, nextLayer.width, nextLayer.height))
        if (bounds.isEmpty) return hide()
        if (popupLayer !== nextLayer) {
            popupLayer?.remove(popupPanel)
            popupLayer?.repaint()
            popupLayer = nextLayer
            nextLayer.add(popupPanel, JLayeredPane.POPUP_LAYER, 0)
        }
        popupPanel.bounds = bounds
        if (!popupPanel.isVisible) completionStarted = System.nanoTime()
        popupPanel.isVisible = true
        page.hide()
        view?.isVisible = false
        hideReturnButton()
        nextLayer.revalidate()
        nextLayer.repaint()
    }

    private fun cancelCompletion() {
        popupPanel.isVisible = false
        popupLayer?.repaint()
    }

    fun showReturnButton(editor: JComponent) {
        val nextLayer = SwingUtilities.getRootPane(editor)?.layeredPane ?: return
        if (!editor.isShowing) return
        val visible = SwingUtilities.convertRectangle(editor, editor.visibleRect, nextLayer)
            .intersection(Rectangle(0, 0, nextLayer.width, nextLayer.height))
        val size = returnButton.preferredSize
        if (visible.width < size.width || visible.height < size.height) return
        if (returnLayer !== nextLayer) {
            returnLayer?.remove(returnButton)
            returnLayer?.repaint()
            returnLayer = nextLayer
            nextLayer.add(returnButton, JLayeredPane.PALETTE_LAYER, 0)
        }
        returnButton.setBounds(
            visible.x + (visible.width - size.width - 5).coerceAtLeast(0),
            visible.y + (5 + (maxOf(size.height, websiteSwitcher.preferredSize.height) - size.height) / 2)
                .coerceAtMost(visible.height - size.height),
            size.width, size.height
        )
        returnButton.isVisible = true
    }

    private fun hideReturnButton() {
        returnButton.isVisible = false
    }

    fun pauseMedia() { page.hide() }

    fun hide() {
        hideReturnButton()
        cancelCompletion()
        page.hide()
        if (warming) return
        if (view?.isVisible != true) return
        view?.isVisible = false
        layer?.revalidate()
        layer?.repaint()
    }

    override fun dispose() {
        warming = false
        hide()
        page.dispose()
        returnLayer?.remove(returnButton)
        returnLayer?.repaint()
        returnLayer = null
        popupLayer?.remove(popupPanel)
        popupLayer?.repaint()
        popupLayer = null
        view?.let { layer?.remove(it) }
        layer?.revalidate()
        layer?.repaint()
        view = null
        layer = null
    }
}

/** Small pill with a semi-transparent dark background and white text; hidden until it has text. */
private class EtaBadge : JBLabel() {
    init {
        isOpaque = false
        foreground = Color.WHITE
        border = javax.swing.BorderFactory.createEmptyBorder(2, 10, 2, 10)
        toolTipText = "Estimated time until your agent finishes this step"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0, 0, 0, 170)
            g2.fillRoundRect(0, 0, width, height, height, height)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
