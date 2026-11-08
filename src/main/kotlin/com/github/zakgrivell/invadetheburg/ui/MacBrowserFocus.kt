package com.github.zakgrivell.invadetheburg.ui

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import java.awt.Component
import javax.swing.SwingUtilities

/** A hidden WKWebView can remain AppKit's first responder while AWT still reports the chat as focused. */
internal object MacBrowserFocus {
    private val log = logger<MacBrowserFocus>()

    fun releaseHiddenBrowser(component: Component, stillHidden: () -> Boolean) {
        if (!SystemInfo.isMac) return
        val window = SwingUtilities.getWindowAncestor(component) ?: return
        val nativeWindow = MacUtil.getWindowFromJavaWindow(window)
        if (nativeWindow == ID.NIL) return
        // Let the heavyweight visibility event enqueue its native hide before inspecting the responder.
        SwingUtilities.invokeLater {
            if (!stillHidden() || !window.isDisplayable) return@invokeLater
            Foundation.executeOnMainThread(true, false) {
                if (!stillHidden()) return@executeOnMainThread
                runCatching {
                    val app = Foundation.invoke("NSApplication", "sharedApplication")
                    // Resolve a live window on AppKit; never touch an inactive window or a newer dialog.
                    val keyWindow = Foundation.invoke(app, "keyWindow")
                    if (keyWindow != nativeWindow) return@runCatching
                    var view = Foundation.invoke(keyWindow, "firstResponder")
                    val viewClass = Foundation.getObjcClass("NSView")
                    val webClass = Foundation.getObjcClass("WKWebView")
                    repeat(64) {
                        if (view == ID.NIL || !Foundation.invoke(view, "isKindOfClass:", viewClass).booleanValue()) return@runCatching
                        if (Foundation.invoke(view, "isKindOfClass:", webClass).booleanValue()) {
                            if (!Foundation.invoke(view, "isHiddenOrHasHiddenAncestor").booleanValue()) return@runCatching
                            // Return AppKit input to the AWT host, preserving Java's existing focus owner.
                            val host = Foundation.invoke(view, "superview")
                            if (host != ID.NIL && !Foundation.invoke(keyWindow, "makeFirstResponder:", host).booleanValue()) {
                                val content = Foundation.invoke(keyWindow, "contentView")
                                if (content != ID.NIL) Foundation.invoke(keyWindow, "makeFirstResponder:", content)
                            }
                            return@runCatching
                        }
                        view = Foundation.invoke(view, "superview")
                    }
                }.onFailure { log.debug("Could not release hidden WebKit keyboard focus", it) }
            }
        }
    }
}
