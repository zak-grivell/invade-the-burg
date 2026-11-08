package com.github.zakgrivell.invadetheburg.ui

import javax.swing.JComponent

internal interface BreakPage {
    val ready: Boolean get() = true
    val component: JComponent
    fun onFinished(callback: () -> Unit) {}
    fun loadUrl(url: String)
    fun setActive(active: Boolean)
    fun dispose()
}

/** Configured URL is tracked separately from navigation inside the retained page. */
internal class RetainedPage(private val create: (String) -> BreakPage) {
    private var page: BreakPage? = null
    private var configuredUrl: String? = null
    private var active = false

    fun prepare(url: String): BreakPage {
        val current = page ?: create(url).also { page = it; configuredUrl = url }
        if (configuredUrl != url) {
            current.loadUrl(url)
            configuredUrl = url
        }
        return current
    }

    fun show(url: String): BreakPage = prepare(url).also {
        if (!active) { active = true; it.setActive(true) }
    }

    fun hide() {
        if (active) { active = false; page?.setActive(false) }
    }

    fun dispose() { hide(); page?.dispose(); page = null; configuredUrl = null }
}
