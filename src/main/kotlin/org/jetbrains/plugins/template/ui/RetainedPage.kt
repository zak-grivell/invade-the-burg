package org.jetbrains.plugins.template.ui

import javax.swing.JComponent

internal interface BreakPage {
    val component: JComponent
    fun loadUrl(url: String)
    fun setActive(active: Boolean)
    fun dispose()
}

/** Configured URL is tracked separately from navigation inside the retained page. */
internal class RetainedPage(private val create: (String) -> BreakPage) {
    private var page: BreakPage? = null
    private var configuredUrl: String? = null
    private var active = false

    fun show(url: String): BreakPage {
        val current = page ?: create(url).also { page = it; configuredUrl = url }
        if (configuredUrl != url) {
            current.loadUrl(url)
            configuredUrl = url
        }
        if (!active) { active = true; current.setActive(true) }
        return current
    }

    fun hide() {
        if (active) { active = false; page?.setActive(false) }
    }

    fun dispose() { hide(); page?.dispose(); page = null; configuredUrl = null }
}
