package com.github.zakgrivell.invadetheburg.ui

import java.awt.Component
import java.awt.Container
import java.util.IdentityHashMap

/** Retained native peers must not remain an enabled focus target after navigation. */
internal class BrowserInputGate {
    private val saved = IdentityHashMap<Component, Pair<Boolean, Boolean>>()
    fun setActive(component: Component, active: Boolean) {
        if (active) {
            saved.forEach { (child, original) -> child.isEnabled = original.first; child.isFocusable = original.second }
            saved.clear()
        } else {
            fun disable(child: Component) {
                if (!saved.containsKey(child)) saved[child] = child.isEnabled to child.isFocusable
                child.isEnabled = false
                child.isFocusable = false
                (child as? Container)?.components?.forEach(::disable)
            }
            disable(component)
        }
    }
}

internal fun chatFocusTarget(preferred: Component?, root: Component?): Component? {
    fun input(component: Component): Component? {
        if (component is javax.swing.text.JTextComponent && component.isEditable && component.isEnabled && component.isShowing) return component
        return (component as? Container)?.components?.reversed()?.firstNotNullOfOrNull(::input)
    }
    return preferred?.let(::input) ?: root?.let(::input) ?: preferred?.takeIf { it.isShowing && it.isFocusable }
}

/** Resolve the composer after browser hiding, and discard a handoff invalidated by navigation or a dialog. */
internal fun requestChatFocus(
    root: Component?,
    preferred: () -> Component?,
    isCurrent: () -> Boolean,
    request: (Component) -> Unit,
    defer: (() -> Unit) -> Unit = { javax.swing.SwingUtilities.invokeLater(it) }
) {
    defer {
        if (isCurrent()) chatFocusTarget(preferred(), root)?.let(request)
    }
}
