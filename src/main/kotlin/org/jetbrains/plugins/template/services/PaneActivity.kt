package org.jetbrains.plugins.template.services

internal data class PaneActivity(val identity: String, val processing: Boolean)

/** Reads Air's actual pane state without linking the plugin to Air's private module classes. */
internal object AirPaneActivity {
    private const val AIR_FILE = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    fun read(file: Any?): PaneActivity? {
        if (file == null || file.javaClass.name != AIR_FILE) return null
        return readKnownPane(file)
    }

    internal fun readKnownPane(file: Any): PaneActivity? = try {
        val identity = file.javaClass.getMethod("getThreadIdentity").invoke(file) as? String
        val activity = file.javaClass.getMethod("getThreadActivity").invoke(file) as? Enum<*>
        if (identity.isNullOrEmpty() || activity == null) null
        else PaneActivity(identity, activity.name == "PROCESSING")
    } catch (_: ReflectiveOperationException) {
        // An incompatible Air update must close Reels, never leave a stale break running.
        null
    } catch (_: LinkageError) {
        null
    }
}

/** A manual dismissal is scoped to the currently running pane. */
internal class BreakState {
    private var identity: String? = null
    private var dismissed = false
    fun update(pane: PaneActivity?): Boolean {
        if (pane?.identity != identity || pane?.processing != true) dismissed = false
        identity = pane?.identity
        return pane?.processing == true && !dismissed
    }
    fun dismiss() { dismissed = true }
    fun resume() { dismissed = false }
}
