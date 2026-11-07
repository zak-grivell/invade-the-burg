package com.github.zakgrivell.invadetheburg.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.github.zakgrivell.invadetheburg.settings.ReelSettingsConfigurable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.github.zakgrivell.invadetheburg.ui.ReelOverlay
import com.github.zakgrivell.invadetheburg.services.eta.EtaMonitor
import com.intellij.openapi.diagnostic.logger
import javax.swing.Timer

private val LOG = logger<ReelService>()

@Service(Service.Level.PROJECT)
class ReelService(private val project: Project) : Disposable {
    private val state = BreakState()
    private val timer = Timer(200) { pollPane() }
    private val overlay = ReelOverlay(
        dismiss = { dismiss() },
        resume = { state.resume(); pollPane() },
        settings = { ShowSettingsUtil.getInstance().showSettingsDialog(project, ReelSettingsConfigurable::class.java) }
    )
    private var disposed = false

    // Read-only ETA tracking and logging; it must never stop the overlay from working.
    private val eta: EtaMonitor? = runCatching { EtaMonitor.createDefault() }
        .onFailure { LOG.warn("Reel Break: ETA tracking unavailable", it) }.getOrNull()
    private var etaFailureLogged = false
    private var etaHistoryRequested = false

    fun start() {
        if (!disposed && !timer.isRunning) {
            timer.start()
            startEtaHistory()
            pollPane()
        }
    }
    fun dismiss() { state.dismiss(); overlay.hide() }

    private fun pollPane() {
        if (disposed || project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        val pane = AirPaneActivity.read(manager.selectedFiles.firstOrNull())
        eta?.let { monitor -> etaGuard { monitor.observe(pane, System.currentTimeMillis()) } }
        val editor = manager.selectedEditor?.component
        if (state.update(pane) && editor != null) {
            // Reposition on each tick so the overlay follows pane resizing and split editors.
            if (!overlay.showOver(editor, service<ReelSettings>().url)) overlay.hide()
        } else {
            overlay.hide()
            if (pane?.processing == true && editor != null) overlay.showReturnButton(editor)
        }
    }

    private fun startEtaHistory() {
        if (etaHistoryRequested) return
        etaHistoryRequested = true
        eta?.let { monitor -> etaGuard { monitor.loadHistoryInBackground() } }
    }

    private fun etaGuard(block: () -> Unit) {
        runCatching(block).onFailure {
            if (!etaFailureLogged) { etaFailureLogged = true; LOG.warn("Reel Break: ETA tracking failed", it) }
        }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        eta?.let { monitor -> etaGuard { monitor.close(System.currentTimeMillis()) } }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) overlay.dispose()
        else application.invokeLater { overlay.dispose() }
    }
}
