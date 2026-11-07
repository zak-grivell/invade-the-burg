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
import com.github.zakgrivell.invadetheburg.ui.PaneTransition
import com.github.zakgrivell.invadetheburg.ui.ReelOverlay
import javax.swing.Timer
import com.github.zakgrivell.invadetheburg.settings.RecapMode
import com.github.zakgrivell.invadetheburg.ui.ChatRecapOverlay
import com.github.zakgrivell.invadetheburg.ui.renderSelectedRecap
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger

@Service(Service.Level.PROJECT)
class ReelService(private val project: Project) : Disposable {
    private val state = BreakState()
    private val transition = PaneTransition()
    private val retiredRecaps = mutableListOf<ChatRecapOverlay>()
    private val failedRecaps = mutableSetOf<VirtualFile>()
    private val completions = CompletionTracker()
    private val narrator = ChatNarrator(project)
    private val recapViews = mutableMapOf<VirtualFile, ChatRecapOverlay>()
    private val recapOffers = mutableMapOf<VirtualFile, Notification>()
    private val latestTurns = mutableMapOf<VirtualFile, String>()
    private val requestedTurns = mutableMapOf<VirtualFile, String>()

    private fun clearRecap(file: VirtualFile) {
        recapViews.remove(file)?.let { it.pause(); retiredRecaps.add(it) }
        recapOffers.remove(file)?.expire()
        latestTurns.remove(file)
        requestedTurns.remove(file)
        failedRecaps.remove(file)
    }

    private fun offerRecap(file: VirtualFile, turn: ChatTurn) {
        clearRecap(file)
        latestTurns[file] = turn.id
        fun wanted() = !disposed && !project.isDisposed && latestTurns[file] == turn.id &&
            FileEditorManager.getInstance(project).openFiles.contains(file) && AirPaneActivity.read(file)?.processing == false
        fun watch(focus: Boolean) {
            if (!wanted() || service<ReelSettings>().recapMode == RecapMode.NEVER || requestedTurns[file] == turn.id) return
            requestedTurns[file] = turn.id
            if (focus) FileEditorManager.getInstance(project).openFile(file, true)
            narrator.completed(turn, ::wanted, onUnavailable = {
                if (wanted()) { failedRecaps.add(file); pollPane() }
            }) { media ->
                recapViews.remove(file)?.let { it.pause(); retiredRecaps.add(it) }
                recapViews[file] = ChatRecapOverlay(media, dismiss = { clearRecap(file); pollPane() })
                pollPane()
            }
        }
        when (service<ReelSettings>().recapMode) {
            RecapMode.NEVER -> clearRecap(file)
            RecapMode.ALWAYS -> watch(false)
            RecapMode.ASK -> {
                val toast = NotificationGroupManager.getInstance().getNotificationGroup("Reel Break Narration")
                    .createNotification("Want a recap?", "Watch this response with Peter Griffin and background video.", NotificationType.INFORMATION)
                toast.addAction(NotificationAction.createSimpleExpiring("Watch recap") { recapOffers.remove(file); watch(true) })
                recapOffers[file] = toast
                toast.notify(project)
            }
        }
    }
    private data class PendingRecap(val previousTurn: String?, val readyAt: Long, var failure: Throwable? = null)
    private val processingTurns = mutableMapOf<String, String?>()
    private val pendingRecaps = mutableMapOf<String, PendingRecap>()
    private val timer = Timer(200) { pollPane() }
    private val overlay = ReelOverlay(
        dismiss = { dismiss() },
        resume = { state.resume(); pollPane() },
        settings = { ShowSettingsUtil.getInstance().showSettingsDialog(project, ReelSettingsConfigurable::class.java) }
    )
    private var disposed = false
    private var finishingIdentity: String? = null

    fun start() { if (!disposed && !timer.isRunning) { timer.start(); pollPane() } }
    fun dismiss() {
        state.dismiss()
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (file != null && AirPaneActivity.read(file)?.completed == true) {
            AirPaneActivity.read(file)?.identity?.let { pendingRecaps.remove(it) }
            clearRecap(file)
            failedRecaps.add(file)
        }
        pollPane()
    }

    private fun pollPane() {
        if (disposed || project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        val panes = manager.openFiles.mapNotNull { file -> AirPaneActivity.read(file)?.let { file to it } }
        (recapViews.keys + recapOffers.keys + latestTurns.keys).toSet().filter {
            it !in manager.openFiles || service<ReelSettings>().recapMode == RecapMode.NEVER
        }.forEach(::clearRecap)
        val identities = panes.map { it.second.identity }.toSet()
        completions.retain(identities)
        processingTurns.keys.retainAll(identities)
        pendingRecaps.keys.retainAll(identities)
        val now = System.nanoTime()
        val completedIdentities = mutableSetOf<String>()
        panes.forEach { (file, activity) ->
            fun turn(): ChatTurn? = runCatching {
                manager.getEditors(file).firstNotNullOfOrNull { AirChatContext.read(it.component) }
            }.onFailure { pendingRecaps[activity.identity]?.failure = it }.getOrNull()
            if (activity.processing) {
                if (latestTurns.containsKey(file)) clearRecap(file)
                pendingRecaps.remove(activity.identity)
                if (!processingTurns.containsKey(activity.identity)) processingTurns[activity.identity] = turn()?.id
            }
            if (completions.update(activity)) {
                completedIdentities.add(activity.identity)
                pendingRecaps[activity.identity] = PendingRecap(processingTurns[activity.identity], now)
            }
            if (!activity.processing) processingTurns.remove(activity.identity)
            pendingRecaps[activity.identity]?.let { pending ->
                // Completion metadata can arrive before the final transcript publication.
                val elapsedMs = (now - pending.readyAt) / 1_000_000
                if (elapsedMs >= 600) {
                    val snapshot = turn()
                    if (snapshot != null && snapshot.id != pending.previousTurn) {
                        pendingRecaps.remove(activity.identity)
                        offerRecap(file, snapshot)
                    } else if (elapsedMs >= 5_000) {
                        pendingRecaps.remove(activity.identity)
                        pending.failure?.let { Logger.getInstance(ReelService::class.java).warn("Could not read Air transcript for completed turn", it) }
                        failedRecaps.add(file)
                        narrator.unavailable()
                    }
                }
            }
        }
        val selectedFile = manager.selectedFiles.firstOrNull()
        val pane = AirPaneActivity.read(selectedFile)
        val editor = manager.selectedEditor?.component
        val showBreak = state.update(pane)
        val always = service<ReelSettings>().recapMode == RecapMode.ALWAYS
        val selectedRecap = recapViews[selectedFile]
        val waiting = always && pane?.completed == true && selectedFile !in failedRecaps &&
            (pendingRecaps.containsKey(pane.identity) || requestedTurns.containsKey(selectedFile)) &&
            selectedRecap == null
        val justCompleted = pane?.identity in completedIdentities
        val completing = !always && editor != null && pane?.completed == true &&
            ((justCompleted && overlay.isVisible) || (finishingIdentity == pane.identity && overlay.isFinishing))
        if (completing) finishingIdentity = pane.identity
        else if (!overlay.isFinishing) finishingIdentity = null
        // Preparing narration never reveals chat between the break and an Always recap.
        val keepBreak = waiting && overlay.isVisible
        if (keepBreak) overlay.pauseMedia()
        val target = when {
            showBreak || keepBreak -> "break"
            completing -> "completion"
            selectedRecap != null -> "recap"
            else -> "chat"
        }
        val key: Any = if (target == "chat") "chat" else Triple(selectedFile, target, if (target == "recap") selectedRecap else null)
        transition.show(key, editor) {
            retiredRecaps.toList().forEach { it.dispose() }
            retiredRecaps.clear()
            renderSelectedRecap(recapViews.values, if (target == "recap") selectedRecap else null, editor)
            when (target) {
                "break" -> if (editor != null && !keepBreak && !overlay.showOver(editor, service<ReelSettings>().url)) overlay.hide()
                "completion" -> if (editor != null) overlay.finishOver(editor)
                else -> {
                    overlay.hide()
                    if (pane?.processing == true && editor != null) overlay.showReturnButton(editor)
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        narrator.dispose()
        val application = ApplicationManager.getApplication()
        val cleanup = {
            transition.dispose()
            (recapViews.keys + recapOffers.keys).toSet().forEach(::clearRecap)
            retiredRecaps.forEach { it.dispose() }
            retiredRecaps.clear()
            overlay.dispose()
        }
        if (application.isDispatchThread) cleanup() else application.invokeLater { cleanup() }
    }
}
