package com.github.zakgrivell.invadetheburg.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.components.service
import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.github.zakgrivell.invadetheburg.settings.FavoriteWebsite
import java.awt.BorderLayout
import java.awt.Rectangle
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
    private var layer: JLayeredPane? = null
    private var returnLayer: JLayeredPane? = null
    private val returnButton = JButton("Back to Reels").apply {
        toolTipText = "Return to your break while the agent is working"
        addActionListener { resume() }
    }
    private var view: JPanel? = null
    private var updatingSwitcher = false
    private var switcherItems: List<FavoriteWebsite> = emptyList()
    private val websiteSwitcher = JComboBox<FavoriteWebsite>().apply {
        toolTipText = "Switch website — manage favourites in Settings"
        addActionListener {
            if (!updatingSwitcher) {
                (selectedItem as? FavoriteWebsite)?.let { service<ReelSettings>().url = it.url }
            }
        }
    }

    private fun updateSwitcher(url: String) {
        val favorites = service<ReelSettings>().favorites
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

    fun showOver(editor: JComponent, url: String): Boolean {
        hideReturnButton()
        val root = SwingUtilities.getRootPane(editor) ?: return false
        if (!editor.isShowing) return false
        val nextLayer = root.layeredPane
        val visible = SwingUtilities.convertRectangle(editor, editor.visibleRect, nextLayer)
            .intersection(Rectangle(0, 0, nextLayer.width, nextLayer.height))
        if (visible.width <= 0 || visible.height <= 0) return false
        val browser = page.show(url)
        updateSwitcher(url)
        if (view == null) {
            view = JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    add(JBLabel("  Reel Break · hides when your agent finishes"), BorderLayout.CENTER)
                    add(JPanel().apply {
                        add(websiteSwitcher)
                        add(JButton("Settings").apply { addActionListener { settings() } })
                        add(JButton("Back to work").apply { addActionListener { dismiss() } })
                    }, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(browser.component, BorderLayout.CENTER)
            }
        }
        if (layer !== nextLayer) {
            layer?.remove(view)
            layer?.repaint()
            layer = nextLayer
            nextLayer.add(view, JLayeredPane.PALETTE_LAYER)
        }
        if (view!!.bounds != visible) {
            view!!.bounds = visible
            view!!.revalidate()
            view!!.repaint()
        }
        view!!.isVisible = true
        return true
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
            nextLayer.add(returnButton, JLayeredPane.PALETTE_LAYER)
        }
        returnButton.setBounds(
            visible.x + (visible.width - size.width - 12).coerceAtLeast(0),
            visible.y + (visible.height - size.height - 12).coerceAtLeast(0),
            size.width, size.height
        )
        returnButton.isVisible = true
    }

    private fun hideReturnButton() {
        returnButton.isVisible = false
    }

    fun hide() {
        hideReturnButton()
        page.hide()
        if (view?.isVisible != true) return
        view?.isVisible = false
        layer?.revalidate()
        layer?.repaint()
    }

    override fun dispose() {
        hide()
        page.dispose()
        returnLayer?.remove(returnButton)
        returnLayer?.repaint()
        returnLayer = null
        view?.let { layer?.remove(it) }
        layer?.revalidate()
        layer?.repaint()
        view = null
        layer = null
    }
}
