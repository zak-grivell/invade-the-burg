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
import javax.swing.Timer

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

    fun start() { if (!disposed && !timer.isRunning) { timer.start(); pollPane() } }
    fun dismiss() { state.dismiss(); overlay.hide() }

    private fun pollPane() {
        if (disposed || project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        val pane = AirPaneActivity.read(manager.selectedFiles.firstOrNull())
        val editor = manager.selectedEditor?.component
        if (state.update(pane) && editor != null) {
            // Reposition on each tick so the overlay follows pane resizing and split editors.
            if (!overlay.showOver(editor, service<ReelSettings>().url)) overlay.hide()
        } else {
            overlay.hide()
            if (pane?.processing == true && editor != null) overlay.showReturnButton(editor)
        }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) overlay.dispose()
        else application.invokeLater { overlay.dispose() }
    }
}
