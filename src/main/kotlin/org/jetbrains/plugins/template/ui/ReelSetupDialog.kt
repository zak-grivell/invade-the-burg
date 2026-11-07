package org.jetbrains.plugins.template.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/** Uses the same native browser engine and its website cookie storage as breaks. */
internal class ReelSetupDialog(parent: JComponent, private val url: String) : DialogWrapper(parent, true) {
    private var page: BreakPage? = null

    init {
        title = "Reel Break Setup"
        setOKButtonText("Done")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        preferredSize = Dimension(900, 650)
        add(JBLabel("Sign in to your website, then click Done. Use Apply or OK in settings to save URL changes."), BorderLayout.NORTH)
        val browser = try {
            SystemBreakPage(url).also { page = it; it.setActive(true) }.component
        } catch (failure: Exception) {
            errorView(failure)
        } catch (failure: LinkageError) {
            errorView(failure)
        }
        add(browser, BorderLayout.CENTER)
    }

    private fun errorView(failure: Throwable) = JTextArea(
        "Browser unavailable.\n\n${BrowserPlatform.current()?.setup.orEmpty()}\n\n${failure.message.orEmpty()}"
    ).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    override fun dispose() {
        page?.dispose()
        page = null
        super.dispose()
    }
}
